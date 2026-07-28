import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';
import type { V2ProjectCandidateTurnResponse } from '../src/api/project';
import {
  isCurrentV2ProjectCandidateRequest,
  isV2ProjectCandidateTerminal,
  newV2ProjectCandidateClientRequestId,
  normalizeV2ProjectCandidateForm,
  startThenPollV2ProjectCandidate,
  V2ProjectCandidateNotCreatedError,
} from '../src/utils/v2ProjectCandidate';

function outcome(status: V2ProjectCandidateTurnResponse['status']): V2ProjectCandidateTurnResponse {
  return {
    projectId: 3, sessionId: 7, clientRequestId: 'project-candidate-fixed',
    status, terminal: status !== 'RUNNING', turnId: 9,
    projectVersion: 'version-1', replayed: false,
  };
}

describe('V2 Project Candidate form', () => {
  it('normalizes a bounded explicit request and stable identity', () => {
    const id = newV2ProjectCandidateClientRequestId(() => 'fixed');
    expect(normalizeV2ProjectCandidateForm({
      objective: '  improve   the explanation ',
      pathsText: 'README.md\nsrc/Guide.md',
    }, id)).toEqual({
      objective: 'improve the explanation',
      paths: ['README.md', 'src/Guide.md'],
      clientRequestId: 'project-candidate-fixed',
    });
  });

  it('rejects blank, duplicate, traversal, and excessive path requests', () => {
    expect(() => normalizeV2ProjectCandidateForm(
      { objective: ' ', pathsText: 'README.md' }, 'id')).toThrow('objective-required');
    expect(() => normalizeV2ProjectCandidateForm(
      { objective: 'edit', pathsText: 'a.md\na.md' }, 'id')).toThrow('paths-must-be-unique');
    expect(() => normalizeV2ProjectCandidateForm(
      { objective: 'edit', pathsText: '../secret' }, 'id')).toThrow('path-invalid');
    expect(() => normalizeV2ProjectCandidateForm(
      { objective: 'edit', pathsText: 'a\nb\nc\nd\ne' }, 'id')).toThrow('paths-out-of-range');
  });
});

describe('V2 Project Candidate recovery and scope', () => {
  it('recovers a lost POST and stops at terminal success', async () => {
    const start = vi.fn(async () => { throw new Error('lost-response'); });
    const states = [outcome('RUNNING'), { ...outcome('SUCCEEDED'), candidateArtifactId: 42 }];
    const read = vi.fn(async () => states.shift()!);
    const result = await startThenPollV2ProjectCandidate(start, read, {
      intervalMs: 1_500, sleep: async () => undefined, now: () => 0,
    });
    expect(result.candidateArtifactId).toBe(42);
    expect(read).toHaveBeenCalledTimes(2);
    expect(isV2ProjectCandidateTerminal(outcome('FAILED'))).toBe(true);
  });

  it('clears a pending identity only after definitive rejection or GET 404', async () => {
    const rejected = { response: { status: 400 } };
    const read = vi.fn(async () => outcome('SUCCEEDED'));
    await expect(startThenPollV2ProjectCandidate(
      async () => { throw rejected; }, read)).rejects.toBe(rejected);
    expect(read).not.toHaveBeenCalled();

    await expect(startThenPollV2ProjectCandidate(
      async () => { throw new Error('lost'); },
      async () => { throw { response: { status: 404 } }; },
    )).rejects.toBeInstanceOf(V2ProjectCandidateNotCreatedError);
  });

  it('suppresses stale Project/session/request/sequence outcomes and aborts polling', async () => {
    const identity = { projectId: 3, sessionId: 7, clientRequestId: 'id', sequence: 2 };
    expect(isCurrentV2ProjectCandidateRequest(identity, { ...identity, projectId: 4 })).toBe(false);
    expect(isCurrentV2ProjectCandidateRequest(identity, { ...identity, sessionId: 8 })).toBe(false);
    expect(isCurrentV2ProjectCandidateRequest(identity, { ...identity, sequence: 3 })).toBe(false);
    const controller = new AbortController();
    controller.abort();
    await expect(startThenPollV2ProjectCandidate(
      async () => outcome('RUNNING'), async () => outcome('RUNNING'),
      { signal: controller.signal },
    )).rejects.toMatchObject({ name: 'AbortError' });
  });
});

describe('Project page Candidate handoff', () => {
  const source = readFileSync(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url), 'utf8');
  const api = readFileSync(new URL('../src/api/project.ts', import.meta.url), 'utf8');

  it('uses explicit candidate endpoints and never routes ordinary chat or calls apply', () => {
    expect(api).toContain('/v2/candidate-turns');
    expect(source).toContain('@click="startProjectCandidate"');
    expect(source).toContain('await startV2ProjectCandidateTurn(projectId, sessionId, request)');
    expect(source).toContain('async function sendChat()');
    expect(source).not.toContain('await applyProjectCandidate(projectId, outcome.candidateArtifactId');
  });

  it('refreshes and selects the returned Candidate while preserving validation confirmation', () => {
    expect(source).toContain('loadCandidates(sessionId, epoch)');
    expect(source).toContain('item.artifact.id === outcome.candidateArtifactId');
    expect(source).toContain('if (candidate) selectCandidate(candidate)');
    expect(source).toContain('candidateCanApply(selectedCandidate.value)');
    expect(source).toContain('openApplyConfirmation');
  });

  it('recovers scoped pending requests and stops on scope change/unmount', () => {
    expect(source).toContain('V2_PROJECT_CANDIDATE_STORAGE_KEY');
    expect(source).toContain('recoverProjectCandidate(activeProjectId.value, sessionId)');
    expect(source).toContain('stopProjectCandidatePolling();');
    expect(source).toContain('onUnmounted(() =>');
  });
});
