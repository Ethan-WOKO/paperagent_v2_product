import { validateFileList, validateFileRead, validateReceipt, validateSandboxView } from './schemas.ts';
import type {
  GatewayClient,
  WorkspaceFileEntry,
  FileRead,
  SandboxSubmitRequest,
  SandboxView,
  ReceiptProjection,
} from './gateway.ts';

/** Real HTTP client for the product tool gateway (contract §2/§5). The task
 * grant travels as Bearer; the gateway origin is deployment config, never task
 * input. */
export class HttpGatewayClient implements GatewayClient {
  private readonly baseUrl: string;
  private readonly taskId: string;
  private readonly grant: string;

  constructor(baseUrl: string, taskId: string, grant: string) {
    this.baseUrl = baseUrl;
    this.taskId = taskId;
    this.grant = grant;
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const res = await fetch(this.baseUrl + path, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + this.grant,
        ...(init.headers ?? {}),
      },
    });
    const text = await res.text();
    if (!res.ok) {
      // Sanitized: never echo raw gateway bodies, paths, or configuration.
      let code = 'GATEWAY_' + res.status;
      try {
        const parsed = JSON.parse(text) as { code?: string };
        if (typeof parsed.code === 'string' && /^[A-Z][A-Z0-9_]{2,95}$/.test(parsed.code)) {
          code = parsed.code;
        }
      } catch {
        /* non-JSON error body */
      }
      throw new Error(code);
    }
    return JSON.parse(text) as T;
  }

  async listWorkspaceFiles(): Promise<WorkspaceFileEntry[]> {
    const body = await this.request<{ files: WorkspaceFileEntry[] }>(`/internal/v1/agent-engine/tasks/${this.taskId}/workspace/files`);
    validateFileList(body);
    return body.files;
  }

  async readWorkspaceFile(path: string, expectedSha256: string): Promise<FileRead> {
    const body = await this.request<FileRead>(`/internal/v1/agent-engine/tasks/${this.taskId}/workspace/read`, {
      method: 'POST',
      body: JSON.stringify({ contractVersion: '1.0', path, expectedSha256 }),
    });
    validateFileRead(body);
    return body;
  }

  async submitSandbox(request: SandboxSubmitRequest): Promise<SandboxView> {
    const view = await this.request<SandboxView>(`/internal/v1/agent-engine/tasks/${this.taskId}/sandbox-executions`, {
      method: 'POST',
      body: JSON.stringify({
        contractVersion: '1.0',
        clientRequestId: request.clientRequestId,
        requestDigest: request.requestDigest,
        argv: request.argv,
        inputs: request.inputs,
        timeoutMillis: request.timeoutMillis,
      }),
    });
    validateSandboxView(view);
    return view;
  }

  async getSandboxExecution(clientRequestId: string): Promise<SandboxView> {
    const view = await this.request<SandboxView>(`/internal/v1/agent-engine/tasks/${this.taskId}/sandbox-executions/${clientRequestId}`);
    validateSandboxView(view);
    return view;
  }

  async getSandboxReceipt(receiptRef: string): Promise<ReceiptProjection> {
    const receipt = await this.request<ReceiptProjection>(`/internal/v1/agent-engine/tasks/${this.taskId}/receipts/${receiptRef}`);
    validateReceipt(receipt);
    return receipt;
  }
}
