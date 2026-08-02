import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const pagePath = fileURLToPath(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url));
const source = readFileSync(pagePath, 'utf8');

describe('Project page presentation contract', () => {
  it('keeps the frozen sidebar allocation while stabilizing every title row', () => {
    expect(source).toContain('.project-sidebar-section--projects { flex: 0 1 25%; min-height: 86px; }');
    expect(source).toContain('.project-sidebar-section--chats { flex: 0 1 25%; min-height: 86px; }');
    expect(source).toContain('.project-sidebar-section--file-browser { flex: 1 1 50%; min-height: 0; }');
    expect(source).toContain('flex: 0 0 32px; min-width: 0; height: 32px; display: flex; align-items: center;');
    expect(source).not.toContain('.project-sidebar-section--collapsed .project-sidebar-section__toggle');
  });

  it('uses the existing UI library chevron with fixed icon buttons and accessible labels', () => {
    expect(source).toContain("import { ChevronRightIcon } from 'naive-ui/es/_internal/icons';");
    expect(source.match(/<ChevronRightIcon \/>/g)?.length).toBeGreaterThanOrEqual(6);
    expect(source).toContain('class="project-chevron-button"');
    expect(source).toContain(":aria-label=\"sidebarSections.projects ? t('project.page.expandProjects') : t('project.page.collapseProjects')\"");
    expect(source).toContain(":aria-label=\"sidebarSections.conversations ? t('project.page.expandConversations') : t('project.page.collapseConversations')\"");
    expect(source).toContain(":aria-label=\"sidebarSections.files ? t('project.page.expandFiles') : t('project.page.collapseFiles')\"");
    expect(source).not.toMatch(/sidebarSections\.[a-z]+ \? '>' : 'v'/);
    expect(source).not.toContain('&gt;</span>');
  });

  it('has one V2 composer, no Chat/Plan mode tabs, and one inspector navigation group', () => {
    expect(source.match(/v-model:value="v2TurnInput"/g)).toHaveLength(1);
    expect(source).not.toContain('v-model:value="chatInput"');
    expect(source).not.toContain('centerTab');
    expect(source).not.toContain('project plan conversation');
    expect(source.match(/class="project-utility-chip"/g)).toHaveLength(4);
    expect(source).not.toContain('@click="inspectorTab =');
  });

  it('renders persisted V2 tasks with one result and collapsed execution details', () => {
    expect(source).toContain('v-for="task in v2TurnHistory"');
    expect(source).toContain('class="v2-task-card__result"');
    expect(source).toContain(':content="task.finalText"');
    expect(source).toContain('class="v2-conversation__process"');
    expect(source.indexOf('class="v2-conversation__process"')).toBeLessThan(source.indexOf('class="v2-task-card__result"'));
    expect(source).toContain(':open="task.status === \'PLANNING\' || task.status === \'RUNNING\'"');
    expect(source).toContain("? '正在处理'");
    expect(source).toContain(": '已处理'");
    expect(source).not.toContain('class="v2-conversation__process" open');
    expect(source).toContain('v-for="step in task.steps"');
    expect(source).not.toContain('item.plan.finalAnswer');
  });

  it('supports a persistent focus view and consumes shared semantic tokens', () => {
    expect(source).toContain("'project-workspace__grid--context-collapsed': contextRailCollapsed");
    expect(source).toContain("contextRailCollapsed ? '展开项目资料' : '收起项目资料'");
    expect(source).toContain('--project-canvas: var(--pa-canvas);');
    expect(source).toContain('--project-surface: var(--pa-surface);');
    expect(source).toContain('inspectorOpen.value = false;');
  });

  it('prevents inspector pills and execution metadata from wrapping or overflowing', () => {
    expect(source).toContain('white-space: nowrap; cursor: pointer;');
    expect(source).toContain('.project-tabs__actions { width: 100%; flex-wrap: nowrap; }');
    expect(source).toContain('.project-execution-card__heading > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap;');
  });
});
