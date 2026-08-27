import { describe, expect, it } from 'vitest';
import type { LongTermMemoryResponse } from '../src/api/memory';
import { isMemoryExpired, isStaleMemoryApiError, memoryActions, memoryApiError } from '../src/utils/memoryGovernance';

type GovernedFields = Pick<LongTermMemoryResponse,
  'status' | 'confirmationStatus' | 'invalidatedAt' | 'expiresAt' | 'supersededByMemoryId'>;

function memory(overrides: Partial<GovernedFields> = {}): GovernedFields {
  return {
    status: 'ACTIVE',
    confirmationStatus: 'UNCONFIRMED',
    invalidatedAt: null,
    expiresAt: null,
    supersededByMemoryId: null,
    ...overrides,
  };
}

describe('memoryActions', () => {
  it('allows only review actions for an unconfirmed memory', () => {
    expect(memoryActions(memory())).toEqual({
      confirm: true,
      reject: true,
      correct: true,
      expiry: false,
      delete: false,
    });
  });

  it('allows correction, expiry, and deletion for confirmed and rejected memories', () => {
    const expected = { confirm: false, reject: false, correct: true, expiry: true, delete: true };
    expect(memoryActions(memory({ confirmationStatus: 'CONFIRMED' }))).toEqual(expected);
    expect(memoryActions(memory({ confirmationStatus: 'REJECTED' }))).toEqual(expected);
  });

  it('keeps superseded and deleted records read-only', () => {
    const none = { confirm: false, reject: false, correct: false, expiry: false, delete: false };
    expect(memoryActions(memory({ status: 'SUPERSEDED', confirmationStatus: 'CONFIRMED' }))).toEqual(none);
    expect(memoryActions(memory({ status: 'DELETED', confirmationStatus: 'REJECTED' }))).toEqual(none);
    expect(memoryActions(memory({ confirmationStatus: 'CONFIRMED', supersededByMemoryId: 91 }))).toEqual(none);
  });

  it('blocks invalidated records and time-sensitive actions on expired records', () => {
    const now = Date.parse('2026-07-15T12:00:00Z');
    expect(memoryActions(memory({ invalidatedAt: '2026-07-14T12:00:00Z' }), now))
      .toEqual({ confirm: false, reject: false, correct: false, expiry: false, delete: false });
    expect(memoryActions(memory({ confirmationStatus: 'CONFIRMED', expiresAt: '2026-07-15T11:00:00Z' }), now))
      .toEqual({ confirm: false, reject: false, correct: false, expiry: true, delete: true });
    expect(isMemoryExpired(memory({ expiresAt: '2026-07-15T11:00:00Z' }), now)).toBe(true);
  });
});

describe('memoryApiError', () => {
  it('preserves stale Project details and exposes a deterministic stale flag', () => {
    const error = {
      response: {
        status: 409,
        data: {
          code: 'CONFLICT',
          message: 'PROJECT memory is stale for the current Project version',
          fieldErrors: {},
        },
      },
    };

    expect(memoryApiError(error, 'fallback')).toBe('PROJECT memory is stale for the current Project version');
    expect(isStaleMemoryApiError(error)).toBe(true);
  });

  it('preserves readable backend validation details', () => {
    expect(memoryApiError({
      response: {
        status: 400,
        data: { code: 'BAD_REQUEST', message: 'expiresAt must be in the future', fieldErrors: {} },
      },
    }, 'fallback'))
      .toBe('expiresAt must be in the future');
  });
});
