import type { FileList, FileRead, ModelRequest, ModelResponse, Receipt, RegisteredToolCatalog, RegisteredToolResult, SandboxView, WorkspaceDiffView, WorkspacePublishResult, WorkspaceWriteResult } from "./types.js";
import { EngineProblem, problem } from "./util.js";

const DEFAULT_GATEWAY_ATTEMPTS = 3;

export interface GatewayRetryPolicy {
  maxAttempts?: number;
  sleep?: (milliseconds: number, signal: AbortSignal) => Promise<void>;
  onRetry?: (event: { attempt: number; maxAttempts: number; status: number | null; category: import("./types.js").Problem["category"] }) => void;
}

export interface SandboxRequest { contractVersion: "1.0"; clientRequestId: string; requestDigest: string; argv: string[]; inputs: Array<{ path: string; sha256: string }>; timeoutMillis: number }
export interface WorkspaceWriteRequest { contractVersion: "1.0"; clientRequestId: string; requestDigest: string; operation: "ADD" | "MODIFY"; path: string; baseSha256: string | null; content: string }
export interface WorkspacePublishRequest { contractVersion: "1.0"; clientRequestId: string; requestDigest: string; receiptRef: string; entries: WorkspaceDiffView["entries"] }
export interface ModelCompletionRequest { contractVersion: "1.0"; clientRequestId: string; requestDigest: string; provider: string; model: string; messages: ModelRequest["messages"]; tools: ModelRequest["tools"]; maxOutputTokens: number }
export interface GatewayClient {
  completeModel?(taskId: string, grant: string, request: ModelCompletionRequest, signal: AbortSignal): Promise<ModelResponse>;
  tools(taskId: string, grant: string, signal: AbortSignal): Promise<RegisteredToolCatalog>;
  invoke(taskId: string, grant: string, request: { contractVersion: "1.0"; callId: string; toolName: string; arguments: Record<string, unknown>; requestDigest: string }, signal: AbortSignal): Promise<RegisteredToolResult>;
  list(taskId: string, grant: string, signal: AbortSignal): Promise<FileList>;
  read(taskId: string, grant: string, path: string, expectedSha256: string, signal: AbortSignal): Promise<FileRead>;
  write(taskId: string, grant: string, request: WorkspaceWriteRequest, signal: AbortSignal): Promise<WorkspaceWriteResult>;
  diff(taskId: string, grant: string, signal: AbortSignal): Promise<WorkspaceDiffView>;
  publish(taskId: string, grant: string, request: WorkspacePublishRequest, signal: AbortSignal): Promise<WorkspacePublishResult>;
  submit(taskId: string, grant: string, request: SandboxRequest, signal: AbortSignal): Promise<SandboxView>;
  cancelExecution(taskId: string, grant: string, clientRequestId: string, signal: AbortSignal): Promise<SandboxView>;
  execution(taskId: string, grant: string, clientRequestId: string, signal: AbortSignal): Promise<SandboxView>;
  receipt(taskId: string, grant: string, receiptRef: string, signal: AbortSignal): Promise<Receipt>;
}

export class HttpGatewayClient implements GatewayClient {
  private readonly maxAttempts: number;
  private readonly retrySleep: (milliseconds: number, signal: AbortSignal) => Promise<void>;
  private readonly onRetry: NonNullable<GatewayRetryPolicy["onRetry"]>;

  constructor(private readonly origin: string, retry: GatewayRetryPolicy = {}) {
    this.maxAttempts = Math.max(1, Math.min(DEFAULT_GATEWAY_ATTEMPTS,
      Math.trunc(retry.maxAttempts ?? DEFAULT_GATEWAY_ATTEMPTS)));
    this.retrySleep = retry.sleep ?? abortableDelay;
    this.onRetry = retry.onRetry ?? ((event) => {
      process.stderr.write(
        `agent-engine-reactplan: gateway retry attempt=${event.attempt}/${event.maxAttempts} status=${event.status ?? "transport"} category=${event.category}\n`
      );
    });
  }
  private base(taskId: string): string { return `${this.origin.replace(/\/$/, "")}/internal/v1/agent-engine/tasks/${encodeURIComponent(taskId)}`; }
  completeModel(taskId: string, grant: string, request: ModelCompletionRequest, signal: AbortSignal): Promise<ModelResponse> { return this.call(`${this.base(taskId)}/model-completions`, grant, signal, request, "model"); }
  tools(taskId: string, grant: string, signal: AbortSignal): Promise<RegisteredToolCatalog> { return this.call(`${this.base(taskId)}/tools`, grant, signal); }
  invoke(taskId: string, grant: string, request: { contractVersion: "1.0"; callId: string; toolName: string; arguments: Record<string, unknown>; requestDigest: string }, signal: AbortSignal): Promise<RegisteredToolResult> { return this.call(`${this.base(taskId)}/tool-calls`, grant, signal, request); }
  list(taskId: string, grant: string, signal: AbortSignal): Promise<FileList> { return this.call(`${this.base(taskId)}/workspace/files`, grant, signal); }
  read(taskId: string, grant: string, path: string, expectedSha256: string, signal: AbortSignal): Promise<FileRead> { return this.call(`${this.base(taskId)}/workspace/read`, grant, signal, { contractVersion: "1.0", path, expectedSha256 }); }
  write(taskId: string, grant: string, request: WorkspaceWriteRequest, signal: AbortSignal): Promise<WorkspaceWriteResult> { return this.call(`${this.base(taskId)}/workspace/write`, grant, signal, request); }
  diff(taskId: string, grant: string, signal: AbortSignal): Promise<WorkspaceDiffView> { return this.call(`${this.base(taskId)}/workspace/diff`, grant, signal); }
  publish(taskId: string, grant: string, request: WorkspacePublishRequest, signal: AbortSignal): Promise<WorkspacePublishResult> { return this.call(`${this.base(taskId)}/workspace/publish`, grant, signal, request); }
  submit(taskId: string, grant: string, request: SandboxRequest, signal: AbortSignal): Promise<SandboxView> { return this.call(`${this.base(taskId)}/sandbox-executions`, grant, signal, request); }
  cancelExecution(taskId: string, grant: string, clientRequestId: string, signal: AbortSignal): Promise<SandboxView> { return this.call(`${this.base(taskId)}/sandbox-executions/${encodeURIComponent(clientRequestId)}/cancel`, grant, signal, { contractVersion: "1.0" }); }
  execution(taskId: string, grant: string, clientRequestId: string, signal: AbortSignal): Promise<SandboxView> { return this.call(`${this.base(taskId)}/sandbox-executions/${encodeURIComponent(clientRequestId)}`, grant, signal); }
  receipt(taskId: string, grant: string, receiptRef: string, signal: AbortSignal): Promise<Receipt> { return this.call(`${this.base(taskId)}/receipts/${encodeURIComponent(receiptRef)}`, grant, signal); }

  private async call<T>(url: string, grant: string, signal: AbortSignal, body?: unknown, fallbackCategory: import("./types.js").Problem["category"] = "tool"): Promise<T> {
    const serialized = body === undefined ? undefined : JSON.stringify(body);
    for (let attempt = 1; attempt <= this.maxAttempts; attempt += 1) {
      let response: Response;
      try {
        response = await fetch(url, { method: serialized === undefined ? "GET" : "POST", headers: { authorization: `Bearer ${grant}`, ...(serialized === undefined ? {} : { "content-type": "application/json" }) }, ...(serialized === undefined ? {} : { body: serialized }), signal });
      } catch (error) {
        if (signal.aborted) throw error;
        if (attempt < this.maxAttempts) {
          await this.retry(attempt, null, fallbackCategory, signal);
          continue;
        }
        throw new EngineProblem(502, problem("GATEWAY_TRANSPORT_FAILED", fallbackCategory, "Product gateway request failed after bounded retries", true));
      }
      if (response.ok) return await response.json() as T;

      let gatewayProblem: { code?: string; category?: import("./types.js").Problem["category"]; message?: string; retryable?: boolean } = {};
      try { gatewayProblem = await response.json() as typeof gatewayProblem; } catch { /* sanitized fallback */ }
      const category = gatewayProblem.category ?? fallbackCategory;
      const retryable = gatewayProblem.retryable ?? response.status >= 500;
      if (attempt < this.maxAttempts && response.status >= 500 && retryable) {
        await this.retry(attempt, response.status, category, signal);
        continue;
      }
      throw new EngineProblem(response.status, problem(gatewayProblem.code ?? "GATEWAY_REQUEST_FAILED", category, gatewayProblem.message ?? `Product gateway returned HTTP ${response.status}`, retryable));
    }
    throw new EngineProblem(502, problem("GATEWAY_RETRY_EXHAUSTED", fallbackCategory, "Product gateway retry budget exhausted", true));
  }

  private async retry(attempt: number, status: number | null, category: import("./types.js").Problem["category"], signal: AbortSignal): Promise<void> {
    this.onRetry({ attempt, maxAttempts: this.maxAttempts, status, category });
    await this.retrySleep(100 * (2 ** (attempt - 1)), signal);
  }
}

function abortableDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
      return;
    }
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, milliseconds);
    signal.addEventListener("abort", onAbort, { once: true });
  });
}
