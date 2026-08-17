# Long-term memory context injection

Issue: #45

## ReAct Engine 全量快照（Issue #173，2026-08-17）

Project ReAct 链路采用更简单的首版策略：新 turn 开始时读取当前认证用户全部通过
现有治理检查的有效长期记忆，不按当前问题做关键词筛选。范围包括 USER 记忆，以及
与当前 Project 和冻结 ProjectVersion 精确匹配的 PROJECT 记忆。仍只接受已确认、
ACTIVE、未删除、未替代、未失效、未过期且不含凭证或本地绝对路径的记录。

Java 产品将结果组装成独立的 `long_term_memory` 数据区；每条记录包含 `id`、`scope`、
`memoryType`、`content` 和 `updatedAt`。它不属于任务 authority，不授予工具或项目权限。
Engine 在首次接受 task 时冻结并持久化这份快照，恢复时继续使用原快照；用户在设置页
编辑记忆后，变化从下一个新 turn 生效。记忆可以指导相关偏好和背景理解，但当前任务、
服务端规则和工具证据始终优先。记忆正文不写入 task event 或日志。

本节只描述 ReAct 链路。下方关键词匹配、最多五条和 1600 字符预算仍是旧普通 Agent
调用链的现状，不应用于 ReAct 首版。RAG、自动记忆提取和动态相关性检索不在 Issue
#173 范围内。

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
