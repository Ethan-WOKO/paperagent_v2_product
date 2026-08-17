# ReAct Agent Plan Shell（P1）

## 结论

新链路不再要求 Planner、Executor、Reflector、Answer 四个模型角色互相传递严格 JSON，也不让模型复制 PlanId、StepId、ToolCallId、ReceiptId 等权威标识。

P1 由产品代码确定性创建一个 `EXECUTE_OBJECTIVE` Step，ReAct 引擎只在这个有边界的目标内选择工具、读取观察并生成结论。复杂任务在 P1 仍可进行多轮工具调用；“一个 Step”不等于“只能调用一次工具”。

## 权威边界

产品代码负责：

- 从已认证 Turn 和当前 ProjectVersion 冻结 TaskFrame；
- 稳定派生 Plan、Revision、Step 身份；
- 冻结权限、尝试次数和最长执行时间；
- 记录 append-only ToolCall/Receipt 事实；
- 判断是否完成，并把最终交付绑定到正式 Receipt；
- 对相同身份的精确重放返回原事实，对不同内容报冲突。

模型只接收：

- 目标、目标对象、交付物和约束；
- 可用能力名称；
- 尝试次数和时长预算；
- 工具返回的有界观察；
- 不含产品内部 ID、token、secret、文件正文型工具事件。

模型不能声明 Step 已完成，也不能提供权威 ID。模型生成的最终文本由产品代码自动绑定到当前终态 Receipt 集合后，完成门才允许结束。

## P1 状态流

```text
Authenticated Turn
  -> deterministic TaskFrame + one-Step Plan bootstrap
  -> authority-safe model projection
  -> ReAct: reason -> tool intent -> observation -> reason ...
  -> append ToolRequested + formal Receipt
  -> product binds conclusion to terminal Receipts
  -> deterministic completion gate
```

完成门规则：

1. 仍有未收到 Receipt 的工具影响时等待，不得完成；
2. 没有正式 Receipt 时不得完成；
3. broker、鉴权或恢复等 `SYSTEM_FAILED` 与任务结果分开处理；
4. 可信的编译成功和可信的编译失败都可以形成任务交付；
5. 交付绑定的 Receipt 集合必须与事实账本完全相等。

因此，`javac` 返回非零且 stderr 已形成可信 Receipt，是“任务执行成功、被测代码失败”；broker 不可用则是“系统失败”。两者不能混为一谈。

## 重放和恢复

`ReactPlanFactLedger` 可从 append-only 事实重建：

- 相同 ToolCallId、相同工具名和相同请求摘要是精确重放，不追加第二次影响；
- 相同 ToolCallId 但请求摘要不同是冲突；
- Receipt 必须引用已存在的 ToolRequested，且摘要必须一致；
- 一个 ToolCall 只能有一个终态 Receipt，精确 Receipt 重放是 no-op。

本 Issue 的可运行切片把 Engine HTTP、产品 Project 网关和沙箱 Receipt 适配放在独立的 `reactplan` 命名空间下，避免依赖或修改其他实验分支。任务 JSON 与事件日志由 Engine 持久化；Plan/TaskFrame 仍进入现有 V2 持久化端口；沙箱执行状态与正式 Receipt 绑定落入独立的 `reactplan_sandbox_executions` 表。

运行时拓扑：

```text
authenticated browser
  -> Java /api/v1/react-agent/sessions/{sessionId}/tasks
       -> idempotent user message + Turn + stable Plan
  -> local agent-engine-reactplan (ReAct loop + event/task recovery)
  -> Java /internal/v1/agent-engine (short task grant)
       -> frozen read-only registered Project tool catalog
       -> frozen ProjectVersion workspace
       -> existing sandbox broker (broker credential stays in Java)
```

长期 authority 和 `requestDigest` 不含任何 token。Java 在每次提交时另行签发绑定 taskId、用户、Turn、ProjectVersion 和到期时间的短期 grant；Engine 只能拿它调用网关，不能直接调用 broker。

产品已经注册的 Project 工具不在 Engine 中复制实现。Java 依据现有 `ToolRegistry` 和产品工具策略只筛选当前任务可见的 `NONE/READ_ONLY` 工具，把名称、说明和参数 schema 通过短期 grant 网关提供给 Engine；Engine 在第一次模型调用前冻结目录，并把模型调用转回同一 Java executor。每次执行前后都重新核对 Turn、Project 和 ProjectVersion。工具结果正文只进入模型观察，公开事件只保留工具名、请求摘要和有界状态摘要。

## 复杂任务与后续 plan.update

数据结构使用有界无环目标图，支持未来多个 Goal、依赖、doneWhen 和独立预算。P1 入口只物化一个产品拥有的 Goal，暂不让模型自动拆解。

后续若真实验收证明复杂任务需要显式拆分，可增加受控 `plan.update`：模型只能提出本地 Goal key、目标、依赖和完成条件；程序校验数量、环、权限和预算后分配权威 StepId。已经完成的事实仍不可改写。

这让 P1 先解决旧链最不稳定的部分，同时不封死复杂任务能力。

## 迁移和回滚

- Java 新代码仅位于 `com.yanban.api.agent.reactplan`，Engine 位于 `agent-engine-reactplan/`，不修改旧四角色链；
- 本 Issue 已接 Engine HTTP 和只读产品网关，但不实现 Project 写入/发布/回滚；
- 未切流前旧链仍是默认路径；切流应由后续独立 Issue 通过显式开关完成；
- 遇到回归可关闭新路径，不需要迁移或重写旧 Plan 数据。

## P1 验证

- 确定性单 Step 与稳定 Step 身份；
- 多 Goal DAG 的结构校验及环/未知依赖拒绝；
- 模型投影不含权威 ID 与凭证字段；
- ToolCall/Receipt 精确重放、摘要冲突和恢复重建；
- 编译成功、可信编译失败、系统失败、待处理影响和伪造 Receipt 绑定的完成判定；
- 认证失败时不进入持久化。
- Engine submit/status/SSE/cancel/answer 与重启恢复；
- 真实 ProjectVersion 清单/按 hash 读取、白名单沙箱 argv 与有界正式 Receipt；
- 服务 token、短期 task grant 和 broker 凭证三层隔离。
