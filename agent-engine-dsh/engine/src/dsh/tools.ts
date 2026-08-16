import { createHash } from 'node:crypto';
import type { GatewayClient } from '../gateway.ts';
import type { TaskRuntime } from '../task.ts';
import { defineTool } from '@deepseek-ai/dsh-tools';
import type { ToolDefinition } from '@deepseek-ai/dsh-tools';

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

function argvDigest(argv: string[], inputs: { path: string; sha256: string }[]): string {
  return createHash('sha256').update(JSON.stringify([argv, inputs])).digest('hex');
}

/** Tool bodies emit TaskEvents directly and return bounded canonical values.
 * Typed loosely at the dsh seam: the runtime validates canonical output. */
export function buildProductTools(task: TaskRuntime, gateway: GatewayClient): ProductTools {
  const receiptRefs: string[] = [];

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
      const digest = argvDigest(argv, inputs);
      const ledger = task.readToolLedger();

      // Recovery reuses the exact prior call: the same argv digest must never
      // mint a new clientRequestId or dispatch a second effect.
      const prior = ledger.find((entry) => entry.argvDigest === digest && typeof entry.receiptRef === 'string');
      if (prior) {
        const clientRequestId = String(prior.clientRequestId);
        const receiptRef = String(prior.receiptRef);
        const receipt = await gateway.getSandboxReceipt(receiptRef);
        receiptRefs.push(receiptRef);
        task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: 'succeeded', inputSummary: argv.join(' '), outputSummary: `reused receipt exit code ${receipt.exitCode ?? 'n/a'}`, receiptRef });
        return { status: receipt.status, exitCode: receipt.exitCode, stdout: receipt.stdout.text, stderr: receipt.stderr.text, truncated: receipt.stdout.truncated || receipt.stderr.truncated };
      }

      const callSeq = ledger.filter((entry) => entry.kind === 'sandbox').length + 1;
      const clientRequestId = stableCallId(task, callSeq);
      const submit = { clientRequestId, requestDigest: digest, argv, inputs, timeoutMillis };
      task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: 'requested', inputSummary: argv.join(' '), outputSummary: null, receiptRef: null });
      const startedAt = performanceNowMs();
      let view = await gateway.submitSandbox(submit);
      task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: 'running', inputSummary: argv.join(' '), outputSummary: null, receiptRef: null });
      const deadline = startedAt + timeoutMillis + 30000;
      let poll = 0;
      while (view.state === 'QUEUED' || view.state === 'RUNNING') {
        if (performanceNowMs() >= deadline) {
          task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: 'failed', inputSummary: argv.join(' '), outputSummary: 'sandbox status deadline exceeded', receiptRef: null });
          return { status: 'SYSTEM_ERROR', code: 'SANDBOX_STATUS_DEADLINE_EXCEEDED', exitCode: null, stdout: '', stderr: '' };
        }
        await new Promise((r) => setTimeout(r, pollDelayMs(poll)));
        poll++;
        view = await gateway.getSandboxExecution(clientRequestId);
      }
      if (view.receiptRef) {
        const receipt = await gateway.getSandboxReceipt(view.receiptRef);
        receiptRefs.push(receipt.receiptRef);
        task.appendToolLedger({ kind: 'sandbox', callSeq, clientRequestId, requestDigest: digest, argvDigest: digest, receiptRef: receipt.receiptRef, status: receipt.status });
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
      task.emit('tool', { callId: clientRequestId, name: 'sandbox.execute', state: 'failed', inputSummary: argv.join(' '), outputSummary: `state ${view.state}`, receiptRef: view.receiptRef });
      return { status: view.state, exitCode: null, stdout: '', stderr: '' };
    },
  } as never) as ToolDefinition;

  return { listTool, readTool, sandboxTool, askUserTool, collectReceiptRefs: () => [...receiptRefs] };
}

function performanceNowMs(): number {
  return Math.floor(performance.now());
}
