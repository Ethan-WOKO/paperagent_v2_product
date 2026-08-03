# Long-Term Memory CRUD

## Scope

This slice creates the durable user-facing long-term memory store. It does not implement automatic memory extraction, vector retrieval, reranking, or context injection. Those belong to later issues after users can inspect and correct the stored facts.

Long-term memory is enabled by default as a product capability, but the first stable control surface is transparency rather than a global user-facing off switch.

## Data Model

`agent_long_term_memories` stores cross-session memories with these governance fields:

- `user_id`: owner. All APIs are scoped to the authenticated user.
- `project_id`: nullable reserved field for future project-scoped memory.
- `scope`: first version defaults to `USER`; future values may include `PROJECT`, `SESSION`, and `TASK`.
- `memory_type`: examples include `PREFERENCE`, `RESEARCH_PROFILE`, `STYLE`, `FACT`, and `WARNING`.
- `source_type`, `source_ref_id`: records where a memory came from.
- `confidence`: 0 to 1 confidence score.
- `status`: `ACTIVE`, `DELETED`, or `SUPERSEDED`.
- `tags_json`: lightweight filtering/debug tags.
- `supersedes_memory_id`, `superseded_by_memory_id`: audit chain for user corrections.

Deletion is soft deletion. Correcting a memory creates a new `ACTIVE` row and marks the old row `SUPERSEDED`; this preserves auditability and prevents silent overwrites.

## User API

The current settings/debug API is:

- `GET /api/v1/settings/memory?status=ACTIVE&limit=50`
- `GET /api/v1/settings/memory/{memoryId}`
- `POST /api/v1/settings/memory`
- `PUT /api/v1/settings/memory/{memoryId}`
- `DELETE /api/v1/settings/memory/{memoryId}`

`status=ALL` is intended for debugging and audit views. Normal user-facing lists should default to `ACTIVE`.

## UI Placement

The user-visible management entry should be placed under:

```text
/settings/memory
```

The page should allow users to view, correct, and delete their memories. The internal debug view can reuse the same API with `status=ALL` and combine it with `/debug/context` to explain which memories were later retrieved or injected.

## Later Work

The next implementation slice should add retrieval and context injection:

1. retrieve only `ACTIVE` memories;
2. rank by scope, memory type, confidence, recency, and future similarity score;
3. record memory retrieval/injection in context snapshots;
4. exclude `DELETED` and `SUPERSEDED` memories from model context.
