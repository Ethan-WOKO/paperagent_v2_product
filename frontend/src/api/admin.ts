import http from './http';

export interface AdminUserSummary {
  id: number;
  username: string;
  accountType: string;
  role: string;
  aiQuotaTotal: number;
  aiQuotaUsed: number;
  aiQuotaRemaining: number;
  createdAt: string;
  lastLoginAt?: string | null;
  chatSessionCount: number;
  paperTaskCount: number;
  projectCount: number;
}

export interface AdminUserDetail {
  user: AdminUserSummary;
  chats: Array<{
    id: number;
    title: string;
    scope: string;
    projectId?: number | null;
    modelProvider: string;
    model: string;
    createdAt: string;
    updatedAt: string;
    archived: boolean;
    archivedAt?: string | null;
    messages: Array<{ id: number; role: string; content?: string | null; createdAt: string; deletable: boolean }>;
  }>;
  papers: Array<{
    id: number;
    title: string;
    sourceFilename?: string | null;
    status: string;
    currentStage?: string | null;
    errorMessage?: string | null;
    createdAt: string;
    updatedAt: string;
  }>;
  projects: Array<{
    id: number;
    name: string;
    rootType: string;
    indexVersion: string;
    createdAt: string;
    updatedAt: string;
  }>;
  usage: Array<{
    id: number;
    feature: string;
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
    createdAt: string;
  }>;
}

export interface AdminInviteCode {
  id: number;
  code: string;
  maxUses: number;
  usedCount: number;
  remainingUses: number;
  enabled: boolean;
  status: 'AVAILABLE' | 'EXHAUSTED' | 'DISABLED';
  createdAt: string;
}

export function listAdminUsers() {
  return http.get<AdminUserSummary[]>('/admin/users');
}

export function getAdminUser(userId: number) {
  return http.get<AdminUserDetail>(`/admin/users/${userId}`);
}

export function deleteAdminUser(userId: number) {
  return http.delete(`/admin/users/${userId}`);
}

export function updateAdminQuota(userId: number, payload: { totalQuota: number; resetUsed: boolean }) {
  return http.put<AdminUserSummary>(`/admin/users/${userId}/quota`, payload);
}

export function resetAdminQuota(userId: number) {
  return http.post<AdminUserSummary>(`/admin/users/${userId}/quota/reset`);
}

export function listAdminInviteCodes() {
  return http.get<AdminInviteCode[]>('/admin/invite-codes');
}

export function generateAdminInviteCode() {
  return http.post<{ code: string }>('/admin/invite-codes/generate');
}

export function createAdminInviteCode(payload: { code: string; maxUses: number }) {
  return http.post<AdminInviteCode>('/admin/invite-codes', payload);
}

export function deleteAdminInviteCode(inviteCodeId: number) {
  return http.delete(`/admin/invite-codes/${inviteCodeId}`);
}

export function deleteDemoMessage(messageId: number) {
  return http.delete(`/admin/demo/messages/${messageId}`);
}

export function deleteArchivedDemoMessage(messageId: number) {
  return http.delete(`/admin/demo/archive/messages/${messageId}`);
}

export function clearDemoChats() {
  return http.delete('/admin/demo/chats');
}

export function clearDemoProjects() {
  return http.delete('/admin/demo/projects');
}
