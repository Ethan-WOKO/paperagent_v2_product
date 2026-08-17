import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { ContractValidator } from "../src/validation.js";
import { canonicalJson } from "../src/util.js";

const contract = resolve(process.cwd(), "../agent-engine-contract");

describe("frozen contract fixtures", () => {
  const validator = new ContractValidator(contract);
  const positive: Array<[string, string]> = [
    ["task-submission", "task-submission.json"], ["task-view", "task-view.json"], ["task-answer", "task-answer.json"], ["receipt", "receipt.json"],
    ["gateway-fileList", "gateway-file-list.json"], ["gateway-fileReadRequest", "gateway-file-read-request.json"], ["gateway-sandboxSubmit", "gateway-sandbox-submit.json"]
  ];
  for (const [schema, file] of positive) it(`consumes ${file}`, async () => {
    const value = JSON.parse(await readFile(resolve(contract, "conformance/fixtures/positive", file), "utf8"));
    expect(() => validator.validate(schema, value)).not.toThrow();
  });
  it("consumes every ordered event", async () => {
    const events = JSON.parse(await readFile(resolve(contract, "conformance/fixtures/positive/events.json"), "utf8")) as unknown[];
    for (const event of events) expect(() => validator.validate("task-event", event)).not.toThrow();
  });
  it("orders canonical object keys by Unicode code point", () => {
    expect(canonicalJson({ "\uE000": 1, "😀": 2 })).toBe('{"":1,"😀":2}');
  });
});
