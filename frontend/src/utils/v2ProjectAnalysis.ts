import type {
  V2ProjectReadAnalysisTurnRequest,
  V2ProjectReadAnalysisTurnResponse,
} from '../api/project';

export const V2_PROJECT_ANALYSIS_OBJECTIVE_MAX = 2_000;
export const V2_PROJECT_ANALYSIS_PATHS_MAX = 4;
export const V2_PROJECT_ANALYSIS_SEARCH_MAX = 256;
export const V2_PROJECT_ANALYSIS_RESULTS_MAX = 20;
export const V2_PROJECT_ANALYSIS_POLL_INTERVAL_MS = 2_000;
export const V2_PROJECT_ANALYSIS_POLL_TIMEOUT_MS = 90_000;

export interface V2ProjectAnalysisFormValue {
  objective: string;
  pathsText: string;
  searchQuery: string;
  maxSearchResults: number | null;
}

export interface V2ProjectAnalysisRequestIdentity {
  projectId: number | null;
  sessionId: number | null;
  clientRequestId: string | null;
  sequence: number;
}

export function normalizeV2ProjectAnalysisForm(
  form: V2ProjectAnalysisFormValue,
  clientRequestId: string,
): V2ProjectReadAnalysisTurnRequest {
  const objective = form.objective.trim().replace(/\s+/g, ' ');
  if (!objective) throw new Error('objective-required');
  if (objective.length > V2_PROJECT_ANALYSIS_OBJECTIVE_MAX) throw new Error('objective-too-long');

  const paths = form.pathsText
    .split(/\r?\n/)
    .map((path) => path.trim().replace(/\\/g, '/'))
    .filter(Boolean);
  if (paths.length < 1 || paths.length > V2_PROJECT_ANALYSIS_PATHS_MAX) {
    throw new Error('paths-out-of-range');
  }
  if (new Set(paths).size !== paths.length) throw new Error('paths-must-be-unique');
  if (paths.some((path) => path.startsWith('/') || /^[A-Za-z]:\//.test(path)
      || path.split('/').some((segment) => segment === '..' || segment === '.' || !segment))) {
    throw new Error('path-invalid');
  }

  const searchQuery = form.searchQuery.trim().replace(/\s+/g, ' ');
  if (searchQuery.length > V2_PROJECT_ANALYSIS_SEARCH_MAX) throw new Error('search-too-long');
  const maxSearchResults = Number(form.maxSearchResults);
  if (searchQuery && (!Number.isInteger(maxSearchResults)
      || maxSearchResults < 1 || maxSearchResults > V2_PROJECT_ANALYSIS_RESULTS_MAX)) {
    throw new Error('search-results-out-of-range');
  }
  if (!clientRequestId.trim()) throw new Error('client-request-id-required');

  return {
    objective,
    paths,
    ...(searchQuery ? { searchQuery, maxSearchResults } : {}),
    clientRequestId,
  };
}

export function newV2ProjectAnalysisClientRequestId(randomUuid?: () => string) {
  const createUuid = randomUuid
    ?? (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? () => crypto.randomUUID()
      : null);
  return createUuid
    ? `project-analysis-${createUuid()}`
    : `project-analysis-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function isCurrentV2ProjectAnalysisRequest(
  expected: V2ProjectAnalysisRequestIdentity,
  current: V2ProjectAnalysisRequestIdentity,
) {
  return expected.projectId === current.projectId
    && expected.sessionId === current.sessionId
    && expected.clientRequestId === current.clientRequestId
    && expected.sequence === current.sequence;
}

export function isV2ProjectAnalysisTerminal(outcome: V2ProjectReadAnalysisTurnResponse) {
  return outcome.terminal || outcome.status === 'SUCCEEDED' || outcome.status === 'FAILED';
}

export interface PollV2ProjectAnalysisOptions {
  intervalMs?: number;
  timeoutMs?: number;
  signal?: AbortSignal;
  now?: () => number;
  sleep?: (milliseconds: number) => Promise<void>;
  onOutcome?: (outcome: V2ProjectReadAnalysisTurnResponse) => void;
}

export class V2ProjectAnalysisNotCreatedError extends Error {
  constructor() {
    super('project-analysis-not-created');
    this.name = 'V2ProjectAnalysisNotCreatedError';
  }
}

function responseStatus(cause: unknown) {
  if (!cause || typeof cause !== 'object') return null;
  const response = (cause as { response?: unknown }).response;
  if (!response || typeof response !== 'object') return null;
  const status = (response as { status?: unknown }).status;
  return typeof status === 'number' ? status : null;
}

export function isDefinitiveV2ProjectAnalysisStartRejection(cause: unknown) {
  const status = responseStatus(cause);
  return status !== null && status >= 400 && status < 500
    && status !== 408 && status !== 429;
}

export function isV2ProjectAnalysisConfirmedNotCreated(cause: unknown) {
  return cause instanceof V2ProjectAnalysisNotCreatedError
    || responseStatus(cause) === 404;
}

export async function pollV2ProjectAnalysis(
  read: () => Promise<V2ProjectReadAnalysisTurnResponse>,
  options: PollV2ProjectAnalysisOptions = {},
) {
  const intervalMs = options.intervalMs ?? V2_PROJECT_ANALYSIS_POLL_INTERVAL_MS;
  const timeoutMs = options.timeoutMs ?? V2_PROJECT_ANALYSIS_POLL_TIMEOUT_MS;
  if (intervalMs < 1_500 || intervalMs > 3_000) throw new Error('poll-interval-out-of-range');
  const now = options.now ?? Date.now;
  const sleep = options.sleep ?? ((milliseconds) => new Promise<void>((resolve, reject) => {
    const onAbort = () => {
      window.clearTimeout(timer);
      reject(new DOMException('Polling aborted', 'AbortError'));
    };
    const timer = window.setTimeout(() => {
      options.signal?.removeEventListener('abort', onAbort);
      resolve();
    }, milliseconds);
    options.signal?.addEventListener('abort', onAbort, { once: true });
  }));
  const startedAt = now();
  while (true) {
    if (options.signal?.aborted) throw new DOMException('Polling aborted', 'AbortError');
    const outcome = await read();
    options.onOutcome?.(outcome);
    if (isV2ProjectAnalysisTerminal(outcome)) return outcome;
    if (now() - startedAt >= timeoutMs) throw new Error('project-analysis-poll-timeout');
    await sleep(intervalMs);
  }
}

export async function startThenPollV2ProjectAnalysis(
  start: () => Promise<V2ProjectReadAnalysisTurnResponse>,
  read: () => Promise<V2ProjectReadAnalysisTurnResponse>,
  options: PollV2ProjectAnalysisOptions = {},
) {
  let startFailure: unknown;
  try {
    const outcome = await start();
    options.onOutcome?.(outcome);
    if (isV2ProjectAnalysisTerminal(outcome)) return outcome;
  } catch (cause) {
    if (isDefinitiveV2ProjectAnalysisStartRejection(cause)) throw cause;
    startFailure = cause;
  }
  try {
    return await pollV2ProjectAnalysis(read, options);
  } catch (recoveryFailure) {
    if (responseStatus(recoveryFailure) === 404) {
      throw new V2ProjectAnalysisNotCreatedError();
    }
    if (startFailure !== undefined) throw startFailure;
    throw recoveryFailure;
  }
}
