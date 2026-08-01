import { existsSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

describe('V2-only frontend boundary', () => {
  it('routes every authenticated default entry to the Project workspace', () => {
    const router = read('../src/router/index.ts');
    const layout = read('../src/components/AppLayout.vue');

    expect(router).not.toContain('ChatPage');
    expect(router).not.toContain("path: '/chat'");
    expect(router).toContain("{ path: '/', redirect: '/projects' }");
    expect(router).toContain("{ path: '/:pathMatch(.*)*', redirect: '/projects' }");
    expect(router).toContain("path: '/paper'");
    expect(router).toContain("path: '/knowledge-base'");
    expect(router).toContain("path: '/settings'");
    expect(layout).not.toContain("'/chat'");
    expect(layout).toContain("router.push('/projects')");
    expect(existsSync(new URL('../src/views/ChatPage.vue', import.meta.url))).toBe(false);
  });

  it('keeps one persisted natural-language V2 conversation surface', () => {
    const project = read('../src/views/ProjectPreviewPage.vue');

    expect(project).toContain('class="v2-conversation__composer"');
    expect(project).toContain('@click="sendV2NaturalLanguageTurn"');
    expect(project).toContain('listV2NaturalLanguageTurns(sessionId, 50)');
    expect(project).toContain('recoverV2NaturalLanguageTurn(activeProjectId.value, sessionId)');
    expect(project).not.toContain('sendProjectMessage');
    expect(project).not.toContain('/api/v1/ws');
    expect(project).not.toContain('listMessages');
    expect(project).not.toContain('listPlans');
    expect(project).not.toContain('startProjectAnalysis');
    expect(project).not.toContain('startProjectCandidate');
  });

  it('removes obsolete V1 and explicit form clients from the browser API', () => {
    const agent = read('../src/api/agent.ts');
    const project = read('../src/api/project.ts');

    expect(agent).not.toContain('sendMessage(');
    expect(agent).not.toContain('listMessages(');
    expect(agent).not.toContain('/agent/plans/');
    expect(agent).not.toContain('/v2/literature-turns');
    expect(project).not.toContain('sendProjectMessage(');
    expect(project).not.toContain('/v2/read-analysis-turns');
    expect(project).not.toContain('/v2/candidate-turns');
    expect(project).not.toContain('/agent/sessions/${sessionId}/plans');
  });
});
