import type {
  V2LiteraturePaperItem,
  V2LiteratureTaskStatus,
  V2LiteratureTurnOutcomeResponse,
  V2LiteratureTurnRequest,
} from '../api/agent';

export const V2_LITERATURE_POLL_INTERVAL_MS = 2_000;
export const V2_LITERATURE_POLL_TIMEOUT_MS = 90_000;
export const V2_LITERATURE_TOP_K_MAX = 20;

export interface V2LiteratureFormValue {
  query: string;
  topK: number | null;
  yearFrom: number | null;
  includeBibtex: boolean;
}

export interface V2LiteraturePresentation {
  status: V2LiteratureTaskStatus;
  tone: 'info' | 'success' | 'warning' | 'error';
  terminal: boolean;
  stage: string;
  papers: Array<V2LiteraturePaperItem & { safeUrl: string | null }>;
}

export function normalizeV2LiteratureForm(
  form: V2LiteratureFormValue,
  clientRequestId: string,
): V2LiteratureTurnRequest {
  const query = form.query.trim().replace(/\s+/g, ' ');
  if (!query) {
    throw new Error('query-required');
  }
  const topK = Number(form.topK);
  if (!Number.isInteger(topK) || topK < 1 || topK > V2_LITERATURE_TOP_K_MAX) {
    throw new Error('top-k-out-of-range');
  }
  const yearFrom = form.yearFrom == null || String(form.yearFrom).trim() === ''
    ? null
    : Number(form.yearFrom);
  const latestAcceptedYear = new Date().getUTCFullYear() + 1;
  if (yearFrom != null && (!Number.isInteger(yearFrom) || yearFrom < 1900 || yearFrom > latestAcceptedYear)) {
    throw new Error('year-out-of-range');
  }
  if (!clientRequestId.trim()) {
    throw new Error('client-request-id-required');
  }
  return {
    query,
    topK,
    ...(yearFrom == null ? {} : { yearFrom }),
    includeBibtex: form.includeBibtex,
    clientRequestId,
  };
}

export function newV2LiteratureClientRequestId(randomUuid?: () => string) {
  const createUuid = randomUuid
    ?? (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? () => crypto.randomUUID()
      : null);
  if (createUuid) {
    return `literature-${createUuid()}`;
  }
  return `literature-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export function isV2LiteratureTerminal(outcome: V2LiteratureTurnOutcomeResponse) {
  return outcome.terminal
    || ['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(outcome.status);
}

export function safeLiteratureUrl(value: string | null | undefined) {
  if (!value) return null;
  try {
    const parsed = new URL(value);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : null;
  } catch {
    return null;
  }
}

export function presentV2LiteratureOutcome(
  outcome: V2LiteratureTurnOutcomeResponse,
): V2LiteraturePresentation {
  const tone = outcome.status === 'COMPLETED'
    ? 'success'
    : outcome.status === 'PARTIAL'
      || outcome.status === 'CANCEL_REQUESTED'
      || outcome.status === 'CANCELLING'
      || outcome.status === 'CANCELLED'
      ? 'warning'
      : outcome.status === 'FAILED'
        ? 'error'
        : 'info';
  return {
    status: outcome.status,
    tone,
    terminal: isV2LiteratureTerminal(outcome),
    stage: outcome.stage?.trim() || outcome.status,
    papers: outcome.items.map((paper) => ({
      ...paper,
      safeUrl: safeLiteratureUrl(paper.url),
    })),
  };
}

export interface PollV2LiteratureOptions {
  intervalMs?: number;
  timeoutMs?: number;
  signal?: AbortSignal;
  now?: () => number;
  sleep?: (milliseconds: number) => Promise<void>;
  onOutcome?: (outcome: V2LiteratureTurnOutcomeResponse) => void;
}

export async function pollV2Literature(
  read: () => Promise<V2LiteratureTurnOutcomeResponse>,
  options: PollV2LiteratureOptions = {},
) {
  const intervalMs = options.intervalMs ?? V2_LITERATURE_POLL_INTERVAL_MS;
  const timeoutMs = options.timeoutMs ?? V2_LITERATURE_POLL_TIMEOUT_MS;
  if (intervalMs < 1_500 || intervalMs > 3_000) {
    throw new Error('poll-interval-out-of-range');
  }
  const now = options.now ?? Date.now;
  const sleep = options.sleep ?? ((milliseconds) => new Promise<void>((resolve) => {
    window.setTimeout(resolve, milliseconds);
  }));
  const startedAt = now();
  while (true) {
    if (options.signal?.aborted) {
      throw new DOMException('Polling aborted', 'AbortError');
    }
    const outcome = await read();
    options.onOutcome?.(outcome);
    if (isV2LiteratureTerminal(outcome)) {
      return outcome;
    }
    if (now() - startedAt >= timeoutMs) {
      throw new Error('literature-poll-timeout');
    }
    await sleep(intervalMs);
  }
}
