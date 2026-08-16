import { validateFileList, validateFileRead, validateProblem, validateReceipt, validateSandboxView } from './schemas.ts';
import type {
  GatewayClient,
  WorkspaceFileEntry,
  FileRead,
  SandboxSubmitRequest,
  SandboxView,
  ReceiptProjection,
} from './gateway.ts';

const ALLOWED_ERROR_CODES = new Set([
  'UNAUTHORIZED',
  'TASK_GRANT_EXPIRED',
  'TASK_GRANT_INVALID',
  'TASK_NOT_FOUND',
  'SUBMIT_DIGEST_CONFLICT',
  'EXECUTION_NOT_FOUND',
  'RECEIPT_NOT_FOUND',
  'FILE_NOT_FOUND',
  'HASH_MISMATCH',
  'POLICY_REJECTED',
  'REQUEST_TOO_LARGE',
  'CONCURRENCY_EXHAUSTED',
  'PROVIDER_REJECTED',
  'TIMED_OUT',
  'CANCELLED',
]);

/** Real HTTP client for the product tool gateway (contract §2/§5). The task
 * grant is read from a provider on every request so a replay-refreshed grant
 * reaches an already-running loop. Success responses are schema-validated and
 * authority-bound to the exact request values; error responses are validated
 * against the shared Problem schema and classified through a stable allowlist,
 * failing closed to a generic gateway error otherwise. */
export class HttpGatewayClient implements GatewayClient {
  private readonly baseUrl: string;
  private readonly taskId: string;
  private readonly grantProvider: () => string;

  constructor(baseUrl: string, taskId: string, grantProvider: () => string) {
    this.baseUrl = baseUrl;
    this.taskId = taskId;
    this.grantProvider = grantProvider;
  }

  private async request<T>(path: string, init: RequestInit = {}, validator?: (value: unknown) => void): Promise<T> {
    const res = await fetch(this.baseUrl + path, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + this.grantProvider(),
        ...(init.headers ?? {}),
      },
    });
    const text = await res.text();
    if (!res.ok) {
      let code: string | null = null;
      try {
        const parsed = JSON.parse(text) as unknown;
        validateProblem(parsed);
        const candidate = (parsed as { code: string }).code;
        if (ALLOWED_ERROR_CODES.has(candidate)) code = candidate;
      } catch {
        /* non-JSON or schema-violating error body: fail closed below */
      }
      throw new Error(code ?? 'GATEWAY_ERROR');
    }
    const body = JSON.parse(text) as T;
    if (validator) validator(body);
    return body;
  }

  async listWorkspaceFiles(): Promise<WorkspaceFileEntry[]> {
    const body = await this.request<{ taskId: string; files: WorkspaceFileEntry[] }>(
      `/internal/v1/agent-engine/tasks/${this.taskId}/workspace/files`,
      {},
      (value) => {
        validateFileList(value);
        if ((value as { taskId: string }).taskId !== this.taskId) throw new Error('FILE_LIST_TASK_BINDING_MISMATCH');
      },
    );
    return body.files;
  }

  async readWorkspaceFile(path: string, expectedSha256: string): Promise<FileRead> {
    const body = await this.request<FileRead>(
      `/internal/v1/agent-engine/tasks/${this.taskId}/workspace/read`,
      { method: 'POST', body: JSON.stringify({ contractVersion: '1.0', path, expectedSha256 }) },
      (value) => {
        validateFileRead(value);
        const read = value as { path: string; sha256: string };
        if (read.path !== path || read.sha256 !== expectedSha256) throw new Error('FILE_READ_BINDING_MISMATCH');
      },
    );
    return body;
  }

  async submitSandbox(request: SandboxSubmitRequest): Promise<SandboxView> {
    return this.request<SandboxView>(
      `/internal/v1/agent-engine/tasks/${this.taskId}/sandbox-executions`,
      {
        method: 'POST',
        body: JSON.stringify({
          contractVersion: '1.0',
          clientRequestId: request.clientRequestId,
          requestDigest: request.requestDigest,
          argv: request.argv,
          inputs: request.inputs,
          timeoutMillis: request.timeoutMillis,
        }),
      },
      (value) => {
        validateSandboxView(value);
        const view = value as { clientRequestId: string; requestDigest: string };
        if (view.clientRequestId !== request.clientRequestId || view.requestDigest !== request.requestDigest) {
          throw new Error('SANDBOX_VIEW_BINDING_MISMATCH');
        }
      },
    );
  }

  async getSandboxExecution(clientRequestId: string): Promise<SandboxView> {
    return this.request<SandboxView>(
      `/internal/v1/agent-engine/tasks/${this.taskId}/sandbox-executions/${clientRequestId}`,
      {},
      (value) => {
        validateSandboxView(value);
        if ((value as { clientRequestId: string }).clientRequestId !== clientRequestId) throw new Error('SANDBOX_VIEW_BINDING_MISMATCH');
      },
    );
  }

  async getSandboxReceipt(receiptRef: string): Promise<ReceiptProjection> {
    return this.request<ReceiptProjection>(
      `/internal/v1/agent-engine/tasks/${this.taskId}/receipts/${receiptRef}`,
      {},
      (value) => {
        validateReceipt(value);
        const receipt = value as { receiptRef: string };
        if (receipt.receiptRef !== receiptRef) throw new Error('RECEIPT_REF_BINDING_MISMATCH');
      },
    );
  }
}
