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
      response = await fetch(url, { method: body === undefined ? "GET" : "POST", headers: { authorization: `Bearer ${grant}`, ...(body === undefined ? {} : { "content-type": "application/json" }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal });
    } catch (error) {
      if (signal.aborted) throw error;
      throw new EngineProblem(502, problem("GATEWAY_TRANSPORT_FAILED", "tool", "Product tool gateway request failed", true));
    }
    if (!response.ok) {
      let gatewayProblem: { code?: string; category?: import("./types.js").Problem["category"]; message?: string; retryable?: boolean } = {};
      try { gatewayProblem = await response.json() as typeof gatewayProblem; } catch { /* sanitized fallback */ }
      throw new EngineProblem(response.status, problem(gatewayProblem.code ?? "GATEWAY_REQUEST_FAILED", gatewayProblem.category ?? "tool", gatewayProblem.message ?? `Product tool gateway returned HTTP ${response.status}`, gatewayProblem.retryable ?? response.status >= 500));
    }
    return await response.json() as T;
  }
}
