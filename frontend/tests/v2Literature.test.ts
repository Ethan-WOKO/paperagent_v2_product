import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';

import type { V2LiteratureTurnOutcomeResponse } from '../src/api/agent';
import {
  isV2LiteratureTerminal,
  newV2LiteratureClientRequestId,
  normalizeV2LiteratureForm,
  pollV2Literature,
  presentV2LiteratureOutcome,
  safeLiteratureUrl,
} from '../src/utils/v2Literature';

function outcome(
  status: V2LiteratureTurnOutcomeResponse['status'],
  overrides: Partial<V2LiteratureTurnOutcomeResponse> = {},
): V2LiteratureTurnOutcomeResponse {
  return {
    sessionId: 7,
    turnId: 11,
    clientRequestId: 'literature-request-1',
    literatureTaskId: 17,
    status,
    stage: status.toLowerCase(),
    terminal: ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(status),
    cancellable: status === 'PENDING' || status === 'RUNNING',
    requestedTopK: 5,
    includeBibtex: true,
    resultMessageId: null,
    resultCount: 0,
    totalCount: 0,
    sourceFailures: [],
    items: [],
    ...overrides,
  };
}

describe('V2 literature form contract', () => {
  it('normalizes only the structured form and preserves one stable request id', () => {
    const id = newV2LiteratureClientRequestId(() => 'fixed-uuid');
    expect(id).toBe('literature-fixed-uuid');
    expect(normalizeV2LiteratureForm({
      query: '  polarimetric   FDA-MIMO\n jamming ',
      topK: 5,
      yearFrom: 2021,
      includeBibtex: true,
    }, id)).toEqual({
      query: 'polarimetric FDA-MIMO jamming',
      topK: 5,
      yearFrom: 2021,
      includeBibtex: true,
      clientRequestId: id,
    });
  });

  it('rejects blank queries and values outside the server bounds', () => {
    expect(() => normalizeV2LiteratureForm({
      query: ' ',
      topK: 5,
      yearFrom: null,
      includeBibtex: false,
    }, 'request')).toThrow('query-required');
    expect(() => normalizeV2LiteratureForm({
      query: 'papers',
      topK: 21,
      yearFrom: null,
      includeBibtex: false,
    }, 'request')).toThrow('top-k-out-of-range');
  });
});

describe('V2 literature polling', () => {
  it('polls PENDING/RUNNING and stops on the first authoritative terminal outcome', async () => {
    const states = [outcome('PENDING'), outcome('RUNNING'), outcome('COMPLETED')];
    const read = vi.fn(async () => states.shift()!);
    const sleep = vi.fn(async () => undefined);
    const seen: string[] = [];

    const terminal = await pollV2Literature(read, {
      intervalMs: 1_500,
      sleep,
      now: () => 0,
      onOutcome: (value) => seen.push(value.status),
    });

    expect(terminal.status).toBe('COMPLETED');
    expect(seen).toEqual(['PENDING', 'RUNNING', 'COMPLETED']);
    expect(read).toHaveBeenCalledTimes(3);
    expect(sleep).toHaveBeenCalledTimes(2);
  });

  it('stops without another read when a session switch aborts polling', async () => {
    const controller = new AbortController();
    const read = vi.fn(async () => outcome('RUNNING'));
    const sleep = vi.fn(async () => {
      controller.abort();
    });

    await expect(pollV2Literature(read, {
      intervalMs: 2_000,
      signal: controller.signal,
      sleep,
      now: () => 0,
    })).rejects.toMatchObject({ name: 'AbortError' });
    expect(read).toHaveBeenCalledTimes(1);
  });

  it('recognizes every true terminal status including partial and cancel', () => {
    expect(isV2LiteratureTerminal(outcome('RUNNING'))).toBe(false);
    expect(isV2LiteratureTerminal(outcome('CANCEL_REQUESTED'))).toBe(false);
    expect(isV2LiteratureTerminal(outcome('CANCELLING'))).toBe(false);
    for (const status of ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'] as const) {
      expect(isV2LiteratureTerminal(outcome(status))).toBe(true);
    }
  });
});

describe('V2 literature result presentation', () => {
  it('renders allowlisted paper facts, safe links, and optional collapsed BibTeX data', () => {
    const presented = presentV2LiteratureOutcome(outcome('PARTIAL', {
      resultCount: 1,
      items: [{
        cardId: 42,
        title: 'A bounded paper',
        authors: ['A. Researcher'],
        year: 2025,
        venue: 'A venue',
        doi: '10.1/example',
        arxivId: null,
        openAlexId: 'W42',
        url: 'https://example.org/paper',
        source: 'OpenAlex',
        score: 0.9,
        bibtex: '@article{bounded}',
      }],
    }));

    expect(presented.tone).toBe('warning');
    expect(presented.papers[0]).toMatchObject({
      title: 'A bounded paper',
      safeUrl: 'https://example.org/paper',
      bibtex: '@article{bounded}',
    });
    expect(safeLiteratureUrl('javascript:alert(1)')).toBeNull();
  });
});

describe('Chat page V2 literature isolation', () => {
  const source = readFileSync(new URL('../src/views/ChatPage.vue', import.meta.url), 'utf8');
  const api = readFileSync(new URL('../src/api/agent.ts', import.meta.url), 'utf8');

  it('uses only the explicit start/read/cancel endpoints and never routes the form through ordinary send', () => {
    expect(api).toContain('/v2/literature-turns');
    expect(api).toContain('/v2/literature-turns/${encodeURIComponent(clientRequestId)}');
    expect(api).toContain('/v2/literature-turns/${encodeURIComponent(clientRequestId)}/cancel');
    expect(source).toContain('@click="startLiteratureSearch"');
    expect(source).toContain('await startV2LiteratureTurn(sessionId, request)');
    expect(source).toContain('async function handleSend()');
    expect(source).toContain('sendMessageWithFallback(sessionId, content');
    expect(source).not.toContain("draft.value.startsWith('/literature')");
  });

  it('clears polling on session switch and unmount, and keeps BibTeX collapsed', () => {
    expect(source).toContain('watch(selectedSessionId');
    expect(source).toContain('stopLiteraturePolling();');
    expect(source).toContain('onBeforeUnmount(() =>');
    expect(source).toContain('literaturePollTimers.forEach((timer) => window.clearTimeout(timer))');
    expect(source).toContain('<details v-if="paper.bibtex"');
    expect(source).toContain('rel="noopener noreferrer"');
  });

  it('cancels only the active scoped request and resumes polling when cancellation is not terminal', () => {
    expect(source).toContain('async function cancelLiteratureSearch()');
    expect(source).toContain('await cancelV2LiteratureTurn(sessionId, clientRequestId)');
    expect(source).toContain('literatureOutcome.value = data;');
    expect(source).toContain('if (!data.terminal && !literaturePolling.value)');
  });
});
