# 统一事件协议与取消语义设计

> 关联 issue: #5 `design: 设计统一事件协议和取消语义`
>
> 本文承接历史设计 `docs/历史归档/已被替代/旧架构与路线/agent-runtime-task-lifecycle.md`，细化 `agent_task_events`、SSE 恢复、Kafka 控制事件、取消状态转换、前端展示和兼容迁移策略。当前 PR 只做设计，不修改业务代码。

## 1. 背景

当前项目有多套事件和取消模型：

1. 论文任务使用 `PaperEventStreamService` 和 `PaperSseEvent`，事件保存在进程内 `history`，没有持久化事件 id、sequence、last-event-id 恢复能力。
2. 计划任务使用 `agent_plan_events` 落库，但前端通过 HTTP 拉取，不走统一 SSE。
3. 普通对话 WebSocket 使用 `WsChatEvent`，只包含 `chunk/done/error`，缺少统一 run trace。
4. 论文 stop 接口当前直接把 `paper_tasks.status` 设为 `STOPPED`，并设置进程内 `ControlState.stopped = true`。
5. 计划 cancel 当前直接把 `agent_plans.status` 设为 `CANCELLED`。

这些实现可以继续兼容，但后续必须形成统一事件事实源和统一取消语义，否则 RAG、长期记忆、论文工具化、文献任务化、Agent plan 和 SSE/WebSocket 稳定性都会继续割裂。

## 2. 设计目标

1. 所有长任务都有可持久化、可恢复、可调试的事件流。
2. SSE 只作为投递通道，不作为事实源。
3. 任务取消不再直接跳到终态，必须表达 `CANCEL_REQUESTED -> CANCELLING -> CANCELLED`。
4. 前端能在点击停止后立即显示“停止请求已受理”，并在 worker 真正停下前显示“正在停止”。
5. 断线重连后能从最后一个事件继续消费，不依赖进程内 history。
6. Kafka 负责任务唤醒和控制事件分发，但 MySQL 仍然是事实源。
7. 旧接口和旧事件名保留兼容，迁移期不能破坏现有论文页。

## 3. 统一事件事实源

建议使用 `agent_task_events` 作为统一事件事实源：

```text
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

字段约束：

1. `task_id` 指向 `agent_tasks.id`。
2. `user_id` 用于权限校验和索引。
3. `event_type` 使用小写 snake_case，例如 `task_started`。
4. `visibility` 区分 `USER`、`DEBUG`、`SYSTEM`。
5. `sequence` 在同一个 `task_id` 内单调递增，从 1 开始。
6. `payload_json` 保存结构化 payload，不保存不可控大文件。
7. `trace_id` 用于日志、模型调用、工具调用和 Kafka 消息关联。
8. 大型产物仍然写 MinIO，事件里只放 artifact id、objectKey、artifactStatus 等 metadata。

索引建议：

```text
idx_agent_task_events_task_sequence(task_id, sequence)
idx_agent_task_events_user_created(user_id, created_at)
idx_agent_task_events_type_created(event_type, created_at)
```

## 4. 事件 Envelope

后端内部和 SSE 输出建议统一使用同一个 envelope：

```json
{
  "eventId": 12345,
  "taskId": 1001,
  "taskType": "PAPER_POLISH",
  "sequence": 17,
  "eventType": "task_progress",
  "visibility": "USER",
  "status": "RUNNING",
  "stage": "RETRIEVE",
  "message": "文献检索完成，已选择 8 篇候选文献",
  "progressPercent": 70,
  "payload": {
    "current": 8,
    "total": 20,
    "source": "paper_orchestrator"
  },
  "traceId": "task-1001-a1b2c3d4",
  "createdAt": "2026-07-04T16:20:00Z"
}
```

字段说明：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `eventId` | 是 | 数据库事件 id，SSE `id` 也使用它 |
| `taskId` | 是 | 统一任务 id |
| `taskType` | 是 | `PAPER_POLISH`、`LITERATURE_SEARCH`、`AGENT_RUN` 等 |
| `sequence` | 是 | 同任务内单调递增序号 |
| `eventType` | 是 | 事件类型 |
| `visibility` | 是 | `USER/DEBUG/SYSTEM` |
| `status` | 否 | 事件发生后的任务状态 |
| `stage` | 否 | 当前业务阶段 |
| `message` | 否 | 用户可见短消息 |
| `progressPercent` | 否 | 0 到 100 的整数 |
| `payload` | 否 | 结构化细节 |
| `traceId` | 否 | 链路追踪 id |
| `createdAt` | 是 | 事件创建时间 |

兼容期可以在 payload 中保留旧论文字段：

```json
{
  "legacyPaperEvent": {
    "type": "retrieve_complete",
    "taskId": 88,
    "currentSection": null,
    "totalSections": null,
    "sectionTitle": null,
    "attempt": null,
    "maxAttempts": null
  }
}
```

## 5. 事件类型

### 5.1 任务生命周期事件

```text
task_created
task_queued
task_started
task_stage_changed
task_progress
task_waiting_user
task_resumed
task_completed
task_failed
task_cancel_requested
task_cancelling
task_cancelled
task_retry_requested
task_retry_queued
```

要求：

1. 每次状态变化必须写事件。
2. 每次阶段变化必须写 `task_stage_changed`。
3. `task_completed/failed/cancelled` 是终态事件，前端收到后可以关闭 SSE。
4. `task_cancel_requested` 必须在用户点击停止后尽快写入。
5. `task_cancelling` 必须由 worker 写入，表示 worker 已看到取消请求。

### 5.2 工具和模型事件

```text
model_call_started
model_call_completed
model_call_failed
tool_call_started
tool_call_completed
tool_call_failed
tool_budget_exceeded
tool_duplicate_blocked
```

默认 `visibility = DEBUG`。只有对用户有帮助的工具进度摘要才转为 `USER` 事件。

### 5.3 RAG 和记忆事件

```text
rag_retrieval_started
rag_retrieval_completed
rag_retrieval_empty
memory_retrieval_completed
memory_injected
memory_skipped
memory_updated
```

默认 `visibility = DEBUG`。调试页可以查看，普通聊天 UI 不默认展示。

### 5.4 人机协同事件

```text
confirmation_requested
confirmation_answered
confirmation_skipped
confirmation_expired
plan_draft_created
plan_option_presented
plan_option_selected
```

当任务需要用户确认时：

1. 任务状态进入 `WAITING_USER`。
2. 写入 `task_waiting_user`。
3. 写入具体 `confirmation_requested`，payload 包含 confirmation id、问题、选项、默认方案、是否 blocking。
4. 用户提交后写入 `confirmation_answered`，任务回到 `RUNNING` 或进入终态。

### 5.5 Artifact 事件

```text
artifact_created
artifact_updated
artifact_marked_partial
artifact_marked_cancelled
artifact_promoted
artifact_superseded
```

约束：

1. Artifact 文件放 MinIO。
2. 事件只引用 metadata。
3. 取消后生成的中间产物必须使用 `artifact_marked_partial` 或 `artifact_marked_cancelled`。
4. 正式下载入口只暴露 `COMPLETED` 任务的最终 artifact。

## 6. SSE 协议

统一任务 SSE endpoint 建议：

```text
GET /api/v1/tasks/{taskId}/events/stream
```

查询参数：

```text
afterSequence=17
visibility=USER
```

Header：

```text
Last-Event-ID: 12345
Authorization: Bearer ...
```

服务端行为：

1. 校验 task ownership。
2. 如果传入 `Last-Event-ID`，查询大于该 event id 的事件。
3. 如果传入 `afterSequence`，查询同 task 内大于该 sequence 的事件。
4. 先补发历史事件，再订阅新事件。
5. SSE `id` 使用 `eventId`。
6. SSE `event` 使用 `eventType`。
7. SSE `data` 使用统一 envelope JSON。
8. 定期发送 heartbeat，避免中间层断开空闲连接。

SSE 示例：

```text
id: 12345
event: task_progress
data: {"eventId":12345,"taskId":1001,"sequence":17,"eventType":"task_progress","status":"RUNNING"}
```

Heartbeat：

```text
event: heartbeat
data: {"taskId":1001,"serverTime":"2026-07-04T16:20:10Z"}
```

## 7. Kafka Topic 和消息

建议 topic：

```text
agent.task.dispatch
agent.task.control
agent.task.events
agent.task.dlt
```

用途：

1. `agent.task.dispatch`：任务创建后通知 worker 领取任务。
2. `agent.task.control`：取消、恢复、用户确认等控制信号。
3. `agent.task.events`：可选，用于多实例 SSE fanout 或异步观测，不作为事实源。
4. `agent.task.dlt`：消费失败或无法处理的消息。

### 7.1 Dispatch 消息

```json
{
  "messageId": "uuid",
  "taskId": 1001,
  "taskType": "PAPER_POLISH",
  "action": "START",
  "attempt": 1,
  "traceId": "task-1001-a1b2c3d4",
  "createdAt": "2026-07-04T16:20:00Z"
}
```

### 7.2 Control 消息

```json
{
  "messageId": "uuid",
  "taskId": 1001,
  "taskType": "PAPER_POLISH",
  "action": "CANCEL",
  "reason": "USER_REQUESTED",
  "requestedBy": 42,
  "clientRequestId": "frontend-generated-uuid",
  "traceId": "task-1001-a1b2c3d4",
  "createdAt": "2026-07-04T16:21:00Z"
}
```

约束：

1. Kafka 消息必须幂等。
2. worker 收到 control 消息后必须回查 MySQL 任务状态。
3. MySQL 状态比 Kafka 消息更可信。
4. 如果消息丢失，worker 也应在安全点回查 DB 状态。

## 8. 取消 API

统一取消 API：

```text
POST /api/v1/tasks/{taskId}/cancel
```

请求：

```json
{
  "clientRequestId": "frontend-generated-uuid",
  "reason": "用户主动停止"
}
```

响应：

```json
{
  "taskId": 1001,
  "status": "CANCEL_REQUESTED",
  "cancelAccepted": true,
  "message": "停止请求已受理，任务将在安全点停止。"
}
```

行为：

1. 校验任务归属。
2. 如果任务已 `COMPLETED/FAILED/CANCELLED`，返回当前终态，`cancelAccepted = false`。
3. 如果任务是 `PENDING/RUNNING/WAITING_USER`，写入 `CANCEL_REQUESTED`。
4. 写入 `task_cancel_requested` 事件。
5. 事务提交后发送 `agent.task.control` 的 `CANCEL` 消息。
6. 前端立即禁用 stop 按钮，显示“正在停止”。

## 9. Worker 取消安全点

worker 至少在以下安全点检查 DB 中的取消状态：

```text
开始处理前
领取任务后
阶段切换前
每次外部 LLM/API 调用前
每次外部 LLM/API 调用后
每个章节/段落处理前后
写入最终 artifact 前
任务完成前
retry 前
```

检查到 `CANCEL_REQUESTED` 后：

1. 原子更新为 `CANCELLING`。
2. 写入 `task_cancelling`。
3. 停止后续未开始步骤。
4. 标记已产生中间 artifact 为 `PARTIAL` 或 `CANCELLED`。
5. 不再写最终 artifact。
6. 更新为 `CANCELLED`。
7. 写入 `task_cancelled`。

如果外部调用不可中断：

1. 依赖调用超时尽快返回。
2. 前端保持 `CANCELLING` 或 `CANCEL_REQUESTED` 显示。
3. 外部调用返回后 worker 必须再次检查取消状态，不能继续写最终结果。

## 10. 重试与 DLT

重试规则：

1. 只有 `FAILED` 默认允许 retry。
2. `CANCELLED` 不自动 retry。
3. `CANCEL_REQUESTED/CANCELLING` 不 retry。
4. retry 创建新 attempt，事件不覆盖旧 attempt。
5. retry 前写 `task_retry_requested`，入队后写 `task_retry_queued`。

Kafka DLT 首批建议：

1. 先写日志和 DLT topic。
2. 不做后台可视化 replay UI。
3. 提供脚本或管理命令用于人工 replay。
4. 后续任务量增加后再做后台 replay 接口。

Pending scanner：

1. 扫描长时间停留在 `PENDING` 且未被 worker 领取的任务。
2. 首批频率建议 30 秒。
3. 单批最多 50 条。
4. 只重发 dispatch 事件，不直接改成 `RUNNING`。
5. 超过最大投递次数后标记 `FAILED`，写入 `task_failed`。

## 11. 前端状态映射

统一状态展示建议：

| 状态 | UI 文案 | 操作 |
| --- | --- | --- |
| `PENDING` | 等待中 | 可取消 |
| `RUNNING` | 运行中 | 可取消 |
| `WAITING_USER` | 等待确认 | 可提交确认、可取消 |
| `CANCEL_REQUESTED` | 正在停止 | 禁用停止按钮 |
| `CANCELLING` | 正在清理 | 禁用停止按钮 |
| `COMPLETED` | 已完成 | 可查看和下载最终产物 |
| `FAILED` | 失败 | 可查看错误、可重试 |
| `CANCELLED` | 已取消 | 可查看部分结果，不默认下载最终产物 |

旧状态兼容：

```text
WAITING_INPUT -> WAITING_USER
STOPPED -> CANCELLED
PAUSED -> WAITING_USER 或 legacy paused
```

前端事件消费：

1. 新任务页优先使用 `eventId/sequence` 作为 key，不能继续用 `timestamp-index`。
2. 收到终态事件后可以关闭 SSE，但仍应刷新任务详情。
3. 收到 `task_cancel_requested` 后立即显示停止中。
4. 收到 `task_cancelled` 后显示已取消和部分结果入口。
5. 调试区域可展示 raw event envelope。

## 12. 旧接口兼容

论文旧接口：

```text
POST /api/v1/paper/tasks/{taskId}/stop
```

迁移策略：

1. 保留旧接口。
2. 兼容期旧接口内部调用统一 cancel service。
3. 响应状态仍可保持 `202 Accepted`，但任务详情返回新状态或兼容映射状态。
4. 前端完全迁移后，再考虑隐藏旧按钮逻辑。

计划旧接口：

```text
POST /api/v1/agent/plans/{planId}/cancel
```

迁移策略：

1. 保留旧接口。
2. 兼容期直接映射到统一 task cancel。
3. 如果计划还没有 `agent_task_id`，先写旧 `CANCELLED`，并同步写统一事件镜像。

论文旧 SSE：

```text
GET /api/v1/paper/events?taskId=...
```

迁移策略：

1. 保留旧 endpoint。
2. 兼容期可以由统一事件服务转换出旧 `PaperSseEvent`。
3. 新前端迁移到 `/api/v1/tasks/{taskId}/events/stream`。

## 13. 实现顺序建议

1. 新增 `agent_tasks`、`agent_task_events` migration。
2. 新增统一任务状态 enum 和转换校验。
3. 新增 `AgentTaskEventService`：写事件、分配 sequence、查询 afterSequence。
4. 新增统一任务查询和取消 API。
5. 新增 SSE stream endpoint，支持 `Last-Event-ID` 和 `afterSequence`。
6. 给论文任务建立 adapter 或 mirror，将 `PaperSseEvent` 同步写入统一事件。
7. 将 `/paper/tasks/{taskId}/stop` 内部映射为统一 cancel。
8. 给计划任务建立 adapter 或 mirror，将 `agent_plan_events` 同步为统一事件。
9. 前端新增统一事件类型和任务状态映射。
10. 迁移论文页到统一 SSE。

## 14. 测试要求

后续实现 issue 需要覆盖：

1. `agent_task_events.sequence` 同任务内递增。
2. SSE 使用事件 id 和 event type。
3. `Last-Event-ID` 能补发漏掉的事件。
4. `afterSequence` 能补发漏掉的事件。
5. 用户不能订阅或取消别人的任务。
6. `RUNNING -> CANCEL_REQUESTED -> CANCELLING -> CANCELLED` 状态转换。
7. 取消后不写最终 artifact。
8. 取消后不自动 retry。
9. 旧 `/paper/tasks/{taskId}/stop` 仍可用。
10. 旧 `STOPPED` 能兼容显示为 `CANCELLED`。
11. pending scanner 能重新投递卡住的 `PENDING` 任务。
12. DLT 消息不会导致任务被误标为完成。

文档类 PR 验证：

1. `git diff --check`。
2. 人工核对 #4 设计与 roadmap 中 10.2、10.4 的已确认决策。

## 15. 非目标

1. 不在本阶段实现 migration。
2. 不在本阶段修改论文 SSE。
3. 不在本阶段修改 WebSocket 聊天协议。
4. 不在本阶段实现 DLT replay UI。
5. 不在本阶段删除任何旧接口。
6. 不在本阶段实现完整 Project/Workspace。
