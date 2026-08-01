import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const panel = readFileSync(new URL('../src/components/ProjectContextDebugPanel.vue', import.meta.url), 'utf8');
const page = readFileSync(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url), 'utf8');
const api = readFileSync(new URL('../src/api/project.ts', import.meta.url), 'utf8');
const types = readFileSync(new URL('../src/api/agent.ts', import.meta.url), 'utf8');

describe('temporary Project context debug view', () => {
  it('keeps the authenticated snapshot contract without exposing the legacy debug panel in V2-only UI', () => {
    expect(api).toContain('/projects/${projectId}/agent/sessions/${sessionId}/context-snapshots');
    expect(page).toContain('listProjectContextSnapshots');
    expect(page).not.toContain('<ProjectContextDebugPanel');
    expect(page).toContain('contextSnapshot.value = snapshots[0] || null');
    expect(page.match(/resetContextDebug\(\);/g)?.length).toBeGreaterThanOrEqual(5);
    expect(types).toContain('context: AgentContextDebugView | null');
  });

  it('shows every required partition and accounting decision without becoming an input surface', () => {
    expect(panel).toContain('currentMessage.content');
    expect(panel).toContain('recentTurns');
    expect(panel).toContain('sessionSummary');
    expect(panel).toContain('project.projectVersion');
    expect(panel).toContain('context.evidence');
    expect(panel).toContain('longTermMemory');
    expect(panel).toContain('context.sections');
    expect(panel).toContain('context.droppedItems');
    expect(panel).not.toContain('<NInput');
    expect(panel).not.toContain('v-model');
  });

  it('never renders evidence or file bodies and contains a 390px overflow guard', () => {
    const evidenceContract = types.slice(
      types.indexOf('export interface AgentContextEvidenceRef'),
      types.indexOf('export interface AgentContextDebugView'),
    );
    expect(evidenceContract).not.toContain('content:');
    expect(panel).not.toContain('item.content');
    expect(panel).toContain('@media (max-width: 390px)');
    expect(panel).toContain('max-width: 100%');
    expect(panel).toContain('overflow-wrap: anywhere');
    expect(panel).toContain('min-width: 0');
  });
});
