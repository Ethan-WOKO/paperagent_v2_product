import http from './http';
import type { AgentSessionResponse, CreateSessionPayload } from './agent';

export interface ProjectSummaryResponse {
  id: number;
  name: string;
  accessMode: 'READ_ONLY';
  createdAt: string;
}

export interface ProjectFileEntry {
  path: string;
  sizeBytes: number;
  modifiedAt: string;
  sha256: string;
}

export interface ProjectManifestResponse {
  projectId: number;
  version: string;
  files: ProjectFileEntry[];
}

export interface ProjectFileResponse extends ProjectFileEntry { content: string; }

export interface ProjectSearchHit { path: string; lineNumber: number; line: string; sha256: string; }

export interface ProjectEvidenceResponse {
  id: string;
  relativePath: string;
  hash: string;
  version: string;
  chunk: string;
  trusted: boolean;
  current: boolean;
}

export interface ProjectRevisionResponse {
  id: number;
  projectVersion: string;
  current: boolean;
  fileCount: number;
  totalBytes: number;
  sourceType: 'UPLOAD' | 'APPLICATION';
  createdAt: string;
}

export interface ProjectRevisionOperationResponse {
  operationId: number;
  operationType: 'APPLICATION' | 'ROLLBACK';
  outcome: 'SUCCEEDED';
  baseRevisionId: number;
  baseVersion: string;
  resultRevisionId: number;
  resultVersion: string;
  candidateArtifactId: number | null;
  candidateFingerprint: string | null;
  acceptedChangeIndexes: number[];
  rejectedChangeIndexes: number[];
  completedAt: string;
}

export type CandidateValidationProfile = 'DOCUMENT_INTEGRITY' | 'MAVEN_TEST' | 'MAVEN_VERIFY' | 'JAVA_SOURCE_RUN'
  | 'PYTHON_SOURCE_RUN' | 'C_SOURCE_RUN' | 'CPP_SOURCE_RUN';

export interface CandidateValidationResponse {
  validationId: string;
  projectId: number;
  artifactId: number;
  projectVersion: string;
  candidateFingerprint: string;
  acceptedChangeIndexes: number[];
  profile: CandidateValidationProfile;
  status: string;
  exitCode: number | null;
  timedOut: boolean;
  provider: string | null;
  stdout: string;
  stderr: string;
  outputTruncated: boolean;
  requestDigest: string;
  receiptDigest: string | null;
  errorCode: string | null;
  analysisSummary: string | null;
  analysisDisclaimer: string | null;
  decisionStatus: 'PENDING' | 'REJECTED' | 'APPLIED';
  applicationOperationId: number | null;
  appliedRevisionId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface UploadProjectPayload {
  name: string;
  includeRules: string[];
  ignoreRules: string[];
  files: File[];
}

const BLOCKED_UPLOAD_SEGMENTS = new Set(['.git', 'target', 'build', 'node_modules', '.idea']);
const BLOCKED_UPLOAD_NAMES = new Set([
  'id_rsa', 'id_dsa', 'id_ed25519', 'credentials', 'credentials.json', '.env',
  '.netrc', '.npmrc', '.pypirc', 'service-account.json', 'secrets.yml', 'secrets.yaml',
]);
const BLOCKED_UPLOAD_SUFFIXES = ['.pem', '.key', '.p12', '.pfx', '.jks', '.keystore', '.kdbx'];

export function projectUploadRelativePath(file: File) {
  const parts = (file.webkitRelativePath || file.name).replace(/\\/g, '/').split('/').filter(Boolean);
  return (parts.length > 1 ? parts.slice(1) : parts).join('/');
}

export function filterProjectUploadFiles(files: File[], includeRules: string[], ignoreRules: string[]) {
  const includes = includeRules.map(globMatcher);
  const ignores = ignoreRules.map(globMatcher);
  return files.filter((file) => {
    const path = projectUploadRelativePath(file);
    const parts = path.toLowerCase().split('/');
    const name = parts[parts.length - 1] || '';
    if (!path || parts.some((part) => BLOCKED_UPLOAD_SEGMENTS.has(part))) return false;
    if (BLOCKED_UPLOAD_NAMES.has(name) || name.startsWith('.env.')
      || BLOCKED_UPLOAD_SUFFIXES.some((suffix) => name.endsWith(suffix))) return false;
    return includes.some((matches) => matches(path)) && !ignores.some((matches) => matches(path));
  });
}

function globMatcher(glob: string) {
  let expression = '^';
  const normalized = glob.replace(/\\/g, '/');
  for (let index = 0; index < normalized.length; index += 1) {
    const char = normalized[index];
    if (char === '*' && normalized[index + 1] === '*') {
      expression += '.*';
      index += 1;
    } else if (char === '*') {
      expression += '[^/]*';
    } else if (char === '?') {
      expression += '[^/]';
    } else {
      expression += char.replace(/[|\\{}()[\]^$+?.]/g, '\\$&');
    }
  }
  const matcher = new RegExp(`${expression}$`);
  return (path: string) => matcher.test(path);
}

export function listProjects() { return http.get<ProjectSummaryResponse[]>('/projects'); }
export function renameProject(projectId: number, name: string) {
  return http.patch<ProjectSummaryResponse>(`/projects/${projectId}`, { name });
}
export function uploadProject(payload: UploadProjectPayload) {
  const form = new FormData();
  form.append('name', payload.name);
  payload.includeRules.forEach((rule) => form.append('includeRules', rule));
  payload.ignoreRules.forEach((rule) => form.append('ignoreRules', rule));
  payload.files.forEach((file) => {
    const relativePath = file.webkitRelativePath || file.name;
    form.append('files', file, relativePath);
  });
  return http.post<ProjectSummaryResponse>('/projects', form);
}
export function deleteProject(projectId: number) { return http.delete<void>(`/projects/${projectId}`); }
export function getProjectManifest(projectId: number) { return http.get<ProjectManifestResponse>(`/projects/${projectId}/manifest`); }
export function listProjectSessions(projectId: number) { return http.get<AgentSessionResponse[]>(`/projects/${projectId}/agent/sessions`); }
export function createProjectSession(projectId: number, payload: CreateSessionPayload) {
  return http.post<AgentSessionResponse>(`/projects/${projectId}/agent/sessions`, payload);
}
export function readProjectFile(projectId: number, path: string) { return http.get<ProjectFileResponse>(`/projects/${projectId}/files/read`, { params: { path } }); }
export function searchProject(projectId: number, query: string) { return http.get<ProjectSearchHit[]>(`/projects/${projectId}/search`, { params: { query, maxResults: 50 } }); }
export function applyProjectCandidate(projectId: number, artifactId: number, projectVersion: string,
                                      acceptedChangeIndexes: number[], validationId: string,
                                      idempotencyKey: string) {
  return http.post<ProjectRevisionOperationResponse>(`/projects/${projectId}/candidates/${artifactId}/applications`,
    { acceptedChangeIndexes, validationId },
    { headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': projectVersion } });
}
export function createCandidateValidation(projectId: number, artifactId: number, projectVersion: string,
                                          profile: CandidateValidationProfile,
                                          acceptedChangeIndexes: number[], idempotencyKey: string) {
  return http.post<CandidateValidationResponse>(`/projects/${projectId}/candidates/${artifactId}/validations`,
    { profile, acceptedChangeIndexes, confirmed: true },
    { headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': projectVersion } });
}
export function listCandidateValidations(projectId: number, artifactId: number) {
  return http.get<CandidateValidationResponse[]>(`/projects/${projectId}/candidates/${artifactId}/validations`);
}
export function cancelCandidateValidation(projectId: number, validationId: string) {
  return http.post<CandidateValidationResponse>(`/projects/${projectId}/candidate-validations/${validationId}/cancel`, {});
}
export function rejectCandidateValidation(projectId: number, validationId: string) {
  return http.post<CandidateValidationResponse>(`/projects/${projectId}/candidate-validations/${validationId}/reject`, {});
}
export function listProjectRevisions(projectId: number) {
  return http.get<ProjectRevisionResponse[]>(`/projects/${projectId}/revisions`);
}
export function rollbackProjectRevision(projectId: number, revisionId: number, projectVersion: string,
                                        idempotencyKey: string) {
  return http.post<ProjectRevisionOperationResponse>(`/projects/${projectId}/revisions/${revisionId}/rollback`,
    {}, { headers: { 'Idempotency-Key': idempotencyKey, 'If-Match': projectVersion } });
}
export function exportProjectRevision(projectId: number, revisionId: number) {
  return http.get<Blob>(`/projects/${projectId}/revisions/${revisionId}/export`, { responseType: 'blob' });
}
export type { AgentSessionResponse };
