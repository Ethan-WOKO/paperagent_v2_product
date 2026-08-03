# Agent Product Reconstruction Roadmap

> 文档分类：**已被替代**。旧 roadmap 的大部分阶段已经完成，Agent 主架构已迁移到 V2。尚有价值的未完成想法见 `docs/当前有效/文档治理/pending-capability-assessment-20260803.md`。

> 面向 AI 开发者的产品定位、架构边界和阶段重构规划。
>
> 本文档用于沉淀当前讨论形成的共识。后续拆 issue、写实现方案、做代码修改、设计测试和 merge gate，都应以本文档为上层依据。本文档不是一次性开发清单，也不是最终需求冻结文档；每个阶段开始前必须先复盘上一阶段结果，再细化本阶段 issue。

## 1. 当前共识

### 1.1 产品不是单纯聊天机器人

本项目的目标不是做一个普通通用聊天助手，也不是把论文润色、文献检索、RAG、工具调用零散堆在一起。

当前产品方向应定义为：

> 面向科研写作和科研项目推进的长期学术 Agent 工作台。

核心用户场景包括：

1. 用户上传论文，系统能进行论文润色、结构检查、段落改写、引用建议和结果导出。
2. 用户需要文献时，系统能做真实文献检索、筛选、排序、生成文献卡片，并支持后续插入论文。
3. 用户上传个人论文、润色后论文、文献、实验记录等资料后，系统能通过 RAG 和记忆逐步理解用户研究领域。
4. 用户可以在对话中调用论文润色、文献检索、知识库检索等能力，而不是每个能力只能在独立页面中使用。
5. 系统需要支持复杂任务执行，包括计划、工具调用、结果反思、失败重试和必要时的人机协作确认。
6. 后续会支持 Project/Workspace 概念，将论文、代码、文献、实验结果组织到一个项目空间中，但当前阶段只预留入口，不实现完整 Project 功能。

### 1.2 三个产品卖点

当前产品应围绕三个相互增强的卖点建设：

1. 学术助手：论文润色、论文审稿、文献推荐、引用建议、学术写作质量提升。
2. 通用 Agent 工具能力：能够根据任务选择工具、执行多步骤任务、处理失败、保留运行轨迹。
3. 领域长期增强：通过用户上传的论文、润色稿、文献、知识库和后续对话，逐步形成用户研究领域画像和可复用 skill，提高用户粘性。

这三个卖点的关系：

```text
论文润色 / 文献检索
        -> 产生高价值学术资产
        -> 入库、去重、版本排序
        -> RAG 和长期记忆增强
        -> 后续对话更懂用户领域
        -> 反过来提升论文润色和文献推荐质量
```

### 1.3 当前阶段不做完整 Project

Project/Workspace 是未来关键方向，但当前不应直接实现完整 Project 功能。

当前只允许做以下预留：

1. 前端导航或信息架构中预留 Project/Workspace 入口。
2. 后端设计中允许出现可为空的 `projectId` 或 `default workspace` 概念，但不要求落完整表结构。
3. 新增实体或接口时避免把未来 Project 扩展堵死。
4. 不实现论文、代码、实验、文献之间的完整项目级资产关系。

禁止在当前阶段扩大的范围：

1. 不做完整项目权限模型。
2. 不做代码仓库接入和代码修改闭环。
3. 不做项目级实验结果管理。
4. 不做论文段落、代码模块、实验结果、文献证据之间的完整跨资产链接。

## 2. 概念边界

### 2.1 Harness 是范式，不等同于某个类

在本项目语境中，Harness 不是单纯指现有 `HarnessEngine` 类。

Harness 应理解为一种运行时治理范式，关注：

1. Agent run 生命周期。
2. 工具权限和工具预算。
3. 超时控制。
4. 错误重试。
5. 幂等和防重复调用。
6. 安全边界。
7. 用户可见输出和过程事件的边界。
8. 运行轨迹和可观测性。
9. 人机协作确认点。
10. 失败后的降级、恢复和总结。

因此，后续引入 LangChain4j 并不意味着放弃 Harness。正确关系是：

```text
Harness 范式负责“怎么安全可靠地运行”
LangChain4j 负责“用哪些框架组件减少 Agent 能力开发难度”
```

### 2.2 LangChain4j 是开发框架，不是产品运行时的全部

LangChain4j 可以帮助减少以下能力的自研成本：

1. Chat model abstraction。
2. Tool/function calling。
3. Chat memory。
4. RAG pipeline。
5. Embedding store integration。
6. Retrieval augmentor。
7. 部分 agentic workflow 能力。

但 LangChain4j 不应承担以下产品级职责：

1. 用户会话完整历史的持久化。
2. 用户权限和数据隔离。
3. Project/Workspace 资产模型。
4. 论文润色长任务生命周期。
5. SSE/WebSocket 断线续传协议。
6. 工具调用审计。
7. 版本化知识库策略。
8. 论文、文献、代码、实验结果的业务关系。
9. merge gate、评测体系和发布流程。

LangChain4j 应被接入为能力组件层，而不是吞掉整个应用运行时。

### 2.3 现有 HarnessEngine 的定位

现有 `HarnessEngine` 可以暂时视为当前版本的自研 agent loop 执行器。

它可以继续服务现有功能，但后续需要逐步被包裹到统一运行时接口中，例如：

```text
AgentRuntimeService
  -> Runtime adapter
       -> CurrentHarnessAdapter
       -> LangChain4jAdapter
       -> FutureSpringAiAdapter
```

注意：这里的 adapter 不是当前阶段必须实现的代码，而是后续重构方向。

当前阶段不要直接删除或大改 `HarnessEngine`。应先建立运行时边界和测试护栏，再决定底层执行器迁移方式。

## 3. 功能需求整理

### 3.1 论文润色能力

当前状态：

1. 论文润色模块已经具备较多基础能力。
2. 该能力不应只作为独立页面存在。
3. 后续应封装为 Agent 可调用工具。

目标形态：

论文润色应封装为异步长任务工具，而不是一次同步 tool call。

并发和任务执行要求：

1. 每次论文润色请求都应创建独立后台任务。
2. 产品语义上可以理解为“一个任务独立执行”，但工程实现不应无限制创建裸线程。
3. 首版采用 MySQL 任务表作为状态事实源，Kafka 作为任务唤醒和分发通道，独立 `paperTaskExecutor` 执行论文润色任务。
4. 论文润色任务不应阻塞普通聊天、RAG 检索、文献检索或其他用户任务。
5. 任务状态必须可查询，Agent 后续既要能从对话创建任务，也要能查询已有任务状态和总结已有任务结果。
6. 任务必须支持用户主动停止，不能只支持“发送后等待结束”。

任务调度建议：

```text
API 创建 PaperPolishTask(PENDING)
  -> 写入 MySQL
  -> 发送 Kafka task_created 事件
  -> Worker 原子领取任务并置为 RUNNING
  -> paperTaskExecutor 执行
  -> 持续写入进度、阶段、事件和产物
  -> COMPLETED / FAILED / CANCELLED
```

首版 `paperTaskExecutor` 建议参数：

```text
corePoolSize: 1
maxPoolSize: 2
queueCapacity: 20
perUserRunningLimit: 1
taskTimeout: 按论文长度和策略配置，必须有上限
```

这些参数不是最终性能配置，只是防止首版无限并发和资源耗尽。后续应通过压测和真实任务耗时调整。

建议工具边界：

```text
paper_polish_start
  输入：论文文件、目标语言、润色策略、文献数量、是否只做文献推荐等
  输出：taskId、初始状态

paper_polish_status
  输入：taskId
  输出：当前阶段、进度、是否需要用户确认、错误信息

paper_polish_result
  输入：taskId
  输出：润色后论文、建议、文献、报告、导出产物

paper_apply_suggestion
  输入：taskId、suggestionId、操作
  输出：建议状态变化

paper_export
  输入：taskId、artifactType
  输出：下载链接或 artifact metadata

paper_task_cancel
  输入：taskId、cancelReason
  输出：取消是否已受理、当前状态
```

必须保留的产品约束：

1. 长任务必须有 taskId。
2. 工具调用不能阻塞一轮对话直到任务全部完成。
3. 需要支持状态查询。
4. 需要支持失败降级。
5. 涉及重要修改时应支持 preview、diff、accept/reject。
6. 论文原稿和润色稿后续需要进入知识库，但必须带版本和来源信息。
7. 如果论文任务需要用户澄清结构、确认修改或选择策略，应进入等待用户确认状态，而不是继续自动执行。
8. 用户点击停止后，后端应进入 `CANCEL_REQUESTED` 或 `CANCELLING`，由 worker 在安全点中止任务并最终写入 `CANCELLED`。
9. 停止不是强杀线程。执行器应在章节处理、外部模型调用前后、写入产物前等安全点检查取消标记。
10. 如果任务正在等待不可中断的外部调用，应依赖超时尽快返回；前端显示“正在停止”，不能误报为已完成。
11. 已取消任务不得继续自动重试，不得继续应用论文修改；已产生的中间产物必须标记为 `PARTIAL` 或 `CANCELLED`。

### 3.2 文献检索能力

当前状态：

1. 文献检索已经有一定实现基础。
2. 后续应封装为 Agent 可调用工具。

目标形态：

文献检索统一任务化。无论用户请求找 3 篇还是 30 篇文献，都应创建独立文献检索任务。

并发和任务执行要求：

1. 每次文献检索请求都创建 `LiteratureSearchTask`。
2. 产品语义上每个检索任务独立运行，不阻塞普通聊天、论文润色或其他任务。
3. 首版同样采用 MySQL 任务表作为状态事实源，Kafka 作为任务唤醒和分发通道，独立 `literatureTaskExecutor` 执行文献检索任务。
4. 检索任务应具备统一状态：`PENDING`、`RUNNING`、`CANCEL_REQUESTED`、`CANCELLING`、`COMPLETED`、`FAILED`、`CANCELLED`。
5. Agent 后续既要能从对话创建文献检索任务，也要能查询已有文献检索任务状态和结果。
6. 文献检索任务也必须支持用户停止，即使只找 3 篇文献也走完整任务生命周期。

首版 `literatureTaskExecutor` 建议参数：

```text
corePoolSize: 2
maxPoolSize: 6
queueCapacity: 50
perUserRunningLimit: 2
sourceRequestTimeout: 每个外部来源必须有独立超时
overallTaskTimeout: 整个检索任务必须有总超时
```

文献检索偏 IO 和外部 API 调用，允许比论文润色更高并发，但必须通过 per-user limit 和全局线程池上限保护系统。

建议工具边界：

```text
literature_search_start
  输入：query、领域、数量、年份范围、来源偏好
  输出：taskId、初始状态

literature_search_status
  输入：taskId
  输出：当前状态、进度、错误信息

literature_search_result
  输入：taskId
  输出：候选文献列表、来源、DOI/URL、摘要、排序理由、文献卡片

literature_search_cancel
  输入：taskId、cancelReason
  输出：取消是否已受理、当前状态

suggest_citations
  输入：论文段落、已有文献、项目上下文
  输出：引用建议、插入位置、支持理由
```

必须保留的产品约束：

1. 不能伪造文献。
2. DOI、URL、来源、检索时间必须保留。
3. 文献卡片入库前应有去重逻辑。
4. 低可信文献或缺少元数据的文献必须标注风险。
5. 文献推荐结果必须能被后续论文润色和 RAG 复用。
6. 如果用户上传 `.bib` 文件，推荐文献必须和用户已有 BibTeX 去重。
7. 如果用户未上传 `.bib` 文件，首版可以直接输出推荐文献列表，不强制生成 BibTeX。
8. BibTeX 去重不能只依赖字符串完全相等，应优先使用 DOI、arXiv ID、规范化 title、year、authors 等多层匹配。
9. 所有完成的文献检索结果都应能沉淀为文献卡片，避免后续重复检索浪费资源。
10. 用户停止文献检索后，不应继续请求新的外部来源；已经返回的候选结果可以作为 `PARTIAL` 结果保存，但必须清晰标注任务已取消。

### 3.3 RAG 能力

当前状态：

1. 当前 RAG 是自研链路。
2. 检索侧和生成侧质量还需要重新评估。
3. 可以考虑引入 LangChain4j RAG 组件来减少底层复杂度。

目标形态：

RAG 应分成两个层次：

```text
底层 RAG 组件层：
  chunking
  embedding
  vector store
  query transform
  retrieval
  rerank
  context assembly

业务检索策略层：
  user/project 权限
  文档版本过滤
  论文原稿/润色稿优先级
  文献真实性约束
  过时版本降权
  引用证据格式
```

LangChain4j 可优先用于底层 RAG 组件层。

LangChain4j 接入优先级：

1. 先做 LangChain4j RAG spike。
2. 再做 ChatMemory/短期记忆 spike。
3. RAG spike 必须和当前自研 RAG 做对照评测。
4. spike 期间不得直接替换主业务链路。

不能完全交给框架的部分：

1. 用户权限过滤。
2. 未来 Project 范围过滤。
3. 文档版本优先级。
4. 论文原稿和润色稿的 lineage。
5. 过时内容降权。
6. 文献真实性和引用约束。
7. 用户长期研究画像的注入策略。

RAG 质量必须通过评测判断，不允许仅凭主观体验判断。

### 3.4 记忆系统

记忆系统需要拆成至少三类：

#### 3.4.1 UI History

用户可见的完整聊天历史。

职责：

1. 完整保存用户和助手消息。
2. 支持前端分页加载。
3. 支持审计和恢复。
4. 不等同于模型上下文。

该部分必须由业务系统自己持久化，不能只依赖 LangChain4j ChatMemory。

#### 3.4.2 Short-term Memory

短期模型上下文。

职责：

1. 为当前对话提供最近上下文。
2. 控制 token 长度。
3. 可使用 LangChain4j ChatMemory 或等价机制。
4. 可以包含 session summary 和最近若干轮原始消息。

#### 3.4.3 Long-term Memory

长期用户画像和研究领域记忆。

可能内容：

1. 用户研究方向。
2. 常用术语。
3. 论文主题。
4. 写作偏好。
5. 常用方法。
6. 已确认的重要事实。
7. 用户反复修改或拒绝的表达偏好。

长期记忆必须有治理机制：

1. 抽取。
2. 去重。
3. 置信度。
4. 来源。
5. 更新时间。
6. 是否仍有效。
7. 用户可查看。
8. 用户可删除。
9. 敏感信息过滤。

不允许把所有对话直接写入长期记忆。

默认策略：

1. 长期记忆默认开启。
2. 普通用户界面不需要提供“关闭长期记忆总开关”作为首版能力。
3. 为开发和调试，应提供调试开关或调试入口，用于验证长期记忆是否生效。
4. 长期记忆首版作用域为跨会话的 `USER` scope。
5. 未来 Project 能力成熟后，再增加 `PROJECT` scope。

用户控制权：

1. 用户必须能查看系统记录的长期记忆。
2. 用户必须能删除不合理的长期记忆。
3. 用户应能修正错误记忆，或通过删除后重新沉淀实现修正。
4. 删除建议采用软删除，便于审计和问题排查。
5. 模型检索和上下文注入必须排除已删除长期记忆。

建议字段：

```text
AgentMemory
  - memoryId
  - userId
  - scope: USER / PROJECT / SESSION
  - content
  - tags
  - source
  - confidence
  - status: ACTIVE / DELETED / SUPERSEDED
  - createdAt
  - updatedAt
  - lastUsedAt
```

调试入口应至少能展示：

1. 本轮是否启用长期记忆。
2. 本轮检索到了哪些长期记忆。
3. 哪些长期记忆被注入模型上下文。
4. 哪些记忆被新增、更新、删除或跳过。

首版 UI 建议：

1. 将长期记忆管理放在设置/调试页，例如 `/settings/memory-debug`。
2. 该页面同时服务用户控制和开发调试，不在主聊天页增加复杂开关。
3. 普通用户可以查看、删除和修正自己的长期记忆。
4. 开发调试时可以查看最近一次对话的记忆检索、注入和更新 trace。
5. 不提供“永久关闭长期记忆”的普通用户总开关，避免首版产品策略发散；调试开关仅用于验证和排障。

### 3.5 会话管理和上下文管理

会话管理目标：

1. 一个用户可以有多个会话。
2. 会话保留模型配置、RAG 开关、skill 选择等快照。
3. 会话消息和模型上下文分离。
4. 对模型传入的上下文由 ContextBuilder 统一生成。

上下文构造建议顺序：

```text
system base instructions
runtime safety instructions
selected skill instructions
user/project/research profile memory
session summary
retrieved RAG snippets
recent raw messages
current user message
```

ContextBuilder 必须成为明确组件，避免各 service 自己拼上下文。

### 3.6 复杂任务执行

复杂任务执行需要结合三类方法：

1. ReAct：模型根据当前状态决定是否调用工具，工具结果回填后继续推理。
2. Plan-and-Execute：先拆计划，再逐步执行，适合较复杂任务。
3. Reflection：对结果进行评估、发现不足、修复或降级。

LangChain4j 可用于其中部分能力，但不应完全替代 Harness 范式。

产品级复杂任务执行必须包含：

1. runId。
2. strategy。
3. step 或 event。
4. 工具调用记录。
5. 超时预算。
6. 最大工具调用数。
7. 失败重试次数。
8. 失败降级策略。
9. 用户确认点。
10. 最终总结。

Human-in-the-loop 要求：

复杂任务遇到目标不明确、风险较高、成本较高、需要修改用户资产、或计划存在多种合理路线时，Agent 不应直接继续执行，应先进入用户确认或澄清流程。

必须支持的确认类型：

```text
CLARIFICATION
  用户目标不明确、输入缺失、约束冲突时，先询问用户。

PLAN_APPROVAL
  复杂计划执行前，展示计划，让用户确认、修改或取消。

OPTION_SELECTION
  存在方案 A / B / 自定义路线时，让用户选择。

RISK_CONFIRMATION
  涉及覆盖文件、修改论文、改代码、删除知识、批量入库、高成本外部调用时，需要确认。

APPLY_CONFIRMATION
  Agent 可以生成建议和 diff，但真正应用到论文或代码前必须确认。
```

建议 run 状态：

```text
RUNNING
WAITING_USER_CONFIRMATION
WAITING_USER_CLARIFICATION
RESUMED
COMPLETED
FAILED
CANCELLED
```

建议事件：

```text
clarification_required
plan_approval_required
option_selection_required
risk_confirmation_required
apply_confirmation_required
user_decision_received
run_resumed
```

第一版可以先支持文本型确认，不要求立即实现复杂按钮 UI。

建议策略类型：

```text
DIRECT
SINGLE_STEP_REACT
PLAN_EXECUTE
PLAN_EXECUTE_WITH_REFLECTION
LONG_RUNNING_TOOL_TASK
```

### 3.7 论文和润色稿入库

用户使用论文润色系统时，应支持将以下内容入库：

1. 用户原始论文。
2. 解析后的章节结构。
3. 润色后的论文。
4. 论文修改建议。
5. 采用/拒绝建议记录。
6. 文献推荐结果。
7. 最终导出产物摘要。

入库时必须处理版本和去重。

禁止简单地把所有版本作为平等文档塞进向量库。

覆盖范围：

1. 论文原稿必须进入版本治理。
2. 润色稿必须进入版本治理。
3. 文献卡片也必须进入版本治理和去重流程。
4. 文献卡片需要同时存入 MySQL 和 Elasticsearch。
5. MySQL 负责结构化元数据、状态、来源和去重 key。
6. Elasticsearch 负责检索文本、摘要、关键词、向量和 RAG 检索。
7. 后续每次文献检索应优先复用已有文献卡片，减少重复外部检索和重复 embedding 成本。

文献卡片存储设计原则：

1. MySQL 是事实源，负责结构化字段、状态、去重、版本、来源和任务关联。
2. Elasticsearch 是可重建索引，负责全文检索、混合检索、向量检索和 RAG candidate recall。
3. ES 中的数据可以从 MySQL 重建，因此不能把唯一业务状态只放在 ES。
4. 检索流程应先按用户、Project、状态、版本过滤，再做 lexical/vector/hybrid retrieval，最终从 MySQL hydrate 完整结构化信息。

建议 MySQL 表：

```text
literature_cards
  - id
  - user_id
  - project_id nullable
  - dedup_key
  - doi
  - arxiv_id
  - title
  - normalized_title_hash
  - authors_json
  - publication_year
  - venue
  - abstract_text
  - url
  - source
  - source_record_id
  - citation_count
  - bibtex_key
  - bibtex_raw
  - status: ACTIVE / DUPLICATE / ARCHIVED / SUPERSEDED
  - quality_score
  - reliability_score
  - es_doc_id
  - created_from_task_id
  - first_seen_at
  - last_seen_at
  - created_at
  - updated_at

literature_card_versions
  - id
  - card_id
  - version_no
  - metadata_hash
  - source_payload_json
  - title
  - authors_json
  - abstract_text
  - status
  - source_task_id
  - created_at

literature_task_cards
  - id
  - task_id
  - card_id
  - rank
  - relevance_score
  - reason
  - selected
  - created_at
```

建议 ES index：

```text
yanban-literature-cards-v1
  - cardId
  - userId
  - projectId nullable
  - status
  - doi
  - arxivId
  - title
  - authors
  - publicationYear
  - venue
  - abstract
  - summary
  - keywords
  - source
  - reliabilityScore
  - qualityScore
  - textForEmbedding
  - vector
  - updatedAt
  - lastSeenAt
```

去重优先级：

```text
DOI
  -> arXiv ID
  -> normalized title + publication year
  -> normalized title + first author
```

去重结果不能只删除重复项。系统应保留来源、版本和 `last_seen_at`，以便后续排序、可信度判断和检索复用。

建议概念：

```text
DocumentIdentity
  - identityId
  - userId
  - projectId nullable
  - canonicalTitle
  - fingerprint
  - sourceType
  - lineageId

DocumentVersion
  - versionId
  - identityId
  - versionNo
  - status
  - contentHash
  - sourceTaskId
  - createdAt
  - qualityScore
  - embeddingStatus
```

建议版本状态：

```text
ACTIVE
SUPERSEDED
ARCHIVED
FAILED
DRAFT
```

检索排序必须考虑：

```text
semanticScore
projectBoost
activeVersionBoost
recencyBoost
qualityBoost
sourceReliabilityBoost
staleVersionPenalty
duplicatePenalty
```

### 3.8 Project 未来方向

后续 Project 应支持：

1. 一个项目包含论文、代码、文献、实验结果、会话和知识库资产。
2. 用户可以围绕一个项目持续推进论文和代码。
3. Agent 可以检查论文描述和代码实现是否一致。
4. Agent 可以根据代码、实验结果和文献更新论文内容。

当前只预留入口，不实现完整功能。

后续 Project 可能包含：

```text
Project
PaperArtifact
CodeRepository
CodeSnapshot
ExperimentResult
LiteratureCard
ProjectKnowledgeIndex
CrossAssetLink
```

典型未来任务：

1. 检查论文方法部分是否和代码实现一致。
2. 根据实验日志更新论文实验结果表。
3. 发现代码里实现了某方法但论文没有解释，并补写方法说明。
4. 为论文中的 claim 寻找文献支撑。
5. 检查 README、代码实现和论文摘要是否一致。

当前预留方式：

1. Project/Workspace 在前端展示入口。
2. 允许预留路由和占位页面。
3. 允许在代码和接口设计中预留可为空的 `projectId`。
4. 占位页面必须明确说明 Project/Workspace 是后续能力，避免用户误以为完整功能已经可用。
5. 不实现项目创建、项目成员权限、项目资产绑定、代码仓库接入和项目知识图谱。

首版入口建议：

1. 在左侧主导航放置顶级入口，位置靠近 Chat/Workspace，并在 Paper Polish 之前。
2. 路由建议为 `/projects`。
3. 页面名称建议为 `Project Preview` 或 `Workspace Preview`，明确这是预留能力。
4. 占位页可以展示后续 Project 将关联论文、代码、文献和实验结果，但不要提供可误操作的完整创建流程。
5. 如果出现创建按钮，首版应禁用或标注为后续能力，避免用户误以为 Project 功能已经可用。

## 4. 主要缺口

### 4.1 产品模型缺口

当前功能更像页面和模块的集合，还没有围绕学术资产形成统一产品模型。

缺失：

1. 统一 artifact 模型。
2. 版本模型。
3. 入库策略。
4. 资产来源和血缘关系。
5. 未来 Project 入口和扩展点。

### 4.2 Agent Runtime 缺口

当前 agent 能力分散在服务层和 `HarnessEngine` 中。

缺失：

1. 统一 `AgentRuntimeService`。
2. 统一 `AgentRun`。
3. 统一 `AgentRunEvent`。
4. 普通聊天和 plan 执行的统一 trace。
5. 明确的 strategy selector。
6. 明确的 context builder。
7. 明确的 tool policy。
8. 明确的 memory updater。
9. 明确的 human-in-the-loop checkpoint。
10. 明确的等待用户确认、恢复执行和取消执行状态。

### 4.3 工具系统缺口

当前工具定义偏薄。

缺失：

1. `whenToUse`。
2. `whenNotToUse`。
3. 权限。
4. 成本。
5. 延迟预期。
6. 超时。
7. 重试策略。
8. 输出 schema。
9. 工具结果归一化。
10. 是否用户可见。
11. 每轮最大调用次数。
12. 是否需要用户确认。
13. side effects 类型。
14. risk level。

### 4.4 RAG 质量缺口

当前 RAG 已有工程链路，但仍需系统评估。

缺失：

1. 检索质量指标。
2. 生成忠实度指标。
3. 引用准确性指标。
4. 版本去重和降权。
5. 论文原稿/润色稿优先级。
6. 领域真实样本评测。
7. LangChain4j RAG 对照实验。
8. 文献卡片复用率。
9. 论文原稿/润色稿版本命中正确率。

### 4.5 传输和前端状态缺口

当前 WebSocket/SSE 容易因为缺少稳定 run event 协议导致前端猜状态。

缺失：

1. runId。
2. event sequence。
3. reconnect/resume。
4. heartbeat。
5. terminal event。
6. 错误可恢复语义。
7. 前端事件驱动状态机。
8. 等待用户确认和用户恢复执行的事件协议。
9. 用户点击停止后的取消事件、后端取消状态和前端“正在停止”状态。

### 4.6 开发流程缺口

当前不应继续“大改完再测试”。

缺失：

1. issue 拆分规范。
2. 阶段目标。
3. 非目标说明。
4. 验收标准。
5. 测试先行或测试伴随。
6. merge gate。
7. AI 能力评测集。
8. 回归测试策略。
9. 本地 merge gate 脚本。
10. 后续真正 CI 自动化。

## 5. 分阶段执行规划

### Phase 0: Alignment And Guardrails

阶段目标：

沉淀共识，明确产品边界、架构边界、开发流程和禁止事项。该阶段不做功能重构。

本阶段输出：

1. 本文档。
2. 后续 issue 模板。
3. merge gate 初稿。
4. 测试策略初稿。
5. 已确认决策清单。

步骤：

1. 确认产品定位：长期学术 Agent 工作台。
2. 确认 Harness 和 LangChain4j 的边界。
3. 确认 Project 只预留入口，不进入当前功能开发。
4. 确认论文润色和文献检索后续要工具化。
5. 确认 RAG/Memory 可以引入 LangChain4j，但必须保留业务治理层。
6. 建立 issue 拆分规范。
7. 建立每阶段复盘机制。
8. 确认 LangChain4j 接入顺序为先 RAG、后 ChatMemory。
9. 确认论文润色和文献检索均采用任务化执行。
10. 确认长期记忆默认开启、跨会话生效、用户可查看和删除。
11. 确认 Project 只做前端展示和代码/路由预留。
12. 确认长任务首版采用 Kafka + MySQL 任务表，后续人机协同复杂后再评估 Temporal。
13. 确认论文润色和文献检索采用独立线程池。
14. 确认长任务和 Agent run 必须支持用户停止。

验收标准：

1. 文档被确认无重大歧义。
2. 用户确认可以基于该文档开始拆 issue。
3. 未修改核心业务代码。

非目标：

1. 不实现 AgentRuntime。
2. 不引入 LangChain4j。
3. 不修改数据库。
4. 不改前端页面。

### Phase 1: Development Process Baseline

阶段目标：

先建立规范开发流程，避免继续出现“大改后才测试”的情况。

步骤：

1. 新增 issue 模板。
2. 新增 PR/merge checklist。
3. 明确每个 issue 必须包含：
   - 背景
   - 目标
   - 非目标
   - 影响模块
   - 验收标准
   - 测试要求
   - 风险和回滚方式
4. 明确后端基础 gate。
5. 明确前端基础 gate。
6. 明确 Agent/RAG 特殊 gate。
7. 先建立本地 merge gate 和脚本化检查。
8. 真正 CI 暂不作为当前阶段强制目标，待重构节奏稳定后再引入。
9. 明确 GitHub 目标仓库、分支策略和首次 push 前的 remote 调整步骤。
10. 确认 roadmap 文档作为后续 issue 拆分和实现评审的上层依据。

建议基础 gate：

```text
mvn test
frontend pnpm build
相关模块 focused tests
涉及 RAG/Agent 时执行对应 eval
涉及 migration 时验证 H2 和 MySQL migration 兼容
```

CI 说明：

CI 是 Continuous Integration，即持续集成。它通常指 GitHub Actions、GitLab CI、Jenkins 等系统在提交或 PR 时自动执行测试和构建。

当前阶段先不强制建设完整 CI。优先目标是形成本地 merge gate 和可脚本化检查，确保每个 issue 在合并前可以稳定复现验证步骤。后续等重构主线稳定后，再把这些 gate 迁移到真正 CI。

验收标准：

1. 每个后续开发任务都能对应独立 issue。
2. 每个 issue 在开发前能写清楚验收标准。
3. 每个 PR 合并前必须通过约定测试。
4. 后续代码和文档变更明确推送到指定 GitHub 仓库。
5. 当前本地旧 remote 不会被误用为后续交付目标。

非目标：

1. 不要求一次性完善 CI。
2. 不要求一次性补全所有历史测试。
3. 不要求立刻重构业务代码。

### Phase 2: Runtime Boundary Design

阶段目标：

设计统一 Agent Runtime 边界，但先不急于替换现有实现。

步骤：

1. 设计 `AgentRuntimeService` 的职责。
2. 设计 `AgentRunRequest`。
3. 设计 `AgentRunResult`。
4. 设计 `AgentRunEvent`。
5. 设计 `AgentEventSink`。
6. 设计 strategy 类型。
7. 设计 ContextBuilder 边界。
8. 设计 ToolPolicy 边界。
9. 设计 MemoryUpdater 边界。
10. 明确现有 `HarnessEngine` 如何被包裹，而不是直接删除。
11. 设计等待用户确认、澄清、方案选择、风险确认和恢复执行的状态机。
12. 设计 run event 中的 human-in-the-loop 事件。

建议运行链路：

```text
UserTurn
  -> AgentRuntimeService
  -> Intent/Strategy Selection
  -> ContextBuilder
  -> ToolPolicy
  -> Runtime Adapter
  -> EventSink
  -> FinalAnswerComposer
  -> MemoryUpdater
  -> Persistence
```

验收标准：

1. 形成设计文档。
2. 能明确普通聊天、工具调用、plan、论文长任务分别走什么策略。
3. 能明确前端需要消费哪些事件。
4. 能明确哪些功能仍由现有代码承担。
5. 能明确不确定任务如何暂停并等待用户确认。

非目标：

1. 不在本阶段直接替换模型调用。
2. 不直接接入 LangChain4j。
3. 不改论文润色业务逻辑。

### Phase 3: Toolization Of Existing Academic Capabilities

阶段目标：

把已有高价值能力封装成 Agent 可调用工具，优先论文润色和文献检索。

步骤：

1. 梳理论文润色现有 API、任务状态、产物、SSE 事件。
2. 设计论文润色异步工具接口。
3. 确认论文润色任务执行器、线程池或队列边界。
4. 梳理文献检索现有 API、服务和结果结构。
5. 将所有文献检索统一设计为任务接口。
6. 设计文献检索任务执行器、线程池或队列边界。
7. 设计 Kafka topic、task table、worker 领取、超时和死信处理策略。
8. 设计论文润色和文献检索的独立线程池参数。
9. 设计任务取消接口和安全停止策略。
10. 设计 BibTeX 去重策略。
11. 定义工具 manifest。
12. 定义工具结果统一格式。
13. 定义工具错误和 degraded 输出。
14. 加入工具级测试。

验收标准：

1. Agent 可以启动论文润色任务并拿到 taskId。
2. Agent 可以查询论文润色任务状态。
3. Agent 可以启动文献检索任务并拿到 taskId。
4. Agent 可以查询文献检索任务状态和结果。
5. 找 3 篇文献也走任务化链路。
6. 上传 `.bib` 时能对推荐结果去重。
7. 工具失败不导致整个 Agent run 崩溃。
8. 工具 manifest 中包含使用场景、权限、超时、预算、风险和确认要求。
9. 用户可以停止论文润色任务或文献检索任务。
10. 停止后任务最终进入 `CANCELLED`，不会继续自动重试或继续写入最终产物。

非目标：

1. 不重写论文润色内部流程。
2. 不重写文献检索内部流程。
3. 不做 Project 级资产绑定。

### Phase 4: LangChain4j RAG And Memory Spike

阶段目标：

验证 LangChain4j 在本项目中的 RAG 和 memory 适配价值，先做对照实验，不替换主链路。

落地设计参考：

1. `docs/design/langchain4j-ab-evaluation-console.md`

步骤：

1. 新建隔离实验模块或实验 package。
2. 接入 LangChain4j 基础依赖。
3. 用最小样本实现 LangChain4j RAG pipeline。
4. 对比当前 RAG 的检索结果。
5. 对比当前 RAG 的生成质量。
6. 验证 ChatMemory 能否适配短期上下文。
7. 明确长期记忆仍需业务自研 store。
8. 输出 spike 结论。
9. RAG spike 完成并确认方向后，再启动 ChatMemory/短期记忆 spike。

评测维度：

1. 检索准确率。
2. 引用准确率。
3. 回答忠实度。
4. 性能。
5. 接入复杂度。
6. 对现有权限模型的适配成本。
7. 对文档版本过滤的支持成本。

验收标准：

1. 有可重复运行的对照实验。
2. 有明确结论：保留现有 RAG、部分迁移、还是逐步替换。
3. 主业务链路不受影响。
4. 明确 ChatMemory 接入前置条件。

非目标：

1. 不在本阶段替换线上 RAG。
2. 不接完整长期记忆系统。
3. 不改论文入库逻辑。
4. 不在 RAG spike 之前做 ChatMemory 主线改造。

### Phase 5: Knowledge Versioning And Ingestion Governance

阶段目标：

解决论文原稿、润色稿、文献卡片等资产入库后的去重、版本排序和过时内容降权问题。

步骤：

1. 设计 DocumentIdentity。
2. 设计 DocumentVersion。
3. 设计 lineage/fingerprint/contentHash。
4. 设计入库来源类型。
5. 设计版本状态。
6. 设计检索时版本过滤和排序策略。
7. 设计重复文档处理策略。
8. 为论文润色结果入库预留接口。
9. 为文献卡片入库预留接口。
10. 设计文献卡片 MySQL 结构化存储。
11. 设计文献卡片 ES 检索和向量索引。
12. 设计文献卡片复用策略。

验收标准：

1. 同一篇论文不同版本不会被当作完全无关文档。
2. 默认检索优先 ACTIVE 版本。
3. 旧版本不会无条件污染生成结果。
4. 文献卡片能去重。
5. 入库数据能追溯来源。
6. 文献卡片同时能从 MySQL 读取结构化信息，并能从 ES 参与检索。
7. 后续文献检索能优先复用已有文献卡片。

非目标：

1. 不做完整 Project。
2. 不做代码资产入库。
3. 不做实验结果资产入库。

### Phase 6: Unified Event Stream And Frontend State Stabilization

阶段目标：

让前端从“本地猜状态”转为消费后端稳定事件，降低 WebSocket/SSE 断连和重复消息问题。

步骤：

1. 定义统一 run event schema。
2. 定义 event sequence。
3. 定义 terminal event。
4. 定义 error event。
5. 定义 reconnect/resume 语义。
6. 定义前端状态机。
7. 让普通聊天、工具调用、论文长任务逐步对齐事件模型。
8. 让等待用户确认、用户选择方案、用户取消任务也进入统一事件模型。
9. 让前端点击停止后进入明确的取消状态，不再依赖关闭连接来模拟停止。

建议事件：

```text
run_started
strategy_selected
context_built
tool_selected
tool_started
tool_finished
reflection_started
human_input_required
answer_chunk
run_completed
run_failed
run_cancelled
cancel_requested
cancelling
clarification_required
plan_approval_required
option_selection_required
risk_confirmation_required
apply_confirmation_required
user_decision_received
run_resumed
```

验收标准：

1. 一轮用户输入只有一个最终 assistant answer。
2. 工具过程进入 trace，不污染聊天气泡。
3. 前端刷新后能恢复已完成消息。
4. 连接断开后能明确知道任务是否完成、失败或仍在运行。
5. 等待用户确认的任务不会被误判为失败或卡死。
6. 用户点击停止后，前端能显示取消已受理、正在停止、已取消或停止失败。
7. 停止操作不会造成重复最终回答或重复任务产物。

非目标：

1. 不一次性重做所有 UI。
2. 不一次性替换所有 SSE/WebSocket。
3. 不把 Project 做进前端。

### Phase 7: Complex Task Execution Hardening

阶段目标：

在统一 runtime 基础上，系统化支持 ReAct、Plan-and-Execute、Reflection。

步骤：

1. 梳理现有 PlanAgentService。
2. 明确 Direct、ReAct、Plan、Reflection 的选择规则。
3. 建立复杂任务策略选择器。
4. 建立 step verifier。
5. 建立 reflection/judge agent。
6. 建立失败修复和降级策略。
7. 建立任务预算。
8. 建立 human-in-the-loop checkpoint。
9. 建立计划确认、方案选择和风险确认流程。
10. 建立复杂任务评测集。

验收标准：

1. 简单问题不会走复杂计划。
2. 复杂任务可以进入 plan。
3. 工具循环不会无限执行。
4. 失败后有明确降级输出。
5. Reflection 不会导致无意义重复尝试。
6. 不确定、高风险或多路线任务会暂停等待用户确认。
7. 用户可以选择方案 A、方案 B、自定义修改或取消任务。

非目标：

1. 不做完全自主代码修改。
2. 不做 Project 代码/论文联动。
3. 不做多 agent 大规模并发调度。

### Phase 8: Project Entrance Reservation

阶段目标：

只预留 Project/Workspace 入口，不实现完整功能。

步骤：

1. 前端导航预留 Project/Workspace 入口。
2. 前端可以提供占位页或 preview 页面。
3. 后端接口和实体设计中保留 nullable projectId 扩展点。
4. 代码和路由层预留 Project/Workspace 入口。
5. 文档中说明 Project 当前为未来能力。
6. 避免当前数据库设计阻塞 Project 扩展。
7. 首版将入口放在左侧主导航，路由为 `/projects`，页面标注为 preview。

验收标准：

1. 用户不会误以为 Project 已完整可用。
2. 后续实现 Project 时不需要推翻当前核心模型。
3. 当前主功能不因 Project 预留而复杂化。
4. 前端能看到 Project/Workspace 入口，但该入口明确标注为预留或 preview。

非目标：

1. 不做项目创建。
2. 不做项目成员权限。
3. 不接代码仓库。
4. 不做项目知识图谱。

## 6. Issue 拆分原则

后续任何阶段都必须先拆 issue，再开发。

每个 issue 必须满足：

1. 目标单一。
2. 可以独立测试。
3. 可以独立回滚。
4. 不混合重构和新功能，除非 issue 明确说明原因。
5. 不跨过多模块，除非该 issue 的目标就是建立跨模块协议。
6. 包含非目标，防止范围膨胀。
7. 包含验收标准。
8. 包含测试要求。

推荐 issue 模板：

```text
## Background

## Goal

## Non-goals

## Impacted modules

## Design notes

## Acceptance criteria

## Tests

## Risks

## Rollback plan
```

## 7. 测试和 Merge Gate 原则

### 7.1 不允许最后才测试

后续开发必须测试伴随开发。

允许：

1. 先写测试再实现。
2. 先写少量实现再立即补 focused test。
3. 小步提交，小步验证。

不允许：

1. 一个大阶段全部开发完再测试。
2. 前端、后端、数据库、Agent 行为一起大改后再补测试。
3. 没有验收标准就开始开发。

### 7.2 后端测试要求

后端改动至少考虑：

1. 单元测试。
2. service 层测试。
3. repository/migration 测试。
4. controller integration test。
5. Agent/RAG focused test。

不是每个 issue 都必须包含所有测试类型，但必须说明选择了哪些测试和为什么。

### 7.3 前端测试要求

当前若没有完整前端测试体系，至少必须通过：

1. TypeScript 编译。
2. Vite build。
3. 手动验证关键交互。
4. 涉及状态机时增加最小可测逻辑或明确人工验证步骤。

### 7.4 Agent/RAG 评测要求

涉及 Agent、RAG、文献检索、论文润色质量时，仅靠传统单测不够。

必须补充 eval case：

1. 是否调用正确工具。
2. 是否避免不必要工具调用。
3. 是否正确引用知识库。
4. 是否不编造文献。
5. 是否能说明证据不足。
6. 是否能在工具失败时降级。
7. 是否只生成一个最终用户可见回答。

### 7.5 Merge Gate

默认 merge gate：

```text
mvn test
frontend pnpm build
相关 focused tests
必要的 Agent/RAG eval
文档更新
```

如果某项 gate 无法执行，必须在 PR 或最终说明中写明：

1. 未执行原因。
2. 风险。
3. 后续补验证方式。

## 8. AI 开发者执行规则

AI 开发者在本项目中执行任务时必须遵守：

1. 先确认当前阶段。
2. 先阅读本文件和相关阶段文档。
3. 不要跳阶段实现未来能力。
4. 不要在未拆 issue 的情况下进行大范围重构。
5. 不要把 Project 完整功能提前做进来。
6. 不要把 LangChain4j 当成替代产品架构的万能方案。
7. 不要删除现有 HarnessEngine 或 PlanAgentService，除非已有明确迁移 issue 和测试护栏。
8. 新增工具必须有 manifest、schema、错误输出、测试和使用边界。
9. 新增记忆写入必须有来源、置信度、去重和删除策略。
10. 修改 RAG 必须有评测。
11. 修改流式协议必须考虑断线、恢复和终态。
12. 修改论文润色必须保护可导出产物和人工确认流程。
13. 涉及不确定、高风险、多路线任务时，必须优先设计用户确认点。
14. 不允许 Agent 在未确认时直接应用高风险论文或代码修改。
15. 涉及长任务时必须设计取消接口、取消状态、取消事件和取消后的产物处理策略。
16. 后续代码、文档和 issue 产物应以 `paperagent_redo` 仓库为交付目标，不应误推到旧仓库。

## 8.1 GitHub 仓库和协作约定

后续本项目的目标 GitHub 仓库为：

```text
https://github.com/Ethan-WOKO/paperagent_redo.git
```

当前本地检查到的 remote 状态：

```text
origin -> https://github.com/Ethan-WOKO/PaperAgent_RAG.git
```

因此，首次真正 push 前必须先处理 remote。推荐方式：

```text
git remote rename origin old-origin
git remote add origin https://github.com/Ethan-WOKO/paperagent_redo.git
git remote -v
```

也可以在用户明确要求后直接将 `origin` 改为新仓库：

```text
git remote set-url origin https://github.com/Ethan-WOKO/paperagent_redo.git
git remote -v
```

默认分支和提交策略：

1. 不直接向主分支推送业务重构。
2. 每个 issue 使用独立分支。
3. 分支名建议使用 `codex/` 前缀，例如 `codex/phase1-process-baseline`。
4. roadmap、issue 模板、merge checklist 可以作为第一批独立提交。
5. 业务代码、数据库 migration、前端 UI、评测用例不要混在同一个无关提交中。
6. 每次 push 前必须确认 remote 指向 `paperagent_redo.git`。
7. 如果当前工作区存在用户未提交改动，AI 开发者不得擅自 reset、checkout 或覆盖。

推荐提交顺序：

```text
1. roadmap baseline
2. issue template and merge checklist
3. local merge gate scripts or docs
4. runtime/task/event design docs
5. small implementation issues with focused tests
```

推送前检查：

```text
git status --short
git remote -v
git branch --show-current
```

只有当目标 remote、分支、变更范围都确认后，才允许 push。

## 9. 当前推荐的下一步

当前不建议直接开始代码重构。

推荐顺序：

1. 将本文档确认为后续重构的 roadmap baseline。
2. 建立 Phase 1 开发流程基线：issue 模板、merge checklist、本地 merge gate、测试矩阵。
3. 确认 GitHub 目标仓库和 remote 调整方案。
4. 单独提交 roadmap 和流程文档，不混入业务代码。
5. 拆 Phase 2 的设计 issue：统一 Agent Runtime 边界设计。
6. 拆任务生命周期、事件流、取消机制的设计 issue。
7. 在设计 issue 通过后，再进入 LangChain4j RAG spike。
8. 每个 issue 单独确认目标和测试后再进入开发。

推荐第一批 issue：

```text
docs: establish roadmap baseline
docs: add issue template and merge checklist
docs: define local merge gate and verification matrix
docs: define agent/rag eval requirements
chore: align git remote with paperagent_redo target repository
design: agent runtime boundary and task lifecycle
design: unified task event protocol and cancellation semantics
spike: langchain4j rag comparison plan
```

其中前五个属于 Phase 1，主要是流程和仓库准备；后面三个属于 Phase 2/Phase 4 前置设计和 spike 准备。

## 10. 已确认决策和待细化问题

### 10.1 已确认决策

1. LangChain4j 接入顺序：先做 RAG spike，再做 ChatMemory/短期记忆 spike。
2. 论文润色统一任务化，由任务执行器、线程池或队列调度，支持并发和状态查询。
3. 文献检索全部任务化，即使只找 3 篇文献也创建独立任务。
4. Agent 后续既要能从对话创建论文润色/文献检索任务，也要能查询已有任务状态和结果。
5. 用户上传 `.bib` 文件时，推荐文献需要和已有 BibTeX 去重；未上传 `.bib` 时，首版直接输出推荐文献列表。
6. 长期记忆默认开启，跨会话生效，首版为 `USER` scope。
7. 长期记忆普通用户不需要总开关，但开发和调试需要可观测入口或调试开关。
8. 用户必须能查看、删除和修正自己的长期记忆。
9. 知识库版本治理覆盖论文原稿、润色稿和文献卡片。
10. 文献卡片需要同时存入 MySQL 和 Elasticsearch，便于结构化管理、检索复用和 RAG。
11. Project 在前端展示入口，同时在代码和路由层预留，但不实现完整 Project 功能。
12. 当前优先建立本地 merge gate 和脚本化检查，真正 CI 后续再引入。
13. Harness 规划中必须包含 human-in-the-loop：澄清问题、计划确认、方案选择、风险确认和应用确认。
14. 长任务首版采用 Kafka + MySQL 任务表：MySQL 作为任务状态事实源，Kafka 作为任务唤醒和分发通道。
15. Temporal 暂不在首版引入，等人机协同、多步骤暂停恢复、长时间 durable workflow 明显复杂后再评估。
16. 论文润色和文献检索采用独立线程池：`paperTaskExecutor` 低并发，`literatureTaskExecutor` 中等并发。
17. 首版 `paperTaskExecutor` 建议 `corePoolSize=1`、`maxPoolSize=2`、`queueCapacity=20`、`perUserRunningLimit=1`。
18. 首版 `literatureTaskExecutor` 建议 `corePoolSize=2`、`maxPoolSize=6`、`queueCapacity=50`、`perUserRunningLimit=2`。
19. 点击发送后的长任务和 Agent run 必须支持停止，停止应进入明确取消状态，而不是仅断开 SSE/WebSocket。
20. 长期记忆 UI 首版放在设置/调试页，例如 `/settings/memory-debug`，支持查看、删除、修正和 trace 调试。
21. 文献卡片设计采用 MySQL 事实源 + Elasticsearch 可重建检索索引。
22. Project/Workspace 入口首版放在左侧主导航，路由建议 `/projects`，页面标注 preview。
23. 统一任务生命周期语义应覆盖论文润色、文献检索和复杂 `AgentRun`。
24. 首版建议使用 SSE 推送服务端事件，使用 HTTP POST 发送消息、取消任务和提交用户确认；WebSocket 暂不作为强依赖。
25. 任务事件需要单独落库，用于断线恢复、调试、审计和用户查看任务轨迹。
26. 长期记忆首版使用独立 store，由 ContextBuilder 单独检索和注入，不和普通知识库 RAG 向量库混在一起。
27. 文献卡片首版采用用户内去重，同时保留未来全局 canonical literature 扩展点。

### 10.2 已审核通过的细化方案

#### 10.2.1 Kafka topic 和消费策略

首版 topic 建议：

```text
agent.task.paper-polish
agent.task.literature-search
agent.task.control
```

用途：

1. `agent.task.paper-polish` 负责论文润色任务唤醒和分发。
2. `agent.task.literature-search` 负责文献检索任务唤醒和分发。
3. `agent.task.control` 负责取消、暂停、恢复、用户确认等控制事件。

分区策略：

```text
partition key = userId
```

原因：

1. 同一用户任务更容易保持相对顺序。
2. 方便实现 `perUserRunningLimit`。
3. 后续如果单用户任务量很大，再评估改为 `userId + taskType` 或 `taskId`。

消费组建议：

```text
paper-polish-workers
literature-search-workers
agent-control-workers
```

重试和死信 topic 建议：

```text
agent.task.paper-polish.retry
agent.task.paper-polish.dlt
agent.task.literature-search.retry
agent.task.literature-search.dlt
```

首版不需要设计过多层级的 retry topic，但必须有 DLT，避免坏消息无限重试。

#### 10.2.2 MySQL 任务表、领取和超时恢复

建议先做统一任务表，不为论文润色和文献检索各自设计完全分裂的生命周期。

建议任务表：

```text
agent_tasks
  - id
  - task_type: PAPER_POLISH / LITERATURE_SEARCH / AGENT_RUN
  - user_id
  - project_id nullable
  - idempotency_key
  - status: PENDING / RUNNING / WAITING_USER / CANCEL_REQUESTED / CANCELLING / COMPLETED / FAILED / CANCELLED
  - priority
  - attempt_count
  - max_attempts
  - worker_id
  - lock_until
  - progress
  - current_stage
  - input_json
  - result_json
  - error_code
  - error_message
  - cancel_reason
  - created_at
  - updated_at
  - started_at
  - finished_at
```

任务产物较大时，不应把正文、报告、文件内容直接塞进 `result_json`。`result_json` 只保存 artifact metadata，实际文件、报告、润色稿等应进入 artifact 表或对象存储。

任务领取建议使用原子更新，避免多个 worker 抢同一个任务：

```sql
UPDATE agent_tasks
SET status = 'RUNNING',
    worker_id = ?,
    lock_until = ?,
    started_at = COALESCE(started_at, NOW())
WHERE id = ?
  AND status = 'PENDING';
```

`idempotency_key` 用于防止前端重复点击、网络重试、Kafka 重复投递造成重复建任务。建议生成规则：

```text
userId + taskType + normalizedInputHash + clientRequestId
```

watchdog 策略：

```text
RUNNING 且 lock_until 过期
  -> attempt_count < max_attempts: 回到 PENDING
  -> attempt_count >= max_attempts: FAILED
```

注意：

1. `CANCEL_REQUESTED` 和 `CANCELLING` 不能被 watchdog 自动重试。
2. watchdog 只能恢复可重试任务，不能恢复已经确认取消的任务。
3. 外部模型/API 调用必须有超时，否则 worker 无法按预期释放。

任务事件建议单独落库：

```text
agent_task_events
  - id
  - task_id
  - run_id nullable
  - user_id
  - event_type
  - sequence_no
  - payload_json
  - created_at
```

任务事件用于断线恢复、前端重放、调试、审计和用户查看任务轨迹。

#### 10.2.3 任务取消接口和安全停止

统一取消接口建议：

```text
POST /api/tasks/{taskId}/cancel
```

入参：

```text
cancelReason
clientRequestId
```

返回：

```text
taskId
status
cancelAccepted
message
```

取消流程：

```text
用户点击停止
  -> API 校验 task ownership
  -> status 改为 CANCEL_REQUESTED
  -> 发送 agent.task.control 事件
  -> worker 在安全点检查取消标记
  -> status 改为 CANCELLING
  -> 清理可清理资源
  -> status 改为 CANCELLED
```

安全点至少包括：

```text
开始处理前
每个章节/段落处理前后
每次外部 LLM/API 调用前后
写入最终产物前
任务阶段切换前
```

取消不是强杀线程。不可中断的外部调用应依赖超时返回。

已产生的中间结果可以保留，但必须标记：

```text
artifact_status = PARTIAL / CANCELLED
```

取消后的任务不得自动 retry，不得继续写最终论文，不得继续进入正式知识库。

#### 10.2.4 独立线程池参数和校准指标

首版参数：

```text
paperTaskExecutor:
  corePoolSize = 1
  maxPoolSize = 2
  queueCapacity = 20
  perUserRunningLimit = 1

literatureTaskExecutor:
  corePoolSize = 2
  maxPoolSize = 6
  queueCapacity = 50
  perUserRunningLimit = 2
```

校准指标：

```text
任务平均耗时
P95/P99 耗时
队列等待时间
取消响应时间
失败率
外部 API 超时率
用户并发数
CPU/内存/数据库连接池/Kafka lag
```

调参原则：

1. 论文润色偏长、LLM 成本高，首版并发必须保守。
2. 文献检索偏 IO 和外部 API 调用，可以适当提高并发。
3. 首先保护系统稳定，再追求吞吐。

#### 10.2.5 长期记忆调试页

建议路由：

```text
/settings/memory-debug
```

页面能力：

1. 查看当前用户长期记忆。
2. 按 tag、source、status 过滤。
3. 删除记忆。
4. 编辑或修正记忆。
5. 查看某次对话检索到了哪些记忆。
6. 查看哪些记忆被注入上下文。
7. 查看哪些候选记忆被跳过以及原因。

权限建议：

1. 普通用户只能查看和管理自己的记忆。
2. 管理员或开发者调试能力必须走后台权限。
3. 删除采用软删除，不物理删除。
4. 模型上下文必须排除 `DELETED` 记忆。

trace 字段建议：

```text
runId
conversationId
memoryId
retrievalScore
injected
skipReason
createdOrUpdated
sourceMessageId
```

长期记忆首版使用独立 store，由 ContextBuilder 单独检索和注入，不和普通知识库 RAG 向量库混在一起。

#### 10.2.6 文献卡片 migration、ES mapping 和索引重建

MySQL migration 首版建议建三张表：

```text
literature_cards
literature_card_versions
literature_task_cards
```

ES index 使用版本化命名：

```text
yanban-literature-cards-v1
```

同时使用 alias：

```text
yanban-literature-cards-read
yanban-literature-cards-write
```

后续 mapping 变化时，创建 `v2`，重建完成后切 alias，避免影响业务查询。

重建脚本必须支持：

```text
全量重建
按 userId 重建
按 cardId 重建
失败重试
dry run
```

查询策略：

```text
先用 MySQL 做权限和状态约束
ES 做 keyword/vector/hybrid recall
再回 MySQL hydrate 完整卡片
```

文献卡片首版采用用户内去重，同时保留未来全局 canonical literature 扩展点。首版不做全局去重的原因：

1. 全局去重会引入来源可信度和版权边界问题。
2. 用户私有标注、选择、拒绝记录不能和其他用户混在一起。
3. 用户内去重足以解决首版重复推荐和重复入库问题。

#### 10.2.7 Project/Workspace preview 页

入口建议：

```text
左侧主导航
路由 /projects
名称 Project Preview 或 Workspace Preview
位置靠近 Chat/Workspace，在 Paper Polish 之前
```

页面文案方向：

```text
Project/Workspace 是后续能力。
未来会把论文、代码、文献、实验结果和对话组织到一个研究项目中。
当前阶段仅预留入口，不支持创建项目和绑定代码仓库。
```

首版不要放可用的“创建项目”按钮。可以放 disabled 按钮，文案类似：

```text
Create project - Coming later
```

#### 10.2.8 SSE、HTTP 和 WebSocket 分工

首版建议：

```text
SSE 负责服务端事件推送。
HTTP POST 负责发送消息、取消任务、用户确认。
WebSocket 暂不作为强依赖。
```

原因：

1. SSE 更适合服务端向前端推送线性事件流。
2. 发送消息、取消任务、用户确认使用 HTTP POST 更容易做鉴权、幂等和重试。
3. 当前主要痛点是稳定事件模型和断线恢复，不是双向低延迟通信。
4. WebSocket 可以保留，但不要让首版稳定性依赖 WebSocket。

#### 10.2.9 统一任务生命周期和 AgentRun

统一任务生命周期应覆盖：

```text
PAPER_POLISH
LITERATURE_SEARCH
AGENT_RUN
```

原因：

1. 停止、恢复、等待用户确认、失败重试不应在不同模块各做一套。
2. 前端状态机可以消费同一类事件。
3. 后续 complex agent workflow 可以复用任务表、事件表、取消接口和 watchdog。
4. 论文润色和文献检索本质上也是 Agent 可启动的长任务。

### 10.3 已验证的项目现状

以下结论来自当前代码和本地 Docker 服务检查，后续拆 issue 时应优先复用现有基础设施。

#### 10.3.1 Kafka 现状

项目已经具备 Kafka 基础设施：

1. `docs/docker-compose.yml` 中已有 `yanban-kafka` 服务。
2. 本地 Kafka 当前健康运行。
3. `KAFKA_AUTO_CREATE_TOPICS_ENABLE` 在本地 compose 中为 `true`。
4. `application-dev.yml` 中已有 `spring.kafka.bootstrap-servers` 配置。
5. `yanban-knowledge` 已引入 `spring-kafka`，并通过 `KafkaTemplate` 和 `@KafkaListener` 支撑知识库文件异步处理。
6. 当前已存在 topic：`file-processing`。

结论：

1. 首版长任务队列可以继续使用现有 Kafka，不需要引入新消息中间件。
2. 新的论文润色、文献检索、控制事件 topic 不应复用 `file-processing`，应按 10.2.1 新增独立 topic。
3. 本地开发可以暂时依赖 topic auto-create。
4. 生产或稳定测试环境不应只依赖 auto-create，后续需要 topic 初始化脚本或部署前检查。

#### 10.3.2 MinIO 和对象存储现状

项目已经具备 MinIO 基础设施：

1. `docs/docker-compose.yml` 中已有 `yanban-minio` 服务。
2. 本地 MinIO 当前健康运行。
3. 当前 bucket `yanban-agent` 已存在。
4. bucket 中已有 `kb/` 和 `paper/` 前缀。
5. `yanban-knowledge` 使用 MinIO 保存知识库上传文件。
6. `yanban-paper` 使用 `PaperStorageService` 保存论文原稿、最终稿和中间 artifact。
7. `PaperStorageService` 当前支持 MinIO 优先，并保留本地文件 fallback。

结论：

1. 任务产物文件继续放 MinIO，不直接写入 MySQL。
2. MySQL 只保存 metadata、objectKey、hash、size、mimeType、artifactStatus、来源任务、创建时间等结构化信息。
3. 后续更稳妥的方向是抽象 `ObjectStorageService`，首个实现仍然是 MinIO。
4. 不应把大文件、长报告、完整论文正文直接塞进 MySQL 的 JSON 字段。

#### 10.3.3 论文任务现状

论文模块已经有局部任务能力：

1. 已有 `paper_tasks` 表和 `PaperTask` 实体。
2. 已有 `paperTaskExecutor`。
3. 已有论文 SSE 事件流。
4. 已有 `/paper/tasks/{taskId}/pause`、`/resume`、`/stop`。
5. 当前停止状态使用 `STOPPED`，不是统一规划中的 `CANCEL_REQUESTED / CANCELLING / CANCELLED`。
6. 当前控制状态主要保存在进程内 `ControlState`，进程重启后不可恢复。

结论：

1. 不应立即强行删除或推翻现有论文任务链路。
2. 后续应通过 adapter 或渐进迁移，把现有 `paper_tasks` 接入统一任务生命周期。
3. 现有 `/paper/tasks/{taskId}/stop` 需要兼容保留，后续可以新增统一 `/api/tasks/{taskId}/cancel`。
4. `STOPPED` 后续应兼容映射到 `CANCELLED` 语义，最终统一为新状态模型。
5. 进程内控制状态需要逐步迁移为数据库状态和任务事件，保证重启、断线、恢复时行为稳定。

### 10.4 已确认的执行默认决策

这些决策用于指导后续 issue 拆分和实现。除非发现新的项目约束，否则 AI 开发者应按以下默认值推进。

#### 10.4.1 文件和任务产物存储

默认决策：

1. 文件、论文原稿、润色稿、导出产物、检索报告、长 JSON artifact 放 MinIO。
2. MySQL 存结构化 metadata 和 objectKey。
3. 不把大文件直接存入 MySQL。
4. 当前优先沿用 `yanban-agent` bucket 和现有 `paper/`、`kb/` 前缀体系。
5. 后续新增长任务 artifact 时，应记录 `artifact_status`。

建议 artifact 状态：

```text
ACTIVE
PARTIAL
CANCELLED
FAILED
SUPERSEDED
```

取消任务后生成的中间产物可以保留，但必须标记为 `PARTIAL` 或 `CANCELLED`，不得当作正式最终结果。

#### 10.4.2 clientRequestId 和幂等

默认决策：

1. 用户从前端点击发送、启动任务、取消任务、提交确认时，`clientRequestId` 由前端生成。
2. 前端应使用 UUID。
3. 后端收到后必须落库并返回。
4. Agent/tool 内部自动启动任务时，允许后端生成 `clientRequestId`。
5. 如果前端暂时未传 `clientRequestId`，后端可以生成兜底值，但这只能作为兼容策略，不能替代前端幂等。

推荐放置方式：

```text
Header: X-Client-Request-Id
或 request body: clientRequestId
```

幂等语义：

```text
same userId + same taskType + same clientRequestId
  -> 返回已有任务或已有操作结果
  -> 不重复创建任务
```

注意：

1. `clientRequestId` 不是数据库主键。
2. `clientRequestId` 只用于一次用户意图的幂等。
3. 同一用户重新发起相同论文或相同检索时，应生成新的 `clientRequestId`，否则会被当作重复请求。

#### 10.4.3 取消后的部分结果体验

默认决策：

1. 用户取消任务后，允许查看已经产生的部分结果。
2. 部分结果不得默认作为最终结果下载。
3. UI 必须清楚显示任务已取消，结果为部分产物。
4. 正式“下载最终结果”只对 `COMPLETED` 任务开放。
5. 部分产物可以在任务详情或调试信息中查看，必要时提供单独下载入口。

用户体验目标：

1. 用户点击停止后能立即知道停止请求已受理。
2. 如果外部模型调用尚未返回，前端显示“正在停止”，而不是卡死。
3. 任务最终进入 `CANCELLED` 后，用户能看到已经完成到哪一步。
4. 用户不会误把部分结果当作完整结果使用。

#### 10.4.4 长期记忆编辑策略

默认决策：

1. 用户修正长期记忆时，不直接覆盖原记录。
2. 系统创建新的 `ACTIVE` 版本。
3. 旧版本标记为 `SUPERSEDED`。
4. 用户删除长期记忆时，状态改为 `DELETED`，采用软删除。
5. 模型检索和上下文注入只使用 `ACTIVE` 记忆。

原因：

1. 保留审计链，方便排查为什么模型曾经注入错误记忆。
2. 支持用户修正后观察效果。
3. 避免直接覆盖导致长期记忆问题无法复盘。

#### 10.4.5 文献卡片用户偏好

默认决策：

1. 首版预留用户偏好字段或偏好表。
2. 首版不强制实现完整收藏、拒绝、不再推荐交互。
3. 后续加深时优先采用独立用户偏好表，而不是把用户偏好直接写死在全局文献卡片中。

建议预留能力：

```text
favorite
rejected
do_not_recommend
preference_status
preference_reason
preference_updated_at
```

更稳妥的后续表：

```text
literature_user_preferences
  - id
  - user_id
  - card_id
  - preference_status: FAVORITE / REJECTED / DO_NOT_RECOMMEND / NEUTRAL
  - reason
  - created_at
  - updated_at
```

#### 10.4.6 Project preview 范围

默认决策：

1. 首版只做左侧导航入口和 `/projects` preview 占位页。
2. 暂不在论文上传页、知识库上传页或聊天页加入 disabled project selector。
3. 后端实体和接口设计可预留 nullable `projectId`。
4. 不实现项目创建、项目权限、项目资产绑定和代码仓库接入。

原因：

1. 避免用户误以为 Project 功能已经半可用。
2. 避免当前主线重构被 Project 交互牵走。
3. 保持未来扩展点，同时不增加当前功能复杂度。

#### 10.4.7 Kafka、DB 事务和消息一致性

默认决策：

1. MySQL 任务表是事实源。
2. Kafka 是任务唤醒和分发通道。
3. 创建任务时必须先写 MySQL。
4. Kafka 消息发送应放在数据库事务提交之后。
5. 首版可以使用 Spring transaction after-commit hook。
6. 后续如果任务消息一致性问题变复杂，再引入 outbox pattern。

风险说明：

1. 如果 DB 回滚但 Kafka 已发，会出现 worker 找不到任务。
2. 如果 DB 成功但 Kafka 发送失败，任务会留在 `PENDING`。
3. 因此需要 watchdog 或 pending scanner 兜底扫描长时间未被领取的 `PENDING` 任务。

首版可接受策略：

```text
DB commit
  -> after commit send Kafka
  -> send failed: task remains PENDING
  -> pending scanner / manual retry republishes task event
```

#### 10.4.8 兼容现有论文任务接口

默认决策：

1. 保留现有论文任务 API，避免前端和用户流程突然断裂。
2. 新增统一任务 API 时，采用兼容迁移，不一次性替换。
3. `/paper/tasks/{taskId}/stop` 可以内部映射到统一取消语义。
4. `STOPPED` 状态在响应层可兼容显示，但内部新任务语义应逐步统一到 `CANCELLED`。
5. `paperTaskExecutor` 现有参数为 `corePoolSize=2`、`maxPoolSize=4`、`queueCapacity=20`；后续为了稳定和成本控制，规划目标调整为更保守的 `corePoolSize=1`、`maxPoolSize=2`、`queueCapacity=20`。

迁移原则：

1. 先建立统一状态和事件协议。
2. 再让现有论文任务通过 adapter 对齐协议。
3. 最后再考虑是否合并底层表结构。
4. 不允许为了追求“统一”而破坏已经可用的论文润色流程。

### 10.5 后续执行中仍需由代码上下文决定的细节

以下问题不需要用户提前拍板，AI 开发者应在具体 issue 中根据现有代码、用户体验和稳定性目标做保守决策，并在 issue/PR 中说明理由：

1. 统一任务表 `agent_tasks` 是先新增并 adapter 旧表，还是先扩展 `paper_tasks` 字段。
2. 任务事件表是否先只覆盖新任务，还是同步记录现有论文 SSE 事件。
3. MinIO objectKey 前缀是否继续沿用现有 `paper/`，还是为统一任务新增 `tasks/{taskType}/...` 前缀。
4. `clientRequestId` 首版放 header 还是 body；如果前端 http 封装更适合 header，优先 header。
5. pending scanner 的执行频率、批量大小和锁策略。
6. Kafka DLT 消息是否首版提供后台重放接口，还是只提供日志和脚本处理。
7. 文献卡片用户偏好首版用字段预留还是直接建独立表。
8. 长期记忆版本表是单表状态字段实现，还是拆 `agent_memory_versions`。

决策原则：

1. 优先用户体验清晰。
2. 优先任务稳定、可恢复、可调试。
3. 优先兼容现有可用功能。
4. 不偏离本文档定义的产品方向。
5. 不为了抽象统一而提前做大范围推翻式重构。
