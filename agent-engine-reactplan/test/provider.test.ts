import { describe, expect, it } from "vitest";
import type { GatewayClient, ModelCompletionRequest } from "../src/gateway.js";
import { GatewayModelProvider } from "../src/provider.js";
import type { ModelResponse } from "../src/types.js";

describe("GatewayModelProvider", () => {
  it("uses only the task grant and product gateway, with a deterministic semantic digest", async () => {
    let observed: ModelCompletionRequest | undefined;
    const response: ModelResponse = { content: "ok", toolCalls: [], finishReason: "stop", usage: { promptTokens: 2, completionTokens: 1 } };
    const gateway = {
      completeModel: async (_taskId: string, _grant: string, request: ModelCompletionRequest) => { observed = request; return response; }
    } as unknown as GatewayClient;
    const provider = new GatewayModelProvider(gateway);
    const result = await provider.complete({
      provider: "deepseek", model: "deepseek-v4-flash",
      messages: [{ role: "user", content: "hello" }], tools: [],
      maxOutputTokens: 4096, signal: new AbortController().signal
    }, {
      taskId: `task.${"a".repeat(64)}`, taskGrant: "grant-value",
      clientRequestId: `model.${"b".repeat(64)}`
    });

    expect(result).toEqual(response);
    expect(observed?.requestDigest).toMatch(/^[a-f0-9]{64}$/);
    expect(JSON.stringify(observed)).not.toContain("grant-value");
    expect(JSON.stringify(observed)).not.toContain("apiKey");
  });
});
