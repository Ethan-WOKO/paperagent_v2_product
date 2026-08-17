import { afterEach, describe, expect, it, vi } from 'vitest';
import { streamReactPlanEvents } from '@/api/reactPlan';
import {
  appendReactPlanEvent,
  consumeReactPlanSseChunk,
  newReactPlanCancelId,
  newReactPlanRequestId,
  parseReactPlanHistory,
  parseReactPlanRecord,
  serializeReactPlanHistory,
  upsertReactPlanRecord,
  type ReactPlanTaskRecord,
  type ReactPlanTaskEvent,
} from '@/utils/reactPlanTask';

vi.mock('@/api/http', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));
vi.mock('@/auth/session', () => ({
  expireAuthSession: vi.fn(),
  isJwtExpired: vi.fn().mockReturnValue(false),
}));

const status = (sequence: number): ReactPlanTaskEvent => ({
  contractVersion: '1.0',
  taskId: `task.${'1'.repeat(64)}`,
  sequence,
  occurredAt: '2026-08-17T00:00:00Z',
  type: 'status',
  state: 'running',
  error: null,
});

describe('ReAct task frontend state', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('generates server-compatible stable request identifiers', () => {
    expect(newReactPlanRequestId(() => '12345678-1234-1234-1234-123456789abc'))
      .toBe('request.12345678-1234-1234-1234-123456789abc');
    expect(newReactPlanCancelId(() => '12345678-1234-1234-1234-123456789abc'))
      .toBe('cancel.12345678-1234-1234-1234-123456789abc');
  });

  it('parses split SSE frames and keeps the incomplete tail', () => {
    const first = consumeReactPlanSseChunk(`id: 1\ndata: ${JSON.stringify(status(1))}\n\nid: 2\ndata:`);
    expect(first.events).toEqual([status(1)]);
    expect(first.remainder).toBe('id: 2\ndata:');
    const second = consumeReactPlanSseChunk(`${first.remainder} ${JSON.stringify(status(2))}\n\n`);
    expect(second.events).toEqual([status(2)]);
    expect(second.remainder).toBe('');
  });

  it('deduplicates replay and rejects a sequence gap', () => {
    const one = appendReactPlanEvent([], status(1), status(1).taskId);
    expect(appendReactPlanEvent(one, status(1), status(1).taskId)).toEqual(one);
    expect(() => appendReactPlanEvent(one, status(3), status(1).taskId))
      .toThrow('reactplan-event-sequence-gap');
  });

  it('rejects a local recovery record from another Project or session', () => {
    const record = JSON.stringify({
      version: 1,
      projectId: 1,
      sessionId: 2,
      clientRequestId: 'request.1234567890123456',
      instruction: 'run',
      turnId: 3,
      taskId: `task.${'1'.repeat(64)}`,
      view: { state: 'running' },
      events: [],
    });
    expect(parseReactPlanRecord(record, 1, 2)?.turnId).toBe(3);
    expect(parseReactPlanRecord(record, 9, 2)).toBeNull();
    expect(parseReactPlanRecord(record, 1, 9)).toBeNull();
  });

  it('migrates one legacy record and keeps a bounded ordered task history', () => {
    const records = Array.from({ length: 13 }, (_, index) => taskRecord(index + 1));
    const bounded = records.reduce(upsertReactPlanRecord, [] as ReactPlanTaskRecord[]);
    expect(bounded).toHaveLength(12);
    expect(bounded[0]?.turnId).toBe(2);
    expect(bounded[bounded.length - 1]?.turnId).toBe(13);

    const serialized = serializeReactPlanHistory(bounded);
    expect(parseReactPlanHistory(serialized, 1, 2).map((record) => record.turnId))
      .toEqual(Array.from({ length: 12 }, (_, index) => index + 2));
    expect(parseReactPlanHistory(JSON.stringify(taskRecord(7)), 1, 2))
      .toEqual([taskRecord(7)]);
    expect(parseReactPlanHistory(serialized, 9, 2)).toEqual([]);
  });

  it('streams with auth and Last-Event-ID and emits parsed events', async () => {
    const payload = `id: 1\ndata: ${JSON.stringify(status(1))}\n\n`;
    const chunks = [new TextEncoder().encode(payload)];
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read: vi.fn()
            .mockResolvedValueOnce({ value: chunks[0], done: false })
            .mockResolvedValueOnce({ value: undefined, done: true }),
        }),
      },
    });
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue('access-token') });
    vi.stubGlobal('fetch', fetchMock);
    const received: ReactPlanTaskEvent[] = [];
    await streamReactPlanEvents(12, status(1).taskId, 7, new AbortController().signal, (event) => {
      received.push(event);
    });
    expect(received).toEqual([status(1)]);
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/react-agent/turns/12/tasks/'),
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer access-token',
          'Last-Event-ID': '7',
        }),
      }),
    );
  });
});

function taskRecord(identity: number): ReactPlanTaskRecord {
  const taskId = `task.${identity.toString(16).padStart(64, '0')}`;
  return {
    version: 1,
    projectId: 1,
    sessionId: 2,
    clientRequestId: `request.${identity.toString(16).padStart(16, '0')}`,
    instruction: `task ${identity}`,
    turnId: identity,
    taskId,
    view: {
      contractVersion: '1.0',
      taskId,
      requestDigest: 'a'.repeat(64),
      state: 'succeeded',
      lastSequence: 2,
      createdAt: '2026-08-17T00:00:00Z',
      updatedAt: '2026-08-17T00:00:01Z',
    },
    events: [],
  };
}
