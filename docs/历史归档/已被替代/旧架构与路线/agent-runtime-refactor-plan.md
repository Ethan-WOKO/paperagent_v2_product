# Agent Runtime Refactor Plan

> 文档分类：**已被替代**。V2 core 已完成导入并持续演进，当前架构以 `docs/当前有效/架构设计/v2-agent-core-integration.md` 为准。

> **Status: HISTORICAL / SUPERSEDED IN PART (2026-07-27).**
> The current Agent-core architecture and dependency direction are defined by
> `docs/当前有效/架构设计/v2-agent-core-integration.md` and the frozen contracts under
> `agent-v2/`. This document remains useful as product-experience background,
> but proposals to evolve the legacy runtime as the target core, retain
> `PlanAgentService` as the target plan strategy, or add top-level strategies
> beyond `DIRECT` and `PERSISTENT_PLAN_EXECUTE` are superseded.
>
## Background

The current project already has useful agent building blocks:

- `HarnessEngine` can run a model/tool loop and enforce basic tool-call budgets.
- `ToolRegistry` and `ToolExecutor` provide a simple tool abstraction.
- `search_web`, `recommend_literature`, `search_knowledge`, and MCP proxy tools exist.
- `PlanAgentService` supports plan creation, DAG-style step execution, limited parallelism, repair steps, degradation, events, and verification.
- `PlanStepVerifier` can judge whether a plan step result satisfies success criteria.
- The chat UI can stream assistant chunks and can show process messages.
- User model configuration supports DeepSeek, GLM, and custom models.

However, the system still behaves closer to "chat with tools" than a goal-driven agent runtime. The main symptoms discussed:

- Stable common-knowledge questions may trigger web search unnecessarily.
- Intermediate model text before tool calls can appear as a separate assistant bubble.
- Tool selection is mostly model-driven instead of runtime-policy-driven.
- Conversation history is passed as raw messages without rolling summaries or memory retrieval.
- Tool metadata lacks when-to-use rules, output schema, cost, latency, permissions, visibility, and budgets.
- Plan mode exists, but it is not naturally selected by a runtime according to task complexity.
- Normal chat runs do not have a first-class `AgentRun` trace comparable to plan events.

## Target Architecture

All user turns should enter a unified `AgentRuntime`:

```text
User turn
-> AgentRuntime
-> IntentClassifier
-> ContextBuilder
-> ToolPolicyEngine
-> StrategySelector
   -> DirectAnswerStrategy
   -> SingleStepReactStrategy
   -> PlanExecuteStrategy
-> Reflection / Verification
-> FinalAnswerComposer
-> MemoryUpdater
-> AgentRunTrace
```

### Runtime Principles

1. The main chat area should show one final assistant answer per user turn.
2. Tool calls, retrieval, retries, and reflection are process events, not normal answer bubbles.
3. Tools are capabilities selected by runtime policy, not default actions exposed on every turn.
4. Search is used when the answer needs current, external, user-requested, or evidence-backed information.
5. Stable background knowledge should normally be answered directly.
6. Complex tasks should use planning; simple tasks should not pay planning overhead.
7. Every run should have traceable decisions: strategy, selected tools, context sources, model calls, tool calls, latency, and errors.
8. Memory should be retrieved and summarized, not appended as unbounded raw history.

## Phase 1: Product Experience And Tool Policy

### Goals

- Stop showing intermediate assistant/tool preambles as separate user-visible answer bubbles.
- Add a runtime tool policy so `search_web` is not available by default for every question.
- Limit normal chat search to one focused web call unless the user explicitly starts a complex research task.
- Keep final answers streamable.

### Proposed Changes

- Add `AgentRuntimeService` as the entry point for normal chat turns.
- Add `AgentStrategy` values:
  - `DIRECT`
  - `SINGLE_STEP_REACT`
  - `PLAN_EXECUTE`
- Add `ToolPolicyEngine`:
  - Detect explicit search requests.
  - Detect current/latest/time-sensitive requests.
  - Detect source/citation/URL requirements.
  - Detect scholarly-literature requests.
  - Detect private-knowledge-base requests.
  - Return a selected tool allowlist and tool-call budgets.
- Add a no-visible-preamble rule for model/tool loops:
  - If an assistant message contains tool calls, do not stream or persist its natural-language content as a final answer.
  - Stream final synthesis only after tools complete.
- Persist tool/process details as trace events, not assistant bubbles.

### Acceptance Criteria

- Asking "西瓜的功效" returns one assistant bubble and does not call `search_web`.
- Asking "搜索一下西瓜的最新研究" may call `search_web` or `recommend_literature`, but still returns one assistant bubble.
- Asking "帮我找 5 篇 RAG 论文" uses `recommend_literature`.
- Tool preamble text like "我来搜索一下" is not persisted as a separate assistant answer.
- Normal chat defaults to at most one web search call.

## Phase 2: Conversation Summary And Memory

### Goals

- Avoid sending all raw history to the model.
- Maintain compact session summaries.
- Retrieve relevant memory on demand.

### Proposed Changes

- Add `AgentConversationSummary`:
  - `sessionId`
  - `userId`
  - `summary`
  - `messageCursor`
  - `updatedAt`
- Add `AgentMemory`:
  - `userId`
  - `scope`: `USER`, `PROJECT`, `SESSION`, `TASK`
  - `content`
  - `tags`
  - `source`
  - `confidence`
  - `createdAt`
  - `updatedAt`
- Add `MemoryService`:
  - Rolling session summarization.
  - Preference extraction.
  - Relevant memory retrieval by keyword first, vector later.
  - Secret redaction before memory write.
- Add `ContextBuilder`:
  - System instructions.
  - Runtime identity guard.
  - Selected skill instructions.
  - Relevant memory.
  - Session summary.
  - Recent raw messages.
  - Current user message.

### Acceptance Criteria

- Long conversations use summary plus recent raw turns instead of full history.
- Summary updates after successful turns.
- Relevant older facts can be injected into later turns.
- Memory can be disabled per session or user.

## Phase 3: Tool Manifest System

### Goals

Upgrade tools from simple callable functions into policy-aware capabilities.

### Proposed Tool Manifest Fields

```json
{
  "name": "search_web",
  "description": "Search public web results for current or externally verifiable information.",
  "whenToUse": [
    "User explicitly asks to search, browse, verify, or cite sources",
    "Question depends on current/latest/time-sensitive facts",
    "Answer needs URLs, prices, policies, release notes, model lists, or dated evidence"
  ],
  "whenNotToUse": [
    "Stable common knowledge",
    "Opinion or drafting tasks without external evidence requirement"
  ],
  "inputSchema": {},
  "outputSchema": {},
  "cost": "MEDIUM",
  "latency": "MEDIUM",
  "sideEffects": "READ_ONLY",
  "permission": "NETWORK",
  "maxCallsPerTurn": 1,
  "visibleToUser": false,
  "cacheable": true,
  "timeoutMs": 12000
}
```

### Proposed Changes

- Extend `ToolDefinition` or add `ToolManifest`.
- Keep model-facing tool schema separate from runtime policy metadata.
- Add tool result normalization and concise evidence formatting.
- Add tool timeout and retry metadata.
- Add tool visibility control.

### Acceptance Criteria

- Runtime can select tools using manifest policy metadata.
- Tool metadata is inspectable from an admin/debug endpoint.
- Search, literature, knowledge, MCP, and echo tools all have manifests.

## Phase 4: Unified Run Trace

### Goals

Normal chat and plan execution should both produce observable run traces.

### Proposed Entities

- `AgentRun`
  - id, sessionId, userId, strategy, status, model, provider, startedAt, finishedAt
- `AgentRunEvent`
  - runId, eventType, payloadJson, createdAt
- `AgentToolRun`
  - reuse existing entity, connect it to `runId` and `stepId`

### Acceptance Criteria

- Every chat turn has one run id.
- UI can show trace without polluting the answer bubble.
- Logs can answer: why a tool was selected, how long it took, and whether it failed.

## Phase 5: Plan-Execute, Reflection, And Subtasks

### Goals

- Use planning only when task complexity warrants it.
- Add reflection to decide whether the result is enough.
- Keep plan execution resumable and inspectable.

### Proposed Changes

- Move strategy selection into `AgentRuntime`.
- Preserve existing `PlanAgentService`, but make it a strategy implementation.
- Add `ReflectionService` for:
  - evidence sufficiency
  - answer completeness
  - over-search detection
  - final-answer quality
- Add optional subtask workers later for read-heavy parallel research.

### Acceptance Criteria

- Direct questions avoid plan overhead.
- Multi-step research tasks automatically suggest or enter plan mode.
- Failed steps can repair or degrade with traceable reasoning.
- Final answer includes limitations when evidence is insufficient.

## Testing Plan

### Normal Functional Tests

Required gates:

- Backend compile.
- Frontend build.
- Existing focused integration tests.
- WebSocket streaming tests.
- Settings/model selection tests.
- Plan service tests.
- No two assistant bubbles for one normal chat turn.
- Tool policy tests.

### Agent Capability Tests

Run with `deepseek-v4-flash` for lower cost.

Minimum benchmark set:

1. Stable common knowledge: no web search, correct answer.
2. Explicit web search: one web search, sourced answer.
3. Latest/current query: uses web search and cites evidence.
4. Literature query: uses literature search.
5. Private KB query: uses knowledge search when enabled.
6. Tool failure: graceful fallback.
7. Long conversation: uses summary context.
8. Multi-step task: creates and executes a plan.
9. Verification failure: triggers repair or degradation.
10. Identity question: backend direct answer, no model call.

Pass threshold: at least 85%.

## Immediate Implementation Scope

The first implementation batch should be intentionally narrow:

1. Add `ToolPolicyEngine` and selected tool allowlists for normal chat.
2. Prevent intermediate tool-call assistant content from being streamed/persisted as final user-visible content.
3. Add lightweight run trace events for normal chat.
4. Add a basic session summary entity/service or, if migration scope is too large for the batch, add the interfaces and context builder first.
5. Add focused tests for tool policy and single-bubble behavior.

This batch improves user experience and creates the path for memory and full runtime without destabilizing the existing plan engine.
