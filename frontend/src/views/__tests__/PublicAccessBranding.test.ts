import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const publicShellSource = readFileSync(new URL('../../components/PublicShell.vue', import.meta.url), 'utf8');
const loginSource = readFileSync(new URL('../LoginPage.vue', import.meta.url), 'utf8');
const registerSource = readFileSync(new URL('../RegisterPage.vue', import.meta.url), 'utf8');
const languageSource = readFileSync(new URL('../../components/LanguageToggle.vue', import.meta.url), 'utf8');
const publicStyles = readFileSync(new URL('../../styles/public-workspace.css', import.meta.url), 'utf8');
const sharedStyles = readFileSync(new URL('../../styles.css', import.meta.url), 'utf8');

describe('研伴公开页面品牌和语言切换', () => {
  it('uses the product name and does not expose internal PaperAgent V2 labels', () => {
    expect(publicShellSource).toContain('aria-label="研伴"');
    expect(publicShellSource).toContain('class="public-shell__brand-mark"');
    expect(publicShellSource).toContain('<span>研伴</span>');
    expect(loginSource).not.toContain('RESEARCH WORKSPACE');
    expect(loginSource).not.toContain('PaperAgent · V2');
    expect(registerSource).not.toContain('CREATE WORKSPACE');
    expect(registerSource).not.toContain('PaperAgent · V2');
  });

  it('enlarges the original mark on a theme-stable contrast surface', () => {
    expect(publicStyles).toContain('width: 40px;');
    expect(publicStyles).toContain('background: #eaf7f6;');
    expect(publicStyles).toContain('transform: scale(1.45);');
  });

  it('shows the QQ support announcement on the login page', () => {
    expect(loginSource).toContain('class="public-access__notice"');
    expect(loginSource).toContain("t('auth.qqSupport')");
    expect(loginSource).toContain('<strong>562720603</strong>');
    expect(registerSource).not.toContain('public-access__notice');
  });

  it('keeps one functional locale button without a reserved empty column', () => {
    expect(languageSource).toContain("locale === 'zh-CN' ? '中' : 'EN'");
    expect(languageSource).toContain('@click="toggleLocale"');
    expect(sharedStyles).toContain('grid-template-columns: 1fr;');
    expect(publicStyles).toContain('width: 30px;');
    expect(publicStyles).not.toContain('width: 74px;');
  });
});
