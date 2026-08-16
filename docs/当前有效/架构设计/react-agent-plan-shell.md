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

正式持久化和沙箱调用由共享网关 Issue #151 提供；本 Issue 只定义 Plan Shell 的产品侧编排和纯事实判定，不另建重复网关。

## 复杂任务与后续 plan.update

数据结构使用有界无环目标图，支持未来多个 Goal、依赖、doneWhen 和独立预算。P1 入口只物化一个产品拥有的 Goal，暂不让模型自动拆解。

后续若真实验收证明复杂任务需要显式拆分，可增加受控 `plan.update`：模型只能提出本地 Goal key、目标、依赖和完成条件；程序校验数量、环、权限和预算后分配权威 StepId。已经完成的事实仍不可改写。

这让 P1 先解决旧链最不稳定的部分，同时不封死复杂任务能力。

## 迁移和回滚

- 新代码仅位于 `com.yanban.api.agent.reactplan`，不修改旧四角色链；
- 本 Issue 不接 Engine HTTP、不实现 Project 写入/发布/回滚；
- 未切流前旧链仍是默认路径；切流应由后续独立 Issue 通过显式开关完成；
- 遇到回归可关闭新路径，不需要迁移或重写旧 Plan 数据。

## P1 验证

- 确定性单 Step 与稳定 Step 身份；
- 多 Goal DAG 的结构校验及环/未知依赖拒绝；
- 模型投影不含权威 ID 与凭证字段；
- ToolCall/Receipt 精确重放、摘要冲突和恢复重建；
- 编译成功、可信编译失败、系统失败、待处理影响和伪造 Receipt 绑定的完成判定；
- 认证失败时不进入持久化。
