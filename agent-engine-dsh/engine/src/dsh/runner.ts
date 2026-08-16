import type { DshRuntime } from './runtime.ts';
import { buildProductTools } from './tools.ts';
import { createUserMessage } from '@deepseek-ai/dsh-llm';
import type { GatewayClient } from '../gateway.ts';
import type { Runner } from '../task.ts';
import type { TaskRuntime } from '../task.ts';

const MAX_MODEL_CALLS_PER_TASK = 20;

function grantExpired(task: TaskRuntime): boolean {
  return Date.parse(task.grant.expiresAt) < Date.now();
}

/** DSH ReactLoopAgent-backed runner: one agent per task, product tools over
 * the gateway, hard model-call budget, durable recovery via transcript replay
 * and gateway idempotency. */
export class DshRunner implements Runner {
  private readonly dsh: DshRuntime;
  private readonly gatewayFactory: (task: TaskRuntime) => GatewayClient;
  private readonly systemPromptText: string;
  private readonly providerRouteOverride: string | null;

  constructor(
    dsh: DshRuntime,
    gatewayFactory: (task: TaskRuntime) => GatewayClient,
    systemPromptText: string,
    providerRouteOverride: string | null = null,
  ) {
    this.dsh = dsh;
    this.gatewayFactory = gatewayFactory;
    this.systemPromptText = systemPromptText;
    this.providerRouteOverride = providerRouteOverride;
  }

  async run(task: TaskRuntime, isCancelled: () => boolean): Promise<void> {
    if (task.isTerminal()) return;
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

    const gateway = this.gatewayFactory(task);
    const taskId = task.meta.taskId;
    const tools = buildProductTools(task, gateway);
    const provider = task.authority.model && typeof (task.authority.model as { provider?: string }).provider === 'string'
      ? (task.authority.model as { provider: string }).provider
      : 'deepseek';
    const model = (task.authority.model as { model: string }).model;
    const providerRoute = this.providerRouteOverride ?? (provider === 'deepseek' ? 'deepseek-official' : provider);

    let latestAssistantText = '';
    let modelCalls = task.meta.modelCallsUsed ?? 0;
    let budgetExhausted = false;
    let agentId: string | null = null;

    const offSession = this.dsh.onSessionEvent((sessionId, event) => {
      if (sessionId !== taskId) return;
      if ((event as { type: string }).type === 'assistant/message') {
        const data = (event as { data: { message: { content: { type: string; text?: string }[] } } }).data;
        const text = (data?.message?.content ?? []).filter((b) => b.type === 'text').map((b) => b.text ?? '').join('');
        if (text.trim().length > 0) {
          latestAssistantText = text;
          task.emit('message', { content: text.slice(0, 16000) });
        }
      }
    });

    const offRequest = this.dsh.onAgentRequest((sessionId, turn, step, next) => {
      if (sessionId !== taskId) return next();
      // Hard budget: the 21st request is rejected BEFORE dispatch — next() is
      // never called, so no provider stream starts.
      if (modelCalls + 1 > MAX_MODEL_CALLS_PER_TASK) {
        budgetExhausted = true;
        throw new Error('MODEL_BUDGET_EXCEEDED');
      }
      modelCalls++;
      task.meta.modelCallsUsed = modelCalls;
      task.touch();
      return next();
    });

    const cancelWatcher = setInterval(() => {
      if (isCancelled() && agentId) {
        try {
          const agent = this.dsh.agents.get(agentId as never);
          if (agent) agent.cancel({ kind: 'user' });
        } catch {
          /* agent may be gone */
        }
      }
    }, 250);

    try {
      const handle = await this.dsh.createAgent({
        sessionId: taskId as never,
        agentOptions: { provider: providerRoute, model, maxTokens: 4096 },
        setup: (agentCtx) => {
          agentCtx.tools.register(tools.listTool as never);
          agentCtx.tools.register(tools.readTool as never);
          agentCtx.tools.register(tools.sandboxTool as never);
          agentCtx.tools.register(tools.askUserTool as never);
          const agentSystemPrompt = agentCtx.systemPrompt;
          if (agentSystemPrompt && typeof agentSystemPrompt.section === 'function') {
            agentSystemPrompt.section({ name: 'paperagent-product', order: 0, text: this.systemPromptText } as never);
          }
        },
      });
      const agent = handle.agent;
      agentId = String(agent.id);
      // Resume detection must read the PERSISTED phase BEFORE the first write:
      // capturing it after setRunnerPhase('started') would always look resumed.
      const resumed = task.meta.runnerPhase !== 'init';
      const questionedResume = task.meta.runnerPhase === 'questioned' && task.meta.pendingQuestionId !== null;
      if (!resumed) {
        task.setRunnerPhase('started');
      }

      const userText = (text: string) => createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } } as never) as never;
      let resolveRunning: () => void = () => {};
      const sawRunning = new Promise<void>((resolve) => {
        resolveRunning = resolve;
      });
      const offStatus = this.dsh.onAgentStatus((sid, status) => {
        if (sid === taskId && status === 'running') resolveRunning();
      });
      if (questionedResume) {
        // Restart with a pending question: the in-process ask_user tool gate is
        // gone. Consume the persisted answer body if one was already accepted;
        // otherwise re-arm the gate and wait for the control plane to deliver it.
        const questionId = task.meta.pendingQuestionId;
        if (questionId === null) {
          // questionedResume requires a pending id; a state mismatch is a
          // program-enforced internal failure, never a silent continue.
          task.emit('status', {
            state: 'failed',
            error: {
              contractVersion: '1.0',
              code: 'ENGINE_QUESTION_STATE_MISMATCH',
              category: 'internal',
              message: 'questioned runner phase without a pending question',
              retryable: false,
              sourceRef: null,
            },
          });
          return;
        }
        let answerBody = task.answerBodyFor(questionId);
        if (answerBody === null) {
          const gate = task.awaitAnswer(questionId);
          answerBody = task.answerBodyFor(questionId);
          if (answerBody === null) {
            let stopCancelWait: () => void = () => {};
            const cancelWait = new Promise<null>((resolve) => {
              const timer = setInterval(() => {
                if (isCancelled()) {
                  clearInterval(timer);
                  resolve(null);
                }
              }, 200);
              stopCancelWait = () => clearInterval(timer);
            });
            answerBody = await Promise.race([gate.answerPromise, cancelWait]);
            stopCancelWait();
            if (answerBody === null) {
              task.cancelFinalize();
              return;
            }
          }
        }
        const questionText = task.meta.pendingQuestionText ?? '';
        agent.inject(
          userText(
            `The user answered the pending question${questionText ? ` ("${questionText.slice(0, 500)}")` : ''}:\n${answerBody}\nContinue the task with this answer.`,
          ),
        );
        agent.followup(userText('Continue to completion.'));
      } else if (resumed) {
        const summary = `Continuation after engine restart. Prior assistant output:\n${latestAssistantText || '(none)'}\nContinue the task to completion; tool calls are idempotent.`;
        agent.inject(userText(summary));
        agent.followup(userText('Continue to completion.'));
      } else {
        const instruction = (task.authority.instruction as string) ?? '';
        agent.followup(userText(instruction));
      }
      if (agent.status === 'running') resolveRunning();
      // Wait until the driver actually claimed the turn, then until quiescence.
      await Promise.race([sawRunning, new Promise((r) => setTimeout(r, 10000))]);
      offStatus();
      await agent.whenIdle();

      if (task.isTerminal()) return;
      if (isCancelled()) {
        task.cancelFinalize();
        return;
      }
      if (budgetExhausted) {
        task.emit('status', {
          state: 'failed',
          error: {
            contractVersion: '1.0',
            code: 'MODEL_BUDGET_EXCEEDED',
            category: 'model',
            message: `task exceeded the frozen ${MAX_MODEL_CALLS_PER_TASK} model-call budget`,
            retryable: false,
            sourceRef: null,
          },
        });
        return;
      }
      const receiptRefs = tools.collectReceiptRefs();
      // P1 completion gate: acceptance tasks must carry at least one formal
      // Receipt; a success without one is a program-enforced failure.
      if (receiptRefs.length === 0) {
        task.emit('status', {
          state: 'failed',
          error: {
            contractVersion: '1.0',
            code: 'RECEIPT_REQUIRED_NOT_SATISFIED',
            category: 'tool',
            message: 'success delivery requires at least one formal Receipt',
            retryable: false,
            sourceRef: null,
          },
        });
        return;
      }
      task.emit('delivery', {
        conclusion: (latestAssistantText || 'task finished without a final model message').slice(0, 16000),
        receiptRefs,
      });
      task.setRunnerPhase('delivered');
      task.emit('status', { state: 'succeeded', error: null });
    } catch (e) {
      // Uniform sanitization: raw error text, paths, and configuration never
      // reach the caller; the code classifies the failure only.
      const raw = e instanceof Error ? e.message : String(e);
      const code = /^[A-Z][A-Z0-9_]{2,95}$/.test(raw) ? raw : 'MODEL_LOOP_FAILED';
      const category = code.startsWith('TASK_GRANT')
        ? 'authorization'
        : code.startsWith('SANDBOX_STATUS_')
          ? 'sandbox_system'
          : code.startsWith('SANDBOX_') || code.startsWith('FILE_') || code.startsWith('RECEIPT_') || code.startsWith('ENGINE_') || code.startsWith('GATEWAY_')
            ? 'internal'
            : 'model';
      task.emit('status', {
        state: 'failed',
        error: {
          contractVersion: '1.0',
          code,
          category,
          message: 'model loop failed to complete the task',
          retryable: false,
          sourceRef: null,
        },
      });
    } finally {
      clearInterval(cancelWatcher);
      offSession();
      offRequest();
      if (agentId) {
        try {
          const agent = this.dsh.agents.get(agentId as never);
          if (agent) agent.cancel({ kind: 'disposed' });
        } catch {
          /* already gone */
        }
      }
    }
  }
}
