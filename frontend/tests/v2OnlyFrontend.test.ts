import { existsSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

describe('workspace chat restoration with V2-only Project boundary', () => {
  it('restores the original authenticated workspace chat entry', () => {
    const router = read('../src/router/index.ts');
    const layout = read('../src/components/AppLayout.vue');

    expect(router).toContain("import ChatPage from '@/views/ChatPage.vue'");
    expect(router).toContain("path: '/chat'");
    expect(router).toContain("{ path: '/', redirect: '/chat' }");
    expect(router).toContain("{ path: '/:pathMatch(.*)*', redirect: '/chat' }");
    expect(router).toContain("path: '/paper'");
    expect(router).toContain("path: '/knowledge-base'");
    expect(router).toContain("path: '/settings'");
    expect(layout).toContain("router.push('/chat')");
    expect(layout).toContain("label: t('nav.workspace'), path: '/chat'");
    expect(existsSync(new URL('../src/views/ChatPage.vue', import.meta.url))).toBe(true);
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

  it('keeps workspace V1 clients without restoring Project V1 clients', () => {
    const agent = read('../src/api/agent.ts');
    const project = read('../src/api/project.ts');

    expect(agent).toContain('sendMessage(');
    expect(agent).toContain('listMessages(');
    expect(agent).toContain('/agent/plans/');
    expect(agent).toContain('/v2/literature-turns');
    expect(project).not.toContain('sendProjectMessage(');
    expect(project).not.toContain('/v2/read-analysis-turns');
    expect(project).not.toContain('/v2/candidate-turns');
    expect(project).not.toContain('/agent/sessions/${sessionId}/plans');
  });
});
