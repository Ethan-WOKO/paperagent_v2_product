// Run against local Vite: node frontend/tests/adminLayout.browser.mjs
// All APIs are mocked in an isolated browser context; no account or data is changed.
import assert from 'node:assert/strict';
import { chromium } from '../../agent-engine-reactplan/node_modules/playwright-core/index.mjs';

const browser = await chromium.launch({
  executablePath: process.env.PAPERAGENT_EVAL_BROWSER ?? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  headless: true,
});
const tabs = ['额度明细', '聊天', '论文', '项目'];
const origin = process.env.PAPERAGENT_EVAL_WEB_ORIGIN ?? 'http://127.0.0.1:5173';
const bounds = async page => page.locator('.admin-detail .n-tab-pane').evaluate(el => ({
  height: el.getBoundingClientRect().height,
  top: el.getBoundingClientRect().top + window.scrollY,
  scrollHeight: el.scrollHeight,
  clientHeight: el.clientHeight,
  inviteTop: document.querySelector('.admin-invites').getBoundingClientRect().top + window.scrollY,
}));
function stable(actual, baseline, label) {
  assert.ok(Math.abs(actual.height - baseline.height) < 2, `${label}: shared height changed`);
  assert.ok(Math.abs(actual.top - baseline.top) < 2, `${label}: panel top moved`);
  assert.ok(Math.abs(actual.inviteTop - baseline.inviteTop) < 2, `${label}: invitations moved`);
}
try {
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    for (const populated of [true, false]) {
    const context = await browser.newContext({ viewport, serviceWorkers: 'block' });
    const page = await context.newPage();
    const pageErrors = [];
    const unexpectedApis = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    const size = populated ? 100 : 0;
    const user = { id: 99, username: 'Layout fixture', role: 'ADMIN', accountType: 'DEMO', aiQuotaTotal: -1,
      aiQuotaUsed: 0, chatSessionCount: size, paperTaskCount: size, projectCount: size, createdAt: '2026-09-09T00:00:00Z' };
    const chats = Array.from({ length: size }, (_, index) => ({
      id: index + 1, title: `Conversation ${index + 1}`, scope: index < 50 ? 'WORKSPACE' : 'PROJECT',
      projectId: index < 50 ? null : 1, archived: true,
      modelProvider: 'fixture', model: 'fixture', updatedAt: user.createdAt,
      messages: [{ id: index + 1, role: 'user', content: 'Long historical message\n'.repeat(300), createdAt: user.createdAt, deletable: true }],
    }));
    const usage = Array.from({ length: size }, (_, index) => ({ id: index + 1, feature: 'CHAT',
      promptTokens: 100, completionTokens: 50, totalTokens: 150, createdAt: user.createdAt }));
    const papers = Array.from({ length: size }, (_, index) => ({ id: index + 1,
      title: `Paper ${index + 1} ${'long-title'.repeat(30)}`, status: 'COMPLETED', currentStage: 'DONE',
      sourceFilename: `${'long-source'.repeat(30)}.tex`, updatedAt: user.createdAt }));
    const projects = Array.from({ length: size }, (_, index) => ({ id: index + 1,
      name: `Project ${index + 1} ${'long-name'.repeat(30)}`, rootType: 'UPLOAD',
      indexVersion: 'a'.repeat(64), updatedAt: user.createdAt }));
    await page.addInitScript(() => {
      const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }));
      localStorage.setItem('yanban_access_token', `fixture.${payload}.fixture`);
    });
    await page.route('**/api/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      let data = path.endsWith('/users/me') ? user
        : path.endsWith('/admin/users') ? [user]
        : path.endsWith('/admin/users/99') ? { user, chats, papers, projects, usage }
        : path.endsWith('/admin/invite-codes') ? [] : undefined;
      if (data === undefined || route.request().method() !== 'GET') {
        unexpectedApis.push(`${route.request().method()} ${path}`);
        data = {};
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
    });
    await page.goto(`${origin}/admin`);
    await page.getByRole('button', { name: /Layout fixture/ }).click({ timeout: 10000 }).catch(async error => {
      console.error('Unexpected page:', page.url(), (await page.locator('body').innerText()).slice(0, 1500));
      throw error;
    });
    const panel = page.locator('.admin-detail .n-tab-pane');
    await panel.waitFor();
    const baseline = await bounds(page);
    assert.ok(baseline.height >= 240 && baseline.height <= 520,
      `Initial quota pane must be bounded, got ${baseline.height}px (${viewport.width}px, populated=${populated})`);
    for (const tab of tabs) {
      await page.locator('.admin-detail .n-tabs-tab').filter({ hasText: tab }).click();
      await panel.waitFor();
      stable(await bounds(page), baseline, tab);
      // Sample live animation frames; do not hide layout jumps by disabling transitions in the test.
      const frames = await page.evaluate(async () => {
        const samples = [];
        for (let i = 0; i < 8; i++) {
          await new Promise(requestAnimationFrame);
          const pane = document.querySelector('.admin-detail .n-tab-pane');
          samples.push({ height: pane.getBoundingClientRect().height,
            top: pane.getBoundingClientRect().top + window.scrollY,
            inviteTop: document.querySelector('.admin-invites').getBoundingClientRect().top + window.scrollY });
        }
        return samples;
      });
      frames.forEach(frame => stable(frame, baseline, `${tab} transition`));
      assert.equal(await panel.getAttribute('role'), 'region');
      assert.ok(await panel.getAttribute('aria-label'));
      assert.equal(await panel.getAttribute('tabindex'), '0');
      if (populated) {
        const scroll = await panel.evaluate(el => { el.scrollTop = 100; return el.scrollTop; });
        assert.ok(scroll > 0, `${tab} must scroll internally`);
        // The active pane is the sole vertical scroll container, including the chat list.
        assert.equal(await panel.evaluate(el => [...el.querySelectorAll('*')].filter(child =>
          /auto|scroll/.test(getComputedStyle(child).overflowY) && child.scrollHeight > child.clientHeight).length), 0);
        await panel.evaluate(el => { el.scrollTop = 0; });
        await panel.focus();
        await page.keyboard.press('PageDown');
        await page.waitForFunction(() => document.querySelector('.admin-detail .n-tab-pane').scrollTop > 0);
        assert.equal(await panel.evaluate(el => document.activeElement === el), true);
      } else {
        assert.equal(await panel.locator('.n-empty').isVisible(), true);
      }
      if (tab === '聊天') {
        for (const category of ['工作区对话', '项目对话']) {
          await page.getByRole('tab', { name: new RegExp(category) }).click();
          if (populated) {
            await panel.locator('.admin-chat-session summary').first().click();
            assert.equal(await panel.locator('.admin-chat-session[open]').count(), 1);
            assert.ok(await panel.getByRole('button', { name: '删除此消息' }).count());
          } else {
            assert.equal(await panel.locator('.n-empty').isVisible(), true);
          }
          stable(await bounds(page), baseline, category);
        }
      }
      assert.equal(await panel.evaluate(el => el.scrollWidth <= el.clientWidth + 1), true, `${tab} horizontal overflow`);
      stable(await bounds(page), baseline, `${tab} after scrolling/expansion`);
    }
    await page.getByRole('button', { name: '生成邀请码', exact: true }).scrollIntoViewIfNeeded();
    assert.equal(await page.getByRole('button', { name: '生成邀请码', exact: true }).isVisible(), true);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true);
    assert.equal(await page.locator('.admin-invite-create').getByRole('button', { name: '保存', exact: true }).isDisabled(), true);
    assert.deepEqual(pageErrors, []);
    assert.deepEqual(unexpectedApis, []);
    console.log(`PASS all 4 admin tabs, both chat categories, shared bounds, keyboard and invites at ${viewport.width}px (${populated ? '100 records/type' : 'empty'})`);
    await context.close();
    }
  }
} finally {
  await browser.close();
}
