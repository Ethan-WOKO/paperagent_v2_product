import type {
  V2NaturalLanguageStepStatus,
  V2NaturalLanguageTurnHistoryItem,
  V2NaturalLanguageTurnRequest,
  V2NaturalLanguageTurnResponse,
  V2NaturalLanguageTurnStartResponse,
  V2NaturalLanguageTurnStatus,
} from '@/api/agent';

export const V2_NATURAL_LANGUAGE_CONTENT_MAX = 20_000;
export const V2_NATURAL_LANGUAGE_POLL_INTERVAL_MS = 2_000;
export const V2_NATURAL_LANGUAGE_POLL_TIMEOUT_MS = 300_000;

export interface V2NaturalLanguageRequestIdentity {
  projectId: number;
  sessionId: number;
  clientRequestId: string;
  sequence: number;
}

interface PollOptions {
  intervalMs?: number;
  timeoutMs?: number;
  signal?: AbortSignal;
  now?: () => number;
  sleep?: (milliseconds: number) => Promise<void>;
  resume?: () => Promise<unknown>;
  onOutcome?: (outcome: V2NaturalLanguageTurnResponse) => void;
}

function abortError() {
  return new DOMException('Aborted', 'AbortError');
}

function status(cause: unknown) {
  return (cause as { response?: { status?: number } } | null)?.response?.status;
}

function defaultSleep(milliseconds: number) {
  return new Promise<void>((resolve) => window.setTimeout(resolve, milliseconds));
}

export function newV2NaturalLanguageClientRequestId(
  randomUuid: () => string = () => crypto.randomUUID(),
) {
  return `v2-turn-${randomUuid()}`;
}

export function normalizeV2NaturalLanguageRequest(
  content: string,
  clientRequestId: string,
): V2NaturalLanguageTurnRequest {
  const normalized = content.trim();
  if (!normalized) throw new Error('content-required');
  if (normalized.length > V2_NATURAL_LANGUAGE_CONTENT_MAX) throw new Error('content-too-long');
  if (!clientRequestId.trim()) throw new Error('client-request-id-required');
  return {
    content: normalized,
    clientRequestId,
  };
}

export function isCurrentV2NaturalLanguageRequest(
  expected: V2NaturalLanguageRequestIdentity,
  current: V2NaturalLanguageRequestIdentity,
) {
  return expected.projectId === current.projectId
    && expected.sessionId === current.sessionId
    && expected.clientRequestId === current.clientRequestId
    && expected.sequence === current.sequence;
}

export function isV2NaturalLanguageTerminal(outcome: V2NaturalLanguageTurnResponse) {
  return ['WAITING_CONFIRMATION', 'SUCCEEDED', 'FAILED'].includes(outcome.status);
}

export function isV2CandidateApplied(
  outcome: Pick<V2NaturalLanguageTurnHistoryItem, 'confirmationValidation'>,
) {
  return outcome.confirmationValidation?.decisionStatus === 'APPLIED'
    && Boolean(outcome.confirmationValidation.appliedRevisionId);
}

export function v2NaturalLanguageStatusLabel(statusValue: V2NaturalLanguageTurnStatus) {
  if (statusValue === 'PLANNING') return '正在制定计划';
  if (statusValue === 'RUNNING') return '正在执行';
  if (statusValue === 'WAITING_CONFIRMATION') return '等待你的确认';
  if (statusValue === 'SUCCEEDED') return '已完成';
  return '执行失败';
}

export function v2NaturalLanguageStepStatusLabel(statusValue: V2NaturalLanguageStepStatus) {
  if (statusValue === 'PENDING') return '等待执行';
  if (statusValue === 'RUNNING') return '正在执行';
  if (statusValue === 'SUCCEEDED') return '已完成';
  if (statusValue === 'FAILED') return '执行失败';
  return '已被新计划替代';
}

export function isDefinitiveV2NaturalLanguageStartRejection(cause: unknown) {
  const value = status(cause);
  return value != null && value >= 400 && value < 500 && value !== 404;
}

export class V2NaturalLanguageTurnNotCreatedError extends Error {
  constructor() {
    super('v2-natural-language-turn-not-created');
    this.name = 'V2NaturalLanguageTurnNotCreatedError';
  }
}

export async function pollV2NaturalLanguageTurn(
  read: () => Promise<V2NaturalLanguageTurnResponse>,
  options: PollOptions = {},
): Promise<V2NaturalLanguageTurnResponse> {
  const intervalMs = options.intervalMs ?? V2_NATURAL_LANGUAGE_POLL_INTERVAL_MS;
  const timeoutMs = options.timeoutMs ?? V2_NATURAL_LANGUAGE_POLL_TIMEOUT_MS;
  if (intervalMs < 1_000 || intervalMs > 10_000) throw new Error('poll-interval-out-of-range');
  if (timeoutMs < intervalMs || timeoutMs > V2_NATURAL_LANGUAGE_POLL_TIMEOUT_MS) {
    throw new Error('poll-timeout-out-of-range');
  }
  const now = options.now ?? Date.now;
  const sleep = options.sleep ?? defaultSleep;
  const startedAt = now();
  while (true) {
    if (options.signal?.aborted) throw abortError();
    const outcome = await read();
    if (options.signal?.aborted) throw abortError();
    options.onOutcome?.(outcome);
    if (isV2NaturalLanguageTerminal(outcome)) return outcome;
    if (now() - startedAt >= timeoutMs) throw new Error('v2-natural-language-poll-timeout');
    await sleep(intervalMs);
    if (options.signal?.aborted) throw abortError();
    if (options.resume) {
      try {
        await options.resume();
      } catch (cause) {
        if (isDefinitiveV2NaturalLanguageStartRejection(cause)) throw cause;
      }
    }
  }
}

export async function startThenPollV2NaturalLanguageTurn(
  start: () => Promise<V2NaturalLanguageTurnStartResponse>,
  read: () => Promise<V2NaturalLanguageTurnResponse>,
  options: PollOptions = {},
): Promise<V2NaturalLanguageTurnResponse> {
  let acknowledgement: V2NaturalLanguageTurnStartResponse;
  try {
    acknowledgement = await start();
  } catch (cause) {
    if (isDefinitiveV2NaturalLanguageStartRejection(cause)) throw cause;
    try {
      const recovered = await read();
      options.onOutcome?.(recovered);
      if (isV2NaturalLanguageTerminal(recovered)) return recovered;
    } catch (readCause) {
      if (status(readCause) === 404) throw new V2NaturalLanguageTurnNotCreatedError();
      throw readCause;
    }
    return pollV2NaturalLanguageTurn(read, options);
  }
  if (acknowledgement.route === 'DIRECT') {
    if (!acknowledgement.answer?.trim()) throw new Error('v2-direct-answer-required');
    return {
      status: 'SUCCEEDED',
      route: 'DIRECT',
      planId: null,
      projectVersion: null,
      steps: [],
      finalText: acknowledgement.answer,
      candidateArtifactId: null,
      outputPaths: [],
      errorCode: null,
    };
  }
  if (acknowledgement.route !== 'PERSISTENT_PLAN_EXECUTE') {
    throw new Error('v2-intake-route-invalid');
  }
  return pollV2NaturalLanguageTurn(read, options);
}
