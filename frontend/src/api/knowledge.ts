import http from './http';

export interface KbDocumentItem {
  id: number;
  userId: number;
  filename: string;
  status: 'UPLOADING' | 'PROCESSING' | 'RETRYING' | 'READY' | 'FAILED';
  isPublic: boolean;
  sourceType: 'USER_UPLOAD' | 'DEMO_SEED' | string;
  projectId: number | null;
  lineageId: string | null;
  versionNo: number;
  versionStatus: 'ACTIVE' | 'SUPERSEDED' | 'DELETED' | 'ARCHIVED' | string;
  canonicalKey: string | null;
  effectiveAt: string | null;
  supersededAt: string | null;
  deletedAt: string | null;
  mimeType: string | null;
  fileSize: number | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KbDocumentResponse {
  id: number;
  userId: number;
  filename: string;
  status: string;
  isPublic: boolean;
  sourceType: string;
  projectId: number | null;
  lineageId: string | null;
  versionNo: number;
  versionStatus: string;
  sourceTaskType: string | null;
  sourceTaskId: number | null;
  sourceArtifactId: number | null;
  sourceDocumentId: number | null;
  canonicalKey: string | null;
  effectiveAt: string | null;
  supersededAt: string | null;
  deletedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KbDocumentPreviewResponse {
  id: number;
  filename: string;
  status: string;
  sourceType: string;
  projectId: number | null;
  lineageId: string | null;
  versionNo: number;
  versionStatus: string;
  canonicalKey: string | null;
  mimeType: string | null;
  fileSize: number | null;
  totalChunks: number;
  previewChunks: number;
  maxChars: number;
  truncated: boolean;
  content: string;
}

export interface KnowledgeSearchResult {
  documentId: number;
  filename: string;
  chunkIndex: number;
  chunkText: string;
  score: number;
  isPublic: boolean;
  sourceType: string;
  versionStatus: string;
  lineageId: string | null;
  versionNo: number;
  projectId: number | null;
  canonicalKey: string | null;
}

export function listKbDocuments() {
  return http.get<KbDocumentItem[]>('/kb/documents');
}

export function deleteKbDocument(documentId: number) {
  return http.delete(`/kb/documents/${documentId}`);
}

export function previewKbDocument(documentId: number, maxChars = 20000) {
  return http.get<KbDocumentPreviewResponse>(`/kb/documents/${documentId}/preview`, {
    params: { maxChars },
  });
}

export function uploadChunk(payload: {
  uploadId: string;
  filename: string;
  chunkNumber: number;
  totalChunks: number;
  file: Blob;
  chunkMd5?: string;
}) {
  const formData = new FormData();
  formData.append('uploadId', payload.uploadId);
  formData.append('filename', payload.filename);
  formData.append('chunkNumber', String(payload.chunkNumber));
  formData.append('totalChunks', String(payload.totalChunks));
  if (payload.chunkMd5) {
    formData.append('chunkMd5', payload.chunkMd5);
  }
  formData.append('file', payload.file, payload.filename);
  return http.post('/upload/chunk', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function mergeKbUpload(payload: {
  uploadId: string;
  filename: string;
  totalChunks: number;
  mimeType: string;
  isPublic: boolean;
}) {
  return http.post<KbDocumentResponse>('/upload/merge', payload);
}

export function searchKnowledge(payload: {
  query: string;
  topK: number;
  projectId?: number | null;
  includeSuperseded?: boolean;
}) {
  return http.post<KnowledgeSearchResult[]>('/search', payload);
}
