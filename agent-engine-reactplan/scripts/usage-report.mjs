import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

// Model-call facts are deduplicated by task/call ID. Replays are not new provider usage.
export function usageReport(lines) {
  const tasks = new Map();
  const calls = new Map();
  for (const line of lines.split(/\r?\n/)) {
    let row;
    try { row = JSON.parse(line.trim()); } catch { continue; }
    if (row.event !== "reactplan_model_context" || !row.taskId || !row.callId) continue;
    const key = `${row.taskId}/${row.callId}`;
    if (!calls.has(key) || calls.get(key).replayed === true && row.replayed === false) calls.set(key, row);
  }
  for (const row of calls.values()) {
    const task = tasks.get(row.taskId) ?? { taskId: row.taskId, observedCalls: 0, replayOnlyCalls: 0,
      usageUnknownCalls: 0, cacheUnknownCalls: 0, promptTokens: 0, completionTokens: 0,
      knownCacheHitTokens: 0, knownCacheMissTokens: 0, modelRoundTripMillis: 0 };
    tasks.set(row.taskId, task);
    task.observedCalls++;
    if (row.replayed === true) { task.replayOnlyCalls++; continue; }
    if (row.replayed !== false || !count(row.promptTokens) || !count(row.completionTokens)) {
      task.usageUnknownCalls++; continue;
    }
    task.promptTokens += row.promptTokens;
    task.completionTokens += row.completionTokens;
    task.modelRoundTripMillis += Number.isFinite(row.durationMillis) ? row.durationMillis : 0;
    if (count(row.cacheHitTokens) && count(row.cacheMissTokens)) {
      task.knownCacheHitTokens += row.cacheHitTokens;
      task.knownCacheMissTokens += row.cacheMissTokens;
    } else task.cacheUnknownCalls++;
  }
  return { note: "Observed usage only, not a bill or a cache-hit guarantee. Missing counters are unavailable; replays and duplicate call IDs do not add usage.", tasks: [...tasks.values()] };
}
function count(value) { return Number.isSafeInteger(value) && value >= 0; }
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (!process.argv[2]) throw new Error("Usage: node scripts/usage-report.mjs <engine-jsonl.log>");
  console.log(JSON.stringify(usageReport(readFileSync(process.argv[2], "utf8")), null, 2));
}
