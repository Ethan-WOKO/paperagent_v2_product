# ChatMemory 与上下文管理接入方案

> 文档分类：**待重新评估**。旧 `/chat` 的窗口、摘要、记忆和快照基础大部分已实现，但 V2 的 Context Revision、摘要更新和恢复不变量尚未完成；后续按 `docs/当前有效/文档治理/pending-capability-assessment-20260803.md` 拆分。

> 关联 issue: #36 `design: 设计 ChatMemory 与上下文管理接入方案`
>
> 本文是后续短期 ChatMemory、长期记忆、上下文裁剪、调试页和 LangChain4j ChatMemory 接入的设计基线。当前 issue 只做设计沉淀，不直接替换生产聊天链路。

## 1. 背景与问题

当前普通对话链路已经能工作：

```text
AgentController
  -> AgentService
  -> AgentToolPolicyEngine
  -> HarnessEngine
  -> ModelProvider / ToolExecutor / KnowledgeContextProvider
```

核心数据已经落在以下表和实体中：

1. `agent_sessions` / `AgentSession`: 保存用户会话、模型快照、最大步数和 RAG 开关。
2. `agent_messages` / `AgentMessage`: 保存 user、assistant、tool、system 等消息及 tool call 信息。
3. `agent_turns` / `AgentTurn`: 保存一次用户输入到助手输出的运行状态。
4. `agent_tool_runs`: 已经用于记录工具运行，后续可与 context trace 关联。

现在的主要问题不是没有历史消息，而是历史消息没有被治理：

1. `AgentService.getHistoryMessages()` 会读取完整会话历史，然后通过 `HarnessRequest.history` 传给 Harness。
2. `AgentService.normalizeHistoryForModel()` 已经能修复历史 tool 消息，但逻辑仍然堆在 `AgentService`。
3. `HarnessEngine` 会继续插入 skill prompt、RAG context、web evidence、tool protocol 和当前用户消息。
4. 没有统一的 token / 字符预算，没有滚动摘要，没有可观察的上下文包，也没有长期记忆检索。
5. LangChain4j 可以降低 ChatMemory/RAG 的接入成本，但不能替代产品侧对权限、版本、调试、生命周期和持久化的治理。

因此，本阶段要把“模型本次到底看到了什么”变成一个可构建、可测试、可调试、可逐步替换的 `AgentContextPackage`。

## 2. 设计目标

1. 建立独立的上下文构建边界，避免 `AgentService` 继续膨胀。
2. 对短期会话记忆做窗口裁剪，避免无界追加完整历史。
3. 为长对话引入滚动 session summary 的数据位置和更新流程。
4. 为长期记忆预留跨会话用户级检索、查看、删除和修正入口。
5. 将 RAG 证据、长期记忆、会话摘要、最近消息和工具结果按照稳定顺序注入。
6. 让上下文构建结果可以在调试页查看，包括注入项、丢弃项、预算估算和来源。
7. 允许未来用 LangChain4j 的 ChatMemory 组件辅助窗口管理和格式转换，但 MySQL 仍然是业务事实源。

## 3. 非目标

1. 当前 issue 不实现长期记忆写入和检索。
2. 当前 issue 不实现 Project 内论文和代码协同，只保留 `projectId` 扩展位。
3. 当前 issue 不替换 `HarnessEngine`，也不改变模型调用协议。
4. 当前 issue 不让 LangChain4j 接管会话持久化。
5. 当前 issue 不改变论文润色、文献检索、RAG 检索排序的既有功能。

## 4. 核心边界

### 4.1 ChatMemory

ChatMemory 指 session-scoped 的短期上下文治理，包含：

1. 当前会话最近若干轮 user/assistant 消息。
2. 必要的 tool call / tool result 上下文，且必须保证模型协议合法。
3. 当前 session summary。
4. 当前用户消息。

ChatMemory 不负责长期记忆，不直接决定是否调用工具，不直接执行 RAG。

### 4.2 LongTermMemory

LongTermMemory 指 user-scoped 的跨会话记忆，默认开启，用于保存长期稳定信息，例如：

1. 用户研究方向、术语偏好、写作风格偏好。
2. 常用论文结构、方法学偏好、引用规范偏好。
3. 用户明确确认或多次稳定出现的项目背景。

长期记忆必须允许用户查看、删除和修正。调试阶段还需要能在调试页看到本轮是否命中了长期记忆、命中了哪些内容、为什么注入。

### 4.3 RAG

RAG 指知识库中的论文原稿、润色稿、文献卡片、用户上传材料等检索证据。它的事实源仍然是 MySQL + Elasticsearch + MinIO：

1. 文件本体放在 MinIO，不直接存 MySQL。
2. 文档元数据、版本状态、文献卡片、去重 key 存 MySQL。
3. 检索索引和向量信息存 Elasticsearch。
4. 生成侧只能注入通过权限、版本和来源过滤后的证据。

### 4.4 Tool Trace

Tool Trace 指最近工具调用和工具结果的摘要。它不是普通聊天消息，也不应该无界塞进模型。

后续应只注入对当前问题有帮助的工具结果摘要，例如：

1. 最近一次文献检索任务的结果摘要。
2. 正在运行或刚完成的论文润色任务状态。
3. 用户追问某个工具结果时需要的上下文。

## 5. 上下文构建顺序

建议后续 `AgentContextBuilder` 输出稳定顺序的 `AgentContextPackage`：

```text
1. Runtime safety / Harness contract
2. Runtime identity guard
3. Selected skill prompt
4. Session summary
5. Long-term memory snippets
6. RAG snippets
7. Recent conversation messages
8. Relevant tool result summaries
9. Current user message
```

约束：

1. 安全协议、工具协议和运行时身份保护优先级最高。
2. 当前用户消息必须保留在末尾，不能被预算裁剪。
3. RAG 证据必须带 citation / documentId / versionStatus / sourceType 等元数据。
4. 长期记忆必须标注来源和置信度，避免伪装成用户本轮输入。
5. tool result 必须保持模型协议合法；不合法的历史 tool 消息继续降级为 assistant 摘要。

## 6. 预算策略

第一阶段可以先使用字符数估算，后续再替换成 tokenizer 估算。建议默认预算按类型分配：

| 上下文区域 | 建议预算 | 裁剪策略 |
| --- | ---: | --- |
| runtime / safety / tool contract | 20% | 不裁剪，只允许人工优化 prompt |
| session summary | 10% | 保留最新摘要 |
| long-term memory | 15% | 按相关性、更新时间和置信度排序 |
| RAG snippets | 25% | 按 rerank score、版本状态、来源排序 |
| recent messages | 25% | 从最近消息向前取，保持协议合法 |
| reserve | 5% | 给模型输出和格式安全留缓冲 |

必须保留：

1. 当前用户消息。
2. runtime safety / tool contract。
3. 与当前 assistant tool call 配套的必要 tool result。

可以裁剪：

1. 很早之前的普通闲聊。
2. 已经被 session summary 覆盖的历史消息。
3. 与当前问题无关的低分 RAG 片段。
4. 过旧、低置信度、用户删除或纠正过的长期记忆。

## 7. 数据模型建议

### 7.1 agent_session_summaries

用于保存滚动会话摘要。

建议字段：

1. `id`
2. `session_id`
3. `user_id`
4. `summary_text`
5. `covered_message_id`: 摘要已经覆盖到的最后一条 message id。
6. `message_count`
7. `model_provider_snapshot`
8. `model_snapshot`
9. `created_at`
10. `updated_at`

### 7.2 agent_long_term_memories

用于保存跨会话长期记忆，后续独立 issue 实现。

建议字段：

1. `id`
2. `user_id`
3. `project_id`: nullable，当前只预留。
4. `scope`: `USER`, `PROJECT`, `SESSION`, `TASK`。
5. `memory_type`: `PREFERENCE`, `RESEARCH_PROFILE`, `STYLE`, `FACT`, `WARNING`。
6. `content`
7. `tags_json`
8. `source_type`: `USER_CONFIRMED`, `MODEL_EXTRACTED`, `DOCUMENT_DERIVED`, `SYSTEM_IMPORTED`。
9. `source_ref_id`
10. `confidence`
11. `status`: `ACTIVE`, `DELETED`, `SUPERSEDED`。
12. `created_at`
13. `updated_at`
14. `deleted_at`

### 7.3 agent_context_snapshots

用于调试页查看一次 turn 的上下文构建结果。

建议字段：

1. `id`
2. `turn_id`
3. `session_id`
4. `user_id`
5. `trace_id`
6. `sections_json`: 每个 section 的类型、来源、长度、摘要和排序原因。
7. `dropped_items_json`: 被裁剪或过滤的项目及原因。
8. `estimated_tokens`
9. `created_at`

生产环境可以用配置控制是否持久化完整 snapshot。默认至少保留结构化摘要，避免泄露过多敏感内容。

## 8. LangChain4j 接入原则

LangChain4j 是能力框架，不是产品运行治理层。建议策略：

1. 可以使用 `MessageWindowChatMemory` 或自定义 `ChatMemoryStore` 辅助短期窗口管理。
2. 不能把 LangChain4j 的内存对象当作业务事实源。
3. `agent_messages`、`agent_session_summaries`、`agent_long_term_memories` 仍然由我们自己的服务持久化。
4. LangChain4j 适合放在 `AgentContextBuilder` 内部的 adapter 层，输入是我们查出的业务数据，输出是符合模型协议的消息列表。
5. 如果 LangChain4j 的裁剪逻辑无法表达 tool message 合法性、RAG citation、用户权限和版本过滤，则只使用其格式转换能力，不使用其默认裁剪策略。

推荐后续命名：

```text
AgentContextBuilder
  -> ConversationHistoryLoader
  -> MessageHistoryNormalizer
  -> SessionSummaryProvider
  -> LongTermMemoryRetriever
  -> RagContextProvider
  -> ToolTraceContextProvider
  -> LangChain4jChatMemoryAdapter
```

## 9. 用户确认与防跑偏机制

当 agent 遇到不确定、风险较高或有明显多方案分支的问题时，不应直接执行复杂计划，而应进入用户确认流程。

触发场景：

1. 用户目标模糊，但执行成本高。
2. 计划会修改论文、代码、知识库或长期记忆。
3. 多个方案代价差异明显，例如快速修复、系统重构、先做实验。
4. 模型置信度低，且跑偏会影响用户体验或数据质量。

交互输出：

1. 推荐方案 A。
2. 备选方案 B。
3. 用户自定义方案入口。
4. 清晰说明每个方案的影响范围、耗时和风险。

运行状态：

```text
WAITING_USER_CONFIRMATION
  -> CONFIRMED
  -> RUNNING
  -> COMPLETED / FAILED / CANCELLED
```

这个机制属于 Harness / AgentRuntime 生命周期治理，不属于 LangChain4j 自带能力。

## 10. 调试页设计

调试入口放在内部调试页，不作为普通用户主流程暴露。建议前端预留：

```text
/debug/context
```

页面能力：

1. 按 sessionId / turnId 查询本轮上下文。
2. 展示上下文 section 顺序。
3. 展示 token / 字符预算估算。
4. 展示 RAG snippet、长期记忆 snippet、session summary 是否注入。
5. 展示哪些消息、记忆、证据被裁剪，原因是什么。
6. 支持仅开发/管理员角色访问。

长期记忆用户自助管理不放在调试页，应作为用户可见设置项：

```text
/settings/memory
```

用户可以查看、删除、修正自己的长期记忆；长期记忆默认开启，但需要可观察、可纠错。

## 11. 分阶段落地

### 11.1 短期上下文构建器

对应 issue #37。

目标：

1. 新增 `AgentContextBuilder`。
2. 将完整历史读取、tool message normalization、runtime identity guard 从 `AgentService` 中抽出。
3. 默认只取最近窗口，保证 tool 消息协议合法。
4. 输出 `AgentContextPackage`，再传给 `HarnessRequest.history`。
5. 增加单元测试覆盖长历史裁剪、tool 消息降级、当前用户消息保留。

### 11.2 会话摘要

目标：

1. 新增 `agent_session_summaries` 表。
2. 成功 turn 后异步或同步更新摘要。
3. 摘要覆盖到 `covered_message_id` 后，旧消息不再全部注入。
4. 摘要更新失败不影响主对话返回。

### 11.3 上下文调试快照

目标：

1. 新增 `agent_context_snapshots` 表或轻量事件记录。
2. 后端提供按 turnId 查询接口。
3. 前端 `/debug/context` 展示本轮上下文构成。

### 11.4 长期记忆

目标：

1. 新增 `agent_long_term_memories` 表。
2. 默认开启长期记忆写入和检索。
3. 先做用户可见的查看、删除、修正。
4. 再做自动抽取和相关性检索。
5. 写入长期记忆前必须去重、合并、标注来源和置信度。

### 11.5 LangChain4j ChatMemory adapter

目标：

1. 在测试或局部 profile 中对比自研窗口裁剪与 LangChain4j `ChatMemory`。
2. 验证 tool message、RAG snippet、summary 和长期记忆能否稳定映射。
3. 只有当收益明确且不会破坏业务事实源时，才进入生产链路。

## 12. 测试要求

每个实现 issue 必须至少包含：

1. 单元测试：覆盖上下文排序、预算裁剪、tool 协议合法性。
2. 仓储测试：涉及新表时覆盖 MySQL/H2 migration。
3. 回归测试：普通对话、RAG 对话、工具调用对话都不能退化。
4. 前端构建：涉及 UI 时必须运行 `npm run build`。
5. 文档校验：设计文档和运行手册修改后运行 `git diff --check`。

## 13. 验收标准

本设计后续全部落地后，应满足：

1. 长会话不会把完整历史无界传给模型。
2. 用户可以稳定追问近期上下文。
3. 长期记忆跨会话生效，且用户可以查看、删除、修正。
4. RAG、长期记忆、摘要、最近消息不会互相污染。
5. 调试页能解释本轮模型看到的上下文构成。
6. 不确定的复杂计划会先请求用户确认，而不是直接执行。
7. LangChain4j 只承担能力组件职责，不接管 Harness 生命周期治理。
