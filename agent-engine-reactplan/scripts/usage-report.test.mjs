import { test } from "node:test";
import assert from "node:assert/strict";
import { usageReport } from "./usage-report.mjs";

test("deduplicates calls, excludes replay usage, and preserves unavailable counters", () => {
  const row = { event: "reactplan_model_context", taskId: "test", callId: "one", replayed: false,
    promptTokens: 100, completionTokens: 5, cacheHitTokens: 80, cacheMissTokens: 20, durationMillis: 10 };
  const lines = [row, row, { ...row, replayed: true }, { ...row, callId: "two", cacheHitTokens: null, cacheMissTokens: null },
    { ...row, callId: "three", replayed: true }, { ...row, callId: "four", replayed: null }];
  const [result] = usageReport(lines.map(JSON.stringify).join("\n")).tasks;
  assert.equal(result.promptTokens, 200);
  assert.equal(result.knownCacheHitTokens, 80);
  assert.equal(result.cacheUnknownCalls, 1);
  assert.equal(result.replayOnlyCalls, 1);
  assert.equal(result.usageUnknownCalls, 1);
  assert.equal(result.observedCalls, 4);
});
