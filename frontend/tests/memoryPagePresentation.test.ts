import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const pagePath = fileURLToPath(new URL('../src/views/MemorySettingsPage.vue', import.meta.url));
const stylePath = fileURLToPath(new URL('../src/styles/memory-workspace.css', import.meta.url));
const appPath = fileURLToPath(new URL('../src/App.vue', import.meta.url));
const mainPath = fileURLToPath(new URL('../src/main.ts', import.meta.url));
const page = readFileSync(pagePath, 'utf8');
const styles = readFileSync(stylePath, 'utf8');
const app = readFileSync(appPath, 'utf8');
const main = readFileSync(mainPath, 'utf8');

describe('long-term memory presentation contract', () => {
  it('uses a compact governance ledger with secondary metadata collapsed', () => {
    expect(page).toContain('memory-page--redesign');
    expect(page).toContain('class="memory-list__head"');
    expect(page).toContain('class="memory-record__summary-cells"');
    expect(page).toContain('<details class="memory-record__details">');
    expect(page.indexOf('<details class="memory-record__details">'))
      .toBeLessThan(page.indexOf('<dl class="memory-fields">'));
    expect(page).toContain("t('memory.field.confirmationStatus')");
    expect(page).toContain("t('memory.content.showDetails')");
  });

  it('preserves every existing memory-governance operation', () => {
    for (const operation of [
      'listLongTermMemories',
      'createLongTermMemory',
      'correctLongTermMemory',
      'confirmLongTermMemory',
      'rejectLongTermMemory',
      'deleteLongTermMemory',
      'updateLongTermMemoryExpiry',
    ]) {
      expect(page).toContain(operation);
    }
  });

  it('uses shared design tokens and true tablet/mobile layouts', () => {
    expect(styles).toContain('background: var(--pa-surface);');
    expect(styles).toContain('border: 1px solid var(--pa-line);');
    expect(styles).toContain('@media (max-width: 1180px)');
    expect(styles).toContain('@media (max-width: 760px)');
    expect(styles).toContain('min-height: clamp(220px, 30vh, 320px);');
    expect(styles).toContain('position: sticky;');
    expect(app).toContain("route.path.startsWith('/settings')");
    expect(main).toContain("import './styles/memory-workspace.css';");
  });
});
