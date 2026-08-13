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

export interface AgentMessageResponse {
  id: number;
  sessionId: number;
  userId: number;
  role: string;
  content: string | null;
  toolCallsJson: string | null;
  paperTaskId: number | null;
  createdAt: string;
}

export interface AgentContextSection {
  type: string;
  itemCount: number;
  estimatedCharacters: number;
  note: string;
}

export interface AgentContextDroppedItem {
  type: string;
  count: number;
  reason: string;
}

export interface AgentContextEvidenceRef {
  id: string;
  sourceType: string;
  source: string;
  file: string | null;
  chunk: string | null;
  citation: string | null;
  version: string | null;
  selectionReason: string | null;
  projectVersion: string | null;
  fileHash: string | null;
  startLine: number | null;
  endLine: number | null;
  parserVersion: string | null;
  versionStatus: string;
}

export interface AgentContextDebugView {
  requestedBudgetCharacters: number;
  effectiveBudgetCharacters: number;
  estimatedCharacters: number;
  currentMessage: { content: string | null; present: boolean; truncated: boolean; source: string };
  recentTurns: Array<{
    turnId: number;
    userMessageId: number;
    assistantMessageId: number;
    user: string;
    assistant: string;
    estimatedCharacters: number;
  }>;
  sessionSummary: { content: string | null; present: boolean; truncated: boolean; source: string };
  project: { projectId: number; projectVersion: string; source: string } | null;
  longTermMemory: {
    content: string | null;
    includedCount: number;
    omittedCount: number;
    truncated: boolean;
    source: string;
    note: string | null;
  };
  evidence: AgentContextEvidenceRef[];
  sections: AgentContextSection[];
  droppedItems: AgentContextDroppedItem[];
}

export interface AgentContextSnapshotResponse {
  id: number;
  turnId: number;
  sessionId: number;
  traceId: string | null;
  sections: AgentContextSection[];
  droppedItems: AgentContextDroppedItem[];
  rawMessageCount: number;
  normalizedMessageCount: number;
  contextMessageCount: number;
  estimatedCharacters: number;
  createdAt: string;
  context: AgentContextDebugView | null;
}

export function listSessions() {
  return http.get<AgentSessionResponse[]>('/agent/sessions');
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

export function createSession(payload: CreateSessionPayload) {
  return http.post<AgentSessionResponse>('/agent/sessions', payload);
}

export function updateSession(sessionId: number, payload: UpdateSessionPayload) {
  return http.patch<AgentSessionResponse>(`/agent/sessions/${sessionId}`, payload);
}

export function deleteSession(sessionId: number) {
  return http.delete<void>(`/agent/sessions/${sessionId}`);
}

export interface ListMessagesParams {
  limit?: number;
  beforeId?: number;
  view?: 'chat' | 'all';
}

export function listMessages(sessionId: number, params?: ListMessagesParams) {
  return http.get<AgentMessageResponse[]>(`/agent/sessions/${sessionId}/messages`, { params });
}

export interface SendMessageRequestPayload {
  content: string;
  ragDisabled?: boolean;
  skillId?: string | null;
  clientRequestId?: string;
  experiment?: AgentExperimentRequestPayload;
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

export interface AgentSelectedModesDebug {
  runtimeMode: AgentRuntimeMode;
  ragMode: AgentRagMode;
  memoryMode: AgentMemoryMode;
  toolCallingMode: AgentToolCallingMode;
}

export interface AgentRetrievedChunkDebug {
  source: string | null;
  documentId: number | null;
  filename: string | null;
  chunkIndex: number | null;
  citationId: string | null;
  score: number | null;
  content: string | null;
}

export interface AgentDebugPayload {
  selectedModes: AgentSelectedModesDebug;
  retrievedChunks: AgentRetrievedChunkDebug[];
  injectedContext: string | null;
  rawPrompt: string | null;
  debugFlags: string[];
  toolTrace: string[];
  finalCitations: string[];
  metrics: AgentExperimentMetricsDebug | null;
  memoryWindow: AgentMemoryWindowDebug | null;
  fallbacks: string[];
}

export interface AgentExperimentMetricsDebug {
  clientRequestId: string | null;
  sessionId: number | null;
  latencyMs: number | null;
  retrievedChunkCount: number | null;
  memoryWindowSize: number | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  toolCallCount: number | null;
  steps: number | null;
  evalRecordId: number | null;
}

export interface AgentMemoryWindowDebug {
  mode: AgentMemoryMode;
  entries: string[];
}

export interface SendMessageResponse {
  success: boolean;
  assistantContent: string | null;
  steps: number;
  errorMessage: string | null;
  navigationUrl: string | null;
  messages: AgentMessageResponse[];
  debug: AgentDebugPayload | null;
  projectEvidence: Array<{ id: string; relativePath: string; hash: string; version: string; chunk: string; trusted: boolean; current: boolean }>;
  completionStatus: 'VERIFIED' | 'PARTIAL' | 'INSUFFICIENT_EVIDENCE' | 'FAILED' | null;
  stopReason: string | null;
  outcome: string | null;
  executionOutcome?: string;
  taskOutcome?: string;
  answerStatus?: EvidenceStatus;
  finalSynthesisInput?: FinalSynthesisInput | null;
}

export type EvidenceStatus = 'VERIFIED' | 'SUPPORTED' | 'INFERRED' | 'UNVERIFIED' | 'CONFLICTING' | 'STALE';
export type EvidenceCategory = 'EXECUTION_FACT' | 'VERIFIED_PROJECT_EVIDENCE' | 'EXTERNAL_SOURCE' | 'INFERENCE' | 'UNVERIFIED_INPUT';
export type ExternalSourceAccess = 'OPENED' | 'SEARCH_SUMMARY' | 'UNKNOWN';

export interface ExecutionFact {
  provider: string | null;
  status: string | null;
  exitCode: number | null;
  timedOut: boolean;
  command: string[];
  stdout: string | null;
  stderr: string | null;
  failurePhase?: string | null;
  failureType?: string | null;
  providerErrorType?: string | null;
  providerCommandExitCode?: number | null;
}

export interface SynthesisEvidence {
  id: string;
  category: EvidenceCategory;
  status: EvidenceStatus;
  statement: string | null;
  basisRefs: string[];
  projectVersion: string | null;
  path: string | null;
  hash: string | null;
  startLine: number | null;
  endLine: number | null;
  sourceType: string | null;
  externalAccess: ExternalSourceAccess;
  executionFact: ExecutionFact | null;
}

export interface FinalSynthesisInput {
  executionOutcome: string;
  taskOutcome: string;
  answerStatus: EvidenceStatus;
  evidence: SynthesisEvidence[];
  verificationScope: { verifies: string[]; limitations: string[] };
}

export function sendMessage(sessionId: number, payload: SendMessageRequestPayload) {
  return http.post<SendMessageResponse>(`/agent/sessions/${sessionId}/messages`, payload);
}

export interface V2NaturalLanguageTurnRequest {
  content: string;
  ragDisabled?: boolean;
  skillId?: string | null;
  clientRequestId: string;
  instructionKind?: V2ProjectInstructionKind;
  targetClientRequestId?: string | null;
}

export type V2ProjectInstructionKind =
  | 'INITIAL'
  | 'SUPPLEMENT'
  | 'CORRECTION'
  | 'REPLACEMENT';

export type V2ProjectRoute = 'DIRECT' | 'PERSISTENT_PLAN_EXECUTE';

export type V2ProjectWorkState =
  | 'PLANNING'
  | 'CLASSIFYING_INSTRUCTION'
  | 'DIRECT_ANSWERING'
  | 'EXECUTING'
  | 'AWAITING_REVIEW'
  | 'VALIDATING_PENDING_ITEM'
  | 'WAITING_USER'
  | 'WAITING_PERMISSION'
  | 'FINALIZING'
  | 'DELIVERING'
  | 'BLOCKED'
  | 'TERMINAL';

export type V2ProjectTaskOutcomeStatus =
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'SUPERSEDED';

export type V2ProjectDeliveryStatus =
  | 'PENDING'
  | 'RETRYING'
  | 'SUCCEEDED'
  | 'DELIVERY_FAILED';

export type V2ProjectStepStatus =
  | 'NOT_STARTED'
  | 'READY'
  | 'ACTIVE'
  | 'AWAITING_REVIEW'
  | 'WAITING_GAP'
  | 'COMPLETED'
  | 'SUPERSEDED_BY_REPLAN';

export type V2ProjectPendingItemType =
  | 'USER_INFORMATION'
  | 'USER_CHOICE'
  | 'PERMISSION';

export type V2ProjectPendingItemStatus =
  | 'PENDING'
  | 'RESPONSE_RECEIVED'
  | 'RESOLVED'
  | 'REJECTED'
  | 'CANCELLED';

export interface V2ProjectStepProjection {
  stepId: string;
  index: number;
  title: string;
  status: V2ProjectStepStatus;
  detail: string | null;
}

export interface V2ProjectPendingItemProjection {
  gapId: string;
  type: V2ProjectPendingItemType;
  status: V2ProjectPendingItemStatus;
  question: string;
  expectedFormat: string | null;
}

export interface V2ProjectValidationProjection {
  validationId: string;
  status: string;
  requestDigest: string;
  receiptDigest: string;
  receipts: V2ProjectValidationReceiptProjection[];
}

export interface V2ProjectValidationReceiptProjection {
  requirementId: string;
  subject: 'CANDIDATE' | 'ACTION_RECEIPT';
  receiptId: string;
  actionId: string | null;
  candidateArtifactId: number | null;
  candidateFingerprint: string | null;
  projectVersion: string | null;
}

export interface V2NaturalLanguageTurnResponse {
  clientRequestId: string;
  workState: V2ProjectWorkState;
  taskOutcomeStatus: V2ProjectTaskOutcomeStatus | null;
  deliveryStatus: V2ProjectDeliveryStatus | null;
  route: V2ProjectRoute | null;
  planId: string | null;
  baseProjectVersion: string | null;
  publishedProjectVersion: string | null;
  revisionId: number | null;
  publishReceiptId: string | null;
  steps: V2ProjectStepProjection[];
  pendingItem: V2ProjectPendingItemProjection | null;
  validation: V2ProjectValidationProjection | null;
  finalText: string | null;
  candidateArtifactId: number | null;
  outputPaths: string[];
  failureCategory: string | null;
  failureCode: string | null;
  deliveryErrorCode: string | null;
}

export interface V2NaturalLanguageTurnHistoryItem extends V2NaturalLanguageTurnResponse {
  question: string;
  createdAt: string;
  updatedAt: string;
}

export interface V2NaturalLanguageTurnStartResponse {
  sessionId: number;
  turnId: number;
  userMessageId: number;
  assistantMessageId: number | null;
  clientRequestId: string;
  rootClientRequestId: string;
  route: V2ProjectRoute | null;
  answer: string | null;
  planId: string | null;
  replayed: boolean;
}

export interface V2TurnGapReplyRequest {
  content: string;
  clientRequestId: string;
}

export interface V2TurnCancelRequest {
  clientRequestId: string;
}

export interface V2TurnCommandResponse {
  rootClientRequestId: string;
  commandClientRequestId: string;
  instructionId: string;
  pendingItemStatus: V2ProjectPendingItemStatus | null;
  taskOutcomeStatus: V2ProjectTaskOutcomeStatus | null;
  replayed: boolean;
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

export function replyV2NaturalLanguagePendingItem(
  sessionId: number,
  targetClientRequestId: string,
  gapId: string,
  payload: V2TurnGapReplyRequest,
  signal?: AbortSignal,
) {
  return http.post<V2TurnCommandResponse>(
    `/agent/sessions/${sessionId}/v2/turns/${encodeURIComponent(targetClientRequestId)}/pending-items/${encodeURIComponent(gapId)}/reply`,
    payload,
    { signal },
  );
}

export function cancelV2NaturalLanguageTurn(
  sessionId: number,
  targetClientRequestId: string,
  payload: V2TurnCancelRequest,
  signal?: AbortSignal,
) {
  return http.post<V2TurnCommandResponse>(
    `/agent/sessions/${sessionId}/v2/turns/${encodeURIComponent(targetClientRequestId)}/cancel`,
    payload,
    { signal },
  );
}

export interface V2LiteratureTurnRequest {
  query: string;
  topK: number;
  yearFrom?: number | null;
  includeBibtex: boolean;
  clientRequestId: string;
}

export interface V2LiteratureTurnStartResponse {
  sessionId: number;
  turnId: number;
  userMessageId: number;
  assistantMessageId: number;
  clientRequestId: string;
  planId: string;
  synthesisId: string;
  assistantContent: string;
  replayed: boolean;
}

export type V2LiteratureTaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'CANCEL_REQUESTED'
  | 'CANCELLING'
  | 'COMPLETED'
  | 'PARTIAL'
  | 'FAILED'
  | 'CANCELLED';

export interface V2LiteraturePaperItem {
  cardId: number | null;
  title: string;
  authors: string[];
  year: number | null;
  venue: string | null;
  doi: string | null;
  arxivId: string | null;
  openAlexId: string | null;
  url: string | null;
  source: string | null;
  score: number | null;
  bibtex?: string | null;
}

export interface V2LiteratureTurnOutcomeResponse {
  sessionId: number;
  turnId: number;
  clientRequestId: string;
  literatureTaskId: number;
  status: V2LiteratureTaskStatus;
  stage: string | null;
  terminal: boolean;
  cancellable: boolean;
  requestedTopK: number;
  includeBibtex: boolean;
  resultMessageId: number | null;
  resultCount: number;
  totalCount: number;
  sourceFailures: string[];
  items: V2LiteraturePaperItem[];
}

export function startV2LiteratureTurn(sessionId: number, payload: V2LiteratureTurnRequest) {
  return http.post<V2LiteratureTurnStartResponse>(
    `/agent/sessions/${sessionId}/v2/literature-turns`,
    payload,
  );
}

export function getV2LiteratureTurn(sessionId: number, clientRequestId: string) {
  return http.get<V2LiteratureTurnOutcomeResponse>(
    `/agent/sessions/${sessionId}/v2/literature-turns/${encodeURIComponent(clientRequestId)}`,
  );
}

export function cancelV2LiteratureTurn(sessionId: number, clientRequestId: string) {
  return http.post<V2LiteratureTurnOutcomeResponse>(
    `/agent/sessions/${sessionId}/v2/literature-turns/${encodeURIComponent(clientRequestId)}/cancel`,
    {},
  );
}

export interface AgentPlanStepResponse {
  id: number;
  stepKey: string;
  sortOrder: number;
  title: string | null;
  description: string;
  type: string;
  dependencies: string[];
  allowedTools: string[];
  successCriteria: string | null;
  status: string;
  attemptCount: number;
  result: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface AgentPlanResponse {
  id: number;
  sessionId: number;
  goal: string;
  summary: string | null;
  status: string;
  ragDisabled: boolean;
  skillId: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  steps: AgentPlanStepResponse[];
  executionOutcome: string;
  taskOutcome?: string;
  answerStatus?: EvidenceStatus;
  finalAnswer: string | null;
  finalSynthesisInput?: FinalSynthesisInput | null;
}

export interface AgentPlanEventResponse {
  id: number;
  planId: number;
  stepId: number | null;
  eventType: string;
  payloadJson: string | null;
  createdAt: string;
}

export interface CreateAgentPlanPayload {
  content: string;
  ragDisabled?: boolean;
  skillId?: string | null;
  autoExecute?: boolean;
}

export function createPlan(sessionId: number, payload: CreateAgentPlanPayload) {
  return http.post<AgentPlanResponse>(`/agent/sessions/${sessionId}/plans`, payload);
}

export function listPlans(sessionId: number) {
  return http.get<AgentPlanResponse[]>(`/agent/sessions/${sessionId}/plans`);
}

export function getPlan(planId: number) {
  return http.get<AgentPlanResponse>(`/agent/plans/${planId}`);
}

export function executePlan(planId: number) {
  return http.post<AgentPlanResponse>(`/agent/plans/${planId}/execute`, {});
}

export function executePlanAsync(planId: number) {
  return http.post<AgentPlanResponse>(`/agent/plans/${planId}/execute-async`, {});
}

export function confirmAndQueueSandboxPlan(planId: number, idempotencyKey: string) {
  return http.post<AgentPlanResponse>(`/agent/plans/${planId}/sandbox-confirm-and-queue`, { idempotencyKey });
}

export function retryPlan(planId: number) {
  return http.post<AgentPlanResponse>(`/agent/plans/${planId}/retry`, {});
}

export function cancelPlan(planId: number) {
  return http.post<AgentPlanResponse>(`/agent/plans/${planId}/cancel`, {});
}

export function listPlanEvents(planId: number) {
  return http.get<AgentPlanEventResponse[]>(`/agent/plans/${planId}/events`);
}
