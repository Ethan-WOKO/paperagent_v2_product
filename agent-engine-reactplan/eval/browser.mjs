import path from 'node:path';
import process from 'node:process';
import { chromium } from 'playwright-core';
import { ProductClient, writeReport } from './run.mjs';
import { redact, summarize } from './scoring.mjs';

const apiOrigin = (process.env.PAPERAGENT_EVAL_ORIGIN ?? 'http://127.0.0.1:8080').replace(/\/$/, '');
const webOrigin = (process.env.PAPERAGENT_EVAL_WEB_ORIGIN ?? 'http://127.0.0.1:5173').replace(/\/$/, '');
const projectId = Number(process.env.PAPERAGENT_EVAL_PROJECT_ID ?? 0);
const executablePath = process.env.PAPERAGENT_EVAL_BROWSER
  ?? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';

async function main() {
  const username = process.env.PAPERAGENT_EVAL_USERNAME;
  const password = process.env.PAPERAGENT_EVAL_PASSWORD;
  if (!username || !password || !projectId) throw new Error('Set eval username, password, and project ID');

  const api = new ProductClient(apiOrigin);
  await api.login(username, password);
  const browserRun = Date.now();
  const activeTitle = `EVAL-193 browser active ${browserRun}`;
  const otherTitle = `EVAL-193 browser target ${browserRun}`;
  const activeSession = await api.createSession(projectId, activeTitle);
  const otherSession = await api.createSession(projectId, otherTitle);
  const browser = await chromium.launch({ executablePath, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const checks = [];
  const check = (name, passed, actual) => checks.push({ name, passed: Boolean(passed), actual });
  const instruction = `EVAL-193-BROWSER-${Date.now()}：逐个读取 src 目录下的源码并概括，完成前不要提前回答。`;

  try {
    await page.goto(`${webOrigin}/login`, { waitUntil: 'networkidle' });
    await page.locator('input').nth(0).fill(username);
    await page.locator('input[type="password"]').fill(password);
    await Promise.all([
      page.waitForURL((url) => !url.pathname.endsWith('/login')),
      page.getByRole('button', { name: /登录/ }).click(),
    ]);
    check('authenticated-page-login', true, page.url());

    await page.goto(`${webOrigin}/projects?projectId=${projectId}&sessionId=${activeSession.id}`, { waitUntil: 'networkidle' });
    const composer = page.getByPlaceholder('让我们一起来做些什么？');
    await composer.waitFor({ state: 'visible' });
    check('project-page-loaded', true, page.url());

    const acceptedResponse = page.waitForResponse((response) =>
      response.request().method() === 'POST'
      && response.url().includes(`/react-agent/sessions/${activeSession.id}/tasks`)
      && response.status() === 202);
    await composer.fill(instruction);
    await composer.press('Enter');
    await acceptedResponse;
    check('enter-submits-task', true, activeSession.id);
    await page.getByRole('button', { name: '停止任务' }).waitFor({ state: 'visible' });

    await page.getByText(otherTitle, { exact: true }).click();
    await page.waitForURL((url) => url.searchParams.get('sessionId') === String(otherSession.id));
    check('switch-session-while-running', true, otherSession.id);

    await page.goto(`${webOrigin}/projects?projectId=${projectId}&sessionId=${activeSession.id}`, { waitUntil: 'networkidle' });
    await page.getByText(instruction, { exact: true }).waitFor({ state: 'visible' });
    check('return-reconciles-background-task', true, activeSession.id);

    await page.evaluate(() => {
      for (const key of Object.keys(localStorage)) {
        if (key.startsWith('yanban.reactPlan.') || key.startsWith('yanban.v2NaturalLanguage.')) {
          localStorage.removeItem(key);
        }
      }
    });
    await page.reload({ waitUntil: 'networkidle' });
    await page.getByText(instruction, { exact: true }).waitFor({ state: 'visible' });
    check('server-history-survives-local-storage-clear', true, activeSession.id);

    const cancelInstruction = `EVAL-193-BROWSER-CANCEL-${Date.now()}：逐个读取并比较 src 下所有源码，完成前不要提前回答。`;
    const restoredComposer = page.getByPlaceholder('让我们一起来做些什么？');
    await restoredComposer.fill(cancelInstruction);
    await restoredComposer.press('Enter');
    await page.getByRole('button', { name: '停止任务' }).waitFor({ state: 'visible' });
    await page.getByRole('button', { name: '停止任务' }).click();
    await page.getByText(/任务已取消|已取消/).first().waitFor({ state: 'visible', timeout: 30_000 });
    check('ui-cancel-reaches-terminal-state', true, activeSession.id);
  } catch (error) {
    check('browser-flow-completed', false, error.message);
  } finally {
    await browser.close();
  }

  const result = { id: 'browser-critical-flow', score: { passed: checks.every(({ passed }) => passed), checks } };
  const report = { contractVersion: '1.0', issue: 193, action: 'browser', projectId, createdAt: new Date().toISOString(), results: [redact(result)], summary: summarize([result]) };
  const file = await writeReport(report);
  console.log(`[eval-browser] ${result.score.passed ? 'PASS' : 'FAIL'} report=${path.resolve(file)}`);
}

main().catch((error) => {
  console.error(`[eval-browser] fatal: ${redact(error.message)}`);
  process.exitCode = 1;
});
