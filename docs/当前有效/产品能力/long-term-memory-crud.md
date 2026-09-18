# Long-Term Memory CRUD

## Scope

This capability provides the durable user-facing long-term memory store, governed context injection, and review-first conversation distillation. Vector retrieval and reranking remain out of scope.

Long-term memory is enabled by default as a product capability, but the first stable control surface is transparency rather than a global user-facing off switch.

## Data Model

`agent_long_term_memories` stores cross-session memories with these governance fields:

- `user_id`: owner. All APIs are scoped to the authenticated user.
- `project_id`: nullable Project authority; required for `PROJECT` memories.
- `scope`: `USER` or `PROJECT`.
- `memory_type`: examples include `PREFERENCE`, `RESEARCH_PROFILE`, `STYLE`, `FACT`, and `WARNING`.
- `source_type`, `source_ref_id`: records where a memory came from.
- `confirmation_status`, `confirmed_at`, `confirmed_source`: user-review state.
- `provenance_type`, `provenance_ref`: auditable confirmation provenance.
- `project_version`: exact immutable Project revision for Project-scoped memory.
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
- `POST /api/v1/settings/memory/{memoryId}/confirm`
- `POST /api/v1/settings/memory/{memoryId}/reject`
- `PUT /api/v1/settings/memory/{memoryId}/expiry`

`status=ALL` is intended for debugging and audit views. Normal user-facing lists should default to `ACTIVE`.

## UI Placement

The user-visible management entry should be placed under:

```text
/settings/memory
```

The page should allow users to view, correct, and delete their memories. The internal debug view can reuse the same API with `status=ALL` and combine it with `/debug/context` to explain which memories were later retrieved or injected.

## Conversation Distillation

Issue #209 adds optional, incremental distillation of owner-qualified `agent_messages` from Workspace and Project sessions:

- Automatic distillation is off by default and can be enabled on `/settings/memory`.
- Users can start a one-time manual run even while automatic distillation is off.
- A job freezes the latest owner-qualified message ID and total pending message count at request time.
  It processes that whole cut in bounded batches (at most 12 user assessments by default), returning to
  PENDING between batches for the worker to continue. Messages arriving after the frozen cut wait for
  the next run. Duplicate manual starts reuse the active job rather than extending its cut.
- Model output must use the strict candidate schema, cite messages in that window, include user evidence, and preserve the exact USER or PROJECT authority.
- Valid candidates are stored as `ACTIVE` but `UNCONFIRMED` rows with source type `LLM_DISTILLED`.
- Candidates are visible and editable in the existing memory ledger. They cannot enter Agent context until the user confirms or corrects them.
- Each batch's candidate writes, cumulative job counts, and cursor checkpoint share one transaction.
  Only reaching the frozen cut marks the job SUCCEEDED and updates the last successful run time.
  Model, validation, or persistence failure leaves the failing batch's cursor unchanged; previously
  committed batches remain durable. A later manual run resumes at that checkpoint. Reclaimed lease
  attempts fence late successes and failures; successful batches reset the per-batch retry budget.
  The page shows processed/total messages and cumulative created memories while the worker continues.
- Raw prompts, model responses, and API keys are not stored in the job tables.

The distillation API is:

- `GET /api/v1/settings/memory/distillation`
- `PUT /api/v1/settings/memory/distillation`
- `POST /api/v1/settings/memory/distillation/jobs`
- `GET /api/v1/settings/memory/distillation/jobs/{jobId}`

## Later Work

Future slices may add vector similarity and reranking while preserving the existing ownership, ProjectVersion, confirmation, and provenance gates.
