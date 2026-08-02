import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const appLayoutSource = readFileSync(
  new URL('../src/components/AppLayout.vue', import.meta.url),
  'utf8',
);
const languageToggleSource = readFileSync(
  new URL('../src/components/LanguageToggle.vue', import.meta.url),
  'utf8',
);
const designSystemSource = readFileSync(
  new URL('../src/design-system.css', import.meta.url),
  'utf8',
);
const chatPageSource = readFileSync(
  new URL('../src/views/ChatPage.vue', import.meta.url),
  'utf8',
);
const chatWorkspaceSource = readFileSync(
  new URL('../src/styles/chat-workspace.css', import.meta.url),
  'utf8',
);

describe('responsive product shell presentation', () => {
  it('keeps desktop utilities fixed while only the navigation list scrolls', () => {
    expect(designSystemSource).toMatch(/\.product-rail\s*\{[\s\S]*?overflow:\s*hidden;/);
    expect(designSystemSource).toMatch(/\.product-rail__nav\s*\{[\s\S]*?flex:\s*1 1 auto;[\s\S]*?overflow-y:\s*auto;/);
    expect(designSystemSource).toMatch(/\.product-rail__utilities\s*\{[\s\S]*?flex:\s*0 0 auto;/);
  });

  it('exposes five primary mobile destinations plus a reachable more menu', () => {
    expect(appLayoutSource).toContain('navItems.value.slice(0, 5)');
    expect(appLayoutSource).toContain('navItems.value.slice(5)');
    expect(appLayoutSource).toContain('class="product-rail__more"');
    expect(appLayoutSource).toContain('v-for="item in mobileSecondaryNavItems"');
    expect(designSystemSource).toContain('grid-template-columns: repeat(6, minmax(0, 1fr));');
  });

  it('uses one language button that toggles the current locale', () => {
    expect(languageToggleSource.match(/<button/g)).toHaveLength(1);
    expect(languageToggleSource).toContain('@click="toggleLocale"');
    expect(languageToggleSource).toContain("locale.value === 'zh-CN' ? 'en-US' : 'zh-CN'");
  });

  it('uses a stable system interface type stack and semantic conversation colors', () => {
    expect(designSystemSource).toContain('--pa-font-sans: "Segoe UI Variable Text"');
    expect(designSystemSource).not.toContain('--pa-font-sans: Inter');
    expect(designSystemSource).toContain('--pa-role-user-surface:');
    expect(designSystemSource).toContain('--pa-role-user-border:');
  });
});

describe('responsive chat controls', () => {
  it('moves every execution control into the compact run settings popover', () => {
    expect(chatPageSource).toContain('<NPopover');
    expect(chatPageSource).toContain('class="chat-run-settings-panel"');
    expect(chatPageSource).toContain('v-model:checked="ragDisabled"');
    expect(chatPageSource).toContain('v-model:checked="planMode"');
    expect(chatPageSource).toContain('v-model:value="selectedSkillId"');
    expect(chatPageSource).toContain('v-model:checked="showProcessMessages"');
    expect(chatWorkspaceSource).not.toContain('.chat-toolbar .n-checkbox:nth-of-type');
    expect(chatWorkspaceSource).not.toContain('.chat-composer__quick-actions button:nth-of-type');
  });

  it('keeps model names inspectable in both the trigger and viewport-sized menu', () => {
    expect(chatPageSource).toContain(':title="selectedModelLabel"');
    expect(chatPageSource).toContain(':consistent-menu-width="false"');
    expect(chatPageSource).toContain("class: 'chat-model-menu'");
    expect(chatWorkspaceSource).toContain('max-width: calc(100vw - 24px) !important;');
    expect(chatWorkspaceSource).toContain('white-space: normal;');
  });

  it('treats the compact session sidebar as a dismissible modal layer', () => {
    expect(chatPageSource).toContain('class="chat-sidebar-scrim"');
    expect(chatPageSource).toContain(':inert="mobileSidebarOpen"');
    expect(chatPageSource).toContain("event.key === 'Escape' && mobileSidebarOpen.value");
    expect(chatPageSource).toContain("window.matchMedia('(max-width: 980px)')");
    expect(chatWorkspaceSource).toMatch(/\.chat-sidebar-scrim\s*\{[\s\S]*?z-index:\s*35;/);
  });

  it('keeps primary mobile chat controls at touch-friendly sizes', () => {
    expect(chatWorkspaceSource).toMatch(/\.chat-rail-toggle\s*\{[\s\S]*?width:\s*44px;[\s\S]*?height:\s*44px;/);
    expect(chatWorkspaceSource).toMatch(/\.chat-upload-button\s*\{[\s\S]*?width:\s*44px !important;[\s\S]*?height:\s*44px !important;/);
    expect(chatWorkspaceSource).toMatch(/\.chat-composer__quick-actions button\s*\{[\s\S]*?min-height:\s*40px;/);
  });
});
