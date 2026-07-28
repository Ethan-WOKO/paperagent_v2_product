import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';

import type { V2ProjectReadAnalysisTurnResponse } from '../src/api/project';
import {
  isCurrentV2ProjectAnalysisRequest,
  isV2ProjectAnalysisTerminal,
  newV2ProjectAnalysisClientRequestId,
  normalizeV2ProjectAnalysisForm,
  pollV2ProjectAnalysis,
} from '../src/utils/v2ProjectAnalysis';

function outcome(status: V2ProjectReadAnalysisTurnResponse['status']): V2ProjectReadAnalysisTurnResponse {
  return {
    projectId: 3,
    sessionId: 7,
    clientRequestId: 'project-analysis-fixed',
    status,
    terminal: status !== 'RUNNING',
    turnId: 11,
    projectVersion: 'version-1',
    replayed: false,
  };
}

describe('V2 Project analysis form contract', () => {
  it('normalizes the explicit bounded form and keeps a stable request id', () => {
    const id = newV2ProjectAnalysisClientRequestId(() => 'fixed');
    expect(id).toBe('project-analysis-fixed');
    expect(normalizeV2ProjectAnalysisForm({
      objective: '  explain   the data flow ',
      pathsText: 'src/main.ts\nREADME.md',
      searchQuery: '  request   authority ',
      maxSearchResults: 8,
    }, id)).toEqual({
      objective: 'explain the data flow',
      paths: ['src/main.ts', 'README.md'],
      searchQuery: 'request authority',
      maxSearchResults: 8,
      clientRequestId: id,
    });
  });

  it('rejects blank objectives, duplicate/unsafe paths, and out-of-range search settings', () => {
    const base = { objective: 'inspect', pathsText: 'README.md', searchQuery: '', maxSearchResults: 10 };
    expect(() => normalizeV2ProjectAnalysisForm({ ...base, objective: ' ' }, 'id')).toThrow('objective-required');
    expect(() => normalizeV2ProjectAnalysisForm({ ...base, pathsText: 'a.md\na.md' }, 'id')).toThrow('paths-must-be-unique');
    expect(() => normalizeV2ProjectAnalysisForm({ ...base, pathsText: '../secret' }, 'id')).toThrow('path-invalid');
    expect(() => normalizeV2ProjectAnalysisForm({
      ...base,
      searchQuery: 'needle',
      maxSearchResults: 21,
    }, 'id')).toThrow('search-results-out-of-range');
    expect(() => normalizeV2ProjectAnalysisForm({
      ...base,
      pathsText: 'a\nb\nc\nd\ne',
    }, 'id')).toThrow('paths-out-of-range');
  });
});

describe('V2 Project analysis polling and isolation', () => {
  it('polls RUNNING and stops at the first authoritative terminal response', async () => {
    const states = [outcome('RUNNING'), outcome('SUCCEEDED')];
    const read = vi.fn(async () => states.shift()!);
    const sleep = vi.fn(async () => undefined);
    const result = await pollV2ProjectAnalysis(read, { intervalMs: 1_500, sleep, now: () => 0 });
    expect(result.status).toBe('SUCCEEDED');
    expect(read).toHaveBeenCalledTimes(2);
    expect(sleep).toHaveBeenCalledTimes(1);
    expect(isV2ProjectAnalysisTerminal(outcome('FAILED'))).toBe(true);
  });

  it('stops after project/session switch aborts the current request', async () => {
    const controller = new AbortController();
    const read = vi.fn(async () => outcome('RUNNING'));
    await expect(pollV2ProjectAnalysis(read, {
      intervalMs: 2_000,
      signal: controller.signal,
      sleep: async () => controller.abort(),
      now: () => 0,
    })).rejects.toMatchObject({ name: 'AbortError' });
    expect(read).toHaveBeenCalledTimes(1);
    const identity = { projectId: 3, sessionId: 7, clientRequestId: 'id', sequence: 1 };
    expect(isCurrentV2ProjectAnalysisRequest(identity, { ...identity, projectId: 4 })).toBe(false);
    expect(isCurrentV2ProjectAnalysisRequest(identity, { ...identity, sessionId: 8 })).toBe(false);
  });

  it('enforces the bounded polling interval and timeout', async () => {
    await expect(pollV2ProjectAnalysis(async () => outcome('RUNNING'), {
      intervalMs: 500,
    })).rejects.toThrow('poll-interval-out-of-range');
    let now = 0;
    await expect(pollV2ProjectAnalysis(async () => outcome('RUNNING'), {
      intervalMs: 1_500,
      timeoutMs: 2_000,
      now: () => now,
      sleep: async (milliseconds) => { now += milliseconds; },
    })).rejects.toThrow('project-analysis-poll-timeout');
  });
});

describe('Project page explicit V2 analysis integration', () => {
  const source = readFileSync(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url), 'utf8');
  const api = readFileSync(new URL('../src/api/project.ts', import.meta.url), 'utf8');

  it('uses only explicit start/read endpoints and does not reroute ordinary messages', () => {
    expect(api).toContain('/v2/read-analysis-turns');
    expect(api).toContain('/v2/read-analysis-turns/${encodeURIComponent(clientRequestId)}');
    expect(source).toContain('@click="startProjectAnalysis"');
    expect(source).toContain('await startV2ProjectReadAnalysisTurn(projectId, sessionId, request)');
    expect(source).toContain('async function sendChat()');
    expect(source).toContain('await sendProjectWithFallback(projectId, sessionId, content, requestId)');
    expect(source).not.toContain("chatInput.value.startsWith('/analyze')");
  });

  it('recovers by scoped storage and cleans polling on project/session switch and unmount', () => {
    expect(source).toContain('V2_PROJECT_ANALYSIS_STORAGE_KEY');
    expect(source).toContain('stopProjectAnalysisPolling();');
    expect(source).toContain('async function selectProject(projectId: number)');
    expect(source).toContain('async function selectConversation(sessionId: number)');
    expect(source).toContain('onUnmounted(() =>');
  });

  it('renders the final text through the existing safe markdown component', () => {
    expect(source).toContain(':content="projectAnalysisOutcome.finalText" variant="project"');
  });
});
