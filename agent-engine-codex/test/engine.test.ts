import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { AgentEngine } from "../src/engine.js";
import type { GatewayClient, SandboxRequest } from "../src/gateway.js";
import { TaskStore } from "../src/store.js";
import type { FileList, FileRead, ModelProvider, ModelRequest, ModelResponse, PersistedTask, Receipt, SandboxView, TaskEvent, TaskSubmission } from "../src/types.js";
import { digestObject, EngineProblem, problem, sha256 } from "../src/util.js";
import { ContractValidator } from "../src/validation.js";

const contractDirectory = resolve(process.cwd(), "../agent-engine-contract");
const taskId = `task.${"a".repeat(64)}`;
const projectVersion = "b".repeat(64);
const fileHash = sha256("SECRET_FILE_BODY");
const grant = "g".repeat(40);
const directories: string[] = [];

afterEach(() => { directories.length = 0; });

describe("AgentEngine", () => {
  it("runs native tools serially and emits a bounded receipt-backed delivery", async () => {
    const provider = new ScriptedProvider([
      tool("list_project_files", {}),
      tool("read_project_file", { path: "Sort.java", expectedSha256: fileHash }),
      tool("execute_in_sandbox", { argv: ["yanban-runner", "java", "Sort.java"], inputs: [{ path: "Sort.java", sha256: fileHash }], timeoutMillis: 5000 }),
      { content: "Sort.java compiled and ran successfully.", toolCalls: [] }
    ]);
    const gateway = new FakeGateway();
    const engine = await createEngine(provider, gateway);
    const accepted = await engine.submit(submission());
    expect(accepted.replayed).toBe(false);
    await waitFor(() => engine.get(taskId).state === "succeeded");
    const view = engine.get(taskId);
    const events = await engine.events(taskId);
    expect(view.deliverySequence).toBeTruthy();
    expect(events.map((event) => event.sequence)).toEqual(events.map((_, index) => index + 1));
    expect(events.filter((event) => event.type === "tool" && event.state === "requested").map((event) => event.type === "tool" ? event.name : "")).toEqual(["project.list", "project.read", "sandbox.execute"]);
    const delivery = events.find((event) => event.type === "delivery");
    expect(delivery?.type === "delivery" ? delivery.receiptRefs : []).toEqual(["receipt.1"]);
    expect(JSON.stringify(events)).not.toContain("SECRET_FILE_BODY");
    expect(gateway.maximumConcurrent).toBe(1);
    expect(provider.requests.every((request) => request.maxOutputTokens === 4096)).toBe(true);
  });

  it("persists a formal receipt before emitting its receipt-bearing tool event", async () => {
    const directory = await temporaryDirectory();
    const store = new ReceiptOrderingStore(directory);
    const engine = new AgentEngine({
      store,
      provider: new ScriptedProvider([sandboxTool(), { content: "Receipt was durably recorded.", toolCalls: [] }]),
      gateway: new FakeGateway(), validator: new ContractValidator(contractDirectory)
    });
    await engine.initialize(); await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "succeeded");
    expect(store.receiptEventObservedAfterSave).toBe(true);
  });

  it("reconciles a legacy receipt-bearing event into the delivery authority", async () => {
    const directory = await temporaryDirectory();
    const store = new TaskStore(directory); await store.initialize();
    await store.create(persistedTask());
    await store.appendEvent({
      contractVersion: "1.0", taskId, sequence: 1, occurredAt: new Date().toISOString(),
      type: "tool", callId: `call.${"r".repeat(16)}`, name: "sandbox.execute",
      state: "succeeded", inputSummary: "bounded", outputSummary: "bounded", receiptRef: "receipt.legacy"
    });
    const [recovered] = await store.loadAll();
    expect(recovered?.receiptRefs).toEqual(["receipt.legacy"]);
  });

  it("accepts exact replay, refreshes the grant, and rejects a digest conflict", async () => {
    const engine = await createEngine(new ScriptedProvider([{ content: "done", toolCalls: [] }]), new FakeGateway());
    const request = submission();
    await engine.submit(request);
    const replay = await engine.submit({ ...request, gateway: { taskGrant: "n".repeat(40), expiresAt: new Date(Date.now() + 60_000).toISOString() } });
    expect(replay.replayed).toBe(true);
    const conflictingAuthority = { ...request.authority, instruction: "A different task" };
    await expect(engine.submit({ ...request, authority: conflictingAuthority, requestDigest: digestObject(conflictingAuthority) })).rejects.toMatchObject({ status: 409, problem: { code: "TASK_DIGEST_CONFLICT" } });
  });

  it("serializes concurrent first submissions into one task", async () => {
    const engine = await createEngine(new NeverProvider(), new FakeGateway());
    const [left, right] = await Promise.all([engine.submit(submission()), engine.submit(submission())]);
    expect([left.replayed, right.replayed].sort()).toEqual([false, true]);
    expect((await engine.events(taskId)).filter((event) => event.type === "status" && event.state === "queued")).toHaveLength(1);
    await engine.cancel(taskId);
  });

  it("freezes the first answer and resumes a question exactly once", async () => {
    const directory = await temporaryDirectory();
    const provider = new ScriptedProvider([tool("ask_user", { question: "Which class should I validate?" }), sandboxTool(), { content: "Validated the selected class.", toolCalls: [] }]);
    const engine = await createEngine(provider, new FakeGateway(), directory);
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "waiting_user");
    const questionId = engine.get(taskId).pendingQuestionId!;
    const answer = { contractVersion: "1.0" as const, clientRequestId: `answer.${"x".repeat(16)}`, questionId, answer: "Sort.java", answerDigest: sha256("Sort.java") };
    await engine.answer(taskId, answer);
    await engine.answer(taskId, answer);
    await expect(engine.answer(taskId, { ...answer, clientRequestId: `answer.${"y".repeat(16)}`, answer: "Other.java", answerDigest: sha256("Other.java") })).rejects.toMatchObject({ status: 409, problem: { code: "QUESTION_ANSWER_CONFLICT" } });
    await waitFor(() => engine.get(taskId).state === "succeeded");
    expect(provider.requests).toHaveLength(3);
    const restarted = await createEngine(new NeverProvider(), new FakeGateway(), directory);
    expect((await restarted.answer(taskId, answer)).state).toBe("succeeded");
    await expect(restarted.answer(taskId, { ...answer, clientRequestId: `answer.${"z".repeat(16)}`, answer: "Other.java", answerDigest: sha256("Other.java") })).rejects.toMatchObject({ status: 409, problem: { code: "QUESTION_ANSWER_CONFLICT" } });
  });

  it("recovers a journaled answer after a crash before the task projection is saved", async () => {
    const directory = await temporaryDirectory();
    const first = await createEngine(new ScriptedProvider([tool("ask_user", { question: "Which class should I validate?" })]), new FakeGateway(), directory);
    await first.submit(submission());
    await waitFor(() => first.get(taskId).state === "waiting_user");
    const questionId = first.get(taskId).pendingQuestionId!;
    const answer = { clientRequestId: `answer.${"j".repeat(16)}`, questionId, answer: "Sort.java", answerDigest: sha256("Sort.java") };
    await new TaskStore(directory).appendAnswer(taskId, answer);

    const recoveredProvider = new ScriptedProvider([sandboxTool(), { content: "Recovered the accepted answer without asking again.", toolCalls: [] }]);
    const recovered = await createEngine(recoveredProvider, new FakeGateway(), directory);
    expect(recovered.get(taskId)).toMatchObject({ state: "waiting_user", pendingQuestionId: null });
    expect((await recovered.submit(submission())).replayed).toBe(true);
    await waitFor(() => recovered.get(taskId).state === "succeeded");
    expect(recoveredProvider.requests).toHaveLength(2);
    expect(recoveredProvider.requests[0]?.messages).toContainEqual(expect.objectContaining({ role: "tool", content: JSON.stringify({ answer: "Sort.java" }) }));
    expect((await recovered.answer(taskId, { contractVersion: "1.0", ...answer })).state).toBe("succeeded");
  });

  it("serializes competing answers and preserves exactly one question authority", async () => {
    const directory = await temporaryDirectory();
    const provider = new ScriptedProvider([tool("ask_user", { question: "Choose one." }), sandboxTool(), { content: "Used the first accepted answer.", toolCalls: [] }]);
    const engine = await createEngine(provider, new FakeGateway(), directory);
    await engine.submit(submission()); await waitFor(() => engine.get(taskId).state === "waiting_user");
    const questionId = engine.get(taskId).pendingQuestionId!;
    const left = { contractVersion: "1.0" as const, clientRequestId: `answer.${"l".repeat(16)}`, questionId, answer: "left", answerDigest: sha256("left") };
    const right = { contractVersion: "1.0" as const, clientRequestId: `answer.${"r".repeat(16)}`, questionId, answer: "right", answerDigest: sha256("right") };
    const results = await Promise.allSettled([engine.answer(taskId, left), engine.answer(taskId, right)]);
    expect(results.filter((result) => result.status === "fulfilled")).toHaveLength(1);
    expect(results.filter((result) => result.status === "rejected").map((result) => result.status === "rejected" ? result.reason.problem.code : null)).toEqual(["QUESTION_ANSWER_CONFLICT"]);
    await waitFor(() => engine.get(taskId).state === "succeeded");
    expect(await new TaskStore(directory).answers(taskId)).toHaveLength(1);
  });

  it("projects a durable question as waiting instead of asking it twice after restart", async () => {
    const directory = await temporaryDirectory();
    const store = new TaskStore(directory); await store.initialize();
    const task = persistedTask();
    const callId = `call.${"q".repeat(16)}`; const questionId = `question.${"q".repeat(48)}`;
    task.pendingCalls = [{ id: callId, name: "ask_user", arguments: JSON.stringify({ question: "Which class?" }), ordinal: 0 }];
    task.messages.push({ role: "assistant", content: null, toolCalls: [{ id: callId, name: "ask_user", arguments: JSON.stringify({ question: "Which class?" }) }] });
    await store.create(task);
    await store.appendEvent({ contractVersion: "1.0", taskId, sequence: 1, occurredAt: new Date().toISOString(), type: "question", questionId, text: "Which class?" });

    const provider = new ScriptedProvider([sandboxTool(), { content: "Accepted the recovered answer.", toolCalls: [] }]);
    const recovered = await createEngine(provider, new FakeGateway(), directory);
    expect(recovered.get(taskId)).toMatchObject({ state: "waiting_user", pendingQuestionId: questionId });
    expect(provider.requests).toHaveLength(0);
    const answer = "Sort.java";
    await recovered.answer(taskId, { contractVersion: "1.0", clientRequestId: `answer.${"q".repeat(16)}`, questionId, answer, answerDigest: sha256(answer) });
    expect(recovered.get(taskId).state).toBe("running");
    expect(provider.requests).toHaveLength(0);
    await recovered.submit(submission());
    await waitFor(() => recovered.get(taskId).state === "succeeded");
    expect((await recovered.events(taskId)).filter((event) => event.type === "question")).toHaveLength(1);
  });

  it("finishes a durable delivery without invoking the model again after restart", async () => {
    const directory = await temporaryDirectory();
    const store = new TaskStore(directory); await store.initialize();
    await store.create(persistedTask());
    await store.appendEvent({ contractVersion: "1.0", taskId, sequence: 1, occurredAt: new Date().toISOString(), type: "delivery", conclusion: "Durable conclusion", receiptRefs: [] });
    const provider = new NeverProvider();
    const recovered = await createEngine(provider, new FakeGateway(), directory);
    expect((await recovered.submit(submission())).replayed).toBe(true);
    await waitFor(() => recovered.get(taskId).state === "succeeded");
    expect((await recovered.events(taskId)).filter((event) => event.type === "delivery")).toHaveLength(1);
  });

  it("loads persisted events and resumes only after an exact replay supplies a fresh grant", async () => {
    const directory = await temporaryDirectory();
    const first = await createEngine(new NeverProvider(), new FakeGateway(), directory);
    await first.submit(submission());
    await waitFor(() => first.get(taskId).state === "running");
    const secondProvider = new ScriptedProvider([sandboxTool(), { content: "Recovered without recreating the task.", toolCalls: [] }]);
    const second = await createEngine(secondProvider, new FakeGateway(), directory);
    expect(second.get(taskId).state).toBe("running");
    const replay = await second.submit(submission());
    expect(replay.replayed).toBe(true);
    await waitFor(() => second.get(taskId).state === "succeeded");
    const sequences = (await second.events(taskId)).map((event) => event.sequence);
    expect(sequences).toEqual(sequences.map((_, index) => index + 1));
  });

  it("cancels idempotently without producing a delivery", async () => {
    const engine = await createEngine(new NeverProvider(), new FakeGateway());
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "running");
    const first = await engine.cancel(taskId);
    const second = await engine.cancel(taskId);
    expect(first.state).toBe("cancelled"); expect(second.terminalSequence).toBe(first.terminalSequence);
    expect((await engine.events(taskId)).some((event) => event.type === "delivery")).toBe(false);
  });

  it("emits one terminal status for concurrent cancellation while waiting for the user", async () => {
    const engine = await createEngine(new ScriptedProvider([tool("ask_user", { question: "Proceed?" })]), new FakeGateway());
    await engine.submit(submission()); await waitFor(() => engine.get(taskId).state === "waiting_user");
    await Promise.all([engine.cancel(taskId), engine.cancel(taskId)]);
    expect((await engine.events(taskId)).filter((event) => event.type === "status" && event.state === "cancelled")).toHaveLength(1);
  });

  it("uses the frozen polling schedule and delivers a receipt-backed compile failure conclusion", async () => {
    const sleeps: number[] = [];
    const directory = await temporaryDirectory();
    const engine = new AgentEngine({
      store: new TaskStore(directory),
      provider: new ScriptedProvider([
        tool("execute_in_sandbox", { argv: ["yanban-runner", "java", "Sort.java"], inputs: [{ path: "Sort.java", sha256: fileHash }], timeoutMillis: 5000 }),
        { content: "Compilation failed.", toolCalls: [] }
      ]),
      gateway: new PollingFailedGateway(), validator: new ContractValidator(contractDirectory), sleep: async (milliseconds) => { sleeps.push(milliseconds); }
    });
    await engine.initialize(); await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "succeeded");
    expect(sleeps).toEqual([1000, 2000]);
    expect(engine.get(taskId).error).toBeNull();
    const events = await engine.events(taskId);
    expect(events).toContainEqual(expect.objectContaining({ type: "tool", name: "sandbox.execute", state: "failed", receiptRef: "receipt.failed" }));
    expect(events).toContainEqual(expect.objectContaining({ type: "delivery", receiptRefs: ["receipt.failed"] }));
  });

  it("returns a denied argv as a bounded tool observation so the model can repair it", async () => {
    const provider = new ScriptedProvider([
      tool("execute_in_sandbox", { argv: ["javac", "-version"], inputs: [{ path: "Sort.java", sha256: fileHash }], timeoutMillis: 5000 }),
      sandboxTool(),
      { content: "Compiled after repairing the denied command.", toolCalls: [] }
    ]);
    const engine = await createEngine(provider, new CommandDeniedOnceGateway());
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "succeeded");
    const events = await engine.events(taskId);
    expect(events).toContainEqual(expect.objectContaining({ type: "tool", name: "sandbox.execute", state: "failed", receiptRef: null }));
    expect(events).toContainEqual(expect.objectContaining({ type: "delivery", receiptRefs: ["receipt.1"] }));
    expect(provider.requests[1]!.messages.at(-1)?.content).toContain("SANDBOX_COMMAND_DENIED");
  });

  it("starts the sandbox deadline after acceptance and never polls beyond it", async () => {
    let clock = 0; const sleeps: number[] = [];
    const gateway = new NeverTerminalGateway(() => { clock = 30_000; });
    const engine = new AgentEngine({
      store: new TaskStore(await temporaryDirectory()),
      provider: new ScriptedProvider([tool("execute_in_sandbox", { argv: ["yanban-runner", "java", "Sort.java"], inputs: [{ path: "Sort.java", sha256: fileHash }], timeoutMillis: 1000 })]),
      gateway, validator: new ContractValidator(contractDirectory), monotonicNow: () => clock,
      sleep: async (milliseconds) => { sleeps.push(milliseconds); clock += milliseconds; }
    });
    await engine.initialize(); await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "failed");
    expect(engine.get(taskId).error).toMatchObject({ code: "SANDBOX_STATUS_DEADLINE_EXCEEDED", category: "sandbox_system" });
    expect(sleeps).toEqual([1000, 2000, 4000, 5000, 5000, 5000, 5000, 4000]);
    expect(gateway.polls).toBe(7);
  });

  it("classifies a sandbox system failure separately and never emits delivery", async () => {
    const engine = await createEngine(new ScriptedProvider([tool("execute_in_sandbox", { argv: ["yanban-runner", "java", "Sort.java"], inputs: [{ path: "Sort.java", sha256: fileHash }], timeoutMillis: 5000 })]), new SystemErrorGateway());
    await engine.submit(submission()); await waitFor(() => engine.get(taskId).state === "failed");
    expect(engine.get(taskId).error).toMatchObject({ code: "SANDBOX_SYSTEM_ERROR", category: "sandbox_system", sourceRef: "receipt.system" });
    expect((await engine.events(taskId)).some((event) => event.type === "delivery")).toBe(false);
  });

  it("pauses on an expired gateway grant and resumes the same sandbox execution after exact replay", async () => {
    const gateway = new RefreshingGrantGateway();
    const engine = await createEngine(new ScriptedProvider([sandboxTool(), { content: "Recovered with a formal receipt.", toolCalls: [] }]), gateway);
    const request = submission();
    await engine.submit(request);
    await waitFor(() => gateway.refreshRequested);
    expect(engine.get(taskId)).toMatchObject({ state: "running", terminalSequence: null });
    const refreshedGrant = "n".repeat(40);
    expect((await engine.submit({ ...request, gateway: { taskGrant: refreshedGrant, expiresAt: new Date(Date.now() + 60_000).toISOString() } })).replayed).toBe(true);
    await waitFor(() => engine.get(taskId).state === "succeeded");
    expect(gateway.submitGrants).toEqual([grant, refreshedGrant]);
    expect(gateway.executionGrants).toEqual([grant, refreshedGrant]);
    expect((await engine.events(taskId)).filter((event) => event.type === "tool" && event.name === "sandbox.execute" && event.state === "requested")).toHaveLength(1);
  });

  it("does not let a late 401 from an old request erase a concurrently refreshed grant", async () => {
    const gateway = new ConcurrentRefreshGateway();
    const engine = await createEngine(new ScriptedProvider([sandboxTool(), { content: "Completed after concurrent refresh.", toolCalls: [] }]), gateway);
    const request = submission();
    await engine.submit(request);
    await gateway.oldRequestEntered;
    const refreshedGrant = "r".repeat(40);
    await engine.submit({ ...request, gateway: { taskGrant: refreshedGrant, expiresAt: new Date(Date.now() + 60_000).toISOString() } });
    gateway.releaseOldRequest();
    await waitFor(() => engine.get(taskId).state === "succeeded");
    expect(gateway.executionGrants).toEqual([grant, refreshedGrant]);
  });

  it("rejects gateway data that is not bound to the frozen task and request", async () => {
    const gateway = new FakeGateway();
    gateway.list = (taskIdValue: string) => Promise.resolve({ contractVersion: "1.0", taskId: taskIdValue, projectVersion: "c".repeat(64), files: [] });
    const engine = await createEngine(new ScriptedProvider([tool("list_project_files", {})]), gateway);
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "failed");
    expect(engine.get(taskId).error).toMatchObject({ code: "GATEWAY_RESPONSE_BINDING_INVALID", category: "tool" });
  });

  it("rejects a terminal receipt whose exact inputs do not match the sandbox submission", async () => {
    const gateway = new FakeGateway();
    gateway.receipt = () => Promise.resolve({ contractVersion: "1.0", receiptRef: "receipt.1", executionRef: "execution.1", status: "SUCCEEDED", exitCode: 0, stdout: { text: "", truncated: false, originalBytes: 0 }, stderr: { text: "", truncated: false, originalBytes: 0 }, inputFingerprint: "d".repeat(64), inputs: [{ path: "Other.java", sha256: fileHash, sizeBytes: 16 }], startedAt: new Date().toISOString(), finishedAt: new Date().toISOString() });
    const engine = await createEngine(new ScriptedProvider([sandboxTool()]), gateway);
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "failed");
    expect(engine.get(taskId).error).toMatchObject({ code: "GATEWAY_RESPONSE_BINDING_INVALID" });
  });

  it("binds receipt inputs as an unordered exact set", async () => {
    const alphaHash = "a".repeat(64);
    const zetaHash = "f".repeat(64);
    const provider = new ScriptedProvider([
      tool("execute_in_sandbox", {
        argv: ["javac", "Alpha.java", "Zeta.java"],
        inputs: [
          { path: "Zeta.java", sha256: zetaHash },
          { path: "Alpha.java", sha256: alphaHash }
        ],
        timeoutMillis: 5000
      }),
      { content: "Validated both inputs.", toolCalls: [] }
    ]);
    const engine = await createEngine(provider, new CanonicallyOrderedReceiptGateway(alphaHash, zetaHash));
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "succeeded");
    const delivery = (await engine.events(taskId)).find((event) => event.type === "delivery");
    expect(delivery?.type === "delivery" ? delivery.receiptRefs : []).toEqual(["receipt.1"]);
  });

  it("does not perform an earlier side effect when ask_user is mixed with another tool call", async () => {
    const gateway = new CountingGateway();
    const engine = await createEngine(new ScriptedProvider([{ content: null, toolCalls: [
      { id: "first", name: "list_project_files", arguments: "{}" },
      { id: "second", name: "ask_user", arguments: JSON.stringify({ question: "Proceed?" }) }
    ] }]), gateway);
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "failed");
    expect(gateway.calls).toBe(0);
    expect(engine.get(taskId).error).toMatchObject({ code: "MODEL_QUESTION_INVALID" });
  });

  it("fails closed when the model concludes P1 without a formal sandbox receipt", async () => {
    const engine = await createEngine(new ScriptedProvider([{ content: "unverified conclusion", toolCalls: [] }]), new FakeGateway());
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "failed");
    expect(engine.get(taskId).error).toMatchObject({ code: "SANDBOX_RECEIPT_REQUIRED", category: "code_validation" });
    expect((await engine.events(taskId)).some((event) => event.type === "delivery")).toBe(false);
  });

  it("keeps the accepted sandbox deadline across a process restart", async () => {
    const directory = await temporaryDirectory();
    const store = new TaskStore(directory); await store.initialize();
    const task = persistedTask();
    const callId = `call.${"d".repeat(40)}`;
    task.pendingCalls = [{ id: callId, name: "execute_in_sandbox", arguments: JSON.stringify({ argv: ["yanban-runner", "java", "Sort.java"], inputs: [{ path: "Sort.java", sha256: fileHash }], timeoutMillis: 5000 }), ordinal: 0, sandbox: { executionRef: "execution.expired", deadlineAt: new Date(Date.now() - 1).toISOString() } }];
    await store.create(task);
    const gateway = new ExpiredRecoveryGateway();
    const engine = await createEngine(new NeverProvider(), gateway, directory);
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "failed");
    expect(engine.get(taskId).error).toMatchObject({ code: "SANDBOX_STATUS_DEADLINE_EXCEEDED" });
    expect(gateway.submits).toBe(1);
    expect(gateway.polls).toBe(0);
  });
});

class ScriptedProvider implements ModelProvider {
  readonly requests: ModelRequest[] = [];
  constructor(private readonly responses: ModelResponse[]) {}
  async complete(request: ModelRequest): Promise<ModelResponse> {
    this.requests.push(request);
    const response = this.responses.shift();
    if (!response) throw new Error("No scripted response");
    return response;
  }
}

class NeverProvider implements ModelProvider {
  complete(request: ModelRequest): Promise<ModelResponse> {
    if (request.signal.aborted) return Promise.reject(new DOMException("aborted", "AbortError"));
    return new Promise((_, reject) => request.signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")), { once: true }));
  }
}

class ReceiptOrderingStore extends TaskStore {
  private receiptSaved = false;
  receiptEventObservedAfterSave = false;

  override async save(task: PersistedTask): Promise<void> {
    if (task.receiptRefs.length > 0) this.receiptSaved = true;
    await super.save(task);
  }

  override async appendEvent(event: TaskEvent): Promise<void> {
    if (event.type === "tool" && event.name === "sandbox.execute" && event.receiptRef !== null) {
      this.receiptEventObservedAfterSave = this.receiptSaved;
    }
    await super.appendEvent(event);
  }
}

class FakeGateway implements GatewayClient {
  concurrent = 0; maximumConcurrent = 0;
  private async operation<T>(value: T): Promise<T> { this.concurrent += 1; this.maximumConcurrent = Math.max(this.maximumConcurrent, this.concurrent); await Promise.resolve(); this.concurrent -= 1; return value; }
  list(taskIdValue: string): Promise<FileList> { return this.operation({ contractVersion: "1.0", taskId: taskIdValue, projectVersion, files: [{ path: "Sort.java", sizeBytes: 16, sha256: fileHash, mediaType: "text/x-java" }] }); }
  read(): Promise<FileRead> { return this.operation({ contractVersion: "1.0", path: "Sort.java", sizeBytes: 16, sha256: fileHash, mediaType: "text/x-java", encoding: "utf-8", content: "SECRET_FILE_BODY", truncated: false }); }
  submit(_taskId: string, _grant: string, request: SandboxRequest): Promise<SandboxView> { return this.operation({ contractVersion: "1.0", clientRequestId: request.clientRequestId, requestDigest: request.requestDigest, executionRef: "execution.1", state: "SUCCEEDED", receiptRef: "receipt.1" }); }
  execution(_taskId: string, _grant: string, _clientRequestId: string): Promise<SandboxView> { throw new Error("terminal submit should not poll"); }
  receipt(): Promise<Receipt> { return this.operation({ contractVersion: "1.0", receiptRef: "receipt.1", executionRef: "execution.1", status: "SUCCEEDED", exitCode: 0, stdout: { text: "ok", truncated: false, originalBytes: 2 }, stderr: { text: "", truncated: false, originalBytes: 0 }, inputFingerprint: "d".repeat(64), inputs: [{ path: "Sort.java", sha256: fileHash, sizeBytes: 16 }], startedAt: new Date().toISOString(), finishedAt: new Date().toISOString() }); }
}

class CanonicallyOrderedReceiptGateway extends FakeGateway {
  constructor(private readonly alphaHash: string, private readonly zetaHash: string) { super(); }
  override receipt(): Promise<Receipt> {
    return Promise.resolve({
      contractVersion: "1.0",
      receiptRef: "receipt.1",
      executionRef: "execution.1",
      status: "SUCCEEDED",
      exitCode: 0,
      stdout: { text: "ok", truncated: false, originalBytes: 2 },
      stderr: { text: "", truncated: false, originalBytes: 0 },
      inputFingerprint: "d".repeat(64),
      inputs: [
        { path: "Alpha.java", sha256: this.alphaHash, sizeBytes: 16 },
        { path: "Zeta.java", sha256: this.zetaHash, sizeBytes: 16 }
      ],
      startedAt: new Date().toISOString(),
      finishedAt: new Date().toISOString()
    });
  }
}

class PollingFailedGateway extends FakeGateway {
  private polls = 0;
  override submit(_taskId: string, _grant: string, request: SandboxRequest): Promise<SandboxView> { return Promise.resolve({ contractVersion: "1.0", clientRequestId: request.clientRequestId, requestDigest: request.requestDigest, executionRef: "execution.failed", state: "QUEUED", receiptRef: null }); }
  override execution(_taskId: string, _grant: string, clientRequestId: string): Promise<SandboxView> {
    this.polls += 1;
    return Promise.resolve({ contractVersion: "1.0", clientRequestId, requestDigest: digestObject({ argv: ["yanban-runner", "java", "Sort.java"], inputs: [{ path: "Sort.java", sha256: fileHash }], timeoutMillis: 5000 }), executionRef: "execution.failed", state: this.polls === 1 ? "RUNNING" : "FAILED", receiptRef: this.polls === 1 ? null : "receipt.failed" });
  }
  override receipt(): Promise<Receipt> { return Promise.resolve({ contractVersion: "1.0", receiptRef: "receipt.failed", executionRef: "execution.failed", status: "FAILED", exitCode: 1, stdout: { text: "", truncated: false, originalBytes: 0 }, stderr: { text: "compile error", truncated: false, originalBytes: 13 }, inputFingerprint: "e".repeat(64), inputs: [{ path: "Sort.java", sha256: fileHash, sizeBytes: 16 }], startedAt: new Date().toISOString(), finishedAt: new Date().toISOString() }); }
}

class CommandDeniedOnceGateway extends FakeGateway {
  private denied = false;
  override submit(taskIdValue: string, currentGrant: string, request: SandboxRequest): Promise<SandboxView> {
    if (!this.denied) {
      this.denied = true;
      throw new EngineProblem(400, problem("SANDBOX_COMMAND_DENIED", "sandbox_system", "redacted upstream policy detail", false));
    }
    return super.submit(taskIdValue, currentGrant, request);
  }
}

class NeverTerminalGateway extends FakeGateway {
  polls = 0;
  constructor(private readonly onAccepted: () => void) { super(); }
  override submit(_taskId: string, _grant: string, request: SandboxRequest): Promise<SandboxView> {
    this.onAccepted();
    return Promise.resolve({ contractVersion: "1.0", clientRequestId: request.clientRequestId, requestDigest: request.requestDigest, executionRef: "execution.never", state: "QUEUED", receiptRef: null });
  }
  override execution(_taskId: string, _grant: string, clientRequestId: string): Promise<SandboxView> {
    this.polls += 1;
    return Promise.resolve({ contractVersion: "1.0", clientRequestId, requestDigest: digestObject({ argv: ["yanban-runner", "java", "Sort.java"], inputs: [{ path: "Sort.java", sha256: fileHash }], timeoutMillis: 1000 }), executionRef: "execution.never", state: "RUNNING", receiptRef: null });
  }
}

class SystemErrorGateway extends FakeGateway {
  override submit(_taskId: string, _grant: string, request: SandboxRequest): Promise<SandboxView> {
    return Promise.resolve({ contractVersion: "1.0", clientRequestId: request.clientRequestId, requestDigest: request.requestDigest, executionRef: "execution.system", state: "SYSTEM_ERROR", receiptRef: "receipt.system" });
  }
  override receipt(): Promise<Receipt> {
    return Promise.resolve({ contractVersion: "1.0", receiptRef: "receipt.system", executionRef: "execution.system", status: "SYSTEM_ERROR", exitCode: null, stdout: { text: "", truncated: false, originalBytes: 0 }, stderr: { text: "", truncated: false, originalBytes: 0 }, inputFingerprint: "f".repeat(64), inputs: [{ path: "Sort.java", sha256: fileHash, sizeBytes: 16 }], startedAt: new Date().toISOString(), finishedAt: new Date().toISOString() });
  }
}

class RefreshingGrantGateway extends FakeGateway {
  refreshRequested = false;
  readonly submitGrants: string[] = [];
  readonly executionGrants: string[] = [];
  override submit(_taskId: string, currentGrant: string, request: SandboxRequest): Promise<SandboxView> {
    this.submitGrants.push(currentGrant);
    return Promise.resolve({ contractVersion: "1.0", clientRequestId: request.clientRequestId, requestDigest: request.requestDigest, executionRef: "execution.refresh", state: "RUNNING", receiptRef: null });
  }
  override execution(_taskId: string, currentGrant: string, clientRequestId: string): Promise<SandboxView> {
    this.executionGrants.push(currentGrant);
    if (!this.refreshRequested) {
      this.refreshRequested = true;
      throw new EngineProblem(401, problem("TASK_GRANT_EXPIRED", "authorization", "expired", true));
    }
    return Promise.resolve({ contractVersion: "1.0", clientRequestId, requestDigest: digestObject({ argv: ["yanban-runner", "java", "Sort.java"], inputs: [{ path: "Sort.java", sha256: fileHash }], timeoutMillis: 5000 }), executionRef: "execution.refresh", state: "SUCCEEDED", receiptRef: "receipt.refresh" });
  }
  override receipt(): Promise<Receipt> { return Promise.resolve({ contractVersion: "1.0", receiptRef: "receipt.refresh", executionRef: "execution.refresh", status: "SUCCEEDED", exitCode: 0, stdout: { text: "ok", truncated: false, originalBytes: 2 }, stderr: { text: "", truncated: false, originalBytes: 0 }, inputFingerprint: "d".repeat(64), inputs: [{ path: "Sort.java", sha256: fileHash, sizeBytes: 16 }], startedAt: new Date().toISOString(), finishedAt: new Date().toISOString() }); }
}

class CountingGateway extends FakeGateway {
  calls = 0;
  override list(taskIdValue: string): Promise<FileList> { this.calls += 1; return super.list(taskIdValue); }
}

class ConcurrentRefreshGateway extends FakeGateway {
  readonly executionGrants: string[] = [];
  private resolveEntered!: () => void;
  private resolveRelease!: () => void;
  readonly oldRequestEntered = new Promise<void>((resolve) => { this.resolveEntered = resolve; });
  private readonly oldRequestRelease = new Promise<void>((resolve) => { this.resolveRelease = resolve; });
  releaseOldRequest(): void { this.resolveRelease(); }
  override submit(_taskId: string, _grant: string, request: SandboxRequest): Promise<SandboxView> { return Promise.resolve({ contractVersion: "1.0", clientRequestId: request.clientRequestId, requestDigest: request.requestDigest, executionRef: "execution.concurrent", state: "RUNNING", receiptRef: null }); }
  override async execution(_taskId: string, currentGrant: string, clientRequestId: string): Promise<SandboxView> {
    this.executionGrants.push(currentGrant);
    if (this.executionGrants.length === 1) {
      this.resolveEntered(); await this.oldRequestRelease;
      throw new EngineProblem(401, problem("TASK_GRANT_EXPIRED", "authorization", "expired", true));
    }
    return { contractVersion: "1.0", clientRequestId, requestDigest: digestObject({ argv: ["yanban-runner", "java", "Sort.java"], inputs: [{ path: "Sort.java", sha256: fileHash }], timeoutMillis: 5000 }), executionRef: "execution.concurrent", state: "SUCCEEDED", receiptRef: "receipt.concurrent" };
  }
  override receipt(): Promise<Receipt> { return Promise.resolve({ contractVersion: "1.0", receiptRef: "receipt.concurrent", executionRef: "execution.concurrent", status: "SUCCEEDED", exitCode: 0, stdout: { text: "ok", truncated: false, originalBytes: 2 }, stderr: { text: "", truncated: false, originalBytes: 0 }, inputFingerprint: "d".repeat(64), inputs: [{ path: "Sort.java", sha256: fileHash, sizeBytes: 16 }], startedAt: new Date().toISOString(), finishedAt: new Date().toISOString() }); }
}

class ExpiredRecoveryGateway extends FakeGateway {
  submits = 0; polls = 0;
  override submit(_taskId: string, _grant: string, request: SandboxRequest): Promise<SandboxView> { this.submits += 1; return Promise.resolve({ contractVersion: "1.0", clientRequestId: request.clientRequestId, requestDigest: request.requestDigest, executionRef: "execution.expired", state: "RUNNING", receiptRef: null }); }
  override execution(): Promise<SandboxView> { this.polls += 1; throw new Error("must not poll past the persisted deadline"); }
}

function tool(name: string, args: unknown): ModelResponse { return { content: null, toolCalls: [{ id: "provider-call", name, arguments: JSON.stringify(args) }] }; }
function sandboxTool(timeoutMillis = 5000): ModelResponse { return tool("execute_in_sandbox", { argv: ["yanban-runner", "java", "Sort.java"], inputs: [{ path: "Sort.java", sha256: fileHash }], timeoutMillis }); }

function submission(): TaskSubmission {
  const authority = { runMode: "PERSISTENT_PLAN_EXECUTE" as const, sessionRef: "session.test", project: { projectId: "1", projectVersion }, instruction: "Compile and run Sort.java", permissions: { readProject: true as const, writeWorkspace: false as const, executeSandbox: true as const }, model: { provider: "test", model: "test-model" } };
  return { contractVersion: "1.0", taskId, requestDigest: digestObject(authority), authority, gateway: { taskGrant: grant, expiresAt: new Date(Date.now() + 60_000).toISOString() } };
}

function persistedTask(): PersistedTask {
  const request = submission(); const now = new Date().toISOString();
  return {
    authority: request.authority,
    view: { contractVersion: "1.0", taskId, requestDigest: request.requestDigest, state: "running", lastSequence: 0, pendingQuestionId: null, deliverySequence: null, terminalSequence: null, error: null, createdAt: now, updatedAt: now },
    messages: [{ role: "system", content: "test" }, { role: "user", content: request.authority.instruction }],
    modelCalls: 1,
    metrics: { startedAt: now, promptTokens: 0, completionTokens: 0 },
    receiptRefs: [], pendingCalls: [], nextPendingCall: 0, acceptedAnswers: []
  };
}

async function createEngine(provider: ModelProvider, gateway: GatewayClient, directory?: string): Promise<AgentEngine> {
  const root = directory ?? await temporaryDirectory();
  const engine = new AgentEngine({ store: new TaskStore(root), provider, gateway, validator: new ContractValidator(contractDirectory), sleep: async () => undefined });
  await engine.initialize(); return engine;
}

async function temporaryDirectory(): Promise<string> { const directory = await mkdtemp(resolve(tmpdir(), "paperagent-codex-")); directories.push(directory); return directory; }
async function waitFor(predicate: () => boolean): Promise<void> { for (let index = 0; index < 100; index += 1) { if (predicate()) return; await new Promise((resolvePromise) => setTimeout(resolvePromise, 5)); } throw new Error("condition not reached"); }
