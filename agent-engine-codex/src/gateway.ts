import type { FileList, FileRead, Receipt, SandboxView } from "./types.js";
import { EngineProblem, problem } from "./util.js";

export interface SandboxRequest { contractVersion: "1.0"; clientRequestId: string; requestDigest: string; argv: string[]; inputs: Array<{ path: string; sha256: string }>; timeoutMillis: number }
export interface GatewayClient {
  list(taskId: string, grant: string, signal: AbortSignal): Promise<FileList>;
  read(taskId: string, grant: string, path: string, expectedSha256: string, signal: AbortSignal): Promise<FileRead>;
  submit(taskId: string, grant: string, request: SandboxRequest, signal: AbortSignal): Promise<SandboxView>;
  execution(taskId: string, grant: string, clientRequestId: string, signal: AbortSignal): Promise<SandboxView>;
  receipt(taskId: string, grant: string, receiptRef: string, signal: AbortSignal): Promise<Receipt>;
}

export class HttpGatewayClient implements GatewayClient {
  constructor(private readonly origin: string) {}
  private base(taskId: string): string { return `${this.origin.replace(/\/$/, "")}/internal/v1/agent-engine/tasks/${encodeURIComponent(taskId)}`; }
  list(taskId: string, grant: string, signal: AbortSignal): Promise<FileList> { return this.call(`${this.base(taskId)}/workspace/files`, grant, signal); }
  read(taskId: string, grant: string, path: string, expectedSha256: string, signal: AbortSignal): Promise<FileRead> { return this.call(`${this.base(taskId)}/workspace/read`, grant, signal, { contractVersion: "1.0", path, expectedSha256 }); }
  submit(taskId: string, grant: string, request: SandboxRequest, signal: AbortSignal): Promise<SandboxView> { return this.call(`${this.base(taskId)}/sandbox-executions`, grant, signal, request); }
  execution(taskId: string, grant: string, clientRequestId: string, signal: AbortSignal): Promise<SandboxView> { return this.call(`${this.base(taskId)}/sandbox-executions/${encodeURIComponent(clientRequestId)}`, grant, signal); }
  receipt(taskId: string, grant: string, receiptRef: string, signal: AbortSignal): Promise<Receipt> { return this.call(`${this.base(taskId)}/receipts/${encodeURIComponent(receiptRef)}`, grant, signal); }

  private async call<T>(url: string, grant: string, signal: AbortSignal, body?: unknown): Promise<T> {
    let response: Response;
    try {
      response = await fetch(url, { method: body === undefined ? "GET" : "POST", headers: { authorization: `Bearer ${grant}`, ...(body === undefined ? {} : { "content-type": "application/json" }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), redirect: "error", signal });
    } catch (error) {
      if (signal.aborted) throw error;
      throw new EngineProblem(502, problem("GATEWAY_TRANSPORT_FAILED", "tool", "Product tool gateway request failed", true));
    }
    if (!response.ok) {
      let candidate: unknown;
      try { candidate = await response.json(); } catch { /* sanitized fallback */ }
      const gatewayProblem = validProblem(candidate) ? candidate : null;
      throw new EngineProblem(response.status, gatewayProblem
        ? problem(gatewayProblem.code, gatewayProblem.category, "Product tool gateway rejected the request", gatewayProblem.retryable)
        : problem("GATEWAY_REQUEST_FAILED", "tool", `Product tool gateway returned HTTP ${response.status}`, response.status >= 500));
    }
    try { return await response.json() as T; }
    catch { throw new EngineProblem(502, problem("GATEWAY_RESPONSE_INVALID", "tool", "Product tool gateway returned an invalid JSON response", true)); }
  }
}

const PROBLEM_CATEGORIES = new Set(["request", "authorization", "model", "tool", "code_validation", "sandbox_system", "cancelled", "internal"]);
const PROBLEM_KEYS = new Set(["contractVersion", "code", "category", "message", "retryable", "sourceRef"]);

function validProblem(value: unknown): value is import("./types.js").Problem {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const candidate = value as Record<string, unknown>;
  return Object.keys(candidate).every((key) => PROBLEM_KEYS.has(key))
    && candidate.contractVersion === "1.0"
    && typeof candidate.code === "string" && /^[A-Z][A-Z0-9_]{2,95}$/.test(candidate.code)
    && typeof candidate.category === "string" && PROBLEM_CATEGORIES.has(candidate.category)
    && typeof candidate.message === "string" && candidate.message.length >= 1 && candidate.message.length <= 1000
    && typeof candidate.retryable === "boolean"
    && (candidate.sourceRef === undefined || candidate.sourceRef === null || (typeof candidate.sourceRef === "string" && candidate.sourceRef.length <= 256));
}
