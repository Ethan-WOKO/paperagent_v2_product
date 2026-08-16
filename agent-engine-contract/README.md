# PaperAgent Agent Engine Contract

日期：2026-08-16  
分类：当前架构合同  
状态：Issue #150 冻结候选  
适用范围：Java 产品与独立 Agent Engine 的 P1 边界  

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
- SSE comment heartbeat 不分配 sequence，也不进入持久日志。
- 断线和 Engine 重启后必须从持久事件继续；不得从用户消息重新创建任务。
- 每个任务最多一个终态 status。`succeeded` 前必须先有且只有一个 delivery。
- 取消是幂等的；已经终态的任务保持原终态。

## 6. 数据最小化

事件中的工具输入和输出只允许有界摘要与权威引用。禁止进入事件或错误：

- 文件正文、task grant、service token、provider key；
- broker 地址或凭证、宿主绝对路径、对象存储 key；
- 未截断 stdout/stderr；
- Java 异常堆栈、数据库实体或内部配置来源。

文件正文只通过受 task grant 保护的 Workspace read 响应传给 Engine。正式 Receipt 可
返回有界 stdout/stderr，并显式声明是否截断。

## 7. P1 与 P2 边界

P1 是严格只读 Project 验证：真实冻结 ProjectVersion → Workspace list/read → 沙箱
执行 → 正式 Receipt → Engine 恢复 → 最终交付。P1 不产生 Workspace diff、Candidate
或新 ProjectVersion。

P2 才增加 Workspace 写入和自动发布。`publish` 永远不是模型工具；确定性终结器必须
证明最终 Candidate 内容与最后一次成功沙箱的全部输入 hash 完全一致，才可创建新的
不可变 ProjectVersion，并保留前一版本回滚入口。

## 8. 本地合同验证

```powershell
python agent-engine-contract/conformance/validate_contract.py
git diff --check
```

验证器只依赖 PyYAML 与 jsonschema。Engine 实现还必须把相同 fixtures 接入自己的测试
框架，不能把本脚本当作实现级行为测试的替代品。
