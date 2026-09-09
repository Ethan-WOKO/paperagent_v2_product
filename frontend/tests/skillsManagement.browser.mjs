// Run against local Vite: node frontend/tests/skillsManagement.browser.mjs
// System Edge, isolated contexts and in-memory HTTP fixtures only. No real API writes.
import assert from 'node:assert/strict';
import { chromium } from '../../agent-engine-reactplan/node_modules/playwright-core/index.mjs';

const origin = process.env.PAPERAGENT_EVAL_WEB_ORIGIN ?? 'http://127.0.0.1:5173';
const browser = await chromium.launch({
  executablePath: process.env.PAPERAGENT_EVAL_BROWSER ?? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  headless: true,
});

try {
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    const context = await browser.newContext({ viewport, serviceWorkers: 'block' });
    const page = await context.newPage();
    page.setDefaultTimeout(10000);
    const browserErrors = [];
    const unexpectedApis = [];
    const writes = [];
    const user = { id: 99, username: 'Skill fixture', role: 'USER', accountType: 'NORMAL', demo: false,
      aiQuotaTotal: -1, aiQuotaUsed: 0, aiQuotaRemaining: -1 };
    const settings = { defaultProvider: 'deepseek', deepseekApiKeyConfigured: true, glmApiKeyConfigured: false,
      githubPatConfigured: false, deepseekModel: 'deepseek-v4-flash', glmModel: 'glm-5.2',
      deepseekModels: ['deepseek-v4-flash'], glmModels: ['glm-5.2'], deepseekTemperature: 0.7,
      maxSteps: 20, ragDefaultEnabled: true, filesystemRoots: ['workspace'], disabledSkills: [],
      customModels: [], updatedAt: '2026-09-09T00:00:00Z' };
    const builtin = { id: 'code-review', name: 'Code review', description: 'Builtin fixture', builtin: true,
      managed: false, enabled: true, source: 'builtin', path: 'skills/builtin/code-review' };
    let skills = [builtin];
    let personal;
    let failInstall = false;
    let failDetail = false;
    let failDelete = false;
    let modulesLoaded = 0;
    page.on('pageerror', error => browserErrors.push(error.message));
    await context.addInitScript(() => {
      const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }));
      localStorage.setItem('yanban_access_token', `fixture.${payload}.fixture`);
      localStorage.setItem('yanban.locale', 'zh-CN');
    });
    await context.route('**/api/**', async route => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      // Vite source modules such as /src/api/skills.ts must reach the dev server.
      if (!path.startsWith('/api/')) { modulesLoaded++; return route.continue(); }
      const method = request.method();
      const body = request.postData() ? request.postDataJSON() : null;
      if (method !== 'GET') writes.push({ method, path, body });
      const respond = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
      const error = (message, status) => respond({ code: 'FIXTURE_ERROR', message, fieldErrors: {} }, status);
      if (method === 'GET' && path === '/api/v1/users/me') return respond(user);
      if (method === 'GET' && path === '/api/v1/settings') return respond(settings);
      if (method === 'PUT' && path === '/api/v1/settings') {
        // Deliberately emulate legacy settings semantics to detect stale overwrites.
        if (Object.hasOwn(body, 'disabledSkills')) settings.disabledSkills = body.disabledSkills;
        return respond(settings);
      }
      if (method === 'GET' && path === '/api/v1/skills') return respond(skills);
      if (method === 'POST' && path === '/api/v1/skills') {
        if (failInstall) return error('技能名称已存在，请换一个名称', 409);
        personal = { id: 'user-browser-1', name: body.name || 'Browser review', description: 'Private fixture',
          builtin: false, managed: true, enabled: true, source: 'user', path: '', prompt: body.markdown.content,
          metadata: body.metadata?.content ?? null, allowedTools: [] };
        skills.push(personal);
        return respond(personal, 201);
      }
      const match = path.match(/^\/api\/v1\/skills\/([^/]+)(\/enabled)?$/);
      if (match) {
        const skill = skills.find(item => item.id === match[1]);
        if (!skill) return error('Skill 不存在或无权访问，请刷新技能列表', 404);
        if (method === 'GET') return failDetail ? error('Skill 不存在或无权访问，请刷新技能列表', 404)
          : respond({ ...skill, prompt: skill.prompt || 'Builtin prompt', metadata: skill.metadata ?? null, allowedTools: skill.allowedTools || ['read_project_file'] });
        if (method === 'PUT' && match[2]) {
          skill.enabled = body.enabled;
          if (skill.builtin) settings.disabledSkills = body.enabled ? [] : [skill.id];
          return route.fulfill({ status: 204 });
        }
        if (method === 'DELETE') {
          if (failDelete) return error('技能卸载失败，请重试', 503);
          skills = skills.filter(item => item.id !== skill.id);
          return route.fulfill({ status: 204 });
        }
      }
      unexpectedApis.push(`${method} ${path}`);
      return error('Unmocked API; never forwarded to real backend', 501);
    });

    const navigate = async () => {
      await page.goto(`${origin}/settings`);
      await page.locator('#skills-settings [data-skill-id="code-review"]').waitFor();
    };
    const card = page.locator('#skills-settings');
    const row = id => card.locator(`[data-skill-id="${id}"]`);
    const switchTo = async (id, enabled) => {
      await row(id).getByRole('switch').click();
      await row(id).locator(`[role="switch"][aria-checked="${enabled}"]`).waitFor();
      await card.getByTestId('skill-refresh').waitFor();
    };
    const files = [
      { name: 'SKILL.md', mimeType: 'text/markdown', buffer: Buffer.from('# Browser review\nExplain findings.\n<img src=x onerror="window.skillInjected=true">') },
      { name: 'skill.yaml', mimeType: 'text/yaml', buffer: Buffer.from('name: Browser review\nallowed_tools: []') },
    ];

    await navigate();
    assert.equal(await row(builtin.id).getByRole('button', { name: '卸载', exact: true }).count(), 0);
    await switchTo(builtin.id, false);
    await Promise.all([
      page.waitForResponse(response => response.url().endsWith('/api/v1/settings') && response.request().method() === 'PUT'),
      page.locator('.settings-header').getByRole('button', { name: '保存设置', exact: true }).click(),
    ]);
    const save = writes.find(write => write.method === 'PUT' && write.path === '/api/v1/settings');
    assert.ok(save, 'General settings Save must reach the mock');
    assert.equal(Object.hasOwn(save.body, 'disabledSkills'), false, 'General Save must omit stale skill settings');
    assert.deepEqual(settings.disabledSkills, [builtin.id]);
    console.log(`PASS ${viewport.width}px: builtin protection and general Save preserves latest enablement`);

    await card.getByTestId('skill-files').setInputFiles(files);
    await card.getByTestId('skill-install').click();
    await row('user-browser-1').waitFor();
    await card.locator('.skill-detail').getByText('允许工具（仍受会话权限约束）：无，禁止所有工具').waitFor();
    const install = writes.find(write => write.method === 'POST' && write.path === '/api/v1/skills');
    assert.equal(install.body.markdown.filename, 'SKILL.md');
    assert.equal(install.body.metadata.filename, 'skill.yaml');
    assert.match(install.body.metadata.content, /allowed_tools: \[\]/);
    assert.equal(await card.locator('.skill-detail img').count(), 0);
    assert.equal(await page.evaluate(() => window.skillInjected), undefined);
    await navigate();
    await row('user-browser-1').waitFor();
    await switchTo('user-browser-1', false);
    await switchTo('user-browser-1', true);
    console.log(`PASS ${viewport.width}px: two-file installation, reload, enable/disable and safe text rendering`);

    failDetail = true;
    await row('user-browser-1').getByRole('button', { name: '详情', exact: true }).click();
    await card.getByRole('alert').filter({ hasText: 'Skill 不存在或无权访问' }).waitFor();
    assert.equal(await card.locator('.skill-detail').count(), 0);
    failDetail = false;
    failInstall = true;
    await card.getByTestId('skill-files').setInputFiles(files);
    await card.getByTestId('skill-install').click();
    await card.getByRole('alert').filter({ hasText: '技能名称已存在' }).waitFor();
    assert.equal(skills.filter(skill => skill.managed).length, 1);
    const postsBefore = writes.filter(write => write.method === 'POST').length;
    await card.getByTestId('skill-files').setInputFiles({ name: 'SKILL.md', mimeType: 'text/markdown', buffer: Buffer.alloc(65537, 120) });
    await card.getByTestId('skill-install').click();
    await card.getByRole('alert').filter({ hasText: '64 KiB' }).waitFor();
    assert.equal(writes.filter(write => write.method === 'POST').length, postsBefore, 'Oversize file must fail before HTTP');
    console.log(`PASS ${viewport.width}px: detail/duplicate errors and bounded upload rejection`);

    failDelete = true;
    await row('user-browser-1').getByRole('button', { name: '卸载', exact: true }).click();
    assert.equal(writes.filter(write => write.method === 'DELETE').length, 0);
    await card.getByTestId('skill-confirm-delete').click();
    await card.getByRole('alert').filter({ hasText: '技能卸载失败' }).waitFor();
    assert.equal(await row('user-browser-1').count(), 1);
    failDelete = false;
    await card.getByTestId('skill-confirm-delete').click();
    await row('user-browser-1').waitFor({ state: 'detached' });
    await navigate();
    assert.equal(await row('user-browser-1').count(), 0);
    assert.equal(await row(builtin.id).count(), 1);
    assert.ok(modulesLoaded > 0, 'Vite /src/api imports must not be replaced with API mock JSON');
    assert.deepEqual(unexpectedApis, [], 'All API requests must be explicitly mocked');
    assert.deepEqual(browserErrors, [], 'No uncaught browser errors');
    console.log(`PASS ${viewport.width}px: uninstall confirmation, failure recovery and deletion after reload`);
    await context.close();
  }
  console.log('PASS skills browser acceptance: 8 scenario groups across desktop/mobile; zero real API requests forwarded');
} finally {
  await browser.close();
}
