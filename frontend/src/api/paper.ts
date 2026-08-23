import http from './http';

export interface PaperTaskResponse {
  id: number;
  userId: number;
  title: string;
  sourceFilename: string | null;
  objectKey: string | null;
  finalObjectKey: string | null;
  clientRequestId: string | null;
  idempotent: boolean | null;
  status: string;
  targetLanguage: 'zh' | 'en';
  currentStage: string | null;
  errorMessage: string | null;
  scoreThreshold: number | null;
  maxRounds: number | null;
  innerMaxAttempts: number | null;
  literatureMinCount: number | null;
  literatureCount: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface PaperSseEvent {
  type: string;
  taskId: number;
  message: string;
  stage: string | null;
  timestamp: string;
  currentSection?: number | null;
  totalSections?: number | null;
  sectionTitle?: string | null;
  attempt?: number | null;
  maxAttempts?: number | null;
  progressPercent?: number | null;
}

export interface PaperClarificationResponse {
  id: number;
  taskId: number;
  type: string;
  questionJson: string;
  optionsJson: string | null;
  status: string;
  userAnswerJson: string | null;
  createdAt: string;
  answeredAt: string | null;
}

export interface PaperSectionResponse {
  id: number;
  taskId: number;
  sourcePath: string | null;
  orderIndex: number;
  level: number;
  title: string;
  role: string;
  roleConfidence: number | null;
  roleSource: string | null;
  charStart: number;
  charEnd: number;
  polishStatus: string | null;
  revisionStatus: string | null;
  reviewJson: string | null;
  diffJson: string | null;
}

export interface PaperEvidenceCardResponse {
  id: number;
  title: string;
  authors: string | null;
  publicationYear: number | null;
  venue: string | null;
  doi: string | null;
  arxivId: string | null;
  openAlexId: string | null;
  s2Id: string | null;
  url: string | null;
  pdfUrl: string | null;
  citationCount: number | null;
  relevanceScore?: number | null;
  narrativeRole?: string | null;
  sourceQuery?: string | null;
}

export interface PaperSuggestionResponse {
  id: number;
  taskId: number;
  sectionId: number | null;
  track: string;
  category: string;
  severity: string | null;
  statement: string;
  applicable: boolean;
  patchJson: string | null;
  status: string;
  honestyGrade: 'A' | 'B' | string;
  honestyReason: string;
  evidenceCount: number;
  evidenceCards: PaperEvidenceCardResponse[];
  createdAt: string;
  updatedAt: string;
}

export interface PaperAnalysisResponse {
  researchProfileJson: string;
  conceptLadderJson: string;
  gapMatrixJson: string;
}

export interface PaperArtifactResponse {
  id: number;
  taskId: number;
  type: string;
  objectKey: string;
  version: number;
  metadataJson: string | null;
  artifactStatus: string | null;
  createdAt: string;
}

export interface PaperTaskHistoryResponse {
  id: number;
  title: string;
  sourceFilename: string | null;
  clientRequestId: string | null;
  status: string;
  currentStage: string | null;
  errorMessage: string | null;
  targetLanguage: 'zh' | 'en' | string;
  finalObjectKey: string | null;
  literatureMinCount: number | null;
  literatureCount: number | null;
  createdAt: string;
  updatedAt: string;
  artifacts: PaperArtifactResponse[];
}

export function createPaperTask(formData: FormData, clientRequestId?: string) {
  return http.post<PaperTaskResponse>('/paper/process', formData, {
    headers: {
      'Content-Type': 'multipart/form-data',
      ...(clientRequestId ? { 'X-Client-Request-Id': clientRequestId } : {}),
    },
  });
}

export function getPaperTask(taskId: number) {
  return http.get<PaperTaskResponse>(`/paper/tasks/${taskId}`);
}

export function getPaperTasks() {
  return http.get<PaperTaskHistoryResponse[]>('/paper/tasks');
}

export function pausePaperTask(taskId: number) {
  return http.post(`/paper/tasks/${taskId}/pause`);
}

export function resumePaperTask(taskId: number) {
  return http.post(`/paper/tasks/${taskId}/resume`);
}

export function stopPaperTask(taskId: number) {
  return http.post(`/paper/tasks/${taskId}/stop`);
}

export function getPaperClarifications(taskId: number) {
  return http.get<PaperClarificationResponse[]>(`/paper/tasks/${taskId}/clarifications`);
}

export function answerPaperClarification(taskId: number, clarificationId: number, answerJson: string) {
  return http.post<PaperClarificationResponse>(`/paper/tasks/${taskId}/clarifications/${clarificationId}/answer`, { answerJson });
}

export function getPaperSections(taskId: number) {
  return http.get<PaperSectionResponse[]>(`/paper/tasks/${taskId}/sections`);
}

export function getPaperSuggestions(taskId: number) {
  return http.get<PaperSuggestionResponse[]>(`/paper/tasks/${taskId}/suggestions`);
}

export function updatePaperSuggestionStatus(taskId: number, suggestionId: number, status: string) {
  return http.post<PaperSuggestionResponse>(`/paper/tasks/${taskId}/suggestions/${suggestionId}/status`, { status });
}

export function getPaperAnalysis(taskId: number) {
  return http.get<PaperAnalysisResponse>(`/paper/tasks/${taskId}/analysis`);
}

export function getPaperArtifacts(taskId: number) {
  return http.get<PaperArtifactResponse[]>(`/paper/tasks/${taskId}/artifacts`);
}

export function updatePaperSectionRole(taskId: number, sectionId: number, role: string) {
  return http.post<PaperSectionResponse>(`/paper/tasks/${taskId}/sections/${sectionId}/role`, { role });
}

export function updatePaperSectionRevisionStatus(taskId: number, sectionId: number, status: string) {
  return http.post<PaperSectionResponse>(`/paper/tasks/${taskId}/sections/${sectionId}/revision-status`, { status });
}

export function updatePaperSectionRevisionStatuses(taskId: number, sectionIds: number[], status: string) {
  return http.post<PaperSectionResponse[]>(`/paper/tasks/${taskId}/sections/revision-status`, { sectionIds, status });
}

export function downloadPaperTask(taskId: number) {
  return http.get<Blob>(`/paper/tasks/${taskId}/download`, {
    responseType: 'blob',
  });
}
