import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
import { HttpGatewayClient, type SandboxRequest } from "../src/gateway.js";
import { HttpTaskStore } from "../src/store.js";
import type { PersistedTask, TaskEvent } from "../src/types.js";

const taskId = `task.${"a".repeat(64)}`;
const hash = "b".repeat(64);
const grant = "grant.integration-test-value";
const servers: ReturnType<typeof createServer>[] = [];

afterEach(async () => { await Promise.all(servers.splice(0).map((server) => new Promise<void>((resolve) => server.close(() => resolve())))); });

describe("HttpGatewayClient", () => {
  it("consumes the Java gateway operations with task-bound bearer authentication", async () => {
    const requests: Array<{ method: string; path: string; authorization: string | undefined; body: unknown }> = [];
    const server = createServer(async (request, response) => {
      const body = await readBody(request) as any;
      requests.push({ method: request.method!, path: request.url!, authorization: request.headers.authorization, body });
      response.setHeader("content-type", "application/json");
      const base = `/internal/v1/agent-engine/tasks/${taskId}`;
      if (request.url === `${base}/model-completions`) return response.end(JSON.stringify({ contractVersion: "1.0", clientRequestId: `model.${"c".repeat(64)}`, requestDigest: hash, content: "done", toolCalls: [], finishReason: "stop", usage: { promptTokens: 3, completionTokens: 1 }, replayed: false }));
      if (request.url === `${base}/tools`) return response.end(JSON.stringify({ contractVersion: "1.0", taskId, projectVersion: hash, catalogDigest: hash, tools: [{ type: "function", function: { name: "project_search", description: "Search project", parameters: { type: "object" } } }] }));
      if (request.url === `${base}/tool-calls`) return response.end(JSON.stringify({ contractVersion: "1.0", callId: "call.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", toolName: "project_search", requestDigest: hash, success: true, output: { hits: [] }, errorCode: null, errorMessage: null, retryable: false, evidenceRefs: [], version: hash }));
      if (request.url === `${base}/workspace/files`) return response.end(JSON.stringify({ contractVersion: "1.0", taskId, projectVersion: hash, files: [{ path: "Sort.java", sizeBytes: 4, sha256: hash, mediaType: "text/x-java-source" }] }));
      if (request.url === `${base}/workspace/read`) return response.end(JSON.stringify({ contractVersion: "1.0", path: "Sort.java", sizeBytes: 4, sha256: hash, mediaType: "text/x-java-source", encoding: "utf-8", content: "code", truncated: false }));
      if (request.url === `${base}/workspace/write`) return response.end(JSON.stringify({ contractVersion: "1.0", clientRequestId: "call.abcdefghijklmnop", requestDigest: hash, replayed: false, operation: "MODIFY", path: "Sort.java", beforeSha256: hash, afterSha256: "c".repeat(64), sizeBytes: 7 }));
      if (request.url === `${base}/workspace/diff`) return response.end(JSON.stringify({ contractVersion: "1.0", taskId, projectVersion: hash, changed: true, entries: [{ operation: "MODIFY", path: "Sort.java", beforeSha256: hash, afterSha256: "c".repeat(64) }] }));
      if (request.url === `${base}/sandbox-executions/call.abcdefghijklmnop/cancel`) {
        return response.end(JSON.stringify({ contractVersion: "1.0", clientRequestId: "call.abcdefghijklmnop", requestDigest: hash, executionRef: "execution.1", state: "CANCELLED", receiptRef: "receipt.1" }));
      }
      if (request.url === `${base}/sandbox-executions` || request.url === `${base}/sandbox-executions/call.abcdefghijklmnop`) {
        response.statusCode = request.method === "POST" ? 202 : 200;
        return response.end(JSON.stringify({ contractVersion: "1.0", clientRequestId: "call.abcdefghijklmnop", requestDigest: hash, executionRef: "execution.1", state: "SUCCEEDED", receiptRef: "receipt.1" }));
      }
      if (request.url === `${base}/receipts/receipt.1`) return response.end(JSON.stringify({ contractVersion: "1.0", receiptRef: "receipt.1", executionRef: "execution.1", status: "SUCCEEDED", exitCode: 0, stdout: { text: "ok", truncated: false, originalBytes: 2 }, stderr: { text: "", truncated: false, originalBytes: 0 }, inputFingerprint: hash, inputs: [{ path: "Sort.java", sha256: hash, sizeBytes: 4 }], startedAt: "2026-08-16T00:00:00Z", finishedAt: "2026-08-16T00:00:01Z" }));
      response.statusCode = 404; response.end("{}");
    });
    servers.push(server); await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const client = new HttpGatewayClient(`http://127.0.0.1:${(server.address() as AddressInfo).port}`);
    const signal = new AbortController().signal;
    const sandbox: SandboxRequest = { contractVersion: "1.0", clientRequestId: "call.abcdefghijklmnop", requestDigest: hash, argv: ["yanban-runner", "java", "Sort.java"], inputs: [{ path: "Sort.java", sha256: hash }], timeoutMillis: 5000 };
    await client.completeModel!(taskId, grant, { contractVersion: "1.0", clientRequestId: `model.${"c".repeat(64)}`, requestDigest: hash, provider: "deepseek", model: "deepseek-v4-flash", messages: [{ role: "user", content: "hello" }], tools: [], maxOutputTokens: 4096 }, signal);
    await client.tools(taskId, grant, signal);
    await client.invoke(taskId, grant, { contractVersion: "1.0", callId: "call.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", toolName: "project_search", arguments: { query: "Sort" }, requestDigest: hash }, signal);
    await client.list(taskId, grant, signal);
    await client.read(taskId, grant, "Sort.java", hash, signal);
    const workspaceWrite = { contractVersion: "1.0" as const, clientRequestId: "call.abcdefghijklmnop", requestDigest: hash, operation: "MODIFY" as const, path: "Sort.java", baseSha256: hash, content: "changed" };
    await client.write(taskId, grant, workspaceWrite, signal);
    await client.diff(taskId, grant, signal);
    await client.submit(taskId, grant, sandbox, signal);
    await client.cancelExecution(taskId, grant, sandbox.clientRequestId, signal);
    await client.execution(taskId, grant, sandbox.clientRequestId, signal);
    await client.receipt(taskId, grant, "receipt.1", signal);
    expect(requests.map(({ method, path }) => `${method} ${path}`)).toEqual([
      `POST /internal/v1/agent-engine/tasks/${taskId}/model-completions`,
      `GET /internal/v1/agent-engine/tasks/${taskId}/tools`,
      `POST /internal/v1/agent-engine/tasks/${taskId}/tool-calls`,
      `GET /internal/v1/agent-engine/tasks/${taskId}/workspace/files`,
      `POST /internal/v1/agent-engine/tasks/${taskId}/workspace/read`,
      `POST /internal/v1/agent-engine/tasks/${taskId}/workspace/write`,
      `GET /internal/v1/agent-engine/tasks/${taskId}/workspace/diff`,
      `POST /internal/v1/agent-engine/tasks/${taskId}/sandbox-executions`,
      `POST /internal/v1/agent-engine/tasks/${taskId}/sandbox-executions/call.abcdefghijklmnop/cancel`,
      `GET /internal/v1/agent-engine/tasks/${taskId}/sandbox-executions/call.abcdefghijklmnop`,
      `GET /internal/v1/agent-engine/tasks/${taskId}/receipts/receipt.1`
    ]);
    expect(requests.every((request) => request.authorization === `Bearer ${grant}`)).toBe(true);
    expect(requests[2]?.body).toEqual({ contractVersion: "1.0", callId: "call.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", toolName: "project_search", arguments: { query: "Sort" }, requestDigest: hash });
    expect(requests[4]?.body).toEqual({ contractVersion: "1.0", path: "Sort.java", expectedSha256: hash });
    expect(requests[5]?.body).toEqual(workspaceWrite);
    expect(requests[7]?.body).toEqual(sandbox);
    expect(requests[8]?.body).toEqual({ contractVersion: "1.0" });
  });
});

describe("HttpTaskStore", () => {
  it("persists checkpoints and events and obtains a fresh recovery grant without storing it", async () => {
    const requests: Array<{ path: string; authorization: string | undefined; body: any }> = [];
    const checkpoint = { view: { taskId, requestDigest: hash } } as PersistedTask;
    const event = { contractVersion: "1.0", taskId, sequence: 1,
      occurredAt: "2026-08-18T00:00:00Z", type: "status", state: "running", error: null } as TaskEvent;
    const serviceToken = "s".repeat(40);
    const server = createServer(async (request, response) => {
      const body = await readBody(request) as any;
      requests.push({ path: request.url!, authorization: request.headers.authorization, body });
      response.setHeader("content-type", "application/json");
      if (request.url?.endsWith("/checkpoints") && request.method === "GET") return response.end(JSON.stringify({ contractVersion: "1.0", tasks: [{ checkpointRevision: 4, checkpoint }] }));
      if (request.url?.endsWith("/authorize-recovery")) return response.end(JSON.stringify({ contractVersion: "1.0", taskGrant: "g".repeat(40), expiresAt: "2099-01-01T00:00:00Z" }));
      if (request.url?.endsWith("/events") && request.method === "GET") return response.end(JSON.stringify({ contractVersion: "1.0", events: [event] }));
      if (request.url?.endsWith("/events")) return response.end(JSON.stringify({ contractVersion: "1.0", accepted: true }));
      if (request.url?.endsWith("/claims/next")) return response.end(JSON.stringify({ contractVersion: "1.0", task: null }));
      if (request.url?.endsWith("/checkpoints")) return response.end(JSON.stringify({ contractVersion: "1.0", checkpointRevision: body.expectedRevision === null ? 1 : 5 }));
      response.statusCode = 404; return response.end("{}");
    });
    servers.push(server); await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const store = new HttpTaskStore(`http://127.0.0.1:${(server.address() as AddressInfo).port}`, serviceToken);

    expect(await store.loadAll()).toEqual([checkpoint]);
    await store.save(checkpoint);
    await store.appendEvent(event);
    expect(await store.events(taskId)).toEqual([event]);
    expect((await store.authorizeRecovery(checkpoint)).taskGrant).toHaveLength(40);
    expect(await store.claimNext("engine.worker_test")).toBeNull();

    expect(requests.every((request) => request.authorization === `Bearer ${serviceToken}`)).toBe(true);
    expect(JSON.stringify(requests)).not.toContain('"taskGrant":"');
    expect(requests.find((request) => request.path.endsWith("/checkpoints")
      && request.body !== null)?.body.expectedRevision).toBe(4);
  });

  it("preserves the per-user queue rejection as an HTTP 429 engine problem", async () => {
    const server = createServer((_request, response) => {
      response.statusCode = 429;
      response.setHeader("content-type", "application/json");
      response.end(JSON.stringify({ message: "AGENT_USER_QUEUE_FULL" }));
    });
    servers.push(server); await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const store = new HttpTaskStore(
      `http://127.0.0.1:${(server.address() as AddressInfo).port}`, "s".repeat(40));

    await expect(store.create({ view: { taskId, requestDigest: hash } } as PersistedTask))
      .rejects.toMatchObject({
        status: 429, problem: { code: "AGENT_USER_QUEUE_FULL" }
      });
  });

  it("reconciles committed events into a newly claimed stale checkpoint", async () => {
    const serviceToken = "s".repeat(40);
    const checkpoint = {
      view: { taskId, requestDigest: hash, state: "running", lastSequence: 1,
        updatedAt: "2026-08-18T00:00:00Z", error: null, terminalSequence: null }
    } as PersistedTask;
    const events = [
      { contractVersion: "1.0", taskId, sequence: 1, occurredAt: "2026-08-18T00:00:00Z",
        type: "status", state: "running", error: null },
      { contractVersion: "1.0", taskId, sequence: 2, occurredAt: "2026-08-18T00:00:01Z",
        type: "tool", callId: "call.abcdefghijklmnop", name: "project.list",
        state: "succeeded", inputSummary: "manifest", outputSummary: "1 file", receiptRef: null }
    ] as TaskEvent[];
    const server = createServer(async (request, response) => {
      await readBody(request);
      response.setHeader("content-type", "application/json");
      if (request.url?.endsWith("/claims/next")) return response.end(JSON.stringify({
        contractVersion: "1.0", task: { checkpointRevision: 7, checkpoint,
          lease: { owner: "engine.worker_test", token: "lease", fence: 2 },
          taskGrant: "g".repeat(40), expiresAt: "2099-01-01T00:00:00Z",
          cancellationRequested: false }
      }));
      if (request.url?.endsWith("/events")) return response.end(JSON.stringify({ contractVersion: "1.0", events }));
      response.statusCode = 404; return response.end("{}");
    });
    servers.push(server); await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const store = new HttpTaskStore(
      `http://127.0.0.1:${(server.address() as AddressInfo).port}`, serviceToken);

    const claimed = await store.claimNext("engine.worker_test");

    expect(claimed?.checkpoint.view.lastSequence).toBe(2);
    expect(claimed?.checkpoint.view.updatedAt).toBe("2026-08-18T00:00:01Z");
  });
});

async function readBody(request: import("node:http").IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.from(chunk));
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : null;
}
