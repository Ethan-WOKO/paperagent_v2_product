import { createHash } from 'node:crypto';
import type { GatewayClient } from '../gateway.ts';
import type { SandboxView } from '../gateway.ts';
import type { TaskRuntime } from '../task.ts';
import { defineTool } from '@deepseek-ai/dsh-tools';
import type { ToolDefinition } from '@deepseek-ai/dsh-tools';
import { canonicalJson, sha256Hex } from '../canonical.ts';

/** Fixed sandbox polling policy (contract §6): 1,2,4,5,5… seconds, no jitter;
 * deadline is timeoutMillis + 30s from local 202 receipt. */
const POLL_SEQUENCE = [1000, 2000, 4000];

function pollDelayMs(poll: number): number {
  return poll < POLL_SEQUENCE.length ? POLL_SEQUENCE[poll] : 5000;
}

const OBJECT_OUTPUT = { type: 'object', additionalProperties: true } as never;
const renderJson = ((_: unknown, value: unknown) => [{ type: 'text', text: JSON.stringify(value) }]) as never;

export interface ProductTools {
  listTool: ToolDefinition;
  readTool: ToolDefinition;
  sandboxTool: ToolDefinition;
  askUserTool: ToolDefinition;
  collectReceiptRefs(): string[];
}

/** Stable per-task tool identity: ledger-backed clientRequestId derivation and
 * Receipt reuse so recovery never mints a new call for the same argv. */
function stableCallId(task: TaskRuntime, callSeq: number): string {
  return 'call.' + createHash('sha256').update(task.meta.taskId + ':' + callSeq).digest('hex').slice(0, 20);
}

/** Sandbox request digest: contract §4 canonical JSON over the exact submit
 * semantics — argv, inputs and timeoutMillis — with keys sorted by Unicode
 * code point and arrays keeping order. timeoutMillis participates so a
 * different budget can never collide with a prior execution. */
function sandboxDigest(argv: string[], inputs: { path: string; sha256: string }[], timeoutMillis: number): string {
  return sha256Hex(canonicalJson({ argv, inputs, timeoutMillis }));
}

const TERMINAL_VIEW_STATES = new Set(['SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'SYSTEM_ERROR']);

interface PollDeadline {
  exceeded(): boolean;
  remainingMs(): number;
}

/** Serial polling until a terminal view or the deadline (contract §6):
 * acceptance + timeoutMillis + 30s. In an active process the deadline is
 * measured with the local MONOTONIC clock; the persisted wall-clock deadline
 * is only a fail-closed upper bound used after restart. The deadline is
 * checked BEFORE the first status request and after every sleep, and sleeps
 * never run past it, so no status request is ever issued beyond the deadline. */
async function pollToTerminal(
  gateway: GatewayClient,
  task: TaskRuntime,
  clientRequestId: string,
  executionRef: string | null,
  requestDigest: string | null,
  deadline: PollDeadline,
  argv: string[],
  initialView: SandboxView | null = null,
): Promise<SandboxView> {
  const failDeadline = (): never => {
    task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: 'failed', inputSummary: argv.join(' '), outputSummary: 'sandbox status deadline exceeded', receiptRef: null });
    // Contract §6: the ENGINE produces the bounded deadline failure itself —
    // the model must never keep polling past the deadline.
    task.emit('status', {
      state: 'failed',
      error: {
        contractVersion: '1.0',
        code: 'SANDBOX_STATUS_DEADLINE_EXCEEDED',
        category: 'sandbox_system',
        message: 'sandbox execution did not reach a terminal state before the fixed deadline',
        retryable: false,
        sourceRef: null,
      },
    });
    throw new Error('SANDBOX_STATUS_DEADLINE_EXCEEDED');
  };

  let view = initialView;
  let poll = 0;
  if (view !== null && TERMINAL_VIEW_STATES.has(view.state)) return view;
  if (deadline.exceeded()) failDeadline();
  if (view === null) {
    view = await gateway.getSandboxExecution(clientRequestId, executionRef, requestDigest);
  }
  while (!TERMINAL_VIEW_STATES.has(view.state)) {
    if (deadline.exceeded()) failDeadline();
    // Never sleep past the deadline; wake up no later than the deadline.
    await new Promise((r) => setTimeout(r, Math.max(1, Math.min(pollDelayMs(poll), Math.ceil(deadline.remainingMs())))));
    poll++;
    if (deadline.exceeded()) failDeadline();
    view = await gateway.getSandboxExecution(clientRequestId, executionRef, requestDigest);
  }
  return view;
}

/** Tool bodies emit TaskEvents directly and return bounded canonical values.
 * Typed loosely at the dsh seam: the runtime validates canonical output. */
export function buildProductTools(task: TaskRuntime, gateway: GatewayClient): ProductTools {
  // Recovery re-adopts completed, gateway-verified Receipts from the persisted
  // ledger so the completion gate never fails just because the model did not
  // repeat a tool call after restart.
  const receiptRefs: string[] = task.completedReceipts().map((entry) => entry.receiptRef);

  const listTool = defineTool({
    name: 'project_list',
    description:
      'List the relative paths of every file in the frozen project Workspace (read only). ' +
      'Use this before reading files or choosing sandbox inputs.',
    parameters: {},
    output: { schema: OBJECT_OUTPUT, render: renderJson },
    async execute(_args: Record<string, never>) {
      const files = await gateway.listWorkspaceFiles();
      task.emit('tool', { callId: 'call.list', name: 'project.list', state: 'succeeded', inputSummary: 'list workspace files', outputSummary: `${files.length} files`, receiptRef: null });
      return { files: files.map((f) => ({ path: f.path, sizeBytes: f.sizeBytes, sha256: f.sha256 })) };
    },
  } as never) as ToolDefinition;

  const readTool = defineTool({
    name: 'project_read',
    description:
      'Read one complete project file from the frozen Workspace by its relative path (read only). ' +
      'Provide expectedSha256 from a prior project_list result.',
    parameters: {
      path: { type: 'string', required: true, description: 'project-relative path, e.g. src/main/java/Sort.java' },
      expectedSha256: { type: 'string', required: true, description: 'sha256 from project_list' },
    },
    output: { schema: OBJECT_OUTPUT, render: renderJson },
    async execute(args: { path: string; expectedSha256: string }) {
      const read = await gateway.readWorkspaceFile(args.path, args.expectedSha256);
      task.emit('tool', { callId: 'call.read', name: 'project.read', state: 'succeeded', inputSummary: `read ${args.path}`, outputSummary: `${read.sizeBytes} bytes`, receiptRef: null });
      return { path: read.path, sha256: read.sha256, content: read.content, truncated: read.truncated };
    },
  } as never) as ToolDefinition;

  const askUserTool = defineTool({
    name: 'ask_user',
    description:
      'Ask the user exactly one precise question when the task cannot proceed without their input. ' +
      'The engine pauses until the user answers; then execution continues.',
    parameters: {
      question: { type: 'string', required: true, description: 'the exact user-facing question (max 4000 chars)' },
    },
    output: { schema: { type: 'string' }, render: ((_: unknown, value: unknown) => [{ type: 'text', text: String(value) }]) as never },
    async execute(args: { question: string }, exec: { signal: AbortSignal }) {
      const { questionId, answerPromise } = task.askUser(String(args.question).slice(0, 4000));
      const answer = await Promise.race([
        answerPromise,
        new Promise<string>((_, reject) => {
          exec.signal.addEventListener('abort', () => reject(new Error('TOOL_ABORTED')), { once: true });
        }),
      ]);
      return answer;
    },
  } as never) as ToolDefinition;

  const sandboxTool = defineTool({
    name: 'sandbox_execute',
    description:
      'Compile or run project files in the isolated sandbox. argv must use the fixed profiles: ' +
      'yanban-runner java <source> [--dependency=group:artifact:version ...], yanban-runner python <source> ' +
      '[--dependency=package==version ...], javac <.java files>, mvn -o test. Declare every non-standard ' +
      'dependency on the first run. inputs lists the involved workspace paths with their sha256 from ' +
      'project_list. Returns the formal execution receipt with bounded stdout/stderr.',
    parameters: {
      argv: { type: 'array', items: { type: 'string' }, required: true, description: 'complete argv in the sandbox' },
      inputs: {
        type: 'array',
        items: { type: 'object', additionalProperties: true },
        required: true,
        description: '[{path, sha256}] from project_list',
      },
      timeoutMillis: { type: 'number', required: true, description: 'execution timeout in ms (max 300000)' },
    },
    output: { schema: OBJECT_OUTPUT, render: renderJson },
    async execute(args: { argv: unknown[]; inputs: { path: unknown; sha256: unknown }[]; timeoutMillis: unknown }) {
      const inputs = args.inputs.map((i) => ({ path: String(i.path), sha256: String(i.sha256) }));
      const argv = args.argv.map(String);
      const timeoutMillis = Math.min(Math.max(Number(args.timeoutMillis) || 120000, 1000), 300000);
      const digest = sandboxDigest(argv, inputs, timeoutMillis);
      const ledger = task.readToolLedger();

      // findLast without ES2023 dependency: latest ledger entry for this digest.
      let prior: Record<string, unknown> | null = null;
      for (let i = ledger.length - 1; i >= 0; i--) {
        if (ledger[i].kind === 'sandbox' && ledger[i].argvDigest === digest) {
          prior = ledger[i];
          break;
        }
      }

      // Recovery: the exact same digest never mints a new call or re-dispatches.
      if (prior !== null) {
        const clientRequestId = String(prior.clientRequestId);
        const executionRef = typeof prior.executionRef === 'string' ? prior.executionRef : null;
        const deadlineAt = Number(prior.deadlineAt);
        if (typeof prior.receiptRef === 'string') {
          // Already completed: reuse the formal Receipt without any new effect.
          const receiptRef = String(prior.receiptRef);
          const receipt = await gateway.getSandboxReceipt(receiptRef, {
            executionRef,
            viewState: typeof prior.status === 'string' ? (prior.status as SandboxView['state']) : null,
            inputs,
          });
          receiptRefs.push(receiptRef);
          task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: receipt.status === 'SUCCEEDED' ? 'succeeded' : 'failed', inputSummary: argv.join(' '), outputSummary: `reused receipt exit code ${receipt.exitCode ?? 'n/a'}`, receiptRef });
          return { status: receipt.status, exitCode: receipt.exitCode, stdout: receipt.stdout.text, stderr: receipt.stderr.text, truncated: receipt.stdout.truncated || receipt.stderr.truncated };
        }
        // Crash window between submit and receipt: resume polling the ORIGINAL
        // execution with the persisted fixed deadline (never a fresh one).
        // After restart there is no live acceptance time, so the persisted wall
        // deadline is the fail-closed upper bound.
        const wallDeadline = {
          exceeded: (): boolean => Date.now() >= deadlineAt,
          remainingMs: (): number => Math.max(0, deadlineAt - Date.now()),
        };
        const view = await pollToTerminal(gateway, task, clientRequestId, executionRef, digest, wallDeadline, argv);
        if (view.receiptRef) {
          const receipt = await gateway.getSandboxReceipt(view.receiptRef, { executionRef: view.executionRef, viewState: view.state, inputs });
          receiptRefs.push(receipt.receiptRef);
          task.appendToolLedger({ kind: 'sandbox', clientRequestId, requestDigest: digest, argvDigest: digest, executionRef, deadlineAt, receiptRef: receipt.receiptRef, status: receipt.status });
          task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: receipt.status === 'SUCCEEDED' ? 'succeeded' : 'failed', inputSummary: argv.join(' '), outputSummary: `exit code ${receipt.exitCode ?? 'n/a'}`, receiptRef: receipt.receiptRef });
          return { status: receipt.status, exitCode: receipt.exitCode, stdout: receipt.stdout.text, stderr: receipt.stderr.text, truncated: receipt.stdout.truncated || receipt.stderr.truncated };
        }
        task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: 'failed', inputSummary: argv.join(' '), outputSummary: `state ${view.state}`, receiptRef: view.receiptRef });
        return { status: view.state, exitCode: null, stdout: '', stderr: '' };
      }

      const callSeq = new Set(ledger.filter((entry) => entry.kind === 'sandbox').map((entry) => String(entry.clientRequestId))).size + 1;
      const clientRequestId = stableCallId(task, callSeq);
      const submit = { clientRequestId, requestDigest: digest, argv, inputs, timeoutMillis };
      task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: 'requested', inputSummary: argv.join(' '), outputSummary: null, receiptRef: null });
      const view = await gateway.submitSandbox(submit);
      // Fixed wall-clock deadline from local acceptance of the 202 (contract
      // §6); persisted BEFORE any receipt so a crash here can never re-dispatch
      // or extend it. In the active process the monotonic clock drives the
      // polling deadline; the wall clock is only the fail-closed backstop.
      const acceptanceMonotonic = performance.now();
      const deadlineAt = Date.now() + timeoutMillis + 30000;
      const executionRef = view.executionRef;
      task.appendToolLedger({ kind: 'sandbox', callSeq, clientRequestId, requestDigest: digest, argvDigest: digest, executionRef, deadlineAt, receiptRef: null, status: null });
      task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: 'running', inputSummary: argv.join(' '), outputSummary: null, receiptRef: null });
      const liveDeadline = {
        exceeded: (): boolean => performance.now() >= acceptanceMonotonic + timeoutMillis + 30000 || Date.now() >= deadlineAt,
        remainingMs: (): number => Math.max(0, acceptanceMonotonic + timeoutMillis + 30000 - performance.now()),
      };
      const terminalView = await pollToTerminal(gateway, task, clientRequestId, executionRef, digest, liveDeadline, argv, view);
      if (terminalView.receiptRef) {
        const receipt = await gateway.getSandboxReceipt(terminalView.receiptRef, { executionRef: terminalView.executionRef, viewState: terminalView.state, inputs });
        receiptRefs.push(receipt.receiptRef);
        task.appendToolLedger({ kind: 'sandbox', callSeq, clientRequestId, requestDigest: digest, argvDigest: digest, executionRef, deadlineAt, receiptRef: receipt.receiptRef, status: receipt.status });
        task.emit('tool', {
          callId: clientRequestId,
          name: 'sandbox.execute',
          state: receipt.status === 'SUCCEEDED' ? 'succeeded' : 'failed',
          inputSummary: argv.join(' '),
          outputSummary: `exit code ${receipt.exitCode ?? 'n/a'}`,
          receiptRef: receipt.receiptRef,
        });
        return {
          status: receipt.status,
          exitCode: receipt.exitCode,
          stdout: receipt.stdout.text,
          stderr: receipt.stderr.text,
          truncated: receipt.stdout.truncated || receipt.stderr.truncated,
        };
      }
      task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: 'failed', inputSummary: argv.join(' '), outputSummary: `state ${terminalView.state}`, receiptRef: terminalView.receiptRef });
      return { status: terminalView.state, exitCode: null, stdout: '', stderr: '' };
    },
  } as never) as ToolDefinition;

  // The delivery schema requires uniqueItems: dedupe at collection time so the
  // ledger-re-adopted receipt and a reused tool call can never double-list a ref.
  return { listTool, readTool, sandboxTool, askUserTool, collectReceiptRefs: () => [...new Set(receiptRefs)] };
}
