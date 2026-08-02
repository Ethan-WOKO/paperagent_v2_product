import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const knowledgePath = fileURLToPath(new URL('../src/views/KnowledgeBasePage.vue', import.meta.url));
const searchPath = fileURLToPath(new URL('../src/views/KnowledgeSearchDebugPage.vue', import.meta.url));
const appPath = fileURLToPath(new URL('../src/App.vue', import.meta.url));
const stylePath = fileURLToPath(new URL('../src/styles/knowledge-workspace.css', import.meta.url));
const knowledgeSource = readFileSync(knowledgePath, 'utf8');
const searchSource = readFileSync(searchPath, 'utf8');
const appSource = readFileSync(appPath, 'utf8');
const styles = readFileSync(stylePath, 'utf8');

describe('Knowledge workspace presentation contract', () => {
  it('keeps upload available but hides it by default to protect browsing space', () => {
    expect(knowledgeSource).toContain("readStoredBoolean('yanban.knowledge.uploadOpen', false)");
    expect(knowledgeSource).toContain('v-if="uploadPanelOpen"');
    expect(knowledgeSource).toContain('@click="toggleUploadPanel"');
    expect(knowledgeSource).toContain('accept=".pdf,.docx,.txt,.md"');
    expect(knowledgeSource).toContain('v-if="documents.length > 0" class="scholar-metric-strip kb-metric-strip"');
    expect(knowledgeSource).toContain("'上传第一份文档'");
    expect(knowledgeSource).toContain('documents.length > 0 || !uploadPanelOpen || loading');
  });

  it('keeps file selection keyboard accessible and preserves upload visibility controls', () => {
    expect(knowledgeSource).toContain('role="button"');
    expect(knowledgeSource).toContain('tabindex="0"');
    expect(knowledgeSource).toContain('@keydown.enter.prevent="fileInputRef?.click()"');
    expect(knowledgeSource).toContain('@keydown.space.prevent="fileInputRef?.click()"');
    expect(knowledgeSource).toContain('v-model:checked="isPublic"');
  });

  it('opens parsed text as an optional inspector rather than reserving an empty column', () => {
    expect(knowledgeSource).toContain("'kb-workspace--preview-open': previewDocument");
    expect(knowledgeSource).toContain('v-if="previewDocument"');
    expect(knowledgeSource).toContain('ref="previewPanelRef"');
    expect(knowledgeSource).toContain('@click.self="closePreview"');
    expect(knowledgeSource).toContain('@keydown.esc.stop="closePreview"');
    expect(knowledgeSource).toContain("window.matchMedia('(max-width: 1100px)')");
    expect(knowledgeSource).toContain('@click="closePreview"');
    expect(knowledgeSource).not.toContain('The full text still participates in retrieval.');
  });

  it('removes invented safety verification and derives diagnostics from returned chunks', () => {
    expect(searchSource).not.toContain('No cross-user leakage');
    expect(searchSource).not.toContain('<strong>Pass</strong>');
    expect(searchSource).toContain('privateResultCount');
    expect(searchSource).toContain('results.value.filter((item) => !item.isPublic).length');
    expect(searchSource).not.toContain("recallStatusLabel.value === 'Good'");
  });

  it('makes result selection keyboard accessible and diagnostics dismissible', () => {
    expect(searchSource).toContain("'search-workspace--diagnostics-open': results.length > 0 && diagnosticsVisible");
    expect(searchSource).toContain('@keydown.enter.prevent="selectedIndex = index"');
    expect(searchSource).toContain('@keydown.space.prevent="selectedIndex = index"');
    expect(searchSource).toContain('ref="diagnosticsPanelRef"');
    expect(searchSource).toContain('@click.self="closeDiagnostics"');
    expect(searchSource).toContain('@keydown.esc.stop="closeDiagnostics"');
    expect(searchSource).toContain("window.matchMedia('(max-width: 1180px)')");
    expect(searchSource).toContain('class="search-result-snippet"');
    expect(searchSource).toContain('v-if="searching || results.length > 0"');
    expect(searchSource).toContain(':autosize="{ minRows: 1, maxRows: 4 }"');
  });

  it('uses viewport-bound inspectors and keeps narrow-screen result content available', () => {
    expect(styles).toContain('@media (max-width: 1100px)');
    expect(styles).toContain('@media (max-width: 1180px)');
    expect(styles).toContain('height: 100dvh;');
    expect(styles).toContain('overscroll-behavior: contain;');
    expect(styles).toContain('-webkit-line-clamp: unset;');
    expect(styles).not.toContain('.search-result-row > .n-tag:last-of-type');
  });

  it('uses responsive semantic-token styling for both knowledge routes', () => {
    expect(appSource).toContain("route.path.startsWith('/knowledge-base')");
    expect(styles).toContain('.kb-page--redesign');
    expect(styles).toContain('.search-page--redesign');
    expect(styles).toContain('background: var(--pa-canvas);');
    expect(styles).toContain('@media (max-width: 760px)');
    expect(styles).toContain('@media (max-width: 900px)');
  });
});
