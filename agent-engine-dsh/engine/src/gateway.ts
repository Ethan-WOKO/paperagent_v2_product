import { appendFileSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { sha256Hex } from './canonical.ts';

export interface WorkspaceFileEntry {
  path: string;
  sizeBytes: number;
  sha256: string;
  mediaType: string;
}

export interface FileRead {
  path: string;
  sizeBytes: number;
  sha256: string;
  mediaType: string;
  encoding: 'utf-8';
  content: string;
  truncated: false;
}

export interface SandboxSubmitRequest {
  clientRequestId: string;
  requestDigest: string;
  argv: string[];
  inputs: { path: string; sha256: string }[];
  timeoutMillis: number;
}

export interface SandboxView {
  clientRequestId: string;
  requestDigest: string;
  executionRef: string;
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED' | 'SYSTEM_ERROR';
  receiptRef: string | null;
}

export interface ReceiptProjection {
  receiptRef: string;
  executionRef: string;
  status: 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED' | 'SYSTEM_ERROR';
  exitCode: number | null;
  stdout: { text: string; truncated: boolean; originalBytes: number };
  stderr: { text: string; truncated: boolean; originalBytes: number };
  inputFingerprint: string;
  inputs: { path: string; sha256: string; sizeBytes: number }[];
}

/** Engine-side gateway seam. P1 uses StubGateway only for control-plane
 * conformance (ENGINE_RUNNER=stub); the formal path uses the real HTTP client
 * with the same interface. Binding expectations are optional: the HTTP client
 * enforces them, the stub is a permissive test double. */
export interface GatewayClient {
  listWorkspaceFiles(): Promise<WorkspaceFileEntry[]>;
  readWorkspaceFile(path: string, expectedSha256: string): Promise<FileRead>;
  submitSandbox(request: SandboxSubmitRequest): Promise<SandboxView>;
  getSandboxExecution(clientRequestId: string, expectedExecutionRef?: string | null): Promise<SandboxView>;
  getSandboxReceipt(
    receiptRef: string,
    expected?: { executionRef: string | null; viewState: SandboxView['state'] | null; inputs: { path: string; sha256: string }[] | null },
  ): Promise<ReceiptProjection>;
}

export class StubGateway implements GatewayClient {
  private submissions = new Map<string, SandboxView & { receipt: ReceiptProjection | null }>();
  private receiptSeq = 0;
  private readonly files: WorkspaceFileEntry[];
  private readonly contents: Map<string, string>;
  private readonly submissionLogPath: string | null;

  constructor(
    files: WorkspaceFileEntry[],
    contents: Map<string, string>,
    submissionLogPath: string | null,
  ) {
    this.files = files;
    this.contents = contents;
    this.submissionLogPath = submissionLogPath;
    // Replay the persisted submission log so restart never re-dispatches.
    for (const line of submissionLogPath ? loadSubmissionLog(submissionLogPath) : []) {
      try {
        const entry = JSON.parse(line) as { clientRequestId: string; requestDigest: string; argv: string[] };
        this.submissions.set(entry.clientRequestId, this.rebuildView(entry));
      } catch {
        /* ignore malformed local log lines */
      }
    }
  }

  private rebuildView(entry: { clientRequestId: string; requestDigest: string; argv: string[] }): SandboxView & { receipt: ReceiptProjection | null } {
    const executionRef = 'stub-exec.' + sha256Hex(entry.clientRequestId).slice(0, 16);
    const receiptRef = 'receipt.stub.' + sha256Hex(entry.clientRequestId).slice(0, 12);
    return {
      clientRequestId: entry.clientRequestId,
      requestDigest: entry.requestDigest,
      executionRef,
      state: 'SUCCEEDED',
      receiptRef,
      receipt: {
        receiptRef,
        executionRef,
        status: 'SUCCEEDED',
        exitCode: 0,
        stdout: { text: '[1, 2, 3]\n', truncated: false, originalBytes: 8 },
        stderr: { text: '', truncated: false, originalBytes: 0 },
        inputFingerprint: sha256Hex(entry.argv.join('\0')),
        inputs: [],
      },
    };
  }

  async listWorkspaceFiles(): Promise<WorkspaceFileEntry[]> {
    return this.files.map((f) => ({ ...f }));
  }

  async readWorkspaceFile(path: string, expectedSha256: string): Promise<FileRead> {
    const content = this.contents.get(path);
    if (content === undefined) {
      throw new Error('FILE_NOT_FOUND:' + path);
    }
    const sha256 = sha256Hex(content);
    if (sha256 !== expectedSha256) {
      throw new Error('HASH_MISMATCH:' + path);
    }
    return {
      path,
      sizeBytes: Buffer.byteLength(content, 'utf8'),
      sha256,
      mediaType: 'text/plain',
      encoding: 'utf-8',
      content,
      truncated: false,
    };
  }

  async submitSandbox(request: SandboxSubmitRequest): Promise<SandboxView> {
    const existing = this.submissions.get(request.clientRequestId);
    if (existing) {
      if (existing.requestDigest !== request.requestDigest) {
        throw new Error('SUBMIT_DIGEST_CONFLICT');
      }
      return this.viewOf(existing);
    }
    const entry = { clientRequestId: request.clientRequestId, requestDigest: request.requestDigest, argv: request.argv };
    const view = this.rebuildView(entry);
    this.submissions.set(request.clientRequestId, view);
    if (this.submissionLogPath) {
      appendFileSync(this.submissionLogPath, JSON.stringify(entry) + '\n', 'utf8');
    }
    return this.viewOf(view);
  }

  async getSandboxExecution(clientRequestId: string, _expectedExecutionRef?: string | null): Promise<SandboxView> {
    const existing = this.submissions.get(clientRequestId);
    if (!existing) throw new Error('EXECUTION_NOT_FOUND');
    return this.viewOf(existing);
  }

  async getSandboxReceipt(
    receiptRef: string,
    _expected?: { executionRef: string | null; viewState: SandboxView['state'] | null; inputs: { path: string; sha256: string }[] | null },
  ): Promise<ReceiptProjection> {
    for (const value of this.submissions.values()) {
      if (value.receipt?.receiptRef === receiptRef) return value.receipt;
    }
    throw new Error('RECEIPT_NOT_FOUND');
  }

  private viewOf(value: SandboxView & { receipt: ReceiptProjection | null }): SandboxView {
    return {
      clientRequestId: value.clientRequestId,
      requestDigest: value.requestDigest,
      executionRef: value.executionRef,
      state: value.state,
      receiptRef: value.receiptRef,
    };
  }
}

export function loadSubmissionLog(path: string): string[] {
  try {
    return readFileSync(join(path), 'utf8').split('\n').filter((line) => line.trim().length > 0);
  } catch {
    return [];
  }
}
