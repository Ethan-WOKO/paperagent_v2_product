import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const pagePath = fileURLToPath(new URL('../src/views/SettingsPage.vue', import.meta.url));
const stylePath = fileURLToPath(new URL('../src/styles/settings-workspace.css', import.meta.url));
const mainPath = fileURLToPath(new URL('../src/main.ts', import.meta.url));
const page = readFileSync(pagePath, 'utf8');
const styles = readFileSync(stylePath, 'utf8');
const main = readFileSync(mainPath, 'utf8');

describe('settings workspace presentation contract', () => {
  it('uses a compact header, anchor navigation, and continuous settings sheet', () => {
    expect(page).toContain('settings-page--redesign');
    expect(page).toContain('class="settings-header"');
    expect(page).toContain('class="settings-section-nav"');
    for (const id of [
      'provider-settings',
      'default-model-settings',
      'agent-settings',
      'skills-settings',
      'custom-model-settings',
    ]) {
      expect(page).toContain(id);
    }
    expect(page).not.toContain('WorkspaceHero');
  });

  it('preserves all existing settings and custom-model API operations', () => {
    for (const operation of [
      'getSettings',
      'updateSettings',
      'refreshProviderModels',
      'createModel',
      'updateModel',
      'deleteModel',
      'testModel',
      'listSkills',
    ]) {
      expect(page).toContain(operation);
    }
    expect(page).toContain('guardDemoSettings');
    expect(page).toContain('modelForm.apiKey || undefined');
    expect(page).toContain('githubPat: form.githubPat.trim() || undefined');
    expect(page).toContain('deepseekApiKey: form.deepseekApiKey.trim() || undefined');
    expect(page).toContain('glmApiKey: form.glmApiKey.trim() || undefined');
  });

  it('uses shared tokens and explicit desktop, tablet, and mobile layouts', () => {
    expect(styles).toContain('background: var(--pa-canvas)');
    expect(styles).toContain('border-bottom: 1px solid var(--pa-line)');
    expect(styles).toContain('@media (max-width: 1180px)');
    expect(styles).toContain('@media (max-width: 760px)');
    expect(main).toContain("import './styles/settings-workspace.css';");
  });

  it('keeps model catalogs compact while making the active model legible', () => {
    expect(page).toContain('class="settings-default-card__identity"');
    expect(page).toContain('class="settings-default-card__model"');
    expect(page).toContain('class="settings-model-catalog"');
    expect(page).toContain('form.glmModels.length');
    expect(page).toContain("autocomplete: 'new-password'");
    expect(page).toContain("autocomplete: 'off'");
    expect(styles).toContain('.settings-model-catalog > summary');
  });
});
