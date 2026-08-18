import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it } from "vitest";
import { AgentEngine } from "../src/engine.js";
import type { GatewayClient } from "../src/gateway.js";
import { createEngineServer } from "../src/server.js";
import { TaskStore } from "../src/store.js";
import type { ModelProvider, ModelRequest, ModelResponse, TaskSubmission } from "../src/types.js";
import { digestObject } from "../src/util.js";
import { ContractValidator } from "../src/validation.js";

const token = "service-token-that-is-at-least-32-chars";
const taskId = `task.${"1".repeat(64)}`;
const servers: Array<ReturnType<typeof createEngineServer>> = [];
afterEach(async () => { await Promise.all(servers.splice(0).map((server) => new Promise<void>((resolveClose) => server.close(() => resolveClose())))); });

describe("Engine HTTP control plane", () => {
  it("requires the service bearer and replays SSE strictly after Last-Event-ID", async () => {
    const directory = await mkdtemp(resolve(tmpdir(), "paperagent-server-"));
    const gateway = {
      tools: () => Promise.resolve({ contractVersion: "1.0" as const, taskId,
        projectVersion: "2".repeat(64), catalogDigest: "3".repeat(64), tools: [] })
    } as unknown as GatewayClient;
    const engine = new AgentEngine({ store: new TaskStore(directory), provider: new FinalProvider(), gateway, validator: new ContractValidator(resolve(process.cwd(), "../agent-engine-contract")) });
    await engine.initialize();
    const server = createEngineServer(engine, token); servers.push(server);
    await new Promise<void>((resolveListen) => server.listen(0, "127.0.0.1", resolveListen));
    const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    expect((await fetch(`${origin}/health`)).status).toBe(401);
    const health = await fetch(`${origin}/health`, { headers: { authorization: `Bearer ${token}` } });
    expect(health.status).toBe(200);
    expect(await health.json()).toEqual({ status: "UP" });
    expect((await fetch(`${origin}/v1/tasks/${taskId}`)).status).toBe(401);
    const submitted = await fetch(`${origin}/v1/tasks`, { method: "POST", headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: JSON.stringify(submission()) });
    expect(submitted.status).toBe(202);
    await waitFor(async () => (await authenticatedJson(`${origin}/v1/tasks/${taskId}`) as { state: string }).state === "succeeded");
    const stream = await fetch(`${origin}/v1/tasks/${taskId}/events`, { headers: { authorization: `Bearer ${token}`, "Last-Event-ID": "1" } });
    const text = await stream.text();
    expect(text).toContain("id: 2"); expect(text).not.toContain("id: 1\n");
    expect(text).toContain('"state":"succeeded"');
  });
});

class FinalProvider implements ModelProvider { complete(_request: ModelRequest): Promise<ModelResponse> { return Promise.resolve({ content: "done", toolCalls: [] }); } }

function submission(): TaskSubmission {
  const authority = { runMode: "PERSISTENT_PLAN_EXECUTE" as const, sessionRef: "session.http", project: { projectId: "9", projectVersion: "2".repeat(64) }, instruction: "Report status", permissions: { readProject: true as const, writeWorkspace: false as const, executeSandbox: true as const }, model: { provider: "test", model: "test" } };
  return { contractVersion: "1.0", taskId, requestDigest: digestObject(authority), authority, gateway: { taskGrant: "g".repeat(40), expiresAt: new Date(Date.now() + 60_000).toISOString() } };
}

async function authenticatedJson(url: string): Promise<unknown> { return await (await fetch(url, { headers: { authorization: `Bearer ${token}` } })).json(); }
async function waitFor(predicate: () => Promise<boolean>): Promise<void> { for (let index = 0; index < 100; index += 1) { if (await predicate()) return; await new Promise((resolveWait) => setTimeout(resolveWait, 5)); } throw new Error("condition not reached"); }
