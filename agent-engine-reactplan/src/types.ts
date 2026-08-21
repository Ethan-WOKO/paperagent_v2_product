export type TaskState = "queued" | "running" | "waiting_user" | "succeeded" | "failed" | "cancelled";

export interface Problem {
  contractVersion: "1.0";
  code: string;
  category: "request" | "authorization" | "model" | "tool" | "code_validation" | "sandbox_system" | "cancelled" | "internal";
  message: string;
  retryable: boolean;
  sourceRef?: string | null;
}

export interface Authority {
  runMode: "PERSISTENT_PLAN_EXECUTE";
  sessionRef: string;
  project: { projectId: string; projectVersion: string };
  instruction: string;
  permissions: { readProject: true; writeWorkspace: boolean; executeSandbox: true };
  model: {
    provider: string;
    model: string;
    fallbacks?: Array<{ provider: string; model: string }>;
  };
  skill?: {
    id: string;
    prompt: string;
    allowedTools: string[];
    digest: string;
  };
}

export interface TaskSubmission {
  contractVersion: "1.0";
  taskId: string;
  requestDigest: string;
  authority: Authority;
  context?: {
    historicalContext?: HistoricalContextEnvelope;
    longTermMemory: LongTermMemoryEnvelope;
  };
  gateway: { taskGrant: string; expiresAt: string };
}

export interface TaskView {
  contractVersion: "1.0";
  taskId: string;
  requestDigest: string;
  state: TaskState;
  lastSequence: number;
  pendingQuestionId?: string | null;
  deliverySequence?: number | null;
  terminalSequence?: number | null;
  error?: Problem | null;
  createdAt: string;
  updatedAt: string;
}

interface EventBase { contractVersion: "1.0"; taskId: string; sequence: number; occurredAt: string }
export type TaskEvent =
  | (EventBase & { type: "status"; state: TaskState; error: Problem | null })
  | (EventBase & { type: "message"; content: string })
  | (EventBase & { type: "question"; questionId: string; text: string })
  | (EventBase & { type: "tool"; callId: string; name: ToolName; registeredToolName?: string; state: "requested" | "running" | "succeeded" | "failed" | "cancelled"; inputSummary: string; outputSummary: string | null; receiptRef: string | null })
  | (EventBase & { type: "delivery"; conclusion: string; receiptRefs: string[]; publication?: PublicationFact });

export type ToolName = "project.list" | "project.read" | "workspace.write" | "workspace.diff" | "sandbox.execute" | "registered.invoke" | "project.publish";

export interface ChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string | null;
  toolCallId?: string;
  toolCalls?: ModelToolCall[];
}

export interface ModelToolCall { id: string; name: string; arguments: string }
export interface RegisteredToolSpec {
  type: "function";
  function: { name: string; description: string; parameters: unknown };
}
export interface RegisteredToolCatalog {
  contractVersion: "1.0";
  taskId: string;
  projectVersion: string;
  catalogDigest: string;
  tools: RegisteredToolSpec[];
}
export interface RegisteredToolResult {
  contractVersion: "1.0";
  callId: string;
  toolName: string;
  requestDigest: string;
  success: boolean;
  output: unknown | null;
  errorCode: string | null;
  errorMessage: string | null;
  retryable: boolean;
  evidenceRefs: string[];
  version: string | null;
}
export interface ModelResponse {
  content: string | null;
  toolCalls: ModelToolCall[];
  finishReason?: string | null;
  usage?: { promptTokens: number; completionTokens: number };
  resolvedProvider?: string;
  resolvedModel?: string;
  fallbackUsed?: boolean;
}
export interface ModelRequest {
  provider: string;
  model: string;
  messages: ChatMessage[];
  tools: unknown[];
  maxOutputTokens: 4096;
  signal: AbortSignal;
}
export interface ModelInvocationContext {
  taskId: string;
  taskGrant: string;
  clientRequestId: string;
}
export interface ModelProvider { complete(request: ModelRequest, context: ModelInvocationContext): Promise<ModelResponse> }

export interface PendingCall extends ModelToolCall {
  ordinal: number;
  /** Provider-owned protocol identifier; distinct from the deterministic product call id. */
  modelCallId?: string;
  /** The schema was visible to the model when this call was emitted. */
  schemaLoadedAtDispatch?: boolean;
  /** Durable model-turn identity used to bound argument-repair rounds. */
  modelCallNumber?: number;
}
export interface AcceptedAnswer { clientRequestId: string; questionId: string; answerDigest: string }

export interface RecentConversationTurn {
  intakeId?: number;
  turnId?: number;
  instruction: string;
  conclusion: string;
  state?: "succeeded" | "failed" | "cancelled";
  projectVersion: string;
  completedAt: string;
}

export interface HistoricalContextEnvelope {
  schemaVersion: "1.0";
  type: "historical_context";
  notAnInstruction: true;
  usage: {
    currentTaskHasPriority: true;
    continueOnlyWhenCurrentTaskRequestsIt: true;
    projectFactsRequireCurrentTaskEvidence: true;
  };
  earlierSummary: {
    text: string;
    coveredThroughIntakeId: number;
    coveredTurnCount: number;
  } | null;
  uncoveredEarlierTurns: RecentConversationTurn[];
  turns: RecentConversationTurn[];
}

export interface LongTermMemoryEntry {
  id: string;
  scope: "USER" | "PROJECT";
  memoryType: string;
  content: string;
  updatedAt: string;
}

export interface LongTermMemoryEnvelope {
  schemaVersion: "1.0";
  type: "long_term_memory";
  notAnInstruction: true;
  usage: {
    currentTaskHasPriority: true;
    mayGuidePreferences: true;
    cannotGrantAuthority: true;
  };
  entries: LongTermMemoryEntry[];
}

export interface TaskObservations {
  manifestPaths: string[];
  readFiles: Array<{ path: string; sha256: string }>;
  toolPaths: string[];
  sandboxRuns: Array<{
    argv: string[];
    status: Receipt["status"];
    inputs: Array<{ path: string; sha256: string }>;
    workspaceRevision: number;
    receiptRef: string;
  }>;
  workspaceRevision: number;
  workspaceDiffObservedRevision: number;
  workspaceChanges: Array<{
    operation: "ADD" | "MODIFY";
    path: string;
    beforeSha256: string | null;
    afterSha256: string;
  }>;
}

export interface PersistedTask {
  authority: Authority;
  view: TaskView;
  messages: ChatMessage[];
  modelCalls: number;
  pendingModelCall?: { clientRequestId: string };
  metrics: { startedAt: string; finishedAt?: string; promptTokens: number; completionTokens: number };
  receiptRefs: string[];
  lastSandboxStatus?: Receipt["status"];
  pendingCalls: PendingCall[];
  nextPendingCall: number;
  acceptedAnswers: AcceptedAnswer[];
  recentConversation: RecentConversationTurn[];
  historicalContext: HistoricalContextEnvelope;
  longTermMemory: LongTermMemoryEnvelope;
  observations: TaskObservations;
  candidateValidationRepairs: number;
  toolArgumentRepairAttempts?: number;
  toolArgumentRepairModelCall?: number;
  publication?: PublicationFact;
  registeredTools?: RegisteredToolSpec[];
  registeredToolCatalogDigest?: string;
  discoveredToolNames?: string[];
  loadedToolNames?: string[];
  cancellationRequested?: boolean;
  activeSandboxCallId?: string | null;
}

export interface FileEntry { path: string; sizeBytes: number; sha256: string; mediaType: string }
export interface FileList { contractVersion: "1.0"; taskId: string; projectVersion: string; files: FileEntry[] }
export interface FileRead extends FileEntry { contractVersion: "1.0"; encoding: "utf-8"; content: string; truncated: false }
export interface WorkspaceWriteResult {
  contractVersion: "1.0";
  clientRequestId: string;
  requestDigest: string;
  replayed: boolean;
  operation: "ADD" | "MODIFY";
  path: string;
  beforeSha256: string | null;
  afterSha256: string;
  sizeBytes: number;
}
export interface WorkspaceDiffView {
  contractVersion: "1.0";
  taskId: string;
  projectVersion: string;
  changed: boolean;
  entries: Array<{
    operation: "ADD" | "MODIFY";
    path: string;
    beforeSha256: string | null;
    afterSha256: string;
  }>;
}
export interface PublicationFact {
  operationId: number;
  baseProjectVersion: string;
  publishedProjectVersion: string;
  publishedRevisionId: number;
  receiptRef: string;
}
export interface WorkspacePublishResult extends PublicationFact {
  contractVersion: "1.0";
  clientRequestId: string;
  requestDigest: string;
}
export interface SandboxView { contractVersion: "1.0"; clientRequestId: string; requestDigest: string; executionRef: string; state: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "TIMED_OUT" | "CANCELLED" | "SYSTEM_ERROR"; receiptRef: string | null }
export interface Receipt { contractVersion: "1.0"; receiptRef: string; executionRef: string; status: "SUCCEEDED" | "FAILED" | "TIMED_OUT" | "CANCELLED" | "SYSTEM_ERROR"; exitCode: number | null; stdout: { text: string; truncated: boolean; originalBytes: number }; stderr: { text: string; truncated: boolean; originalBytes: number }; inputFingerprint: string; inputs: Array<{ path: string; sha256: string; sizeBytes: number }>; startedAt: string; finishedAt: string }
