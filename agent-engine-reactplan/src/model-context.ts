import { AIMessage, HumanMessage, SystemMessage, ToolMessage, type BaseMessage } from "@langchain/core/messages";
import type { ChatMessage, ModelToolCall, PersistedTask } from "./types.js";

function object(text: string | null | undefined): Record<string, any> | undefined {
  try {
    const value: unknown = JSON.parse(text ?? "");
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, any> : undefined;
  } catch { return undefined; }
}

/** LangChain messages at the prompt boundary, retaining raw call arguments for replay. */
export function toLangChainMessage(message: ChatMessage): BaseMessage {
  const content = message.content ?? "";
  switch (message.role) {
    case "system": return new SystemMessage(content);
    case "user": return new HumanMessage(content);
    case "tool": return new ToolMessage({ content, tool_call_id: message.toolCallId! });
    case "assistant": return new AIMessage({ content,
      tool_calls: (message.toolCalls ?? []).flatMap(call => {
        const args = object(call.arguments);
        return args ? [{ id: call.id, name: call.name, args, type: "tool_call" as const }] : [];
      }),
      additional_kwargs: { gatewayToolCalls: message.toolCalls ?? [], contentWasNull: message.content === null } });
  }
}

export function fromLangChainMessage(message: BaseMessage): ChatMessage {
  if (typeof message.content !== "string") throw new Error("Only gateway text messages are supported");
  switch (message.type) {
    case "system": return { role: "system", content: message.content };
    case "human": return { role: "user", content: message.content };
    case "tool": return { role: "tool", content: message.content, toolCallId: (message as ToolMessage).tool_call_id };
    case "ai": {
      const calls = message.additional_kwargs.gatewayToolCalls as ChatMessage["toolCalls"];
      return { role: "assistant", content: message.additional_kwargs.contentWasNull ? null : message.content,
        ...(calls?.length ? { toolCalls: calls } : {}) };
    }
    default: throw new Error("Unsupported gateway message role");
  }
}

/** Compact only the model projection. Durable history and authoritative evidence stay intact. */
export function projectModelContext(task: Pick<PersistedTask, "messages" | "observations">) {
  const messages = structuredClone(task.messages);
  const results = new Map<ModelToolCall, Record<string, any> | undefined>();
  const calls = new Map<ChatMessage, ModelToolCall>();
  let pending = new Map<string, ModelToolCall>();
  for (const message of messages) {
    if (message.role === "assistant") pending = new Map((message.toolCalls ?? []).map(call => [call.id, call]));
    if (message.role === "tool") {
      const call = pending.get(message.toolCallId!);
      if (call) { results.set(call, object(message.content)); calls.set(message, call); }
    }
  }
  for (const message of messages) {
    for (const call of message.toolCalls ?? []) {
      if (call.name !== "write_workspace_file") continue;
      const args = object(call.arguments);
      const result = results.get(call);
      if (!args || !result || result.path !== args.path || !/^[a-f0-9]{64}$/.test(result.afterSha256 ?? "")) continue;
      const sourceHints = typeof args.content === "string" && String(result.path).endsWith(".java")
        ? { imports: [...args.content.matchAll(/\bimport\s+(?:static\s+)?([\w.*]+)\s*;/g)].map(m => m[1]).slice(0, 40),
          hasMainSignature: /\bstatic\s+void\s+main\s*\(/.test(args.content) } : undefined;
      // The write succeeded. Repeatedly transmitting its entire content cannot re-execute it.
      call.arguments = JSON.stringify({ ...args, content: `[Written content omitted from prompt; path=${result.path}; afterSha256=${result.afterSha256}; syntaxHints=${JSON.stringify(sourceHints ?? {})}. Hints are not proof of compilation or dependency completeness. Read this exact current file if its content is needed.]` });
    }
    if (message.role !== "tool") continue;
    const result = object(message.content);
    const call = calls.get(message);
    if (!result || !call) continue;
    if (call.name === "search_tools" && Array.isArray(result.tools)) {
      message.content = JSON.stringify({ ...result, tools: result.tools.map((t: any) => ({
        name: t.name, description: String(t.description ?? "").split(". ")[0]!.slice(0, 180)
      })) });
    }
    if (call.name === "read_project_file" && typeof result.content === "string") {
      const change = task.observations.workspaceChanges.find(c => c.path === result.path);
      if (change && change.afterSha256 !== result.sha256) {
        const { content: _content, ...metadata } = result;
        message.content = JSON.stringify({ ...metadata, contentOmitted: true,
          notice: `Superseded file content omitted; current hash=${change.afterSha256}. Re-read if needed. This old read is not evidence of current content.` });
      }
    }
  }
  const native = messages.map(toLangChainMessage);
  const projected = native.map(fromLangChainMessage);
  return { messages: projected,
    originalChars: JSON.stringify(task.messages).length,
    projectedChars: JSON.stringify(projected).length };
}
