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
});

async function readBody(request: import("node:http").IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.from(chunk));
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : null;
}
