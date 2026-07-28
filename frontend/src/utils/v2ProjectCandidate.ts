import type { V2ProjectCandidateTurnRequest, V2ProjectCandidateTurnResponse } from '../api/project';

export const V2_PROJECT_CANDIDATE_POLL_INTERVAL_MS = 2_000;
export const V2_PROJECT_CANDIDATE_POLL_TIMEOUT_MS = 120_000;

export interface V2ProjectCandidateFormValue {
  objective: string;
  pathsText: string;
}

export interface V2ProjectCandidateRequestIdentity {
  projectId: number | null;
  sessionId: number | null;
  clientRequestId: string | null;
  sequence: number;
}

export function normalizeV2ProjectCandidateForm(
  form: V2ProjectCandidateFormValue,
  clientRequestId: string,
): V2ProjectCandidateTurnRequest {
  const objective = form.objective.trim().replace(/\s+/g, ' ');
  if (!objective) throw new Error('objective-required');
  if (objective.length > 2_000) throw new Error('objective-too-long');
  const paths = form.pathsText.split(/\r?\n/).map((value) => value.trim().replace(/\\/g, '/')).filter(Boolean);
  if (paths.length < 1 || paths.length > 4) throw new Error('paths-out-of-range');
  if (new Set(paths).size !== paths.length) throw new Error('paths-must-be-unique');
  if (paths.some((path) => path.startsWith('/') || /^[A-Za-z]:\//.test(path)
      || path.split('/').some((segment) => !segment || segment === '.' || segment === '..'))) {
    throw new Error('path-invalid');
  }
  if (!clientRequestId.trim()) throw new Error('client-request-id-required');
  return { objective, paths, clientRequestId };
}

export function newV2ProjectCandidateClientRequestId(randomUuid?: () => string) {
  const uuid = randomUuid ?? (typeof crypto !== 'undefined' && crypto.randomUUID
    ? () => crypto.randomUUID() : null);
  return uuid ? `project-candidate-${uuid()}`
    : `project-candidate-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function isCurrentV2ProjectCandidateRequest(
  expected: V2ProjectCandidateRequestIdentity,
  current: V2ProjectCandidateRequestIdentity,
) {
  return expected.projectId === current.projectId
    && expected.sessionId === current.sessionId
    && expected.clientRequestId === current.clientRequestId
    && expected.sequence === current.sequence;
}

export function isV2ProjectCandidateTerminal(value: V2ProjectCandidateTurnResponse) {
  return value.terminal || value.status === 'SUCCEEDED' || value.status === 'FAILED';
}

export class V2ProjectCandidateNotCreatedError extends Error {
  constructor() {
    super('project-candidate-not-created');
    this.name = 'V2ProjectCandidateNotCreatedError';
  }
}

function status(cause: unknown) {
  const response = cause && typeof cause === 'object' ? (cause as { response?: unknown }).response : null;
  const value = response && typeof response === 'object' ? (response as { status?: unknown }).status : null;
  return typeof value === 'number' ? value : null;
}

export function isDefinitiveV2ProjectCandidateStartRejection(cause: unknown) {
  const value = status(cause);
  return value !== null && value >= 400 && value < 500 && value !== 408 && value !== 429;
}

export function isV2ProjectCandidateConfirmedNotCreated(cause: unknown) {
  return cause instanceof V2ProjectCandidateNotCreatedError || status(cause) === 404;
}

export async function startThenPollV2ProjectCandidate(
  start: () => Promise<V2ProjectCandidateTurnResponse>,
  read: () => Promise<V2ProjectCandidateTurnResponse>,
  options: {
    intervalMs?: number; timeoutMs?: number; signal?: AbortSignal;
    now?: () => number; sleep?: (milliseconds: number) => Promise<void>;
    onOutcome?: (outcome: V2ProjectCandidateTurnResponse) => void;
  } = {},
) {
  const intervalMs = options.intervalMs ?? V2_PROJECT_CANDIDATE_POLL_INTERVAL_MS;
  const timeoutMs = options.timeoutMs ?? V2_PROJECT_CANDIDATE_POLL_TIMEOUT_MS;
  if (intervalMs < 1_500 || intervalMs > 3_000) throw new Error('poll-interval-out-of-range');
  let startFailure: unknown;
  try {
    const value = await start();
    options.onOutcome?.(value);
    if (isV2ProjectCandidateTerminal(value)) return value;
  } catch (cause) {
    if (isDefinitiveV2ProjectCandidateStartRejection(cause)) throw cause;
    startFailure = cause;
  }
  const now = options.now ?? Date.now;
  const started = now();
  const sleep = options.sleep ?? ((milliseconds: number) => new Promise<void>((resolve, reject) => {
    const timer = window.setTimeout(resolve, milliseconds);
    options.signal?.addEventListener('abort', () => {
      window.clearTimeout(timer);
      reject(new DOMException('Polling aborted', 'AbortError'));
    }, { once: true });
  }));
  while (true) {
    if (options.signal?.aborted) throw new DOMException('Polling aborted', 'AbortError');
    try {
      const value = await read();
      options.onOutcome?.(value);
      if (isV2ProjectCandidateTerminal(value)) return value;
    } catch (cause) {
      if (status(cause) === 404) throw new V2ProjectCandidateNotCreatedError();
      if (startFailure !== undefined) throw startFailure;
      throw cause;
    }
    if (now() - started >= timeoutMs) throw new Error('project-candidate-poll-timeout');
    await sleep(intervalMs);
  }
}
