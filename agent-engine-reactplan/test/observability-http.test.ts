import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { expect, it, vi } from "vitest";
import { AgentEngine } from "../src/engine.js";
import { HttpGatewayClient } from "../src/gateway.js";
import { GatewayModelProvider } from "../src/provider.js";
import { createEngineServer } from "../src/server.js";
import { TaskStore } from "../src/store.js";
import { ContractValidator } from "../src/validation.js";
import { digestObject } from "../src/util.js";

it.each([false, true].flatMap(compactContext => [false, true].map(batchToolLoading => ({ compactContext, batchToolLoading }))))
  ("runs an isolated HTTP task with frozen experiments %j and observable cache counters", async experiments => {
    const taskId = `task.${"9".repeat(64)}`;
    const token = "isolated-http-smoke-token-32-characters";
    const requests: any[] = [];
    let attempts = 0;
    const gatewayServer = createServer(async (req, res) => {
      expect(req.headers.authorization).toBe(`Bearer ${token}`);
      res.setHeader("content-type", "application/json");
      if (req.url?.endsWith("/tools")) {
        res.end(JSON.stringify({ contractVersion: "1.0", taskId, projectVersion: "a".repeat(64), catalogDigest: "b".repeat(64), tools: [] }));
      } else if (req.url?.endsWith("/workspace/files")) {
        res.end(JSON.stringify({ contractVersion: "1.0", taskId, projectVersion: "a".repeat(64), files: [] }));
      } else if (req.url?.endsWith("/model-completions")) {
        let body = "";
        for await (const part of req) body += part;
        const request = JSON.parse(body);
        const { requestDigest, ...semantic } = request;
        expect(requestDigest).toBe(digestObject(semantic));
        if (++attempts === 1) { res.statusCode = 503; res.end(JSON.stringify({ retryable: true })); return; }
        requests.push(request);
        res.end(JSON.stringify({ content: requests.length === 1 ? null : "No files in the test fixture.",
          toolCalls: requests.length === 1 ? [{ id: "fixture-list", name: "list_project_files", arguments: "{}" }] : [],
          usage: { promptTokens: 100, completionTokens: 5, cacheHitTokens: requests.length === 1 ? null : 80,
            cacheMissTokens: requests.length === 1 ? null : 20 }, replayed: false }));
      } else { res.statusCode = 404; res.end("{}"); }
    });
    const output = vi.spyOn(process.stdout, "write").mockImplementation(() => true);
    let server: Server | undefined;
    try {
      const gateway = new HttpGatewayClient(await listen(gatewayServer), { sleep: async () => undefined });
      const store = new TaskStore(await mkdtemp(resolve(tmpdir(), "paperagent-cache-smoke-")));
      const engine = new AgentEngine({ store, gateway, provider: new GatewayModelProvider(gateway), experiments,
        validator: new ContractValidator(resolve(process.cwd(), "../agent-engine-contract")) });
      await engine.initialize();
      server = createEngineServer(engine, token);
      const origin = await listen(server);
      const authority = { runMode: "PERSISTENT_PLAN_EXECUTE", sessionRef: "isolated.fixture", instruction: "List test files",
        project: { projectId: "999999", projectVersion: "a".repeat(64) },
        permissions: { readProject: true, writeWorkspace: false, executeSandbox: true }, model: { provider: "fixture", model: "fixture" } };
      const accepted = await fetch(`${origin}/v1/tasks`, { method: "POST",
        headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
        body: JSON.stringify({ contractVersion: "1.0", taskId, authority, requestDigest: digestObject(authority),
          gateway: { taskGrant: token, expiresAt: new Date(Date.now() + 60_000).toISOString() } }) });
      expect(accepted.status).toBe(202);
      const events = await (await fetch(`${origin}/v1/tasks/${taskId}/events`, { headers: { authorization: `Bearer ${token}` } })).text();
      expect(events).toContain('"state":"succeeded"');
      expect(requests).toHaveLength(2);
      expect(requests[1].messages.slice(0, requests[0].messages.length)).toEqual(requests[0].messages);
      const logs = output.mock.calls.flatMap(([line]) => String(line).trim().split("\n")).map(line => JSON.parse(line));
      const usage = logs.filter(row => row.event === "reactplan_model_context");
      expect(usage.map(row => row.cacheHitTokens)).toEqual([null, 80]);
      expect(usage.map(row => row.cacheMissTokens)).toEqual([null, 20]);
      expect(usage.every(row => row.batchToolLoading === experiments.batchToolLoading)).toBe(true);
      expect(logs.some(row => row.event === "reactplan_gateway_attempt" && row.attempt === 2)).toBe(true);
      expect(JSON.stringify(logs)).not.toContain(token);
      expect((await store.loadAll())[0]!.experiments).toEqual(experiments);
    } finally {
      output.mockRestore();
      if (server) await close(server);
      await close(gatewayServer);
    }
  });

async function listen(server: Server): Promise<string> {
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}
async function close(server: Server) { await new Promise<void>(resolve => server.close(() => resolve())); }
