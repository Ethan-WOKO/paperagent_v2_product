// Run against local Vite: node frontend/tests/adminLayout.browser.mjs
// All APIs are mocked in an isolated browser context; no account or data is changed.
import assert from 'node:assert/strict';
import { chromium } from '../../agent-engine-reactplan/node_modules/playwright-core/index.mjs';

const browser = await chromium.launch({
  executablePath: process.env.PAPERAGENT_EVAL_BROWSER ?? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  headless: true,
});
try {
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    const page = await browser.newPage({ viewport });
    page.on('pageerror', error => console.error('Browser error:', error.message));
    const user = { id: 99, username: 'Layout fixture', role: 'ADMIN', accountType: 'DEMO', aiQuotaTotal: -1,
      aiQuotaUsed: 0, chatSessionCount: 100, paperTaskCount: 0, projectCount: 0, createdAt: '2026-09-09T00:00:00Z' };
    const chats = Array.from({ length: 100 }, (_, index) => ({
      id: index + 1, title: `Conversation ${index + 1}`, scope: 'WORKSPACE', archived: true,
      modelProvider: 'fixture', model: 'fixture', updatedAt: user.createdAt,
      messages: [{ id: index + 1, role: 'user', content: 'Long historical message\n'.repeat(300), createdAt: user.createdAt, deletable: true }],
    }));
    await page.addInitScript(() => {
      const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }));
      localStorage.setItem('yanban_access_token', `fixture.${payload}.fixture`);
    });
    await page.route('**/api/**', async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      const data = path.endsWith('/users/me') ? user
        : path.endsWith('/admin/users') ? [user]
        : path.endsWith('/admin/users/99') ? { user, chats, papers: [], projects: [], usage: [] }
        : path.endsWith('/admin/invite-codes') ? [] : {};
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(data) });
    });
    await page.goto(`${process.env.PAPERAGENT_EVAL_WEB_ORIGIN ?? 'http://127.0.0.1:5173'}/admin`);
    await page.addStyleTag({ content: '*, *::before, *::after { transition-duration: 0s !important; animation-duration: 0s !important; }' });
    await page.getByRole('button', { name: /Layout fixture/ }).click({ timeout: 10000 }).catch(async error => {
      console.error('Unexpected page:', page.url(), (await page.locator('body').innerText()).slice(0, 1500));
      throw error;
    });
    await page.locator('.n-tabs-tab').filter({ hasText: /^聊天$/ }).click();
    const panel = page.getByRole('region', { name: '历史对话，可在区域内滚动' });
    await panel.waitFor({ state: 'visible' });
    const before = await page.locator('.admin-invites').evaluate(el => el.getBoundingClientRect().top + window.scrollY);
    await page.locator('.admin-chat-session summary').first().click();
    const after = await page.locator('.admin-invites').evaluate(el => el.getBoundingClientRect().top + window.scrollY);
    assert.ok(Math.abs(after - before) < 2, `Expanding a long conversation must not move invitations: ${before} -> ${after}`);
    const dimensions = await panel.evaluate(el => {
      el.scrollTop = 100;
      return { height: el.clientHeight, scrollHeight: el.scrollHeight, scrollTop: el.scrollTop };
    });
    assert.ok(dimensions.height >= 240 && dimensions.height <= 520);
    assert.ok(dimensions.scrollHeight > dimensions.height && dimensions.scrollTop > 0);
    await panel.focus();
    await page.keyboard.press('Home');
    assert.equal(await panel.evaluate(el => document.activeElement === el), true);
    await page.getByRole('button', { name: '生成邀请码', exact: true }).scrollIntoViewIfNeeded();
    assert.equal(await page.getByRole('button', { name: '生成邀请码', exact: true }).isVisible(), true);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true);
    console.log(`PASS admin containment, scroll, keyboard focus and invite access at ${viewport.width}px`);
    await page.close();
  }
} finally {
  await browser.close();
}
