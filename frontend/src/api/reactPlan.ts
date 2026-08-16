import http from '@/api/http';
import { expireAuthSession, isJwtExpired } from '@/auth/session';
import { consumeReactPlanSseChunk, type ReactPlanTaskEvent } from '@/utils/reactPlanTask';

export type ReactPlanTaskState = 'queued' | 'running' | 'waiting_user' | 'succeeded' | 'failed' | 'cancelled';

export interface ReactPlanProblem {
  contractVersion: '1.0';
  code: string;
  category: string;
  message: string;
  retryable: boolean;
  sourceRef?: string | null;
}

export interface ReactPlanTaskView {
  contractVersion: '1.0';
  taskId: string;
  requestDigest: string;
  state: ReactPlanTaskState;
  lastSequence: number;
  pendingQuestionId?: string | null;
  deliverySequence?: number | null;
  terminalSequence?: number | null;
  error?: ReactPlanProblem | null;
  createdAt: string;
  updatedAt: string;
}

export interface StartReactPlanTaskResponse {
  contractVersion: '1.0';
  replayed: boolean;
  turnId: number;
  taskId: string;
  task: ReactPlanTaskView;
}

export function startReactPlanTask(
  sessionId: number,
  payload: { clientRequestId: string; instruction: string; provider?: string; model?: string },
  signal?: AbortSignal,
) {
  return http.post<StartReactPlanTaskResponse>(
    `/react-agent/sessions/${sessionId}/tasks`,
    payload,
    { signal },
  );
}

export function getReactPlanTask(turnId: number, taskId: string, signal?: AbortSignal) {
  return http.get<ReactPlanTaskView>(
    `/react-agent/turns/${turnId}/tasks/${encodeURIComponent(taskId)}`,
    { signal },
  );
}

export function cancelReactPlanTask(
  turnId: number,
  taskId: string,
  clientRequestId: string,
  signal?: AbortSignal,
) {
  return http.post<ReactPlanTaskView>(
    `/react-agent/turns/${turnId}/tasks/${encodeURIComponent(taskId)}/cancel`,
    { clientRequestId },
    { signal },
  );
}

export function answerReactPlanQuestion(
  turnId: number,
  taskId: string,
  payload: { questionId: string; answer: string },
  signal?: AbortSignal,
) {
  return http.post<ReactPlanTaskView>(
    `/react-agent/turns/${turnId}/tasks/${encodeURIComponent(taskId)}/answer`,
    payload,
    { signal },
  );
}

export async function streamReactPlanEvents(
  turnId: number,
  taskId: string,
  lastEventId: number,
  signal: AbortSignal,
  onEvent: (event: ReactPlanTaskEvent) => void,
) {
  const token = localStorage.getItem('yanban_access_token');
  if (token && isJwtExpired(token)) {
    expireAuthSession();
    throw new Error('Access token expired');
  }
  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
    'Last-Event-ID': String(lastEventId),
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(
    `/api/v1/react-agent/turns/${turnId}/tasks/${encodeURIComponent(taskId)}/events`,
    { headers, signal },
  );
  if (response.status === 401) expireAuthSession();
  if (!response.ok || !response.body) {
    throw new Error(`ReAct event stream failed (${response.status})`);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const parsed = consumeReactPlanSseChunk(buffer);
    buffer = parsed.remainder;
    parsed.events.forEach(onEvent);
    if (done) break;
  }
}
