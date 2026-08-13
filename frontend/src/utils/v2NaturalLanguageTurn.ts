import type { V2NaturalLanguageTurnRequest } from '@/api/agent';

export const V2_NATURAL_LANGUAGE_CONTENT_MAX = 20_000;

export interface V2NaturalLanguageRequestIdentity {
  projectId: number;
  sessionId: number;
  clientRequestId: string;
  sequence: number;
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
