import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const pagePath = fileURLToPath(new URL('../src/views/PaperPage.vue', import.meta.url));
const appPath = fileURLToPath(new URL('../src/App.vue', import.meta.url));
const stylePath = fileURLToPath(new URL('../src/styles/paper-workspace.css', import.meta.url));
const source = readFileSync(pagePath, 'utf8');
const appSource = readFileSync(appPath, 'utf8');
const styles = readFileSync(stylePath, 'utf8');

describe('Paper page presentation contract', () => {
  it('keeps one real manuscript input and removes the hidden duplicate workspace', () => {
    expect(source.match(/ref="texInputRef"/g)).toHaveLength(1);
    expect(source.match(/ref="bibInputRef"/g)).toHaveLength(1);
    expect(source).not.toContain('paper-steps-bar');
    expect(source).toContain('Accept selected');
    expect(source).toContain('selectedCandidateCount');
    expect(source).not.toContain('NCollapseItem');
  });

  it('uses only task-backed facts instead of invented project metadata', () => {
    expect(source).toContain("currentTask?.sourceFilename || selectedTexFile?.name");
    expect(source).toContain('#{{ currentTask.id }}');
    expect(source).toContain('currentTask.targetLanguage || form.targetLanguage');
    expect(source).toContain('artifacts.length');
    expect(source).not.toContain('Full paper polish');
    expect(source).not.toContain("'Owner'");
    expect(source).not.toContain("'Project'");
    expect(source).not.toContain("t('paper.saveDraft')");
  });

  it('keeps task history visible independently from the selected task', () => {
    expect(source).toContain('<aside class="paper-polish-side">');
    expect(source).toContain('paper-history-card-v2');
    expect(source).toContain("isEnglish ? 'Polish tasks' : '润色任务'");
    expect(source).toContain('historyTaskDownloadable(task)');
    expect(source).toContain(':title="task.title || task.sourceFilename || `Task ${task.id}`"');
    expect(source).toContain(':aria-label="task.title || task.sourceFilename || `Task ${task.id}`"');
    expect(source).toContain('formatTaskListDate(task.updatedAt)');
    expect(source).not.toContain('<NTabPane name="history"');
    expect(styles).toContain('grid-template-columns: minmax(54px, 1fr) 76px minmax(48px, .7fr) 72px 40px;');
    expect(styles).toContain('.paper-history-compact-v2__primary,');
    expect(styles).toContain('display: contents;');
  });

  it('consolidates selected-task information into one evidence-oriented side panel', () => {
    expect(source).toContain('class="workbench-card scholar-card paper-polish-card paper-side-tabs"');
    expect(source).toContain('<NTabPane name="evidence"');
    expect(source).toContain('<NTabPane name="artifacts"');
    expect(source).toContain('<NTabPane name="events"');
    expect(source).toContain('selectedSuggestionEvidence');
  });

  it('defaults advanced parameters to a native collapsed disclosure', () => {
    expect(source).toContain('<details class="paper-config-grid">');
    expect(source).not.toContain('<details class="paper-config-grid" open>');
  });

  it('progressively reveals task-only workspaces after a paper task exists', () => {
    expect(source).toContain('<div class="paper-polish-shell">');
    expect(source).toContain('<details v-if="currentTask" class="workbench-card scholar-card paper-polish-card paper-workflow-card-v2">');
    expect(source).toContain("isEnglish ? 'View 9 stages' : '查看 9 个阶段'");
    expect(source).toContain('<NCard v-if="currentTask" class="workbench-card scholar-card paper-polish-card paper-side-tabs"');
    expect(source).toContain('<NCard v-if="currentTask" id="paper-structure-confirmation"');
    expect(styles).toContain('.paper-history-card-v2');
  });

  it('loads structure confirmations immediately and opens the modal only when data is ready', () => {
    expect(source).toContain("event.status === 'WAITING_INPUT'");
    expect(source).toContain("event.stage === 'STRUCTURE_CHECK'");
    expect(source).toContain('await loadClarifications(taskId);');
    expect(source).toContain('const structureConfirmationReady = computed');
    expect(source).toContain('watch(structureConfirmationReady');
    expect(source).toContain('const clarificationPromise = loadClarifications(taskId);');
    expect(source).toContain('clarificationsLoading');
    expect(source).toContain('clarificationsError');
  });

  it('opts into real responsive layout and shared semantic tokens', () => {
    expect(appSource).toContain("route.path.startsWith('/projects')");
    expect(appSource).toContain("route.path.startsWith('/paper')");
    expect(styles).toContain('.paper-page--redesign');
    expect(styles).toContain('background: var(--pa-canvas);');
    expect(styles).toContain('grid-template-columns: minmax(0, 1fr) 330px');
    expect(styles).toContain('@media (max-width: 760px)');
  });
});
