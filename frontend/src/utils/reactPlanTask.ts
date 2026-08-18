import type {
  ReactPlanProblem,
  ReactPlanSessionTask,
  ReactPlanTaskState,
  ReactPlanTaskView,
} from '@/api/reactPlan';

interface ReactPlanEventBase {
  contractVersion: '1.0';
  taskId: string;
  sequence: number;
  occurredAt: string;
}

export type ReactPlanTaskEvent =
  | (ReactPlanEventBase & { type: 'status'; state: ReactPlanTaskState; error: ReactPlanProblem | null })
  | (ReactPlanEventBase & { type: 'message'; content: string })
  | (ReactPlanEventBase & { type: 'question'; questionId: string; text: string })
  | (ReactPlanEventBase & {
      type: 'tool';
      callId: string;
      name: 'project.list' | 'project.read' | 'workspace.write' | 'workspace.diff'
        | 'sandbox.execute' | 'registered.invoke' | 'project.publish';
      registeredToolName?: string;
      state: 'requested' | 'running' | 'succeeded' | 'failed' | 'cancelled';
      inputSummary: string;
      outputSummary: string | null;
      receiptRef: string | null;
    })
  | (ReactPlanEventBase & { type: 'delivery'; conclusion: string; receiptRefs: string[] });

export interface ReactPlanTaskRecord {
  version: 1;
  projectId: number;
  sessionId: number;
  clientRequestId: string;
  instruction: string;
  turnId: number;
  taskId: string;
  startedAt: string;
  finishedAt: string | null;
  view: ReactPlanTaskView;
  events: ReactPlanTaskEvent[];
}

export interface ReactPlanTaskHistory {
  version: 2;
  projectId: number;
  sessionId: number;
  records: ReactPlanTaskRecord[];
}

export const MAX_REACT_PLAN_CACHE = 50;

export function newReactPlanRequestId(randomUuid: () => string = () => crypto.randomUUID()) {
  return `request.${randomUuid()}`;
}

export function newReactPlanCancelId(randomUuid: () => string = () => crypto.randomUUID()) {
  return `cancel.${randomUuid()}`;
}

export function isReactPlanTerminal(state: ReactPlanTaskState) {
  return state === 'succeeded' || state === 'failed' || state === 'cancelled';
}

export function appendReactPlanEvent(
  events: ReactPlanTaskEvent[],
  event: ReactPlanTaskEvent,
  taskId: string,
) {
  if (event.taskId !== taskId || !Number.isSafeInteger(event.sequence) || event.sequence < 1) return events;
  const lastSequence = events.length ? events[events.length - 1].sequence : 0;
  if (event.sequence <= lastSequence) return events;
  if (event.sequence !== lastSequence + 1) throw new Error('reactplan-event-sequence-gap');
  return [...events, event].slice(-200);
}

export function latestReactPlanQuestion(events: ReactPlanTaskEvent[], pendingQuestionId?: string | null) {
  if (!pendingQuestionId) return null;
  return [...events].reverse().find(
    (event): event is Extract<ReactPlanTaskEvent, { type: 'question' }> => (
      event.type === 'question' && event.questionId === pendingQuestionId
    ),
  ) ?? null;
}

export function reactPlanDelivery(events: ReactPlanTaskEvent[]) {
  return [...events].reverse().find(
    (event): event is Extract<ReactPlanTaskEvent, { type: 'delivery' }> => event.type === 'delivery',
  ) ?? null;
}

export function reactPlanToolEvents(events: ReactPlanTaskEvent[]) {
  return events.filter(
    (event): event is Extract<ReactPlanTaskEvent, { type: 'tool' }> => event.type === 'tool',
  );
}

export function reactPlanStateLabel(state: ReactPlanTaskState) {
  return {
    queued: '等待执行',
    running: '正在执行',
    waiting_user: '等待回复',
    succeeded: '任务已完成',
    failed: '任务未完成',
    cancelled: '执行已取消',
  }[state];
}

export function reactPlanStateTagType(state: ReactPlanTaskState) {
  if (state === 'succeeded') return 'success' as const;
  if (state === 'failed') return 'error' as const;
  if (state === 'waiting_user') return 'warning' as const;
  if (state === 'running') return 'info' as const;
  return 'default' as const;
}

export function reactPlanElapsedMillis(record: ReactPlanTaskRecord, now = Date.now()) {
  const startedAt = Date.parse(record.startedAt);
  const finishedAt = record.finishedAt ? Date.parse(record.finishedAt) : now;
  if (!Number.isFinite(startedAt) || !Number.isFinite(finishedAt)) return 0;
  return Math.max(0, finishedAt - startedAt);
}

export function formatReactPlanDuration(durationMillis: number) {
  const totalSeconds = Math.max(0, Math.floor(durationMillis / 1_000));
  const hours = Math.floor(totalSeconds / 3_600);
  const minutes = Math.floor((totalSeconds % 3_600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) return `${hours} 小时 ${minutes} 分 ${seconds} 秒`;
  if (minutes > 0) return `${minutes} 分 ${seconds} 秒`;
  return `${seconds} 秒`;
}

export function reactPlanToolLabel(tool: Extract<ReactPlanTaskEvent, { type: 'tool' }>) {
  const registeredToolName = tool.registeredToolName ?? registeredToolNameFromSummary(tool.inputSummary);
  if (registeredToolName) {
    const labels: Record<string, string> = {
      search_web: '联网搜索',
      search_knowledge: '检索知识库',
      recommend_literature: '推荐文献',
      literature_search_start: '发起文献检索',
      literature_search_status: '查询文献检索状态',
      literature_search_result: '读取文献检索结果',
      literature_search_cancel: '取消文献检索',
      paper_polish_status: '查询论文任务状态',
      paper_polish_result: '读取论文任务结果',
    };
    const label = labels[registeredToolName];
    return `${label ?? '调用工具'}（${registeredToolName}）`;
  }
  return {
    'project.list': '查看项目文件',
    'project.read': '读取项目信息',
    'workspace.write': '修改隔离工作区',
    'workspace.diff': '查看工作区变更',
    'sandbox.execute': '沙箱执行',
    'registered.invoke': '调用注册工具',
    'project.publish': '发布项目版本',
  }[tool.name];
}

function registeredToolNameFromSummary(summary: string) {
  return summary.match(/(?:^|;\s*)registeredTool=([a-z][a-z0-9_]{0,63})(?:;|$)/)?.[1];
}

export function reactPlanToolStateLabel(state: Extract<ReactPlanTaskEvent, { type: 'tool' }>['state']) {
  return {
    requested: '已请求',
    running: '执行中',
    succeeded: '已完成',
    failed: '失败',
    cancelled: '已取消',
  }[state];
}

export function consumeReactPlanSseChunk(value: string) {
  const normalized = value.replace(/\r\n/g, '\n');
  const frames = normalized.split('\n\n');
  const remainder = frames.pop() ?? '';
  const events: ReactPlanTaskEvent[] = [];
  for (const frame of frames) {
    const data = frame.split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trimStart())
      .join('\n');
    if (!data) continue;
    events.push(JSON.parse(data) as ReactPlanTaskEvent);
  }
  return { events, remainder };
}

export function parseReactPlanRecord(value: string | null, projectId: number, sessionId: number) {
  if (!value) return null;
  try {
    return validReactPlanRecord(JSON.parse(value), projectId, sessionId);
  } catch {
    return null;
  }
}

export function parseReactPlanHistory(value: string | null, projectId: number, sessionId: number) {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value) as ReactPlanTaskHistory | ReactPlanTaskRecord;
    if ((parsed as ReactPlanTaskRecord).version === 1) {
      const migrated = validReactPlanRecord(parsed, projectId, sessionId);
      return migrated ? [migrated] : [];
    }
    const history = parsed as ReactPlanTaskHistory;
    if (history.version !== 2 || history.projectId !== projectId
        || history.sessionId !== sessionId || !Array.isArray(history.records)) return [];
    return history.records.reduce<ReactPlanTaskRecord[]>((records, candidate) => {
      const record = validReactPlanRecord(candidate, projectId, sessionId);
      return record ? upsertReactPlanRecord(records, record) : records;
    }, []).slice(-MAX_REACT_PLAN_CACHE);
  } catch {
    return [];
  }
}

export function upsertReactPlanRecord(
  records: ReactPlanTaskRecord[],
  record: ReactPlanTaskRecord,
) {
  return [...records.filter((candidate) => candidate.taskId !== record.taskId), record]
    .sort((left, right) => Date.parse(left.startedAt) - Date.parse(right.startedAt));
}

export function mergeReactPlanSessionTasks(
  localRecords: ReactPlanTaskRecord[],
  serverTasks: ReactPlanSessionTask[],
  projectId: number,
  sessionId: number,
) {
  const localByTaskId = new Map(localRecords.map((record) => [record.taskId, record]));
  return serverTasks.reduce<ReactPlanTaskRecord[]>((records, task) => {
    const local = localByTaskId.get(task.taskId);
    const record: ReactPlanTaskRecord = {
      version: 1,
      projectId,
      sessionId,
      clientRequestId: task.clientRequestId,
      instruction: task.instruction,
      turnId: task.turnId,
      taskId: task.taskId,
      startedAt: task.startedAt,
      finishedAt: task.finishedAt,
      view: task.task,
      events: task.events ?? local?.events ?? [],
    };
    return upsertReactPlanRecord(records, record);
  }, localRecords);
}

export function serializeReactPlanHistory(records: ReactPlanTaskRecord[]) {
  const latest = records[records.length - 1];
  if (!latest) return null;
  const history: ReactPlanTaskHistory = {
    version: 2,
    projectId: latest.projectId,
    sessionId: latest.sessionId,
    records: records.slice(-MAX_REACT_PLAN_CACHE),
  };
  return JSON.stringify(history);
}

function validReactPlanRecord(value: unknown, projectId: number, sessionId: number) {
  if (!value || typeof value !== 'object') return null;
  const record = value as ReactPlanTaskRecord;
  if (record.version !== 1 || record.projectId !== projectId || record.sessionId !== sessionId) return null;
  if (!record.clientRequestId?.startsWith('request.') || !record.taskId?.startsWith('task.')) return null;
  if (!Number.isSafeInteger(record.turnId) || record.turnId < 1 || !record.view) return null;
  const storedStartedAt = Date.parse(record.startedAt);
  const startedAt = Number.isFinite(storedStartedAt) ? record.startedAt : record.view.createdAt;
  const storedFinishedAt = record.finishedAt ? Date.parse(record.finishedAt) : Number.NaN;
  const finishedAt = isReactPlanTerminal(record.view.state)
    ? (Number.isFinite(storedFinishedAt) ? record.finishedAt : record.view.updatedAt)
    : null;
  return {
    ...record,
    startedAt,
    finishedAt,
    events: Array.isArray(record.events) ? record.events.slice(-200) : [],
  };
}
