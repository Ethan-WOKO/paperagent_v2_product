import type { TaskRuntime } from './task.ts';
import type { GatewayClient } from './gateway.ts';

export interface StubRunnerOptions {
  question?: boolean;
  fail?: boolean;
  stepDelayMs: number;
  message?: string;
  conclusion?: string;
  useGateway?: boolean;
  gateway?: GatewayClient;
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

function grantExpired(task: TaskRuntime): boolean {
  return Date.parse(task.grant.expiresAt) < Date.now();
}

/** Deterministic conformance runner. Exercises the full control-plane state
 * machine and, with useGateway, the gateway seam without a model. */
export class StubRunner {
  private readonly options: StubRunnerOptions;

  constructor(options: StubRunnerOptions) {
    this.options = options;
  }

  async run(task: TaskRuntime, isCancelled: () => boolean): Promise<void> {
    const cancelled = (): boolean => {
      if (isCancelled()) {
        task.cancelFinalize();
        return true;
      }
      return false;
    };

    await sleep(this.options.stepDelayMs);
    if (cancelled()) return;

    task.emit('message', { content: this.options.message ?? 'stub engine working' });
    if (cancelled()) return;

    if (this.options.question) {
      task.emit('question', { questionId: 'q1', text: 'stub question: continue?' });
      return;
    }

    let receiptRef = 'receipt.stub.1';
    if (this.options.useGateway) {
      if (grantExpired(task)) {
        task.emit('status', {
          state: 'failed',
          error: {
            contractVersion: '1.0',
            code: 'TASK_GRANT_EXPIRED',
            category: 'authorization',
            message: 'task grant has expired; Java must resubmit with a fresh grant',
            retryable: true,
            sourceRef: task.meta.taskId,
          },
        });
        return;
      }
      const gateway = this.options.gateway;
      if (!gateway) throw new Error('useGateway requires a GatewayClient');
      const callId = 'call.' + task.meta.taskId.slice(5, 21) + 'stubgw';
      task.emit('tool', { callId, name: 'sandbox.execute', state: 'requested', inputSummary: 'stub sandbox run', outputSummary: null, receiptRef: null });
      if (cancelled()) return;
      const view = await gateway.submitSandbox({
        clientRequestId: callId,
        requestDigest: 'd'.repeat(64),
        argv: ['javac', 'src/main/java/Sort.java'],
        inputs: [{ path: 'src/main/java/Sort.java', sha256: 'a'.repeat(64) }],
        timeoutMillis: 120000,
      });
      task.emit('tool', { callId, name: 'sandbox.execute', state: 'running', inputSummary: 'stub sandbox run', outputSummary: null, receiptRef: null });
      if (cancelled()) return;
      const receipt = view.receiptRef ? await gateway.getSandboxReceipt(view.receiptRef) : null;
      task.emit('tool', { callId, name: 'sandbox.execute', state: 'succeeded', inputSummary: 'stub sandbox run', outputSummary: `exit code ${receipt?.exitCode ?? 0}`, receiptRef: view.receiptRef });
      receiptRef = view.receiptRef ?? receiptRef;
      if (cancelled()) return;
    }

    await sleep(this.options.stepDelayMs);
    if (cancelled()) return;

    task.emit('delivery', {
      conclusion: this.options.conclusion ?? 'stub conclusion: task finished',
      receiptRefs: [receiptRef],
    });

    if (this.options.fail) {
      task.emit('status', {
        state: 'failed',
        error: {
          contractVersion: '1.0',
          code: 'STUB_FAILURE',
          category: 'internal',
          message: 'stub induced failure',
          retryable: false,
          sourceRef: null,
        },
      });
    } else {
      task.emit('status', { state: 'succeeded', error: null });
    }
  }
}

export function stubResumeAfterAnswer(task: TaskRuntime, options: { conclusion?: string }): void {
  void (async () => {
    await sleep(10);
    if (task.isTerminal()) return;
    task.emit('delivery', { conclusion: options.conclusion ?? 'stub conclusion after answer', receiptRefs: ['receipt.stub.1'] });
    task.emit('status', { state: 'succeeded', error: null });
  })();
}
