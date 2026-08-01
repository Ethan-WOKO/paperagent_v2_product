import http from './http';

export type V2ProductCapability =
  | 'literature.search'
  | 'project.read-analysis'
  | 'project.candidate'
  | 'agent.turn';

export interface V2ProductAvailabilityDocument {
  formatVersion: number;
  enabled: boolean;
  capabilities: string[];
}

export function getV2ProductAvailability() {
  return http.get<V2ProductAvailabilityDocument>('/agent/sessions/v2/capabilities');
}

export interface AgentSessionResponse {
  id: number;
  userId: number;
  scope: 'WORKSPACE' | 'PROJECT';
  projectId: number | null;
  title: string;
  modelProvider: string;
  model: string;
  maxSteps: number;
  ragDisabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateSessionPayload {
  title?: string;
  modelProvider?: string;
  model?: string;
  maxSteps?: number;
  ragDisabled?: boolean;
}

export interface CreateSessionPayload {
  title?: string;
  modelProvider?: string;
  model?: string;
  maxSteps?: number;
  ragDisabled?: boolean;
}

export function updateSession(sessionId: number, payload: UpdateSessionPayload) {
  return http.patch<AgentSessionResponse>(`/agent/sessions/${sessionId}`, payload);
}

export function deleteSession(sessionId: number) {
  return http.delete<void>(`/agent/sessions/${sessionId}`);
}

export type AgentRuntimeMode = 'LANGCHAIN4J';
export type AgentRagMode = 'LANGCHAIN4J_AUGMENTOR';
export type AgentMemoryMode = 'CONTEXT_PACKER';
export type AgentToolCallingMode = 'LANGCHAIN4J_TOOL_BINDING';
export type AgentDebugFlag =
  | 'SHOW_RETRIEVED_CHUNKS'
  | 'SHOW_INJECTED_CONTEXT'
  | 'SHOW_TOOL_TRACE'
  | 'SHOW_MEMORY_WINDOW'
  | 'SHOW_RAW_PROMPT';

export interface AgentExperimentRequestPayload {
  enabled: boolean;
  runtimeMode?: AgentRuntimeMode;
  ragMode?: AgentRagMode;
  memoryMode?: AgentMemoryMode;
  toolCallingMode?: AgentToolCallingMode;
  debugFlags?: AgentDebugFlag[];
  persistEvalRecord?: boolean;
}

export type V2NaturalLanguageTurnStatus =
  | 'PLANNING'
  | 'RUNNING'
  | 'WAITING_CONFIRMATION'
  | 'SUCCEEDED'
  | 'FAILED';

export type V2NaturalLanguageStepStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'SUPERSEDED_BY_REPLAN';

export interface V2NaturalLanguageTurnRequest {
  content: string;
  ragDisabled?: boolean;
  skillId?: string | null;
  experiment?: AgentExperimentRequestPayload;
  clientRequestId: string;
}

export interface V2NaturalLanguageTurnStartResponse {
  sessionId: number;
  turnId: number;
  userMessageId: number;
  assistantMessageId: number | null;
  clientRequestId: string;
  route: 'DIRECT' | 'PERSISTENT_PLAN_EXECUTE';
  answer: string | null;
  planId: string | null;
  replayed: boolean;
}

export interface V2NaturalLanguageTurnStep {
  index: number;
  title: string;
  status: V2NaturalLanguageStepStatus;
  detail: string | null;
}

export interface V2NaturalLanguageTurnResponse {
  status: V2NaturalLanguageTurnStatus;
  route: 'DIRECT' | 'PERSISTENT_PLAN_EXECUTE';
  planId: string | null;
  projectVersion: string | null;
  steps: V2NaturalLanguageTurnStep[];
  finalText: string | null;
  candidateArtifactId: number | null;
  outputPaths: string[];
  errorCode: string | null;
}

export interface V2AgentAutomaticValidation {
  status: 'PASSED';
  provider: 'E2B';
  exitCode: number;
  receiptId: string;
}

export interface V2CandidateConfirmationValidation {
  status: string;
  decisionStatus: string;
  applicationOperationId: number | null;
  appliedRevisionId: number | null;
  appliedProjectVersion: string | null;
}

export interface V2NaturalLanguageTurnHistoryItem extends Omit<V2NaturalLanguageTurnResponse, 'route'> {
  clientRequestId: string;
  question: string;
  route: 'DIRECT' | 'PERSISTENT_PLAN_EXECUTE' | null;
  createdAt: string;
  updatedAt: string;
  agentAutomaticValidation: V2AgentAutomaticValidation | null;
  confirmationValidation: V2CandidateConfirmationValidation | null;
}

export function startV2NaturalLanguageTurn(
  sessionId: number,
  payload: V2NaturalLanguageTurnRequest,
  signal?: AbortSignal,
) {
  return http.post<V2NaturalLanguageTurnStartResponse>(
    `/agent/sessions/${sessionId}/v2/turns`,
    payload,
    { signal },
  );
}

export function getV2NaturalLanguageTurn(
  sessionId: number,
  clientRequestId: string,
  signal?: AbortSignal,
) {
  return http.get<V2NaturalLanguageTurnResponse>(
    `/agent/sessions/${sessionId}/v2/turns/${encodeURIComponent(clientRequestId)}`,
    { signal },
  );
}

export function listV2NaturalLanguageTurns(
  sessionId: number,
  limit = 50,
  signal?: AbortSignal,
) {
  return http.get<V2NaturalLanguageTurnHistoryItem[]>(
    `/agent/sessions/${sessionId}/v2/turns`,
    { params: { limit }, signal },
  );
}
