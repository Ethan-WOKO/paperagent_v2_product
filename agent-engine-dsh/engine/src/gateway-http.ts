import { validateFileList, validateFileRead, validateProblem, validateReceipt, validateSandboxView } from './schemas.ts';
import { sha256Hex } from './canonical.ts';
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
  'SUBMIT_DIGEST_INVALID',
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

// The #151 gateway's real error vocabulary: TASK_GRANT_REQUIRED,
// TASK_GRANT_WRONG_TASK, WORKSPACE_FILE_NOT_FOUND, SANDBOX_COMMAND_DENIED,
// SANDBOX_EXECUTION_NOT_FOUND, SANDBOX_STATUS_UNAVAILABLE, ... The frozen
// contract owns these prefixes; anything else still fails closed. The raw
// message/sourceRef are never propagated — only the classified code.
const ALLOWED_ERROR_PREFIX = /^(?:TASK|WORKSPACE|SANDBOX)_[A-Z0-9_]{1,95}$/;

function isAllowedErrorCode(code: string): boolean {
  return ALLOWED_ERROR_CODES.has(code) || ALLOWED_ERROR_PREFIX.test(code);
}

const NON_TERMINAL_STATES = new Set(['QUEUED', 'RUNNING']);

/** Real HTTP client for the product tool gateway (contract §2/§5). The task
 * grant is read from a provider on every request so a replay-refreshed grant
 * reaches an already-running loop. Every success response is schema-validated
 * and authority-bound to the EXACT request values it answers: taskId and
 * projectVersion for the file manifest, path/hash plus re-attested content
 * hash/size for file reads, clientRequestId/requestDigest/executionRef for
 * sandbox views, and receiptRef/executionRef/view state/exact inputs for
 * receipts. Error responses are validated against the shared Problem schema
 * and classified through a stable allowlist, failing closed to a generic
 * gateway error otherwise. */
export class HttpGatewayClient implements GatewayClient {
  private readonly baseUrl: string;
  private readonly taskId: string;
  private readonly grantProvider: () => string;
  private readonly expectedProjectVersion: () => string | null;

  constructor(baseUrl: string, taskId: string, grantProvider: () => string, expectedProjectVersion: () => string | null = () => null) {
    this.baseUrl = baseUrl;
    this.taskId = taskId;
    this.grantProvider = grantProvider;
    this.expectedProjectVersion = expectedProjectVersion;
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
        if (isAllowedErrorCode(candidate)) code = candidate;
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
    const body = await this.request<{ taskId: string; projectVersion: string; files: WorkspaceFileEntry[] }>(
      `/internal/v1/agent-engine/tasks/${this.taskId}/workspace/files`,
      {},
      (value) => {
        validateFileList(value);
        const list = value as { taskId: string; projectVersion: string };
        if (list.taskId !== this.taskId) throw new Error('FILE_LIST_TASK_BINDING_MISMATCH');
        const expectedVersion = this.expectedProjectVersion();
        if (expectedVersion !== null && list.projectVersion !== expectedVersion) {
          throw new Error('FILE_LIST_PROJECT_VERSION_BINDING_MISMATCH');
        }
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
        const read = value as { path: string; sha256: string; sizeBytes: number; content: string };
        if (read.path !== path || read.sha256 !== expectedSha256) throw new Error('FILE_READ_BINDING_MISMATCH');
        // Re-attest the exact body: declared size and hash must describe the
        // bytes actually returned (truncated=false is enforced by the schema).
        if (Buffer.byteLength(read.content, 'utf8') !== read.sizeBytes) throw new Error('FILE_READ_SIZE_BINDING_MISMATCH');
        if (sha256Hex(read.content) !== read.sha256) throw new Error('FILE_READ_HASH_BINDING_MISMATCH');
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
        const view = value as { clientRequestId: string; requestDigest: string; state: string; receiptRef: string | null };
        if (view.clientRequestId !== request.clientRequestId || view.requestDigest !== request.requestDigest) {
          throw new Error('SANDBOX_VIEW_BINDING_MISMATCH');
        }
        // The acceptance response must obey the same state/receipt invariant as
        // every later poll: a non-terminal projection carries no receiptRef.
        if (NON_TERMINAL_STATES.has(view.state) && view.receiptRef !== null) {
          throw new Error('SANDBOX_VIEW_STATE_BINDING_MISMATCH');
        }
      },
    );
  }

  async getSandboxExecution(clientRequestId: string, expectedExecutionRef: string | null = null, expectedRequestDigest: string | null = null): Promise<SandboxView> {
    return this.request<SandboxView>(
      `/internal/v1/agent-engine/tasks/${this.taskId}/sandbox-executions/${clientRequestId}`,
      {},
      (value) => {
        validateSandboxView(value);
        const view = value as { clientRequestId: string; requestDigest: string; executionRef: string; state: string; receiptRef: string | null };
        // The poll response must answer the exact original submission: same
        // client identity, same digest, same execution identity.
        if (view.clientRequestId !== clientRequestId) throw new Error('SANDBOX_VIEW_BINDING_MISMATCH');
        if (expectedRequestDigest !== null && view.requestDigest !== expectedRequestDigest) {
          throw new Error('SANDBOX_VIEW_BINDING_MISMATCH');
        }
        // One execution identity across the whole poll cycle, including recovery.
        if (expectedExecutionRef !== null && view.executionRef !== expectedExecutionRef) {
          throw new Error('SANDBOX_EXECUTION_REF_BINDING_MISMATCH');
        }
        // A non-terminal projection must not carry a receipt reference.
        if (NON_TERMINAL_STATES.has(view.state) && view.receiptRef !== null) {
          throw new Error('SANDBOX_VIEW_STATE_BINDING_MISMATCH');
        }
      },
    );
  }

  async getSandboxReceipt(
    receiptRef: string,
    expected: { executionRef: string | null; viewState: SandboxView['state'] | null; inputs: { path: string; sha256: string }[] | null } = {
      executionRef: null,
      viewState: null,
      inputs: null,
    },
  ): Promise<ReceiptProjection> {
    return this.request<ReceiptProjection>(
      `/internal/v1/agent-engine/tasks/${this.taskId}/receipts/${encodeURIComponent(receiptRef)}`,
      {},
      (value) => {
        validateReceipt(value);
        const receipt = value as { receiptRef: string; executionRef: string; status: string; inputs: { path: string; sha256: string }[] };
        if (receipt.receiptRef !== receiptRef) throw new Error('RECEIPT_REF_BINDING_MISMATCH');
        if (expected.executionRef !== null && receipt.executionRef !== expected.executionRef) {
          throw new Error('RECEIPT_EXECUTION_REF_BINDING_MISMATCH');
        }
        // Terminal-status binding: the formal Receipt must report exactly the
        // terminal state the execution projection declared.
        if (expected.viewState !== null && receipt.status !== expected.viewState) {
          throw new Error('RECEIPT_STATUS_BINDING_MISMATCH');
        }
        // Exact inputs binding: same length, same order, identical path+hash.
        if (expected.inputs !== null) {
          const receiptInputs = receipt.inputs;
          if (receiptInputs.length !== expected.inputs.length) throw new Error('RECEIPT_INPUTS_BINDING_MISMATCH');
          for (let i = 0; i < expected.inputs.length; i++) {
            if (receiptInputs[i].path !== expected.inputs[i].path || receiptInputs[i].sha256 !== expected.inputs[i].sha256) {
              throw new Error('RECEIPT_INPUTS_BINDING_MISMATCH');
            }
          }
        }
      },
    );
  }
}
