import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { AgentEngine } from "../src/engine.js";
import type { GatewayClient, SandboxRequest } from "../src/gateway.js";
import { TaskStore } from "../src/store.js";
import type { FileList, FileRead, ModelProvider, ModelRequest, ModelResponse, Receipt, RegisteredToolCatalog, RegisteredToolResult, SandboxView, TaskSubmission } from "../src/types.js";
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
    expect(JSON.stringify(provider.requests)).not.toContain(taskId);
    expect(JSON.stringify(provider.requests)).not.toContain("receipt.1");
    expect(gateway.maximumConcurrent).toBe(1);
    expect(provider.requests.every((request) => request.maxOutputTokens === 4096)).toBe(true);
    expect(JSON.stringify(provider.requests[0]?.tools)).toContain("project_search");
  });

  it("injects only four bounded completed turns from the same Project session", async () => {
    const responses: ModelResponse[] = [];
    for (let index = 1; index <= 5; index += 1) {
      responses.push({ content: `history conclusion ${index}`, toolCalls: [] });
    }
    responses.push({ content: "other session conclusion", toolCalls: [] });
    responses.push({ content: "other project conclusion", toolCalls: [] });
    responses.push({ content: "continued from bounded history", toolCalls: [] });
    const provider = new ScriptedProvider(responses);
    const engine = await createEngine(provider, new FakeGateway());

    for (let index = 1; index <= 5; index += 1) {
      const request = submissionFor(index, "session.same", `history instruction ${index}`);
      await engine.submit(request);
      await waitFor(() => engine.get(request.taskId).state === "succeeded");
    }
    const other = submissionFor(90, "session.other", "private other instruction");
    await engine.submit(other);
    await waitFor(() => engine.get(other.taskId).state === "succeeded");
    const otherProject = submissionFor(92, "session.same", "private other project instruction", "2");
    await engine.submit(otherProject);
    await waitFor(() => engine.get(otherProject.taskId).state === "succeeded");
    const current = submissionFor(91, "session.same", "continue the previous task");
    await engine.submit(current);
    await waitFor(() => engine.get(current.taskId).state === "succeeded");

    const request = provider.requests.at(-1)!;
    const history = request.messages.find((message) =>
      message.role === "user" && message.content?.startsWith("Historical conversation data:"));
    expect(history).toBeTruthy();
    const turns = JSON.parse(history!.content!.slice(history!.content!.indexOf("\n") + 1)) as Array<{ instruction: string; conclusion: string }>;
    expect(turns).toHaveLength(4);
    expect(turns.at(-1)).toMatchObject({ instruction: "history instruction 5", conclusion: "history conclusion 5" });
    expect(history!.content).not.toContain("private other instruction");
    expect(history!.content).not.toContain("private other project instruction");
    expect(request.messages.at(-1)).toMatchObject({ role: "user", content: "Current task: continue the previous task" });
    expect(JSON.stringify(turns)).not.toContain("toolCallId");
  });

  it("rejects an unobserved Project filename and lets the model repair its conclusion", async () => {
    const provider = new ScriptedProvider([
      tool("list_project_files", {}),
      { content: "The pom.xml declares a missing dependency.", toolCalls: [] },
      { content: "Only Sort.java was observed in the Project manifest; dependency configuration was not inspected.", toolCalls: [] }
    ]);
    const engine = await createEngine(provider, new FakeGateway());
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "succeeded");

    const delivery = (await engine.events(taskId)).find((event) => event.type === "delivery");
    expect(delivery?.type === "delivery" ? delivery.conclusion : "").not.toContain("pom.xml");
    expect(provider.requests).toHaveLength(3);
    expect(provider.requests[2]!.messages.some((message) =>
      message.role === "user" && message.content?.includes("unobserved Project files: pom.xml"))).toBe(true);
  });

  it("fails closed after two grounding repairs instead of publishing unsupported files", async () => {
    const provider = new ScriptedProvider([
      tool("list_project_files", {}),
      { content: "pom.xml proves the dependency is present.", toolCalls: [] },
      { content: "build.gradle proves the dependency is present.", toolCalls: [] },
      { content: "settings.xml proves the dependency is present.", toolCalls: [] }
    ]);
    const engine = await createEngine(provider, new FakeGateway());
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "failed");

    expect(engine.get(taskId).error).toMatchObject({ code: "MODEL_GROUNDING_FAILED", category: "model" });
    expect((await engine.events(taskId)).some((event) => event.type === "delivery")).toBe(false);
  });

  it("restores the frozen recent conversation instead of rebuilding it on replay", async () => {
    const directory = await temporaryDirectory();
    const historyRequest = submissionFor(70, "session.persisted", "inspect the source");
    const first = await createEngine(
      new ScriptedProvider([{ content: "the source was inspected", toolCalls: [] }]),
      new FakeGateway(), directory);
    await first.submit(historyRequest);
    await waitFor(() => first.get(historyRequest.taskId).state === "succeeded");

    const current = submissionFor(71, "session.persisted", "continue the inspection");
    const interrupted = await createEngine(new NeverProvider(), new FakeGateway(), directory);
    await interrupted.submit(current);
    await waitFor(() => interrupted.get(current.taskId).state === "running");

    const resumedProvider = new ScriptedProvider([{ content: "continued from frozen context", toolCalls: [] }]);
    const resumed = await createEngine(resumedProvider, new FakeGateway(), directory);
    await resumed.submit(current);
    await waitFor(() => resumed.get(current.taskId).state === "succeeded");
    const historicalData = resumedProvider.requests[0]!.messages.find((message) =>
      message.role === "user" && message.content?.startsWith("Historical conversation data:"));
    expect(historicalData?.content).toContain("the source was inspected");
    expect(historicalData?.content).toContain("inspect the source");
  });

  it("invokes a frozen read-only registered product tool without leaking its output into events", async () => {
    const provider = new ScriptedProvider([
      tool("project_search", { query: "order-service", maxResults: 20 }),
      { content: "Located the requested module.", toolCalls: [] }
    ]);
    const engine = await createEngine(provider, new FakeGateway());
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "succeeded");
    const events = await engine.events(taskId);
    expect(events.filter((event) => event.type === "tool" && event.state === "requested")
      .map((event) => event.type === "tool" ? event.name : "")).toEqual(["project.read"]);
    expect(JSON.stringify(events)).not.toContain("PRIVATE_SEARCH_RESULT");
    expect(JSON.stringify(provider.requests)).toContain("PRIVATE_SEARCH_RESULT");
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
    const provider = new ScriptedProvider([tool("ask_user", { question: "Which class should I validate?" }), { content: "Validated the selected class.", toolCalls: [] }]);
    const engine = await createEngine(provider, new FakeGateway());
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "waiting_user");
    const questionId = engine.get(taskId).pendingQuestionId!;
    const answer = { contractVersion: "1.0" as const, clientRequestId: `answer.${"x".repeat(16)}`, questionId, answer: "Sort.java", answerDigest: sha256("Sort.java") };
    await engine.answer(taskId, answer);
    await engine.answer(taskId, answer);
    await expect(engine.answer(taskId, { ...answer, clientRequestId: `answer.${"y".repeat(16)}`, answer: "Other.java", answerDigest: sha256("Other.java") })).rejects.toMatchObject({ status: 409, problem: { code: "QUESTION_ANSWER_CONFLICT" } });
    await waitFor(() => engine.get(taskId).state === "succeeded");
    expect(provider.requests).toHaveLength(2);
  });

  it("loads persisted events and resumes only after an exact replay supplies a fresh grant", async () => {
    const directory = await temporaryDirectory();
    const first = await createEngine(new NeverProvider(), new FakeGateway(), directory);
    await first.submit(submission());
    await waitFor(() => first.get(taskId).state === "running");
    const secondProvider = new ScriptedProvider([{ content: "Recovered without recreating the task.", toolCalls: [] }]);
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

  it("uses the frozen polling schedule and delivers a trustworthy failed compile", async () => {
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
    const delivery = (await engine.events(taskId)).find((event) => event.type === "delivery");
    expect(delivery?.type === "delivery" ? delivery.receiptRefs : []).toEqual(["receipt.failed"]);
  });

  it("returns a policy-rejected sandbox request to the model so it can repair argv", async () => {
    const gateway = new RejectingOnceGateway();
    const provider = new ScriptedProvider([
      tool("execute_in_sandbox", { argv: ["java", "Sort.java"], inputs: [{ path: "nested/Sort.java", sha256: fileHash }], timeoutMillis: 5000 }),
      tool("execute_in_sandbox", { argv: ["yanban-runner", "java", "nested/Sort.java"], inputs: [{ path: "nested/Sort.java", sha256: fileHash }], timeoutMillis: 5000 }),
      { content: "Recovered with an allowed command.", toolCalls: [] }
    ]);
    const engine = await createEngine(provider, gateway);
    await engine.submit(submission());
    await waitFor(() => engine.get(taskId).state === "succeeded");
    const sandboxEvents = (await engine.events(taskId)).filter(
      (event) => event.type === "tool" && event.name === "sandbox.execute");
    expect(sandboxEvents.some((event) => event.type === "tool"
      && event.state === "failed" && event.outputSummary?.includes("SANDBOX_COMMAND_DENIED"))).toBe(true);
    expect(gateway.submissions).toBe(2);
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

class FakeGateway implements GatewayClient {
  concurrent = 0; maximumConcurrent = 0;
  private async operation<T>(value: T): Promise<T> { this.concurrent += 1; this.maximumConcurrent = Math.max(this.maximumConcurrent, this.concurrent); await Promise.resolve(); this.concurrent -= 1; return value; }
  tools(taskIdValue: string): Promise<RegisteredToolCatalog> { return this.operation({ contractVersion: "1.0", taskId: taskIdValue, projectVersion, catalogDigest: "c".repeat(64), tools: [{ type: "function", function: { name: "project_search", description: "Search the frozen Project.", parameters: { type: "object", properties: { query: { type: "string" } }, required: ["query"] } } }] }); }
  invoke(_taskId: string, _grant: string, request: { callId: string; toolName: string; requestDigest: string }): Promise<RegisteredToolResult> { return this.operation({ contractVersion: "1.0", callId: request.callId, toolName: request.toolName, requestDigest: request.requestDigest, success: true, output: { projectVersion, hits: [{ path: "services/order-service/pom.xml", line: "PRIVATE_SEARCH_RESULT" }] }, errorCode: null, errorMessage: null, retryable: false, evidenceRefs: ["project:1:search"], version: projectVersion }); }
  list(taskIdValue: string): Promise<FileList> { return this.operation({ contractVersion: "1.0", taskId: taskIdValue, projectVersion, files: [{ path: "Sort.java", sizeBytes: 16, sha256: fileHash, mediaType: "text/x-java" }] }); }
  read(): Promise<FileRead> { return this.operation({ contractVersion: "1.0", path: "Sort.java", sizeBytes: 16, sha256: fileHash, mediaType: "text/x-java", encoding: "utf-8", content: "SECRET_FILE_BODY", truncated: false }); }
  submit(_taskId: string, _grant: string, request: SandboxRequest): Promise<SandboxView> { return this.operation({ contractVersion: "1.0", clientRequestId: request.clientRequestId, requestDigest: request.requestDigest, executionRef: "execution.1", state: "SUCCEEDED", receiptRef: "receipt.1" }); }
  execution(_taskId: string, _grant: string, _clientRequestId: string): Promise<SandboxView> { throw new Error("terminal submit should not poll"); }
  receipt(): Promise<Receipt> { return this.operation({ contractVersion: "1.0", receiptRef: "receipt.1", executionRef: "execution.1", status: "SUCCEEDED", exitCode: 0, stdout: { text: "ok", truncated: false, originalBytes: 2 }, stderr: { text: "", truncated: false, originalBytes: 0 }, inputFingerprint: "d".repeat(64), inputs: [{ path: "Sort.java", sha256: fileHash, sizeBytes: 16 }], startedAt: new Date().toISOString(), finishedAt: new Date().toISOString() }); }
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

class RejectingOnceGateway extends FakeGateway {
  submissions = 0;
  override submit(taskIdValue: string, grantValue: string, request: SandboxRequest): Promise<SandboxView> {
    this.submissions += 1;
    if (this.submissions === 1) {
      return Promise.reject(new EngineProblem(400,
        problem("SANDBOX_COMMAND_DENIED", "request", "rejected")));
    }
    return super.submit(taskIdValue, grantValue, request);
  }
}

function tool(name: string, args: unknown): ModelResponse { return { content: null, toolCalls: [{ id: "provider-call", name, arguments: JSON.stringify(args) }] }; }

function submission(): TaskSubmission {
  return submissionFor("a", "session.test", "Compile and run Sort.java");
}

function submissionFor(identity: number | string, sessionRef: string, instruction: string, projectId = "1"): TaskSubmission {
  const suffix = String(identity).repeat(64).slice(0, 64);
  const authority = { runMode: "PERSISTENT_PLAN_EXECUTE" as const, sessionRef, project: { projectId, projectVersion }, instruction, permissions: { readProject: true as const, writeWorkspace: false as const, executeSandbox: true as const }, model: { provider: "test", model: "test-model" } };
  return { contractVersion: "1.0", taskId: `task.${suffix}`, requestDigest: digestObject(authority), authority, gateway: { taskGrant: grant, expiresAt: new Date(Date.now() + 60_000).toISOString() } };
}

async function createEngine(provider: ModelProvider, gateway: GatewayClient, directory?: string): Promise<AgentEngine> {
  const root = directory ?? await temporaryDirectory();
  const engine = new AgentEngine({ store: new TaskStore(root), provider, gateway, validator: new ContractValidator(contractDirectory), sleep: async () => undefined });
  await engine.initialize(); return engine;
}

async function temporaryDirectory(): Promise<string> { const directory = await mkdtemp(resolve(tmpdir(), "paperagent-reactplan-")); directories.push(directory); return directory; }
async function waitFor(predicate: () => boolean): Promise<void> { for (let index = 0; index < 100; index += 1) { if (predicate()) return; await new Promise((resolvePromise) => setTimeout(resolvePromise, 5)); } throw new Error("condition not reached"); }
