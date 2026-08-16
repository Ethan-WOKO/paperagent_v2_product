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

const PHASE_ORDER = ['init', 'messaged', 'tool-requested', 'tool-running', 'tool-succeeded', 'delivered', 'questioned'];

/** Deterministic conformance runner. Exercises the full control-plane state
 * machine and, with useGateway, the gateway seam without a model.
 * Resume-safe: runnerPhase in the task meta makes every step idempotent, so a
 * non-terminal task can be re-armed after restart without duplicating events. */
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
    const phase = task.meta.runnerPhase;
    const atLeast = (p: string): boolean => PHASE_ORDER.indexOf(phase) >= PHASE_ORDER.indexOf(p);

    if (this.options.question) {
      if (!atLeast('questioned')) {
        await sleep(this.options.stepDelayMs);
        if (cancelled()) return;
        if (task.meta.runnerPhase === 'init') {
          task.emit('message', { content: this.options.message ?? 'stub engine working' });
          task.setRunnerPhase('messaged');
        }
        task.emit('question', { questionId: 'q1', text: 'stub question: continue?' });
        task.setRunnerPhase('questioned');
      }
      return;
    }

    if (!atLeast('messaged')) {
      await sleep(this.options.stepDelayMs);
      if (cancelled()) return;
      task.emit('message', { content: this.options.message ?? 'stub engine working' });
      task.setRunnerPhase('messaged');
    }

    let receiptRef = 'receipt.stub.1';
    if (this.options.useGateway && !atLeast('tool-succeeded')) {
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
      if (!atLeast('tool-requested')) {
        task.emit('tool', { callId, name: 'sandbox.execute', state: 'requested', inputSummary: 'stub sandbox run', outputSummary: null, receiptRef: null });
        task.setRunnerPhase('tool-requested');
      }
      if (cancelled()) return;
      const view = await gateway.submitSandbox({
        clientRequestId: callId,
        requestDigest: 'd'.repeat(64),
        argv: ['javac', 'src/main/java/Sort.java'],
        inputs: [{ path: 'src/main/java/Sort.java', sha256: 'a'.repeat(64) }],
        timeoutMillis: 120000,
      });
      if (!atLeast('tool-running')) {
        task.emit('tool', { callId, name: 'sandbox.execute', state: 'running', inputSummary: 'stub sandbox run', outputSummary: null, receiptRef: null });
        task.setRunnerPhase('tool-running');
      }
      if (cancelled()) return;
      const receipt = view.receiptRef ? await gateway.getSandboxReceipt(view.receiptRef) : null;
      task.emit('tool', { callId, name: 'sandbox.execute', state: 'succeeded', inputSummary: 'stub sandbox run', outputSummary: `exit code ${receipt?.exitCode ?? 0}`, receiptRef: view.receiptRef });
      task.setRunnerPhase('tool-succeeded');
      receiptRef = view.receiptRef ?? receiptRef;
      if (cancelled()) return;
    }

    if (!atLeast('delivered')) {
      await sleep(this.options.stepDelayMs);
      if (cancelled()) return;
      task.emit('delivery', {
        conclusion: this.options.conclusion ?? 'stub conclusion: task finished',
        receiptRefs: [receiptRef],
      });
      task.setRunnerPhase('delivered');
    }

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

export function stubResumeAfterAnswer(task: TaskRuntime, options: { conclusion?: string; answerDelayMs?: number }): void {
  void (async () => {
    await sleep(options.answerDelayMs ?? 10);
    if (task.isTerminal()) return;
    task.emit('delivery', { conclusion: options.conclusion ?? 'stub conclusion after answer', receiptRefs: ['receipt.stub.1'] });
    task.setRunnerPhase('delivered');
    task.emit('status', { state: 'succeeded', error: null });
  })();
}
