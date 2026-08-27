import type { LongTermMemoryResponse } from '@/api/memory';
import { apiErrorPayload } from '@/api/errors';

export interface MemoryActions {
  confirm: boolean;
  reject: boolean;
  correct: boolean;
  expiry: boolean;
  delete: boolean;
}

const NO_ACTIONS: MemoryActions = Object.freeze({
  confirm: false,
  reject: false,
  correct: false,
  expiry: false,
  delete: false,
});

export function isMemoryExpired(memory: Pick<LongTermMemoryResponse, 'expiresAt'>, now = Date.now()) {
  return Boolean(memory.expiresAt && new Date(memory.expiresAt).getTime() <= now);
}

export function memoryActions(
  memory: Pick<LongTermMemoryResponse, 'status' | 'confirmationStatus' | 'invalidatedAt' | 'expiresAt' | 'supersededByMemoryId'>,
  now = Date.now(),
): MemoryActions {
  if (memory.status !== 'ACTIVE' || memory.invalidatedAt || memory.supersededByMemoryId != null) {
    return NO_ACTIONS;
  }

  const expired = isMemoryExpired(memory, now);
  if (memory.confirmationStatus === 'UNCONFIRMED') {
    return {
      confirm: !expired,
      reject: !expired,
      correct: !expired,
      expiry: false,
      delete: false,
    };
  }
  if (memory.confirmationStatus === 'CONFIRMED' || memory.confirmationStatus === 'REJECTED') {
    return {
      confirm: false,
      reject: false,
      correct: !expired,
      expiry: true,
      delete: true,
    };
  }
  return NO_ACTIONS;
}

export function memoryApiError(error: unknown, fallback: string) {
  return apiErrorPayload(error, fallback).message;
}

export function isStaleMemoryApiError(error: unknown) {
  const status = responseStatus(error);
  const detail = apiErrorPayload(error).message;
  return status === 409 && Boolean(detail && /stale|project.*version/i.test(detail));
}

function responseStatus(error: unknown) {
  if (typeof error !== 'object' || error === null || !('response' in error)) return undefined;
  const response = error.response;
  if (typeof response !== 'object' || response === null || !('status' in response)) return undefined;
  return typeof response.status === 'number' ? response.status : undefined;
}
