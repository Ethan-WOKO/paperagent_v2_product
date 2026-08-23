import { afterEach, describe, expect, it, vi } from 'vitest';
import { streamReactPlanEvents } from '@/api/reactPlan';
import {
  appendReactPlanEvent,
  consumeReactPlanSseChunk,
  formatReactPlanDuration,
  mergeReactPlanSessionTasks,
  newReactPlanCancelId,
  newReactPlanRequestId,
  parseReactPlanHistory,
  parseReactPlanRecord,
  reactPlanActivityEvents,
  reactPlanElapsedMillis,
  reactPlanMessageEvents,
  reactPlanToolLabel,
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

type ToolEvent = Extract<ReactPlanTaskEvent, { type: 'tool' }>;

const toolEvent = (overrides: Partial<ToolEvent> = {}): ToolEvent => ({
  contractVersion: '1.0',
  taskId: `task.${'1'.repeat(64)}`,
  sequence: 1,
  occurredAt: '2026-08-17T00:00:00Z',
  type: 'tool',
  callId: `call.${'2'.repeat(40)}`,
  name: 'registered.invoke',
  registeredToolName: 'search_web',
  state: 'succeeded',
  inputSummary: 'registeredTool=search_web; requestDigest=test',
  outputSummary: 'registeredTool=search_web; success=true; provider=tavily; resultCount=5; evidenceCount=5',
  receiptRef: null,
  ...overrides,
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

  it('keeps all loaded tasks in memory and only bounds the optional browser cache', () => {
    const records = Array.from({ length: 51 }, (_, index) => taskRecord(index + 1));
    const loaded = records.reduce(upsertReactPlanRecord, [] as ReactPlanTaskRecord[]);
    expect(loaded).toHaveLength(51);
    expect(loaded[0]?.turnId).toBe(1);
    expect(loaded[loaded.length - 1]?.turnId).toBe(51);

    const serialized = serializeReactPlanHistory(loaded);
    expect(parseReactPlanHistory(serialized, 1, 2).map((record) => record.turnId))
      .toEqual(Array.from({ length: 50 }, (_, index) => index + 2));
    expect(parseReactPlanHistory(JSON.stringify(taskRecord(7)), 1, 2))
      .toEqual([taskRecord(7)]);
    expect(parseReactPlanHistory(serialized, 9, 2)).toEqual([]);
  });

  it('recovers a session history from the server when local storage is empty', () => {
    const record = taskRecord(7);
    const recovered = mergeReactPlanSessionTasks([], [{
      contractVersion: '1.0',
      clientRequestId: record.clientRequestId,
      instruction: record.instruction,
      turnId: record.turnId,
      taskId: record.taskId,
      task: record.view,
      events: [status(1)],
      startedAt: record.startedAt,
      finishedAt: record.finishedAt,
    }], 1, 2);

    expect(recovered).toEqual([{ ...record, events: [status(1)] }]);
  });

  it('keeps cached events when a server summary intentionally omits event bodies', () => {
    const record = { ...taskRecord(7), events: [status(1)] };
    const merged = mergeReactPlanSessionTasks([record], [{
      contractVersion: '1.0',
      clientRequestId: record.clientRequestId,
      instruction: record.instruction,
      turnId: record.turnId,
      taskId: record.taskId,
      task: record.view,
      events: null,
      startedAt: record.startedAt,
      finishedAt: record.finishedAt,
    }], 1, 2);

    expect(merged[0]?.events).toEqual([status(1)]);
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

  it('shows the real registered tool name and product label', () => {
    expect(reactPlanToolLabel(toolEvent())).toBe('联网搜索（search_web）');
  });

  it('keeps public progress messages in event order', () => {
    const messages: ReactPlanTaskEvent[] = [
      status(1),
      { ...status(2), type: 'message', content: '正在读取项目文件。' },
      toolEvent({ sequence: 3 }),
      { ...status(4), type: 'message', content: '正在核对读取结果。' },
    ];
    expect(reactPlanMessageEvents(messages).map((event) => event.content))
      .toEqual(['正在读取项目文件。', '正在核对读取结果。']);
  });

  it('merges tool and progress events by their authoritative sequence', () => {
    const activity: ReactPlanTaskEvent[] = [
      toolEvent({ sequence: 4 }),
      { ...status(2), type: 'message', content: '正在读取项目文件。' },
      status(1),
      toolEvent({ sequence: 3 }),
    ];
    expect(reactPlanActivityEvents(activity).map((event) => `${event.sequence}:${event.type}`))
      .toEqual(['2:message', '3:tool', '4:tool']);
  });

  it('recognizes historical registered-tool events from their safe summary', () => {
    expect(reactPlanToolLabel(toolEvent({
      name: 'project.read',
      registeredToolName: undefined,
    }))).toBe('联网搜索（search_web）');
  });

  it('keeps native event labels unchanged', () => {
    expect(reactPlanToolLabel(toolEvent({
      name: 'sandbox.execute',
      registeredToolName: undefined,
      inputSummary: 'argvDigest=test',
    }))).toBe('沙箱执行');
  });

  it('shows a live duration and freezes it at the terminal timestamp', () => {
    const running = { ...taskRecord(1), view: { ...taskRecord(1).view, state: 'running' as const }, finishedAt: null };
    expect(reactPlanElapsedMillis(running, Date.parse('2026-08-17T00:01:05Z'))).toBe(65_000);
    expect(formatReactPlanDuration(65_000)).toBe('1 分 5 秒');

    const finished = { ...taskRecord(1), finishedAt: '2026-08-17T00:00:09Z' };
    expect(reactPlanElapsedMillis(finished, Date.parse('2026-08-18T00:00:00Z'))).toBe(9_000);
    expect(formatReactPlanDuration(9_000)).toBe('9 秒');
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
    startedAt: '2026-08-17T00:00:00Z',
    finishedAt: '2026-08-17T00:00:01Z',
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
