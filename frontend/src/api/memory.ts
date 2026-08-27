import http from './http';

export type MemoryScope = 'USER' | 'PROJECT';
export type MemoryStatus = 'ACTIVE' | 'SUPERSEDED' | 'DELETED' | string;
export type MemoryConfirmationStatus = 'UNCONFIRMED' | 'CONFIRMED' | 'REJECTED' | string;
export type MemoryListStatus = 'ACTIVE' | 'ALL';
export type MemoryDistillationJobStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'NO_WORK';

export interface MemoryDistillationJobResponse {
  id: number;
  triggerType: 'MANUAL' | 'AUTO';
  status: MemoryDistillationJobStatus;
  fromMessageId: number;
  throughMessageId: number;
  messageCount: number;
  candidateCount: number;
  createdMemoryCount: number;
  attemptCount: number;
  errorCode: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MemoryDistillationSettingsResponse {
  available: boolean;
  autoEnabled: boolean;
  intervalSeconds: number;
  lastProcessedMessageId: number;
  nextRunAt: string | null;
  lastSuccessAt: string | null;
  latestJob: MemoryDistillationJobResponse | null;
}

export interface LongTermMemoryResponse {
  id: number;
  userId: number;
  projectId: number | null;
  scope: MemoryScope;
  memoryType: string;
  content: string;
  tags: string[];
  sourceType: string | null;
  sourceRefId: string | null;
  confidence: number | null;
  status: MemoryStatus;
  confirmationStatus: MemoryConfirmationStatus;
  confirmedAt: string | null;
  confirmedSource: string | null;
  provenanceType: string | null;
  provenanceRef: string | null;
  projectVersion: string | null;
  expiresAt: string | null;
  invalidatedAt: string | null;
  invalidationReason: string | null;
  supersedesMemoryId: number | null;
  supersededByMemoryId: number | null;
  createdAt: string;
  updatedAt: string;
  deletedAt: string | null;
}

export interface CreateLongTermMemoryRequest {
  projectId?: number;
  scope: MemoryScope;
  memoryType: string;
  content: string;
  tags: string[];
  confidence?: number;
}

export interface CorrectLongTermMemoryRequest {
  memoryType?: string;
  content: string;
  tags?: string[];
  confidence?: number;
}

export function listLongTermMemories(status: MemoryListStatus, limit = 100) {
  return http.get<LongTermMemoryResponse[]>('/settings/memory', { params: { status, limit } });
}

export function createLongTermMemory(payload: CreateLongTermMemoryRequest) {
  return http.post<LongTermMemoryResponse>('/settings/memory', payload);
}

export function correctLongTermMemory(memoryId: number, payload: CorrectLongTermMemoryRequest) {
  return http.put<LongTermMemoryResponse>(`/settings/memory/${memoryId}`, payload);
}

export function confirmLongTermMemory(memoryId: number) {
  return http.post<LongTermMemoryResponse>(`/settings/memory/${memoryId}/confirm`, {});
}

export function rejectLongTermMemory(memoryId: number) {
  return http.post<LongTermMemoryResponse>(`/settings/memory/${memoryId}/reject`, {});
}

export function updateLongTermMemoryExpiry(memoryId: number, expiresAt: string | null) {
  return http.put<LongTermMemoryResponse>(`/settings/memory/${memoryId}/expiry`, { expiresAt });
}

export function deleteLongTermMemory(memoryId: number) {
  return http.delete<void>(`/settings/memory/${memoryId}`);
}

export function getMemoryDistillationSettings() {
  return http.get<MemoryDistillationSettingsResponse>('/settings/memory/distillation');
}

export function updateMemoryDistillationSettings(autoEnabled: boolean) {
  return http.put<MemoryDistillationSettingsResponse>('/settings/memory/distillation', { autoEnabled });
}

export function startMemoryDistillation() {
  return http.post<MemoryDistillationJobResponse>('/settings/memory/distillation/jobs', {});
}

export function getMemoryDistillationJob(jobId: number) {
  return http.get<MemoryDistillationJobResponse>(`/settings/memory/distillation/jobs/${jobId}`);
}
