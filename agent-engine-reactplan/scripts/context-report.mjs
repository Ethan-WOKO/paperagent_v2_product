// Offline, read-only replay of a supplied checkpoint. Never invokes a model or tool.
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { projectModelContext } from "../dist/model-context.js";

export function contextReport(checkpoint) {
  const rows = [];
  const changes = new Map();
  let pending = new Map();
  for (let i = 0; i < checkpoint.messages.length; i++) {
    const message = checkpoint.messages[i];
    if (message.role === "tool" && pending.get(message.toolCallId) === "write_workspace_file") {
      let result;
      try { result = JSON.parse(message.content); } catch { continue; }
      if (typeof result.path === "string" && /^[a-f0-9]{64}$/.test(result.afterSha256 ?? "")) {
        changes.set(result.path, { path: result.path, afterSha256: result.afterSha256 });
      }
    }
    if (message.role !== "assistant") continue;
    const projection = projectModelContext({
      messages: checkpoint.messages.slice(0, i),
      observations: { ...checkpoint.observations, workspaceChanges: [...changes.values()] }
    });
    rows.push({ turn: rows.length + 1,
      tools: (message.toolCalls ?? []).map(call => call.name),
      originalChars: projection.originalChars, projectedChars: projection.projectedChars });
    pending = new Map((message.toolCalls ?? []).map(call => [call.id, call.name]));
  }
  const originalChars = rows.reduce((sum, row) => sum + row.originalChars, 0);
  const projectedChars = rows.reduce((sum, row) => sum + row.projectedChars, 0);
  return { rows, originalChars, projectedChars,
    reductionPercent: originalChars ? Math.round((1 - projectedChars / originalChars) * 1000) / 10 : 0,
    unit: "message JSON characters; excludes extra evidence ledger and tool schemas; not billed tokens" };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (!process.argv[2]) throw new Error("Usage: node scripts/context-report.mjs <checkpoint.json>");
  const input = JSON.parse(await readFile(process.argv[2], "utf8"));
  console.log(JSON.stringify(contextReport(input.checkpoint ?? input), null, 2));
}
