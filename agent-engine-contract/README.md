# PaperAgent Agent Engine Contract

日期：2026-08-16

分类：当前架构合同

状态：Issue #150 冻结候选

适用范围：Java 产品与独立 Agent Engine 的 P1/P2 隔离 Workspace 边界

## 1. 决策

PaperAgent 保留 `DIRECT` 与 `PERSISTENT_PLAN_EXECUTE` 两种顶层路由。任何涉及
Project、工具、执行、网络或修改的请求仍进入持久 Plan。新的 DSH 与 Codex Engine
只替换 Plan 内部的模型执行循环，不取得产品权威。

Java 产品继续拥有：

- 已认证用户、会话、TaskFrame、Plan 和冻结 ProjectVersion；
- Workspace 的创建、物化、路径策略和内容 hash；
- Sandbox policy、broker 凭证、fence、幂等摘要与正式 Receipt；
- Candidate、精确沙箱输入绑定、ProjectVersion 发布与回滚。

Engine 只拥有：

- 有界 ReAct 模型循环；
- 模型可见的受限工具描述；
- 自己的任务运行状态和可重放事件日志；
- 取消、恢复、提问以及最终用户可见交付的编排。

## 2. 合同组成

- `openapi.yaml`：Java → Engine 控制面和 Engine → Java 工具网关。
- `schemas/`：可独立验证的 Draft 2020-12 JSON Schema。
- `conformance/`：双方必须运行的正反 fixture 和验证器。
- `ACCEPTANCE.md`：P1 任务、指标和通过标准。

合同版本为 `1.0`。不兼容变更必须升级 major；新增可选字段可以升级 minor。双方不得
根据未知字段猜测语义。所有 schema 默认 `additionalProperties: false`。

## 3. 两条调用边界

```text
authenticated product request
        |
        v
Java product authority --POST /v1/tasks--> selected Engine
        ^                                      |
        |                                      | short-lived task grant
        +---- Workspace / Sandbox gateway <----+
```

Java 调用 Engine 使用部署时配置的 service credential。产品工具网关 origin 也由 Engine
部署配置固定，任务体不得覆盖，避免把 Engine 变成任意 URL 客户端。Engine 调用网关使用
每个任务单独签发的短期 task grant。task grant 必须绑定 `taskId`、权限和到期时间，且：

- 标记为 `writeOnly`；
- 不进入 canonical request digest；
- 不写入任务、事件、日志、错误或模型上下文；
- 不得换取 broker token、宿主路径或更宽权限。

P1 模型 provider/model 来自任务权威，但模型 API 密钥由 Engine 进程配置，不通过本
合同传输。用户级动态 provider credential 是后续独立安全边界。

## 4. Canonical request digest

`requestDigest` 是 `authority` 对象 UTF-8 canonical JSON 的小写 SHA-256：

1. 对象键按 Unicode code point 升序递归排序；
2. 数组保留原顺序；
3. 不添加无意义空白；
4. 字符串使用 JSON 转义；
5. 只允许 schema 定义的 JSON 类型；
6. `gateway.taskGrant`、HTTP Authorization、模型密钥和任何运行时 secret 均不参与。

相同 `taskId` 与相同 digest 是幂等重放；相同 `taskId` 与不同 digest 必须返回 409
`TASK_DIGEST_CONFLICT`，不得覆盖或启动第二个任务。

精确重放可以携带同一任务的新 task grant 与新到期时间。Engine 只替换内存中的短期
凭证，不得改写 authority、清空事件或重新提交已经存在的沙箱执行。Engine 冷启动后，
Java 用相同 taskId/digest 重新提交以恢复运行时凭证；凭证本身不属于持久恢复事实。

## 5. 事件和恢复

- 每个任务的持久事件 `sequence` 从 1 开始严格连续递增。
- SSE `id` 必须是该事件十进制 `sequence`，`data` 是完整 `TaskEvent` JSON。
- `Last-Event-ID: N` 只返回 `sequence > N` 的事件。
- 空闲 live SSE 每 15000 ms 发送一次 comment heartbeat。heartbeat 不分配 sequence，
  也不进入持久日志。
- 断线和 Engine 重启后必须从持久事件继续；不得从用户消息重新创建任务。
- 每个任务最多一个终态 status。`succeeded` 前必须先有且只有一个 delivery。
- 取消是幂等的；已经终态的任务保持原终态。

### 5.1 用户回答幂等

`answerDigest` 是 `answer` 精确 UTF-8 字节的小写 SHA-256。Engine 必须先验证摘要，再
以 `questionId` 冻结首个已接受答案：

- 同 questionId、同 answerDigest 是精确重放，返回 202，不再消费一次；
- 同 questionId、不同 answerDigest 返回 409 `QUESTION_ANSWER_CONFLICT`；
- 同 clientRequestId 被另一 questionId 或 answerDigest 使用时返回 409
  `ANSWER_REQUEST_CONFLICT`；
- 非当前 pending question 返回 409 `QUESTION_NOT_PENDING`。

任何冲突都保留第一个已接受答案，不改写事件历史。

### 5.2 工具事件命名

`tool.name` 是合同稳定名，只允许 `project.list`、`project.read` 和
`sandbox.execute` 和确定性终结器 `project.publish`。`project.publish` 不属于模型
可见工具。稳定事件名与模型函数名无关；Engine 负责在内部映射，SSE 消费方不得
看到框架或 provider 私有工具名。

## 6. P1 固定执行预算

双方必须使用完全相同的硬上限：

- 每次模型请求 `maxOutputTokens = 4096`；
- 每个任务最多 20 次模型请求，所有继续、纠错和最终合成调用均计入；
- 每次向 provider 发出可能消费 token 的请求时立即占用一次预算；
- 并行工具调用数为 1；同一模型响应包含多个 tool call 时按原顺序串行处理；
- 不启用 subagent。

达到模型调用上限必须产生有界失败，不得通过任务恢复重置预算。

Sandbox 202 后按 1、2、4、5、5……秒串行轮询，不加 jitter。截止时间从接受提交起为
`timeoutMillis + 30000 ms`；“接受提交”是 Engine 收到 202 时记录的本地 monotonic
时刻，不依赖两台机器的 wall clock。截止时仍无终态，Engine 产生
`SANDBOX_STATUS_DEADLINE_EXCEEDED`，category 为 `sandbox_system`，不得让模型自行继续
无限轮询。相同 clientRequestId 的后续恢复仍只能查询原执行。

## 7. 数据最小化

事件中的工具输入和输出只允许有界摘要与权威引用。禁止进入事件或错误：

- 文件正文、task grant、service token、provider key；
- broker 地址或凭证、宿主绝对路径、对象存储 key；
- 未截断 stdout/stderr；
- Java 异常堆栈、数据库实体或内部配置来源。

文件正文只通过受 task grant 保护的 Workspace read 响应传给 Engine。正式 Receipt 可
返回有界 stdout/stderr，并显式声明是否截断。

`mediaType` 只用于展示和诊断。Engine 不得用它推断解析器、权限、文件语义或可执行性；
这些判断只来自产品工具合同和服务端 policy。

## 8. P1 与 P2 边界

P1 是严格只读 Project 验证：真实冻结 ProjectVersion → Workspace list/read → 沙箱
执行 → 正式 Receipt → Engine 恢复 → 最终交付。P1 不产生 Workspace diff、Candidate
或新 ProjectVersion。

通用 delivery 允许空 `receiptRefs`，以兼容未来无正式工具回执的回答。但 P1 T1–T5
验收任务涉及沙箱，成功 delivery 必须携带至少一个正式 Receipt 引用；该要求由
conformance/acceptance 行为测试强制，而不是由通用事件 schema 强制。

Issue #165 的 P2 第一段增加受 task authority 约束的 Workspace ADD/MODIFY、diff 与
精确 Candidate 沙箱绑定。Issue #167 增加确定性自动发布终结器；`publish` 永远不是
模型工具。产品会重新校验完整 Workspace diff、Candidate 文件正文与成功 Receipt 的
全部输入 hash，且冻结版本仍是当前版本时，直接创建新的不可变 ProjectVersion。该过程
不要求用户二次确认，前一版本保留在现有 revision 历史中，可直接回滚。分析、问候、无
diff、沙箱失败或 Receipt 不匹配的任务不会发布。

## 9. 本地合同验证

```powershell
python agent-engine-contract/conformance/validate_contract.py
git diff --check
```

验证器只依赖 PyYAML 与 jsonschema。Engine 实现还必须把相同 fixtures 接入自己的测试
框架，不能把本脚本当作实现级行为测试的替代品。
