import { beforeEach, describe, expect, it, vi } from 'vitest';

const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('../src/api/http', () => ({ default: http }));

import {
  confirmLongTermMemory,
  correctLongTermMemory,
  createLongTermMemory,
  deleteLongTermMemory,
  getMemoryDistillationJob,
  getMemoryDistillationSettings,
  listLongTermMemories,
  rejectLongTermMemory,
  startMemoryDistillation,
  updateMemoryDistillationSettings,
  updateLongTermMemoryExpiry,
} from '../src/api/memory';

describe('long-term memory API client', () => {
  beforeEach(() => vi.clearAllMocks());

  it('maps ACTIVE and ALL list requests to the governance endpoint', () => {
    listLongTermMemories('ACTIVE', 50);
    listLongTermMemories('ALL', 200);

    expect(http.get).toHaveBeenNthCalledWith(1, '/settings/memory', { params: { status: 'ACTIVE', limit: 50 } });
    expect(http.get).toHaveBeenNthCalledWith(2, '/settings/memory', { params: { status: 'ALL', limit: 200 } });
  });

  it('maps create, confirm, reject, and correct without client-side simulation', () => {
    const createPayload = { scope: 'USER' as const, memoryType: 'FACT', content: 'Durable fact', tags: ['research'] };
    const correctionPayload = { memoryType: 'DECISION', content: 'Corrected decision', tags: ['verified'], confidence: 0.9 };

    createLongTermMemory(createPayload);
    confirmLongTermMemory(11);
    rejectLongTermMemory(12);
    correctLongTermMemory(13, correctionPayload);

    expect(http.post).toHaveBeenNthCalledWith(1, '/settings/memory', createPayload);
    expect(http.post).toHaveBeenNthCalledWith(2, '/settings/memory/11/confirm', {});
    expect(http.post).toHaveBeenNthCalledWith(3, '/settings/memory/12/reject', {});
    expect(http.put).toHaveBeenCalledWith('/settings/memory/13', correctionPayload);
  });

  it('maps expiry set, expiry clear, and soft delete exactly', () => {
    updateLongTermMemoryExpiry(21, '2026-08-01T08:00:00.000Z');
    updateLongTermMemoryExpiry(21, null);
    deleteLongTermMemory(21);

    expect(http.put).toHaveBeenNthCalledWith(1, '/settings/memory/21/expiry', { expiresAt: '2026-08-01T08:00:00.000Z' });
    expect(http.put).toHaveBeenNthCalledWith(2, '/settings/memory/21/expiry', { expiresAt: null });
    expect(http.delete).toHaveBeenCalledWith('/settings/memory/21');
  });

  it('maps opt-in settings, manual runs, and job polling to the distillation endpoints', () => {
    getMemoryDistillationSettings();
    updateMemoryDistillationSettings(true);
    startMemoryDistillation();
    getMemoryDistillationJob(91);

    expect(http.get).toHaveBeenNthCalledWith(1, '/settings/memory/distillation');
    expect(http.put).toHaveBeenCalledWith('/settings/memory/distillation', { autoEnabled: true });
    expect(http.post).toHaveBeenCalledWith('/settings/memory/distillation/jobs', {});
    expect(http.get).toHaveBeenNthCalledWith(2, '/settings/memory/distillation/jobs/91');
  });
});
