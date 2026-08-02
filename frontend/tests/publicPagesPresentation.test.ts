import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const read = (path: string) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');
const app = read('../src/App.vue');
const login = read('../src/views/LoginPage.vue');
const register = read('../src/views/RegisterPage.vue');
const demo = read('../src/views/DemoPage.vue');
const shell = read('../src/components/PublicShell.vue');
const accessLayout = read('../src/components/PublicAccessLayout.vue');
const styles = read('../src/styles/public-workspace.css');
const router = read('../src/router/index.ts');

describe('public access presentation contract', () => {
  it('shares one PaperAgent shell with language, theme, and filing controls', () => {
    expect(app).toContain('<PublicShell v-else>');
    expect(shell).toContain('PaperAgent');
    expect(shell).toContain('LanguageToggle');
    expect(shell).toContain('SiteFilingFooter');
    expect(shell).toContain('toggleTheme');
    expect(accessLayout).toContain('public-access__intro');
    expect(accessLayout).toContain('public-access__form');
  });

  it('keeps login redirect and registration validation order unchanged', () => {
    expect(login).toContain('authStore.signIn(form)');
    expect(login).toContain("(route.query.redirect as string) || '/chat'");
    expect(login).toContain('authStore.signInDemo()');
    expect(login).toContain("router.push('/chat?demo=1')");

    const requiredCredentials = register.indexOf('if (!form.username || !form.password)');
    const requiredInvite = register.indexOf('if (!form.inviteCode)');
    const matchingPassword = register.indexOf('if (form.password !== form.confirmPassword)');
    expect(requiredCredentials).toBeGreaterThan(-1);
    expect(requiredCredentials).toBeLessThan(requiredInvite);
    expect(requiredInvite).toBeLessThan(matchingPassword);
    expect(register).toContain('authStore.signUp');
    expect(register).toContain('inviteCode: form.inviteCode');
  });

  it('keeps Demo configuration, availability guard, and question handoff intact', () => {
    expect(demo).toContain('getDemoConfig');
    expect(demo).toContain('config.value && !config.value.enabled');
    expect(demo).toContain("const query: Record<string, string> = { demo: '1' }");
    expect(demo).toContain('query.q = pendingQuestion.value');
    expect(demo).toContain('config?.limitations?.length');
    expect(router).toContain("{ path: '/demo', name: 'demo', component: DemoPage }");
    expect(router).toContain("meta: { guestOnly: true }");
  });

  it('removes the old gradient/card-wall class chain and defines real breakpoints', () => {
    for (const source of [login, register]) {
      expect(source).not.toContain('page-shell auth-shell');
      expect(source).not.toContain('auth-card');
      expect(source).toContain('PublicAccessLayout');
    }
    expect(demo).not.toContain('demo-page');
    expect(demo).not.toContain('demo-preview-card');
    expect(styles).toContain('background: var(--pa-canvas)');
    expect(styles).toContain('@media (max-width: 900px)');
    expect(styles).toContain('@media (max-width: 600px)');
  });
});
