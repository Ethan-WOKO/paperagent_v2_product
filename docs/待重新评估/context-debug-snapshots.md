# Context Debug Snapshots

> 文档分类：**待重新评估**。现有表/API 可复用；V2 多次上下文构建必须设计显式 revision，不能静默覆盖一次 turn 的快照。

## Purpose

Context snapshots are an internal debugging surface for short-term memory, session summary, RAG context, tool trace context, and runtime guard injection. They are intentionally metadata-only. The system must not persist the full model prompt, user paper text, uploaded file content, or raw RAG snippets in this table.

## Stored Metadata

Each chat turn may write one `agent_context_snapshots` row:

- `turn_id`, `session_id`, `user_id`: ownership and lookup keys.
- `trace_id`: request trace id from `X-Trace-Id` / MDC.
- `sections_json`: serialized `AgentContextSection` list, such as `session_summary`, `recent_messages`, `rag_context`, `tool_trace_context`, and `runtime_identity_guard`.
- `dropped_items_json`: serialized `AgentContextDroppedItem` list explaining window, budget, or protocol drops.
- `raw_message_count`, `normalized_message_count`, `context_message_count`, `estimated_characters`: aggregate counts for diagnosing context assembly.

The table has a unique index on `turn_id`; if a future flow rebuilds context multiple times inside a turn, that work should create an explicit revision model rather than overwriting silently.

## Backend API

The current internal API is scoped under the owned agent session:

- `GET /api/v1/agent/sessions/{sessionId}/context-snapshots?limit=20`
  Lists recent context snapshots for the current user and session.
- `GET /api/v1/agent/sessions/{sessionId}/turns/{turnId}/context-snapshot`
  Returns one snapshot for the current user, session, and turn.

Both endpoints verify session ownership. A user cannot query another user's session snapshots.

## Future `/debug/context` UI

The debug page should read from the list endpoint first, then call the turn endpoint when the operator expands a row. The UI should show:

- trace id, turn id, creation time, and aggregate counts;
- section order with item count and estimated characters;
- dropped item reasons;
- warnings when `session_summary`, `rag_context`, or future `long_term_memory` sections are missing from a turn that was expected to include them.

The UI should not display raw prompt text by default because this table deliberately does not store it. If a future admin-only raw prompt capture is needed, it must use a separate gated table with retention, masking, and explicit opt-in.
