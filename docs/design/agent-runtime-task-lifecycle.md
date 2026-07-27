# Agent Runtime 边界与统一任务生命周期设计

> **状态：HISTORICAL / PARTIALLY SUPERSEDED（2026-07-27）。**
> 当前 Agent 核心边界以 `docs/design/v2-agent-core-integration.md` 和
> `agent-v2/` 的冻结契约为准。本文继续作为 V1 产品生命周期和兼容 API 的
> 背景材料；其中保留旧 `PlanAgentService` 作为目标 Runtime、增加额外顶层策略，
> 或让 V2 核心依赖产品实现的建议均已被替代。
>
> 关联 issue: #4 `design: 设计 Agent Runtime 边界和统一任务生命周期`
>
> 本文是后续实现统一 Agent Runtime、任务生命周期、事件协议、取消机制、RAG/记忆接入的设计基线。当前 PR 只做设计沉淀，不修改业务代码。

## 1. 背景

当前项目已经有三条可用但彼此割裂的执行链路：

1. 普通对话：`AgentService -> AgentToolPolicyEngine -> HarnessEngine`。
2. 复杂计划：`PlanAgentService -> agent_plans / agent_plan_steps / agent_plan_events -> HarnessEngine`。
3. 论文任务：`PaperController -> PaperTaskService -> PaperOrchestrator -> paper_tasks / paper artifacts / SSE`。

这些链路都有价值，不应该在重构早期直接推翻：

1. `HarnessEngine` 已经能完成模型/tool loop、tool budget、防伪 tool-call 文本、RAG 注入、部分流式保护。
2. `PlanAgentService` 已经有 plan-and-execute、有限并发、step verification、repair、degrade、事件记录和取消。
3. `PaperOrchestrator` 已经有论文解析、结构确认、文献检索、Gap 分析、分章润色、产物保存、SSE、暂停/停止检查点。

但它们的生命周期、取消语义、事件格式、任务查询入口、错误重试和可观测性不统一。后续如果直接把 LangChain4j、长期记忆、RAG spike、论文润色工具化、文献检索任务化堆到现有入口上，会继续放大维护成本。

因此本阶段目标是先定义统一边界，再按小 issue 渐进落地。

## 2. 设计原则

1. Harness 是运行治理范式，不等同于当前 `HarnessEngine` 这个类。
2. LangChain4j 是能力框架，负责降低 RAG、ChatMemory、tool/function calling 等能力的开发成本，不负责产品级任务治理。
3. `AgentRuntimeService` 是未来统一入口，但不是一次性替换现有所有链路。
4. MySQL 任务表是事实源，Kafka 是任务唤醒和分发通道。
5. SSE 负责服务端事件推送，HTTP POST 负责发送消息、取消任务、提交用户确认。
6. 论文任务、文献检索任务、Agent run 都必须能映射到统一任务生命周期。
7. 取消不是强杀线程，而是在安全点检查并终止。
8. Project/Workspace 当前只预留可空 `projectId` 设计位置，不实现完整 Project 功能。

## 3. 目标架构

```text
UserTurn / TaskRequest
  -> AgentRuntimeService
  -> StrategySelector
  -> ContextBuilder
  -> ToolPolicy
  -> RuntimeAdapter
       -> CurrentHarnessAdapter
       -> PlanExecuteAdapter
       -> PaperTaskAdapter
       -> LiteratureTaskAdapter
       -> LangChain4jRagAdapter
  -> EventSink
  -> FinalAnswerComposer
  -> MemoryUpdater
  -> Persistence
```

### 3.1 AgentRuntimeService

职责：

1. 接收一次用户意图，生成 `runId` 或 `taskId`。
2. 决定该意图是普通对话、单步工具调用、复杂计划、论文长任务、文献检索长任务，还是需要用户确认。
3. 调用 `ContextBuilder` 装配短期上下文、长期记忆、RAG 证据和 skill prompt。
4. 调用 `ToolPolicy` 计算工具 allowlist、预算、权限和可见性。
5. 选择 `RuntimeAdapter` 执行。
6. 通过 `EventSink` 写入统一事件，并向 SSE 推送用户可见事件。
7. 在任务结束后调用 `MemoryUpdater`，只把合适的高价值信息写入长期记忆。

首批实现时可以先新增接口和骨架，不要求立即接管所有入口。

### 3.2 RuntimeAdapter

`RuntimeAdapter` 是对现有执行器和未来框架能力的包裹层，避免上层业务直接依赖具体实现。

建议接口语义：

```java
public interface RuntimeAdapter {
    AgentRunResult run(AgentRunRequest request, AgentEventSink eventSink);
    boolean supports(AgentStrategy strategy);
}
```

首批 adapter 映射：

1. `CurrentHarnessAdapter`：包装当前 `HarnessEngine`，服务普通对话和单步 ReAct。
2. `PlanExecuteAdapter`：包装 `PlanAgentService`，服务 plan-and-execute。
3. `PaperTaskAdapter`：包装 `PaperOrchestrator` 和 `paper_tasks`，服务论文润色长任务。
4. `LiteratureTaskAdapter`：后续从现有 `LiteratureService` 抽出，服务独立文献检索任务。
5. `LangChain4jRagAdapter`：只负责 RAG spike 和底层 RAG 能力对照，不直接接管任务生命周期。

### 3.3 ContextBuilder

职责：

1. 装配系统提示和运行时身份保护。
2. 注入当前 session summary 和最近若干轮原始消息。
3. 注入短期 ChatMemory 结果。
4. 检索并注入长期记忆。
5. 检索并注入知识库 RAG 证据。
6. 按用户、未来 project、文档版本和来源做权限过滤。
7. 输出可审计的 context trace，说明哪些证据被注入、哪些被跳过。

ContextBuilder 不应该直接执行任务，也不应该把所有历史消息无界追加给模型。

### 3.4 ToolPolicy

职责：

1. 根据用户意图选择工具 allowlist。
2. 控制每类工具最大调用次数、超时、重试、是否用户可见。
3. 区分只读工具和有副作用工具。
4. 对论文润色、文献检索这类长任务工具，只允许返回 `taskId` 和初始状态，不允许同步阻塞到任务完成。
5. 对需要用户确认的危险或易跑偏计划，要求进入 `WAITING_USER`。

### 3.5 EventSink

职责：

1. 写入统一任务事件表。
2. 推送 SSE 事件。
3. 记录 traceId、runId/taskId、stepId、toolCallId、payload、耗时、错误。
4. 区分用户可见事件和调试事件。
5. 支持断线后按 lastEventId 或事件序号恢复。

事件协议的具体字段放到 issue #5 继续细化。

## 4. Strategy 类型

建议先定义以下策略：

```text
DIRECT
SINGLE_STEP_REACT
PLAN_EXECUTE
PLAN_EXECUTE_WITH_REFLECTION
LONG_RUNNING_TOOL_TASK
WAIT_FOR_USER_CONFIRMATION
```

含义：

1. `DIRECT`：稳定知识、简单解释、普通写作，不调用工具。
2. `SINGLE_STEP_REACT`：一次或少量工具调用即可完成的问题。
3. `PLAN_EXECUTE`：多步骤任务，需要拆分、执行、汇总。
4. `PLAN_EXECUTE_WITH_REFLECTION`：执行后需要验证证据充分性、结果完整性和是否需要修复。
5. `LONG_RUNNING_TOOL_TASK`：论文润色、文献检索、未来代码分析等长任务。
6. `WAIT_FOR_USER_CONFIRMATION`：计划存在不确定、风险或多方案分叉，需要用户确认。

## 5. 统一任务生命周期

统一状态必须覆盖 `PAPER_POLISH`、`LITERATURE_SEARCH`、`AGENT_RUN`。

```text
PENDING
RUNNING
WAITING_USER
CANCEL_REQUESTED
CANCELLING
COMPLETED
FAILED
CANCELLED
```

### 5.1 状态定义

| 状态 | 含义 | 是否终态 |
| --- | --- | --- |
| `PENDING` | 已创建任务，等待 worker 领取或执行器调度 | 否 |
| `RUNNING` | 正在执行 | 否 |
| `WAITING_USER` | 等待用户确认计划、补充信息、选择方案或确认修改 | 否 |
| `CANCEL_REQUESTED` | 用户已请求取消，任务尚未到达安全停止点 | 否 |
| `CANCELLING` | worker 已看到取消请求，正在清理资源或停止后续步骤 | 否 |
| `COMPLETED` | 正常完成，最终产物可用 | 是 |
| `FAILED` | 执行失败，可能可重试 | 是 |
| `CANCELLED` | 已取消，不再自动重试，不再写最终产物 | 是 |

`DEGRADED` 不作为任务终态，建议作为 step/result quality 标记。原因是任务仍然可能 `COMPLETED`，只是部分步骤降级。

### 5.2 状态转换

```text
PENDING -> RUNNING
PENDING -> CANCEL_REQUESTED
RUNNING -> WAITING_USER
RUNNING -> CANCEL_REQUESTED
RUNNING -> COMPLETED
RUNNING -> FAILED
WAITING_USER -> RUNNING
WAITING_USER -> CANCEL_REQUESTED
CANCEL_REQUESTED -> CANCELLING
CANCEL_REQUESTED -> CANCELLED
CANCELLING -> CANCELLED
CANCELLING -> FAILED
FAILED -> PENDING   // 用户或系统显式 retry
```

禁止转换：

1. `CANCELLED -> RUNNING`。
2. `COMPLETED -> RUNNING`。
3. `FAILED -> RUNNING`，必须先创建 retry attempt 或回到 `PENDING`。
4. `CANCEL_REQUESTED/CANCELLING` 后继续写正式最终产物。

## 6. 现有模块映射

### 6.1 普通聊天

现状：

1. `AgentService` 保存用户消息，创建 `AgentTurn`。
2. `AgentToolPolicyEngine` 决定工具 allowlist。
3. `HarnessEngine` 执行模型和工具循环。
4. 结果保存为 `AgentMessage`，`AgentTurn` 标记完成或失败。

目标映射：

1. `AgentTurn` 可作为普通对话 run 的兼容实体。
2. 后续新增 `agent_tasks` 后，普通聊天可以映射为 `AGENT_RUN`。
3. 当前 `AgentTurn` 状态 `RUNNING/COMPLETED/FAILED/CANCELLED` 可直接兼容统一状态。
4. 首批不强制迁移历史 `AgentTurn`。

### 6.2 计划执行

现状：

1. `AgentPlanStatus` 包含 `REVIEWING/RUNNING/PAUSED/COMPLETED/FAILED/CANCELLED`。
2. `PlanAgentService` 使用进程内固定线程池执行计划。
3. 有 `agent_plan_events` 记录 plan/step 事件。
4. `cancelPlan` 直接把 plan 标记为 `CANCELLED`。

目标映射：

1. `REVIEWING` 映射为 `WAITING_USER` 或 `PENDING`，取决于是否需要用户确认。
2. `PAUSED` 暂时保留，但新统一模型优先使用 `WAITING_USER` 表达等待用户输入。
3. `RUNNING/COMPLETED/FAILED/CANCELLED` 直接映射统一状态。
4. 后续取消不应只标记终态，应支持 `CANCEL_REQUESTED -> CANCELLING -> CANCELLED`。
5. `PlanAgentService` 应作为 `PlanExecuteAdapter` 被 Runtime 调用，而不是被删除。

### 6.3 论文任务

现状：

1. `paper_tasks.status` 使用 `PENDING/RUNNING/WAITING_INPUT/PAUSED/STOPPED/COMPLETED/FAILED` 等语义。
2. `PaperOrchestrator` 通过进程内 `ControlState` 控制暂停和停止。
3. `/api/v1/paper/tasks/{taskId}/stop` 会立即把状态设为 `STOPPED`。
4. 进程重启后 `ControlState` 不可恢复。

目标映射：

1. `WAITING_INPUT` 映射为 `WAITING_USER`。
2. `STOPPED` 在响应层兼容映射为 `CANCELLED`。
3. 新实现应逐步引入 `CANCEL_REQUESTED/CANCELLING/CANCELLED`。
4. `/api/v1/paper/tasks/{taskId}/stop` 保留，但内部后续应调用统一 cancel 语义。
5. 当前 `PaperOrchestrator` 继续保留，后续通过 `PaperTaskAdapter` 接入统一任务生命周期。

### 6.4 文献检索

现状：

1. 文献检索主要嵌在论文任务中。
2. 也存在 `SearchLiteratureToolExecutor`、`LiteratureService`、`AdHocLiteratureSearchService` 等能力。

目标映射：

1. 文献检索必须独立任务化，即使用户只要求找 3 篇也创建任务。
2. 新任务类型为 `LITERATURE_SEARCH`。
3. 使用独立 `literatureTaskExecutor`。
4. 文献卡片沉淀到 MySQL，ES 作为可重建索引。
5. 后续 Agent 工具只返回 `taskId/status`，不阻塞等待全部检索结束。

## 7. 数据模型方向

首选渐进路径：新增通用任务表，不直接扩展或推翻 `paper_tasks`。

建议后续 issue 设计：

```text
agent_tasks
  id
  user_id
  project_id nullable
  task_type
  status
  strategy
  source
  source_id
  client_request_id
  title
  input_summary
  progress_percent
  current_stage
  error_code
  error_message
  cancellation_reason
  retry_count
  max_retries
  created_at
  updated_at
  started_at
  finished_at

agent_task_events
  id
  task_id
  user_id
  event_type
  visibility
  sequence
  payload_json
  trace_id
  created_at
```

关系策略：

1. `agent_tasks` 是统一生命周期事实源。
2. 业务表继续保存业务细节，例如 `paper_tasks`、未来 `literature_search_tasks`。
3. `agent_tasks.source/source_id` 指向业务表，避免第一阶段强迁移。
4. 未来业务表可增加 `agent_task_id`，但不作为首批强要求。
5. `project_id` 预留 nullable，不做完整 Project 功能。

## 8. API 边界

建议统一任务 API：

```text
GET  /api/v1/tasks/{taskId}
GET  /api/v1/tasks/{taskId}/events
POST /api/v1/tasks/{taskId}/cancel
POST /api/v1/tasks/{taskId}/retry
POST /api/v1/tasks/{taskId}/confirmations/{confirmationId}/answer
```

兼容要求：

1. 现有 `/api/v1/paper/**` 不删除。
2. 现有 `/api/v1/agent/**` 和 `/api/v1/agent/plans/**` 不删除。
3. 新 API 先覆盖新任务和 adapter 任务查询，后续再逐步让前端迁移。
4. `/api/v1/paper/tasks/{taskId}/stop` 后续内部映射到统一 `cancel`，但前端可继续调用旧接口直到迁移完成。

## 9. 用户确认与防跑偏

Agent 遇到不确定问题时不应强行执行。统一 Runtime 需要支持：

1. 给出计划并请求用户确认。
2. 给出方案 A/B，让用户选择。
3. 允许用户自定义计划。
4. 用户确认前任务进入 `WAITING_USER`。
5. 用户拒绝或取消后任务进入 `CANCELLED` 或回到草稿态。

触发条件：

1. 涉及大范围论文改写、批量替换、不可逆操作。
2. 用户目标含糊，继续执行可能跑偏。
3. 检索结果证据不足但任务要求高确定性。
4. 计划成本较高、耗时较长或会消耗大量外部 API 调用。
5. 存在多个合理路线且取舍会影响最终结果。

## 10. 取消和重试

取消语义：

1. 用户点击停止后，API 立即写入 `CANCEL_REQUESTED` 并返回。
2. worker 在安全点检查取消标记。
3. worker 开始清理时写入 `CANCELLING`。
4. 清理完成后写入 `CANCELLED`。
5. 已取消任务不得自动 retry。
6. 已产生中间产物可以保留，但必须标记 `PARTIAL` 或 `CANCELLED`。
7. 正式最终产物只允许 `COMPLETED` 任务下载或入库。

重试语义：

1. 只有 `FAILED` 可自动或手动 retry。
2. `CANCELLED` 默认不可自动 retry，只能由用户显式新建任务或手动 retry。
3. retry 必须记录 attempt，不覆盖旧事件。
4. 由于 Kafka 发送失败导致长时间 `PENDING` 的任务，由 pending scanner 重新投递。

## 11. 兼容迁移阶段

### Phase A: 设计基线

1. 完成本文。
2. 完成统一事件协议和取消语义设计。
3. 完成 LangChain4j RAG spike 评估方案。

### Phase B: 任务骨架

1. 新增 `agent_tasks` 和 `agent_task_events` migration。
2. 新增任务状态 enum 和转换校验。
3. 新增 `AgentTaskService` 查询和取消接口骨架。
4. 新增事件写入服务，但不立即迁移所有业务。

### Phase C: 现有链路 adapter 化

1. 普通聊天接入 `AgentRuntimeService` 外壳。
2. `HarnessEngine` 包装为 `CurrentHarnessAdapter`。
3. `PlanAgentService` 包装为 `PlanExecuteAdapter`。
4. `PaperOrchestrator` 包装为 `PaperTaskAdapter`。
5. 保留旧 API，新增统一任务查询视图。

### Phase D: 新能力任务化

1. 文献检索独立为 `LiteratureSearchTask`。
2. 论文润色启动工具化：`paper_polish_start/status/result/cancel`。
3. 文献检索启动工具化：`literature_search_start/status/result/cancel`。
4. Kafka topic、独立线程池、pending scanner、cancel control event 落地。

### Phase E: RAG 和记忆接入

1. 完成 LangChain4j RAG 对照实验。
2. 决定底层 RAG 组件替换或并行运行方式。
3. 接入短期 ChatMemory。
4. 接入长期记忆 store、调试页、用户查看/删除/修正能力。

### Phase F: 版本治理和知识沉淀

1. 论文原稿/润色稿版本入库。
2. 文献卡片 MySQL + ES。
3. 去重、排序、过时版本降权。
4. Project/Workspace 继续只保留入口，直到主线稳定后再扩展。

## 12. 测试要求

后续实现每个 issue 至少需要覆盖：

1. 状态转换单元测试。
2. 用户只能查询和取消自己的任务。
3. `clientRequestId` 幂等测试。
4. 取消从 `RUNNING` 到 `CANCELLED` 的路径测试。
5. 取消后不写最终产物测试。
6. pending scanner 重新投递测试。
7. Kafka 消息消费失败后的失败记录或 DLT 行为测试。
8. SSE 断线恢复或事件序号测试。
9. 论文旧 stop API 兼容测试。
10. Plan 旧状态映射测试。

文档类 PR 的验证范围：

1. `git diff --check`。
2. 人工核对文档是否覆盖 roadmap 中的已确认决策。

## 13. 风险和回滚

风险：

1. 过早把所有入口切到统一 Runtime，可能破坏已可用的论文流程。
2. 只新增抽象不接入真实链路，会形成空架构。
3. 统一状态和旧状态双轨太久，前端显示可能混乱。
4. Kafka 与 DB 一致性处理不足，会导致任务卡在 `PENDING`。
5. 取消语义如果不落库，进程重启后仍不可恢复。

回滚策略：

1. 旧 API 和旧业务表保留。
2. adapter 接入按入口灰度，不一次性替换。
3. 统一任务表先作为镜像/查询视图使用，确认稳定后再成为主写路径。
4. 前端先提供统一任务详情入口，不立即删除论文页原有状态显示。

## 14. 后续 issue 建议

1. `design: 设计统一事件协议、SSE 恢复和取消事件字段`。
2. `implementation: 新增 agent_tasks 与 agent_task_events migration`。
3. `implementation: 新增 AgentTaskService 查询和取消接口骨架`。
4. `implementation: 将论文 stop 兼容映射到 CANCEL_REQUESTED/CANCELLED`。
5. `implementation: 新增 AgentRuntimeService 与 CurrentHarnessAdapter 骨架`。
6. `implementation: 文献检索任务化并引入 literatureTaskExecutor`。
7. `spike: LangChain4j RAG 与当前自研 RAG 对照实验`。

## 15. 本设计的非目标

1. 不在本阶段替换 `HarnessEngine`。
2. 不在本阶段替换 `PaperOrchestrator`。
3. 不在本阶段引入完整 Project/Workspace。
4. 不在本阶段把 LangChain4j 作为唯一 Agent Runtime。
5. 不在本阶段实现完整长期记忆。
6. 不在本阶段删除任何现有用户可用 API。
