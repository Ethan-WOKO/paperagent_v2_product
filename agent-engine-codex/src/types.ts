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
  permissions: { readProject: true; writeWorkspace: false; executeSandbox: true };
  model: { provider: string; model: string };
}

export interface TaskSubmission {
  contractVersion: "1.0";
  taskId: string;
  requestDigest: string;
  authority: Authority;
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
  | (EventBase & { type: "tool"; callId: string; name: ToolName; state: "requested" | "running" | "succeeded" | "failed" | "cancelled"; inputSummary: string; outputSummary: string | null; receiptRef: string | null })
  | (EventBase & { type: "delivery"; conclusion: string; receiptRefs: string[] });

export type ToolName = "project.list" | "project.read" | "sandbox.execute";

export interface ChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string | null;
  /** Provider-owned thinking transcript that some OpenAI-compatible models
   * require to be echoed with the assistant tool call on the next request. */
  reasoningContent?: string;
  toolCallId?: string;
  toolCalls?: ModelToolCall[];
}

export interface ModelToolCall { id: string; name: string; arguments: string }
export interface ModelResponse { content: string | null; reasoningContent?: string; toolCalls: ModelToolCall[]; usage?: { promptTokens: number; completionTokens: number } }
export interface ModelRequest {
  provider: string;
  model: string;
  messages: ChatMessage[];
  tools: unknown[];
  maxOutputTokens: 4096;
  signal: AbortSignal;
}
export interface ModelProvider { complete(request: ModelRequest): Promise<ModelResponse> }

export interface PendingCall extends ModelToolCall {
  ordinal: number;
  /** Durable sandbox acceptance identity. It prevents restart/re-grant from
   * creating a new execution identity or resetting the fixed wait budget. */
  sandbox?: {
    executionRef?: string;
    deadlineAt: string;
  };
}
export interface AcceptedAnswer {
  clientRequestId: string;
  questionId: string;
  answerDigest: string;
  /** Persisted so a journal-only recovery can reconstruct the exact tool reply. */
  answer?: string;
}

export interface PersistedTask {
  authority: Authority;
  view: TaskView;
  messages: ChatMessage[];
  modelCalls: number;
  metrics: { startedAt: string; finishedAt?: string; promptTokens: number; completionTokens: number };
  receiptRefs: string[];
  lastSandboxStatus?: Receipt["status"];
  pendingCalls: PendingCall[];
  nextPendingCall: number;
  acceptedAnswers: AcceptedAnswer[];
}

export interface FileEntry { path: string; sizeBytes: number; sha256: string; mediaType: string }
export interface FileList { contractVersion: "1.0"; taskId: string; projectVersion: string; files: FileEntry[] }
export interface FileRead extends FileEntry { contractVersion: "1.0"; encoding: "utf-8"; content: string; truncated: false }
export interface SandboxView { contractVersion: "1.0"; clientRequestId: string; requestDigest: string; executionRef: string; state: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "TIMED_OUT" | "CANCELLED" | "SYSTEM_ERROR"; receiptRef: string | null }
export interface Receipt { contractVersion: "1.0"; receiptRef: string; executionRef: string; status: "SUCCEEDED" | "FAILED" | "TIMED_OUT" | "CANCELLED" | "SYSTEM_ERROR"; exitCode: number | null; stdout: { text: string; truncated: boolean; originalBytes: number }; stderr: { text: string; truncated: boolean; originalBytes: number }; inputFingerprint: string; inputs: Array<{ path: string; sha256: string; sizeBytes: number }>; startedAt: string; finishedAt: string }
