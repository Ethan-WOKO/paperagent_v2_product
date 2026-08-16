import type { ChatMessage, ModelProvider, ModelRequest, ModelResponse, ModelToolCall } from "./types.js";
import { EngineProblem, problem } from "./util.js";

interface ProviderConfig { baseUrl: string; apiKeyEnv: string }

export class OpenAiCompatibleProvider implements ModelProvider {
  constructor(private readonly configurations: Record<string, ProviderConfig>, private readonly environment: NodeJS.ProcessEnv = process.env) {}

  static fromEnvironment(environment: NodeJS.ProcessEnv = process.env): OpenAiCompatibleProvider {
    let configurations: Record<string, ProviderConfig> = {};
    try { configurations = JSON.parse(environment.AGENT_ENGINE_PROVIDERS_JSON ?? "{}"); }
    catch { throw new Error("AGENT_ENGINE_PROVIDERS_JSON must be valid JSON"); }
    return new OpenAiCompatibleProvider(configurations, environment);
  }

  async complete(request: ModelRequest): Promise<ModelResponse> {
    const configuration = this.configurations[request.provider];
    if (!configuration) throw new EngineProblem(502, problem("MODEL_PROVIDER_NOT_CONFIGURED", "model", "Requested model provider is not configured"));
    const key = this.environment[configuration.apiKeyEnv];
    if (!key) throw new EngineProblem(502, problem("MODEL_CREDENTIAL_UNAVAILABLE", "model", "Model provider credential is unavailable"));
    let response: Response;
    try {
      response = await fetch(`${configuration.baseUrl.replace(/\/$/, "")}/chat/completions`, {
        method: "POST",
        headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
        body: JSON.stringify({ model: request.model, messages: request.messages.map(toWireMessage), tools: request.tools, tool_choice: "auto", max_tokens: request.maxOutputTokens }),
        signal: request.signal
      });
    } catch (error) {
      if (request.signal.aborted) throw error;
      throw new EngineProblem(502, problem("MODEL_TRANSPORT_FAILED", "model", "Model provider request failed", true));
    }
    if (!response.ok) throw new EngineProblem(502, problem("MODEL_PROVIDER_REJECTED", "model", `Model provider returned HTTP ${response.status}`, response.status >= 500));
    const body = await response.json() as { choices?: Array<{ message?: { content?: string | null; tool_calls?: Array<{ id?: string; function?: { name?: string; arguments?: string } }> } }>; usage?: { prompt_tokens?: number; completion_tokens?: number } };
    const message = body.choices?.[0]?.message;
    if (!message) throw new EngineProblem(502, problem("MODEL_RESPONSE_INVALID", "model", "Model provider returned no assistant message"));
    const toolCalls: ModelToolCall[] = (message.tool_calls ?? []).map((call, index) => ({ id: call.id ?? `provider-${index}`, name: call.function?.name ?? "", arguments: call.function?.arguments ?? "{}" }));
    return { content: message.content ?? null, toolCalls, ...(body.usage ? { usage: { promptTokens: body.usage.prompt_tokens ?? 0, completionTokens: body.usage.completion_tokens ?? 0 } } : {}) };
  }
}

function toWireMessage(message: ChatMessage): Record<string, unknown> {
  const result: Record<string, unknown> = { role: message.role, content: message.content };
  if (message.toolCallId) result.tool_call_id = message.toolCallId;
  if (message.toolCalls) result.tool_calls = message.toolCalls.map((call) => ({ id: call.id, type: "function", function: { name: call.name, arguments: call.arguments } }));
  return result;
}
