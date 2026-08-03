import type {
  V2NaturalLanguageStepStatus,
  V2ContextPhase,
  V2NaturalLanguageTurnContext,
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
  startObservationDelayMs?: number;
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

export function v2ContextPhaseLabel(phase: V2ContextPhase) {
  if (phase === 'ASSEMBLING') return '正在整理上下文';
  if (phase === 'COMPACTION_REQUIRED') return '正在压缩上下文（准备中）';
  if (phase === 'COMPACTING') return '正在压缩上下文';
  if (phase === 'READY') return '上下文已就绪';
  return '上下文准备失败';
}

export function v2ContextSectionLabel(section: string) {
  const labels: Record<string, string> = {
    CORE_AUTHORITY: '核心任务信息',
    RECENT_CONVERSATION: '近期完整对话',
    CONVERSATION_SUMMARY: '对话摘要',
    TOOL_RESULTS: '工具执行结果',
    STEP_STATE: '步骤状态',
    LONG_TERM_MEMORY: '长期记忆',
    RAG_EVIDENCE: '检索证据',
    OUTPUT_RESERVE: '输出预留',
    SAFETY_MARGIN: '安全余量',
  };
  return labels[section] || section;
}

export function v2ContextCompactedSectionText(
  context: V2NaturalLanguageTurnContext,
) {
  if (!context.compactedSections.length) return '';
  return `压缩区域：${context.compactedSections.map(v2ContextSectionLabel).join('、')}`;
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
  let startSettled = false;
  const startAttempt = start()
    .then((acknowledgement) => ({ acknowledgement, cause: null as unknown }))
    .catch((cause: unknown) => ({ acknowledgement: null, cause }))
    .finally(() => {
      startSettled = true;
    });
  let observedWhileStarting: V2NaturalLanguageTurnResponse | null = null;
  const observationDelayMs = options.startObservationDelayMs ?? 250;
  if (observationDelayMs < 0 || observationDelayMs > 1_000) {
    throw new Error('start-observation-delay-out-of-range');
  }
  const startFinishedBeforeObservation = await Promise.race([
    startAttempt.then(() => true),
    new Promise<boolean>((resolve) => globalThis.setTimeout(
      () => resolve(false), observationDelayMs,
    )),
  ]);
  while (!startFinishedBeforeObservation && !startSettled) {
    if (options.signal?.aborted) throw abortError();
    try {
      const observed = await read();
      options.onOutcome?.(observed);
      observedWhileStarting = observed;
      if (isV2NaturalLanguageTerminal(observed)) break;
    } catch (cause) {
      if (isDefinitiveV2NaturalLanguageStartRejection(cause)) throw cause;
    }
    if (!startSettled) {
      await Promise.race([
        startAttempt.then(() => undefined),
        (options.sleep ?? defaultSleep)(
          options.intervalMs ?? V2_NATURAL_LANGUAGE_POLL_INTERVAL_MS,
        ),
      ]);
    }
  }
  const { acknowledgement, cause } = await startAttempt;
  if (cause) {
    if (isDefinitiveV2NaturalLanguageStartRejection(cause)) throw cause;
    if (observedWhileStarting && isV2NaturalLanguageTerminal(observedWhileStarting)) {
      return observedWhileStarting;
    }
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
  if (!acknowledgement) throw new Error('v2-intake-acknowledgement-required');
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
