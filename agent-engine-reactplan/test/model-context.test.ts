import { describe, expect, it } from "vitest";
import { fromLangChainMessage, projectModelContext, toLangChainMessage } from "../src/model-context.js";
import type { ChatMessage, TaskObservations } from "../src/types.js";

const hash = "b".repeat(64);
const oldHash = "a".repeat(64);
const observations: TaskObservations = { manifestPaths: [], readFiles: [], toolPaths: [], sandboxRuns: [],
  workspaceRevision: 1, workspaceDiffObservedRevision: 1,
  workspaceChanges: [{ operation: "MODIFY", path: "Sort.java", beforeSha256: oldHash, afterSha256: hash }] };
const call = (name: string, args: unknown, id = "same-id"): ChatMessage => ({ role: "assistant", content: null,
  toolCalls: [{ id, name, arguments: JSON.stringify(args) }] });
const result = (value: unknown, id = "same-id"): ChatMessage => ({ role: "tool", toolCallId: id, content: JSON.stringify(value) });

describe("LangChain model projection", () => {
  it("preserves native roles, exact IDs, raw JSON arguments and malformed calls for existing repair", () => {
    const messages: ChatMessage[] = [{ role: "system", content: "policy" }, { role: "user", content: "current task" },
      { role: "assistant", content: null, toolCalls: [{ id: "abc", name: "read_project_file", arguments: '{ "path": "Sort.java" }' }] },
      result({ error: "missing hash" }, "abc"),
      { role: "assistant", content: "", toolCalls: [{ id: "bad", name: "x", arguments: "{" }] }];
    expect(messages.map(toLangChainMessage).map(fromLangChainMessage)).toEqual(messages);
    expect(toLangChainMessage(messages[2]!).type).toBe("ai");
  });

  it("omits superseded bodies and confirmed writes without mutating history or losing hashes", () => {
    const messages: ChatMessage[] = [{ role: "user", content: "Add insertion sort" },
      call("read_project_file", { path: "Sort.java", expectedSha256: oldHash }),
      result({ path: "Sort.java", sha256: oldHash, content: "OLD_BODY".repeat(1000) }),
      call("write_workspace_file", { path: "Sort.java", baseSha256: oldHash, content: "NEW_BODY".repeat(1000) }),
      result({ path: "Sort.java", afterSha256: hash })];
    const original = structuredClone(messages);
    const projected = projectModelContext({ messages, observations });
    expect(messages).toEqual(original);
    expect(projected.projectedChars).toBeLessThan(projected.originalChars / 4);
    expect(JSON.stringify(projected.messages)).not.toContain("OLD_BODY");
    expect(JSON.stringify(projected.messages)).not.toContain("NEW_BODY");
    expect(JSON.stringify(projected.messages)).toContain(hash);
    expect(JSON.stringify(projected.messages)).toContain(oldHash);
    expect(projected.messages.filter(m => m.role === "tool").map(m => m.toolCallId)).toEqual(["same-id", "same-id"]);
  });

  it("never abbreviates failed writes, fresh reads or failure diagnostics", () => {
    const messages = [call("write_workspace_file", { path: "Sort.java", content: "KEEP_WRITE" }), result({ status: "REJECTED" }),
      call("read_project_file", { path: "Sort.java", expectedSha256: hash }), result({ path: "Sort.java", sha256: hash, content: "KEEP_READ", nextDocumentCursor: "v1:1:2" }),
      call("execute_in_sandbox", {}), result({ status: "FAILED", exitCode: 1, stderr: "offline dependency missing" })];
    expect(projectModelContext({ messages, observations }).messages).toEqual(messages);
  });

  it("bounds discovery descriptions while retaining every discovered name", () => {
    const messages = [call("search_tools", { group: "project" }), result({ tools: [
      { name: "read_project_file", description: "Read project. " + "long detail ".repeat(200) },
      { name: "write_workspace_file", description: "Write isolated candidate." }
    ] })];
    const projected = projectModelContext({ messages, observations });
    expect(projected.projectedChars).toBeLessThan(projected.originalChars);
    expect(projected.messages[1]!.content).toContain("read_project_file");
    expect(projected.messages[1]!.content).toContain("write_workspace_file");
  });
});
