# Long-term memory context injection

Issue: #45

## Goal

Inject user-owned long-term memories into ordinary agent chat context after the CRUD foundation exists.

This implementation is intentionally narrow:

- Retrieve only `ACTIVE` memories owned by the current user.
- Exclude deleted, superseded, blank, or low-confidence memories.
- Use keyword/tag relevance first, then confidence and recency for ranking.
- Format matched memories into a separate `long_term_memory` context section.
- Expose hit count, omitted count, and short debug previews through context snapshot sections.

## Non-goals

- No automatic memory extraction from chat turns.
- No vector retrieval.
- No LangChain4j memory adapter yet.
- No project-scoped memory retrieval beyond preserving the existing `project_id` field.

## Runtime flow

1. `AgentService` receives a user message.
2. `LongTermMemoryRetrievalService` retrieves relevant user memories for the message text.
3. `AgentContextBuilder` injects the returned memory context after the session summary and before RAG context.
4. `AgentContextSnapshotService` persists section metadata for debugging.
5. If retrieval fails, chat degrades to an empty long-term memory context and continues.

## Retrieval rules

- Candidate source: `AgentLongTermMemoryRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(..., ACTIVE, ...)`.
- Minimum confidence: `0.30`.
- Query tokens are matched against memory content and tags.
- A memory must have at least one content or tag match before confidence/recency can improve its score.
- Maximum formatted hits: 5.
- Maximum memory context budget: 1600 characters.

## Debugging

The `long_term_memory` context section note includes:

- hit count
- candidate count
- omitted count
- minimum confidence
- short memory previews

This is for the authenticated user's debug view only and should stay concise.
