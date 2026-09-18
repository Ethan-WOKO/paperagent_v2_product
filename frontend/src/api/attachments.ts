import http from './http';
export interface SessionAttachment {
  id: number;
  filename: string;
  mimeType: string;
  fileSize: number;
  status: 'PROCESSING' | 'READY' | 'FAILED';
  errorMessage?: string | null;
  firstMessageId?: number | null;
  knowledgeDocumentId?: number | null;
}
export function listSessionAttachments(sessionId: number) {
  return http.get<SessionAttachment[]>(`/agent/sessions/${sessionId}/attachments`);
}
export function uploadSessionAttachment(sessionId: number, file: File) {
  const form = new FormData();
  form.append('file', file);
  return http.post<SessionAttachment>(`/agent/sessions/${sessionId}/attachments`, form, { timeout: 120000 });
}
export function removeSessionAttachment(sessionId: number, id: number) {
  return http.delete(`/agent/sessions/${sessionId}/attachments/${id}`);
}
export function promoteSessionAttachment(sessionId: number, id: number) {
  return http.post<{ documentId: number }>(`/agent/sessions/${sessionId}/attachments/${id}/knowledge`);
}
