import type { AcceptedAnswer, ChatMessage, ModelProvider, PendingCall, PersistedTask, Problem, Receipt, TaskEvent, TaskSubmission, TaskView, ToolName } from "./types.js";
import type { GatewayClient, SandboxRequest } from "./gateway.js";
import { ContractValidator } from "./validation.js";
import { TaskStore } from "./store.js";
import { bounded, digestObject, EngineProblem, problem, sha256, terminal } from "./util.js";

const MAX_MODEL_CALLS = 20;
const MAX_OUTPUT_TOKENS = 4096 as const;
const TERMINAL_SANDBOX = new Set(["SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "SYSTEM_ERROR"]);

const MODEL_TOOLS = [
  functionTool("list_project_files", "List the frozen ProjectVersion workspace manifest.", { type: "object", additionalProperties: false, properties: {} }),
  functionTool("read_project_file", "Read one complete workspace file using its manifest hash.", { type: "object", additionalProperties: false, required: ["path", "expectedSha256"], properties: { path: { type: "string" }, expectedSha256: { type: "string" } } }),
  functionTool("execute_in_sandbox", "Compile or run exact workspace inputs. Allowed argv forms only: `yanban-runner java <source> [--dependency=group:artifact:version ...]`, `yanban-runner python <source> [--dependency=package==version ...]`, `javac <one or more .java files>`, or `mvn -o test`. Do not probe with `java -version`, `javac -version`, shell commands, or package-manager commands. When source imports a third-party library, use yanban-runner and declare every dependency on the first execution.", { type: "object", additionalProperties: false, required: ["argv", "inputs", "timeoutMillis"], properties: { argv: { type: "array", items: { type: "string" }, minItems: 2 }, inputs: { type: "array", items: { type: "object", required: ["path", "sha256"], properties: { path: { type: "string" }, sha256: { type: "string" } } }, minItems: 1 }, timeoutMillis: { type: "integer", minimum: 1000, maximum: 300000 } } }),
  functionTool("ask_user", "Pause and ask one necessary, concrete question.", { type: "object", additionalProperties: false, required: ["question"], properties: { question: { type: "string", minLength: 1, maxLength: 4000 } } })
];

type Subscriber = (event: TaskEvent) => void;
type Sleeper = (milliseconds: number, signal: AbortSignal) => Promise<void>;
type EventBody = TaskEvent extends infer Event ? Event extends TaskEvent ? Omit<Event, "contractVersion" | "taskId" | "sequence" | "occurredAt"> : never : never;

export interface EngineOptions {
  store: TaskStore;
  provider: ModelProvider;
  gateway: GatewayClient;
  validator: ContractValidator;
  sleep?: Sleeper;
  monotonicNow?: () => number;
}

export class AgentEngine {
  private readonly tasks = new Map<string, PersistedTask>();
  private readonly grants = new Map<string, { value: string; expiresAt: string }>();
  private readonly active = new Map<string, Promise<void>>();
  private readonly submissionLocks = new Map<string, Promise<void>>();
  private readonly answerLocks = new Map<string, Promise<void>>();
  private readonly cancellations = new Map<string, Promise<void>>();
  private readonly aborters = new Map<string, AbortController>();
  private readonly subscribers = new Map<string, Set<Subscriber>>();
  private readonly sleep: Sleeper;
  private readonly monotonicNow: () => number;

  constructor(private readonly options: EngineOptions) {
    this.sleep = options.sleep ?? abortableSleep;
    this.monotonicNow = options.monotonicNow ?? (() => performance.now());
  }

  async initialize(): Promise<void> {
    for (const task of await this.options.store.loadAll()) this.tasks.set(task.view.taskId, task);
  }

  async submit(submission: TaskSubmission): Promise<{ contractVersion: "1.0"; replayed: boolean; task: TaskView }> {
    this.options.validator.validate("task-submission", submission);
    const previous = this.submissionLocks.get(submission.taskId) ?? Promise.resolve();
    let release!: () => void;
    const current = new Promise<void>((resolveLock) => { release = resolveLock; });
    const barrier = previous.then(() => current);
    this.submissionLocks.set(submission.taskId, barrier);
    await previous;
    try { return await this.acceptSubmission(submission); }
    finally { release(); if (this.submissionLocks.get(submission.taskId) === barrier) this.submissionLocks.delete(submission.taskId); }
  }

  private async acceptSubmission(submission: TaskSubmission): Promise<{ contractVersion: "1.0"; replayed: boolean; task: TaskView }> {
    if (digestObject(submission.authority) !== submission.requestDigest) throw new EngineProblem(400, problem("REQUEST_DIGEST_INVALID", "request", "requestDigest does not match canonical authority"));
    if (Date.parse(submission.gateway.expiresAt) <= Date.now()) throw new EngineProblem(401, problem("TASK_GRANT_EXPIRED", "authorization", "Task grant is already expired"));
    const existing = this.tasks.get(submission.taskId);
    if (existing) {
      if (existing.view.requestDigest !== submission.requestDigest) throw new EngineProblem(409, problem("TASK_DIGEST_CONFLICT", "request", "taskId already belongs to another request digest"));
      this.grants.set(submission.taskId, { value: submission.gateway.taskGrant, expiresAt: submission.gateway.expiresAt });
      if (!terminal(existing.view.state) && (existing.view.state !== "waiting_user" || existing.view.pendingQuestionId === null)) this.schedule(existing);
      return { contractVersion: "1.0", replayed: true, task: structuredClone(existing.view) };
    }
    const now = new Date().toISOString();
    const task: PersistedTask = {
      authority: structuredClone(submission.authority),
      view: { contractVersion: "1.0", taskId: submission.taskId, requestDigest: submission.requestDigest, state: "queued", lastSequence: 0, pendingQuestionId: null, deliverySequence: null, terminalSequence: null, error: null, createdAt: now, updatedAt: now },
      messages: initialMessages(submission), modelCalls: 0, metrics: { startedAt: now, promptTokens: 0, completionTokens: 0 }, receiptRefs: [], pendingCalls: [], nextPendingCall: 0, acceptedAnswers: []
    };
    await this.options.store.create(task);
    this.tasks.set(submission.taskId, task);
    this.grants.set(submission.taskId, { value: submission.gateway.taskGrant, expiresAt: submission.gateway.expiresAt });
    await this.status(task, "queued", null);
    this.schedule(task);
    return { contractVersion: "1.0", replayed: false, task: structuredClone(task.view) };
  }

  get(taskId: string): TaskView {
    return structuredClone(this.requireTask(taskId).view);
  }

  async events(taskId: string, after = 0): Promise<TaskEvent[]> {
    this.requireTask(taskId);
    return (await this.options.store.events(taskId)).filter((event) => event.sequence > after);
  }

  subscribe(taskId: string, subscriber: Subscriber): () => void {
    this.requireTask(taskId);
    const set = this.subscribers.get(taskId) ?? new Set<Subscriber>();
    set.add(subscriber); this.subscribers.set(taskId, set);
    return () => { set.delete(subscriber); if (set.size === 0) this.subscribers.delete(taskId); };
  }

  async cancel(taskId: string): Promise<TaskView> {
    const task = this.requireTask(taskId);
    if (terminal(task.view.state)) return structuredClone(task.view);
    let cancellation = this.cancellations.get(taskId);
    if (!cancellation) {
      cancellation = (async () => {
        this.aborters.get(taskId)?.abort();
        const active = this.active.get(taskId);
        if (active) await active;
        if (!terminal(task.view.state)) await this.status(task, "cancelled", null);
      })().finally(() => this.cancellations.delete(taskId));
      this.cancellations.set(taskId, cancellation);
    }
    await cancellation;
    return structuredClone(task.view);
  }

  async answer(taskId: string, value: { contractVersion: "1.0"; clientRequestId: string; questionId: string; answer: string; answerDigest: string }): Promise<TaskView> {
    this.options.validator.validate("task-answer", value);
    if (sha256(value.answer) !== value.answerDigest) throw new EngineProblem(400, problem("ANSWER_DIGEST_INVALID", "request", "answerDigest does not match the exact answer bytes"));
    const previous = this.answerLocks.get(taskId) ?? Promise.resolve();
    let release!: () => void;
    const current = new Promise<void>((resolveLock) => { release = resolveLock; });
    const barrier = previous.then(() => current);
    this.answerLocks.set(taskId, barrier);
    await previous;
    try { return await this.acceptAnswer(taskId, value); }
    finally { release(); if (this.answerLocks.get(taskId) === barrier) this.answerLocks.delete(taskId); }
  }

  private async acceptAnswer(taskId: string, value: { contractVersion: "1.0"; clientRequestId: string; questionId: string; answer: string; answerDigest: string }): Promise<TaskView> {
    const task = this.requireTask(taskId);
    const requestReplay = task.acceptedAnswers.find((answer) => answer.clientRequestId === value.clientRequestId);
    if (requestReplay && (requestReplay.questionId !== value.questionId || requestReplay.answerDigest !== value.answerDigest)) throw new EngineProblem(409, problem("ANSWER_REQUEST_CONFLICT", "request", "clientRequestId was already used for another answer"));
    const questionReplay = task.acceptedAnswers.find((answer) => answer.questionId === value.questionId);
    if (questionReplay) {
      if (questionReplay.answerDigest !== value.answerDigest) throw new EngineProblem(409, problem("QUESTION_ANSWER_CONFLICT", "request", "The question already has a different accepted answer"));
      return structuredClone(task.view);
    }
    if (task.view.state !== "waiting_user" || task.view.pendingQuestionId !== value.questionId) throw new EngineProblem(409, problem("QUESTION_NOT_PENDING", "request", "The question is not currently pending"));
    const pending = task.pendingCalls[task.nextPendingCall];
    if (!pending || pending.name !== "ask_user") throw new EngineProblem(500, problem("QUESTION_STATE_INVALID", "internal", "Pending question state could not be resumed"));
    const accepted: AcceptedAnswer & { answer: string } = { clientRequestId: value.clientRequestId, questionId: value.questionId, answerDigest: value.answerDigest, answer: value.answer };
    await this.options.store.appendAnswer(taskId, accepted);
    task.acceptedAnswers.push(accepted);
    task.messages.push({ role: "tool", toolCallId: pending.id, content: JSON.stringify({ answer: value.answer }) });
    task.nextPendingCall += 1;
    task.view.pendingQuestionId = null;
    await this.status(task, "running", null);
    this.schedule(task);
    return structuredClone(task.view);
  }

  private schedule(task: PersistedTask): void {
    if (!this.grants.has(task.view.taskId) || this.active.has(task.view.taskId) || terminal(task.view.state) || (task.view.state === "waiting_user" && task.view.pendingQuestionId !== null)) return;
    const controller = new AbortController();
    this.aborters.set(task.view.taskId, controller);
    const promise = this.run(task, controller.signal).finally(() => {
      this.active.delete(task.view.taskId); this.aborters.delete(task.view.taskId);
      if (task.view.state === "running") this.schedule(task);
    });
    this.active.set(task.view.taskId, promise);
  }

  private async run(task: PersistedTask, signal: AbortSignal): Promise<void> {
    try {
      if (task.view.deliverySequence !== null && task.view.deliverySequence !== undefined) {
        await this.status(task, "succeeded", null);
        return;
      }
      if (task.view.state !== "running") await this.status(task, "running", null);
      while (!terminal(task.view.state) && task.view.state !== "waiting_user") {
        this.checkCancelled(signal);
        while (task.nextPendingCall < task.pendingCalls.length) {
          const paused = await this.executePending(task, task.pendingCalls[task.nextPendingCall]!, signal);
          if (paused) return;
          task.nextPendingCall += 1;
          await this.options.store.save(task);
        }
        task.pendingCalls = []; task.nextPendingCall = 0;
        if (task.modelCalls >= MAX_MODEL_CALLS) throw new EngineProblem(422, problem("MODEL_CALL_BUDGET_EXHAUSTED", "model", "Task reached the 20-call model budget"));
        task.modelCalls += 1;
        await this.options.store.save(task);
        const response = await this.options.provider.complete({ provider: task.authority.model.provider, model: task.authority.model.model, messages: structuredClone(task.messages), tools: MODEL_TOOLS, maxOutputTokens: MAX_OUTPUT_TOKENS, signal });
        task.metrics.promptTokens += response.usage?.promptTokens ?? 0;
        task.metrics.completionTokens += response.usage?.completionTokens ?? 0;
        await this.options.store.save(task);
        this.checkCancelled(signal);
        const calls = response.toolCalls.map((call, ordinal) => ({ ...call, id: deterministicCallId(task.view.taskId, task.modelCalls, ordinal), ordinal }));
        validateCallSet(calls);
        task.messages.push({
          role: "assistant",
          content: response.content,
          ...(response.reasoningContent !== undefined ? { reasoningContent: response.reasoningContent } : {}),
          ...(calls.length ? { toolCalls: calls.map(({ id, name, arguments: args }) => ({ id, name, arguments: args })) } : {})
        });
        if (calls.length === 0) {
          const conclusion = bounded(response.content?.trim() ?? "", 16000);
          if (!conclusion) throw new EngineProblem(502, problem("MODEL_RESPONSE_EMPTY", "model", "Model returned neither content nor tool calls"));
          if (task.receiptRefs.length === 0) throw new EngineProblem(422, problem("SANDBOX_RECEIPT_REQUIRED", "code_validation", "P1 delivery requires at least one formal sandbox receipt"));
          const delivery = await this.emit(task, { type: "delivery", conclusion, receiptRefs: [...task.receiptRefs] });
          task.view.deliverySequence = delivery.sequence;
          await this.options.store.save(task);
          await this.status(task, "succeeded", null);
          return;
        }
        task.pendingCalls = calls; task.nextPendingCall = 0;
        await this.options.store.save(task);
      }
    } catch (error) {
      if (terminal(task.view.state)) return;
      if (signal.aborted) { await this.status(task, "cancelled", null); return; }
      if (isGrantRefresh(error)) {
        return;
      }
      const failure = error instanceof EngineProblem ? error.problem : problem("ENGINE_INTERNAL_FAILURE", "internal", "The agent engine encountered an internal failure", true);
      await this.status(task, "failed", failure);
    }
  }

  private async executePending(task: PersistedTask, call: PendingCall, signal: AbortSignal): Promise<boolean> {
    let args: Record<string, unknown>;
    try { args = JSON.parse(call.arguments) as Record<string, unknown>; }
    catch { throw new EngineProblem(502, problem("MODEL_TOOL_ARGUMENTS_INVALID", "model", "Model emitted invalid tool arguments")); }
    if (call.name === "ask_user") {
      if (task.pendingCalls.length !== 1 || typeof args.question !== "string" || !args.question.trim()) throw new EngineProblem(502, problem("MODEL_QUESTION_INVALID", "model", "ask_user must be the only call and contain one question"));
      const questionId = `question.${sha256(`${task.view.taskId}:${call.id}`).slice(0, 48)}`;
      await this.emit(task, { type: "question", questionId, text: bounded(args.question, 4000) });
      task.view.pendingQuestionId = questionId;
      await this.status(task, "waiting_user", null);
      return true;
    }
    if (call.name === "list_project_files") {
      await this.tool(task, call.id, "project.list", "frozen workspace manifest", "requested", null, null);
      const result = await this.withGrant(task.view.taskId, (grant) => this.options.gateway.list(task.view.taskId, grant, signal));
      this.options.validator.validate("gateway-fileList", result);
      if (result.taskId !== task.view.taskId || result.projectVersion !== task.authority.project.projectVersion) throw gatewayBinding("Workspace manifest does not match the frozen task authority");
      await this.tool(task, call.id, "project.list", "frozen workspace manifest", "succeeded", `${result.files.length} files; projectVersion=${result.projectVersion}`, null);
      task.messages.push({ role: "tool", toolCallId: call.id, content: JSON.stringify(result) });
      return false;
    }
    if (call.name === "read_project_file") {
      const path = requireString(args.path, "path"); const expectedSha256 = requireString(args.expectedSha256, "expectedSha256");
      await this.tool(task, call.id, "project.read", `path=${bounded(path, 512)}; expectedSha256=${expectedSha256}`, "requested", null, null);
      const result = await this.withGrant(task.view.taskId, (grant) => this.options.gateway.read(task.view.taskId, grant, path, expectedSha256, signal));
      this.options.validator.validate("gateway-fileRead", result);
      if (result.path !== path || result.sha256 !== expectedSha256 || sha256(result.content) !== result.sha256 || Buffer.byteLength(result.content, "utf8") !== result.sizeBytes) throw gatewayBinding("Workspace read does not match the requested path, hash, and byte size");
      await this.tool(task, call.id, "project.read", `path=${bounded(path, 512)}; expectedSha256=${expectedSha256}`, "succeeded", `read ${result.sizeBytes} bytes; sha256=${result.sha256}`, null);
      task.messages.push({ role: "tool", toolCallId: call.id, content: JSON.stringify(result) });
      return false;
    }
    if (call.name === "execute_in_sandbox") {
      const request = sandboxRequest(call.id, args);
      const summary = `argv=${JSON.stringify(request.argv).slice(0, 700)}; inputs=${request.inputs.map((input) => `${input.path}@${input.sha256}`).join(",").slice(0, 250)}; timeoutMillis=${request.timeoutMillis}`;
      const recoveringSandboxRequest = call.sandbox !== undefined;
      if (!recoveringSandboxRequest) {
        await this.tool(task, call.id, "sandbox.execute", summary, "requested", null, null);
        // Persist the fail-closed recovery deadline before the external call.
        // This includes submission latency, but prevents a crash in the
        // submit-response/save window from resetting the total wait budget.
        call.sandbox = { deadlineAt: new Date(Date.now() + request.timeoutMillis + 30000).toISOString() };
        await this.options.store.save(task);
      }
      const sandboxState = call.sandbox;
      if (!sandboxState) throw new EngineProblem(500, problem("SANDBOX_RECOVERY_STATE_INVALID", "internal", "Sandbox recovery state is missing"));
      const durableDeadline = Date.parse(sandboxState.deadlineAt);
      if (!Number.isFinite(durableDeadline)) throw new EngineProblem(500, problem("SANDBOX_RECOVERY_STATE_INVALID", "internal", "Sandbox recovery deadline is invalid"));
      let view: import("./types.js").SandboxView;
      try {
        view = await this.withGrant(task.view.taskId, (grant) => this.options.gateway.submit(task.view.taskId, grant, request, signal));
      } catch (error) {
        if (!isRepairableSandboxPolicy(error)) throw error;
        const guidance = "Sandbox command rejected. Use exactly one documented argv form; for third-party Java imports use yanban-runner java <source> with --dependency=group:artifact:version.";
        await this.tool(task, call.id, "sandbox.execute", summary, "failed", `${error.problem.code}: ${guidance}`, null);
        task.messages.push({ role: "tool", toolCallId: call.id, content: JSON.stringify({ code: error.problem.code, message: guidance, retryable: true }) });
        return false;
      }
      const acceptedAt = this.monotonicNow();
      this.options.validator.validate("gateway-sandboxView", view);
      bindSandboxView(view, request, sandboxState.executionRef);
      if (sandboxState.executionRef === undefined) {
        sandboxState.executionRef = view.executionRef;
        await this.options.store.save(task);
      }
      if (!recoveringSandboxRequest) await this.tool(task, call.id, "sandbox.execute", summary, "running", `executionRef=${view.executionRef}; state=${view.state}`, view.receiptRef);
      const delays = [1000, 2000, 4000]; let poll = 0;
      while (!TERMINAL_SANDBOX.has(view.state)) {
        const remaining = Math.min(request.timeoutMillis + 30000 - (this.monotonicNow() - acceptedAt), durableDeadline - Date.now());
        if (remaining <= 0) throw sandboxDeadline(view.executionRef);
        await this.sleep(Math.min(delays[poll] ?? 5000, remaining), signal); poll += 1;
        if (this.monotonicNow() - acceptedAt >= request.timeoutMillis + 30000 || Date.now() >= durableDeadline) throw sandboxDeadline(view.executionRef);
        view = await this.withGrant(task.view.taskId, (grant) => this.options.gateway.execution(task.view.taskId, grant, request.clientRequestId, signal));
        this.options.validator.validate("gateway-sandboxView", view);
        bindSandboxView(view, request, sandboxState.executionRef);
      }
      if (!view.receiptRef) throw new EngineProblem(502, problem("SANDBOX_RECEIPT_MISSING", "sandbox_system", "Terminal sandbox execution has no receipt reference", true, view.executionRef));
      const receiptRef = view.receiptRef;
      const receipt = await this.withGrant(task.view.taskId, (grant) => this.options.gateway.receipt(task.view.taskId, grant, receiptRef, signal));
      this.options.validator.validate("receipt", receipt);
      bindReceipt(receipt, view, request);
      if (!task.receiptRefs.includes(receipt.receiptRef)) task.receiptRefs.push(receipt.receiptRef);
      task.lastSandboxStatus = receipt.status;
      const succeeded = receipt.status === "SUCCEEDED";
      await this.tool(task, call.id, "sandbox.execute", summary, succeeded ? "succeeded" : "failed", receiptSummary(receipt), receipt.receiptRef);
      task.messages.push({ role: "tool", toolCallId: call.id, content: JSON.stringify(receipt) });
      if (receipt.status === "SYSTEM_ERROR" || receipt.status === "TIMED_OUT") throw new EngineProblem(502, problem(receipt.status === "SYSTEM_ERROR" ? "SANDBOX_SYSTEM_ERROR" : "SANDBOX_TIMED_OUT", "sandbox_system", `Sandbox ended with ${receipt.status}`, true, receipt.receiptRef));
      if (receipt.status === "CANCELLED") throw new EngineProblem(409, problem("SANDBOX_CANCELLED", "cancelled", "Sandbox execution was cancelled", false, receipt.receiptRef));
      return false;
    }
    throw new EngineProblem(502, problem("MODEL_TOOL_UNKNOWN", "model", "Model requested an unsupported tool"));
  }

  private async withGrant<T>(taskId: string, operation: (grant: string) => Promise<T>): Promise<T> {
    const grant = this.requireGrant(taskId);
    try { return await operation(grant.value); }
    catch (error) {
      if (isGrantRefresh(error) && this.grants.get(taskId) === grant) this.grants.delete(taskId);
      throw error;
    }
  }

  private requireGrant(taskId: string): { value: string; expiresAt: string } {
    const grant = this.grants.get(taskId);
    if (!grant || Date.parse(grant.expiresAt) <= Date.now()) {
      this.grants.delete(taskId);
      throw new EngineProblem(401, problem("TASK_GRANT_REFRESH_REQUIRED", "authorization", "A fresh task grant is required to continue", true));
    }
    return grant;
  }

  private requireTask(taskId: string): PersistedTask {
    const task = this.tasks.get(taskId);
    if (!task) throw new EngineProblem(404, problem("TASK_NOT_FOUND", "request", "Task was not found"));
    return task;
  }

  private async status(task: PersistedTask, state: TaskView["state"], error: Problem | null): Promise<void> {
    if (terminal(task.view.state) && state !== task.view.state) return;
    const event = await this.emit(task, { type: "status", state, error });
    task.view.state = state; task.view.error = error;
    if (terminal(state)) { task.view.terminalSequence = event.sequence; task.metrics.finishedAt = event.occurredAt; }
    await this.options.store.save(task);
  }

  private tool(task: PersistedTask, callId: string, name: ToolName, inputSummary: string, state: "requested" | "running" | "succeeded" | "failed" | "cancelled", outputSummary: string | null, receiptRef: string | null): Promise<TaskEvent> {
    return this.emit(task, { type: "tool", callId, name, state, inputSummary: bounded(inputSummary, 1000), outputSummary: outputSummary === null ? null : bounded(outputSummary, 2000), receiptRef });
  }

  private async emit(task: PersistedTask, body: EventBody): Promise<TaskEvent> {
    const event = { contractVersion: "1.0", taskId: task.view.taskId, sequence: task.view.lastSequence + 1, occurredAt: new Date().toISOString(), ...body } as TaskEvent;
    this.options.validator.validate("task-event", event);
    await this.options.store.appendEvent(event);
    task.view.lastSequence = event.sequence; task.view.updatedAt = event.occurredAt;
    for (const subscriber of this.subscribers.get(task.view.taskId) ?? []) subscriber(event);
    return event;
  }

  private checkCancelled(signal: AbortSignal): void { if (signal.aborted) throw new DOMException("Cancelled", "AbortError"); }
}

function functionTool(name: string, description: string, parameters: unknown): unknown { return { type: "function", function: { name, description, parameters } }; }
function isRepairableSandboxPolicy(error: unknown): error is EngineProblem {
  return error instanceof EngineProblem && error.problem.code === "SANDBOX_COMMAND_DENIED";
}
function deterministicCallId(taskId: string, modelCall: number, ordinal: number): string { return `call.${sha256(`${taskId}:${modelCall}:${ordinal}`).slice(0, 40)}`; }
function requireString(value: unknown, name: string): string { if (typeof value !== "string" || !value) throw new EngineProblem(502, problem("MODEL_TOOL_ARGUMENTS_INVALID", "model", `Tool argument ${name} must be a non-empty string`)); return value; }

function sandboxRequest(callId: string, args: Record<string, unknown>): SandboxRequest {
  const argv = args.argv; const inputs = args.inputs; const timeoutMillis = args.timeoutMillis;
  if (!Array.isArray(argv) || !argv.every((item) => typeof item === "string") || !Array.isArray(inputs) || !Number.isInteger(timeoutMillis)) throw new EngineProblem(502, problem("MODEL_TOOL_ARGUMENTS_INVALID", "model", "Sandbox tool arguments are invalid"));
  const normalizedInputs = inputs.map((item) => { const input = item as Record<string, unknown>; return { path: requireString(input.path, "inputs.path"), sha256: requireString(input.sha256, "inputs.sha256") }; });
  const semantics = { argv, inputs: normalizedInputs, timeoutMillis };
  return { contractVersion: "1.0", clientRequestId: callId, requestDigest: digestObject(semantics), argv, inputs: normalizedInputs, timeoutMillis: timeoutMillis as number };
}

function receiptSummary(receipt: Receipt): string {
  return `status=${receipt.status}; exitCode=${receipt.exitCode ?? "null"}; stdoutBytes=${receipt.stdout.originalBytes}; stderrBytes=${receipt.stderr.originalBytes}; inputFingerprint=${receipt.inputFingerprint}`;
}

function sandboxDeadline(executionRef: string): EngineProblem {
  return new EngineProblem(504, problem("SANDBOX_STATUS_DEADLINE_EXCEEDED", "sandbox_system", "Sandbox status did not become terminal before the fixed deadline", true, executionRef));
}

function gatewayBinding(message: string, sourceRef?: string): EngineProblem {
  return new EngineProblem(502, problem("GATEWAY_RESPONSE_BINDING_INVALID", "tool", message, false, sourceRef));
}

function bindSandboxView(view: import("./types.js").SandboxView, request: SandboxRequest, expectedExecutionRef?: string): void {
  if (view.clientRequestId !== request.clientRequestId || view.requestDigest !== request.requestDigest) throw gatewayBinding("Sandbox view does not match the submitted request", view.executionRef);
  if (expectedExecutionRef !== undefined && view.executionRef !== expectedExecutionRef) throw gatewayBinding("Sandbox execution identity changed during replay or polling", view.executionRef);
}

function bindReceipt(receipt: Receipt, view: import("./types.js").SandboxView, request: SandboxRequest): void {
  if (receipt.receiptRef !== view.receiptRef || receipt.executionRef !== view.executionRef || receipt.status !== view.state) throw gatewayBinding("Receipt does not match the terminal sandbox execution", receipt.receiptRef);
  const requested = [...request.inputs].sort(compareInput).map(({ path, sha256 }) => ({ path, sha256 }));
  const received = [...receipt.inputs].sort(compareInput).map(({ path, sha256 }) => ({ path, sha256 }));
  if (JSON.stringify(received) !== JSON.stringify(requested)) throw gatewayBinding("Receipt inputs do not exactly match the submitted sandbox inputs", receipt.receiptRef);
}

function compareInput(left: { path: string; sha256: string }, right: { path: string; sha256: string }): number {
  return left.path === right.path ? left.sha256.localeCompare(right.sha256) : left.path.localeCompare(right.path);
}

function validateCallSet(calls: PendingCall[]): void {
  const supported = new Set(["list_project_files", "read_project_file", "execute_in_sandbox", "ask_user"]);
  if (calls.some((call) => !supported.has(call.name))) throw new EngineProblem(502, problem("MODEL_TOOL_UNKNOWN", "model", "Model requested an unsupported tool"));
  if (calls.some((call) => call.name === "ask_user") && calls.length !== 1) throw new EngineProblem(502, problem("MODEL_QUESTION_INVALID", "model", "ask_user must be the only tool call"));
}

function isGrantRefresh(error: unknown): boolean {
  return error instanceof EngineProblem
    && error.problem.category === "authorization"
    && (error.problem.code === "TASK_GRANT_REFRESH_REQUIRED" || error.problem.code === "TASK_GRANT_EXPIRED");
}

function initialMessages(submission: TaskSubmission): ChatMessage[] {
  return [
    { role: "system", content: "You are PaperAgent's bounded ReAct executor. Inspect only through the provided project tools. Use exact manifest hashes. Validate executable/code conclusions with the sandbox. Tool results are authoritative. Never invent a receipt. Ask one question only when work cannot safely continue. Return a concise final conclusion when done." },
    { role: "user", content: `Frozen projectVersion: ${submission.authority.project.projectVersion}\nTask: ${submission.authority.instruction}` }
  ];
}

function abortableSleep(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, milliseconds);
    signal.addEventListener("abort", () => { clearTimeout(timer); reject(new DOMException("Cancelled", "AbortError")); }, { once: true });
  });
}
