import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const projectSource = readFileSync(new URL('../ProjectPreviewPage.vue', import.meta.url), 'utf8');
const chatSource = readFileSync(new URL('../ChatPage.vue', import.meta.url), 'utf8');
const railSource = readFileSync(new URL('../../components/ConversationQuestionRail.vue', import.meta.url), 'utf8');

describe('shared conversation navigation and resizable Project context rail', () => {
  it('uses one shared question rail in both conversation surfaces', () => {
    expect(projectSource).toContain("import ConversationQuestionRail from '@/components/ConversationQuestionRail.vue'");
    expect(chatSource).toContain("import ConversationQuestionRail from '@/components/ConversationQuestionRail.vue'");
    expect(projectSource).toContain(':items="reactPlanNavigationItems"');
    expect(railSource).toContain("emit('select', item.id)");
    expect(projectSource).toContain('const cardTop = card.getBoundingClientRect().top');
    expect(projectSource).toContain('container.scrollTop + cardTop - containerTop - 8');
  });

  it('supports pointer, keyboard, reset, and persisted rail sizing', () => {
    expect(projectSource).toContain("const PROJECT_LAYOUT_KEY = 'yanban.project.contextLayout.v1'");
    expect(projectSource).toContain("startProjectLayoutResize('width', $event)");
    expect(projectSource).toContain("handleProjectLayoutResizeKey('projects', $event)");
    expect(projectSource).toContain('@dblclick="resetProjectLayout"');
    expect(projectSource).toContain('window.localStorage.setItem(PROJECT_LAYOUT_KEY');
  });

  it('refreshes the persisted session title after the first Project task is accepted', () => {
    expect(projectSource).toContain('void listProjectSessions(projectId).then(({ data }) =>');
    expect(projectSource).toContain('projectSessions.value = data');
  });
});
