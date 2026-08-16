import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
import { HttpGatewayClient, type SandboxRequest } from "../src/gateway.js";

const taskId = `task.${"a".repeat(64)}`;
const hash = "b".repeat(64);
const grant = "grant.integration-test-value";
const servers: ReturnType<typeof createServer>[] = [];

afterEach(async () => { await Promise.all(servers.splice(0).map((server) => new Promise<void>((resolve) => server.close(() => resolve())))); });

describe("HttpGatewayClient", () => {
  it("consumes all five Java gateway operations with task-bound bearer authentication", async () => {
    const requests: Array<{ method: string; path: string; authorization: string | undefined; body: unknown }> = [];
    const server = createServer(async (request, response) => {
      const body = await readBody(request);
      requests.push({ method: request.method!, path: request.url!, authorization: request.headers.authorization, body });
      response.setHeader("content-type", "application/json");
      const base = `/internal/v1/agent-engine/tasks/${taskId}`;
      if (request.url === `${base}/workspace/files`) return response.end(JSON.stringify({ contractVersion: "1.0", taskId, projectVersion: hash, files: [{ path: "Sort.java", sizeBytes: 4, sha256: hash, mediaType: "text/x-java-source" }] }));
      if (request.url === `${base}/workspace/read`) return response.end(JSON.stringify({ contractVersion: "1.0", path: "Sort.java", sizeBytes: 4, sha256: hash, mediaType: "text/x-java-source", encoding: "utf-8", content: "code", truncated: false }));
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
    await client.list(taskId, grant, signal);
    await client.read(taskId, grant, "Sort.java", hash, signal);
    await client.submit(taskId, grant, sandbox, signal);
    await client.execution(taskId, grant, sandbox.clientRequestId, signal);
    await client.receipt(taskId, grant, "receipt.1", signal);
    expect(requests.map(({ method, path }) => `${method} ${path}`)).toEqual([
      `GET /internal/v1/agent-engine/tasks/${taskId}/workspace/files`,
      `POST /internal/v1/agent-engine/tasks/${taskId}/workspace/read`,
      `POST /internal/v1/agent-engine/tasks/${taskId}/sandbox-executions`,
      `GET /internal/v1/agent-engine/tasks/${taskId}/sandbox-executions/call.abcdefghijklmnop`,
      `GET /internal/v1/agent-engine/tasks/${taskId}/receipts/receipt.1`
    ]);
    expect(requests.every((request) => request.authorization === `Bearer ${grant}`)).toBe(true);
    expect(requests[1]?.body).toEqual({ contractVersion: "1.0", path: "Sort.java", expectedSha256: hash });
    expect(requests[2]?.body).toEqual(sandbox);
  });

  it("fails closed for malformed success bodies and invalid problem projections", async () => {
    let calls = 0;
    const server = createServer((_request, response) => {
      calls += 1;
      response.setHeader("content-type", "application/json");
      if (calls === 1) {
        response.statusCode = 500;
        return response.end(JSON.stringify({ contractVersion: "1.0", code: "BROKEN", category: "forged", message: "must-not-propagate", retryable: true }));
      }
      response.end("not-json");
    });
    servers.push(server); await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const client = new HttpGatewayClient(`http://127.0.0.1:${(server.address() as AddressInfo).port}`);
    const signal = new AbortController().signal;
    await expect(client.list(taskId, grant, signal)).rejects.toMatchObject({ status: 500, problem: { code: "GATEWAY_REQUEST_FAILED", category: "tool", message: "Product tool gateway returned HTTP 500" } });
    await expect(client.list(taskId, grant, signal)).rejects.toMatchObject({ status: 502, problem: { code: "GATEWAY_RESPONSE_INVALID", category: "tool" } });
  });

  it("preserves only a valid upstream code and category while redacting its message", async () => {
    const server = createServer((_request, response) => {
      response.statusCode = 401;
      response.setHeader("content-type", "application/json");
      response.end(JSON.stringify({ contractVersion: "1.0", code: "TASK_GRANT_EXPIRED", category: "authorization", message: "secret path and token", retryable: true, sourceRef: "internal.secret" }));
    });
    servers.push(server); await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const client = new HttpGatewayClient(`http://127.0.0.1:${(server.address() as AddressInfo).port}`);
    await expect(client.list(taskId, grant, new AbortController().signal)).rejects.toMatchObject({ status: 401, problem: { code: "TASK_GRANT_EXPIRED", category: "authorization", message: "Product tool gateway rejected the request", retryable: true } });
    try { await client.list(taskId, grant, new AbortController().signal); } catch (error) { expect(JSON.stringify(error)).not.toContain("secret path and token"); expect(JSON.stringify(error)).not.toContain("internal.secret"); }
  });
});

async function readBody(request: import("node:http").IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.from(chunk));
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : null;
}
