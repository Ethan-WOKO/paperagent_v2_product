import { afterEach, describe, expect, it, vi } from "vitest";
import { OpenAiCompatibleProvider } from "../src/provider.js";
import type { ModelRequest } from "../src/types.js";

describe("OpenAiCompatibleProvider thinking tool calls", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("persists and echoes provider reasoning_content on the next request", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        choices: [{ message: {
          content: null,
          reasoning_content: "bounded provider reasoning",
          tool_calls: [{ id: "provider-call", function: { name: "list_project_files", arguments: "{}" } }]
        } }]
      }), { status: 200, headers: { "content-type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        choices: [{ message: { content: "done" } }]
      }), { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const provider = new OpenAiCompatibleProvider(
      { deepseek: { baseUrl: "https://provider.invalid/v1", apiKeyEnv: "TEST_KEY" } },
      { TEST_KEY: "test-only" }
    );
    const base: ModelRequest = {
      provider: "deepseek",
      model: "deepseek-v4-pro",
      messages: [{ role: "user", content: "inspect" }],
      tools: [],
      maxOutputTokens: 4096,
      signal: new AbortController().signal
    };

    const first = await provider.complete(base);
    expect(first.reasoningContent).toBe("bounded provider reasoning");

    await provider.complete({
      ...base,
      messages: [
        { role: "assistant", content: null, reasoningContent: first.reasoningContent!, toolCalls: first.toolCalls },
        { role: "tool", content: "{}", toolCallId: "provider-call" }
      ]
    });
    const secondBody = JSON.parse(String(fetchMock.mock.calls[1]![1]!.body)) as { messages: Array<Record<string, unknown>> };
    expect(secondBody.messages[0]!.reasoning_content).toBe("bounded provider reasoning");
  });
});
