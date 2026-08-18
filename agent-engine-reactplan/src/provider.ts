import type { GatewayClient, ModelCompletionRequest } from "./gateway.js";
import type { ModelInvocationContext, ModelProvider, ModelRequest, ModelResponse } from "./types.js";
import { digestObject } from "./util.js";

export class GatewayModelProvider implements ModelProvider {
  constructor(private readonly gateway: GatewayClient) {}

  complete(request: ModelRequest, context: ModelInvocationContext): Promise<ModelResponse> {
    if (!this.gateway.completeModel) throw new Error("Product model gateway is unavailable");
    const semantic = {
      contractVersion: "1.0" as const,
      clientRequestId: context.clientRequestId,
      provider: request.provider,
      model: request.model,
      messages: request.messages,
      tools: request.tools,
      maxOutputTokens: request.maxOutputTokens
    };
    const body: ModelCompletionRequest = { ...semantic, requestDigest: digestObject(semantic) };
    return this.gateway.completeModel(context.taskId, context.taskGrant, body, request.signal);
  }
}
