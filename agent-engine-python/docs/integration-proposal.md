# 后续产品接入提案（未实施）

日期：2026-09-19。分类：待后续 Issue 冻结的接口提案。适用：Python 引擎后续接入；非目标：本 PR 切流、修改 Java/前端/缓存/数据库。

当前 Project 是 TypeScript ReAct + Java 产品权威。本 demo 不是废弃 DSH/独立轻量引擎的重新接入，也不继续 #228。

## 权威划分

| 数据/行为 | 权威拥有者 | Python 角色 |
| --- | --- | --- |
| owner、session、Turn、TaskFrame、ProjectVersion、权限 | Java | 接受已验证投影，不信任浏览器自报 |
| 计划编排、当前步骤短期上下文 | Python | LangGraph checkpoint；不是产品 Step 完成证明 |
| ToolCall/Receipt、Workspace、sandbox | Java 网关 | 提出调用，使用稳定身份和 digest；结果引用权威回执 |
| 会话消息、任务产品状态 | Java | 只发事件/交付；不写业务表 |
| 长期记忆 | 现有产品记忆服务 | owner-qualified 召回快照，MemoryPort 适配，不新增双写库 |
| 发布/回滚 | Java | 仅请求验证后确定性终结；不能通过图终态授权发布 |
| 会话缓存失效、历史分页版本 | Java/现有前端方案 | 不读取/写入共享缓存、不自行驱逐 |

## 接口适配

后续优先新增 Python 适配层实现现有 `agent-engine-contract`，保持 Java 入口不变；阶段一 `/dev/v1` 不被映射到生产流量。

- Java → Python：原 `POST /v1/tasks`、GET 状态、GET SSE、cancel、answer 合同；owner-qualified Java 入口不变。
- 请求沿用 `taskId, requestDigest, authority, context, gateway`。模型计划只含本地 key/依赖/完成条件，不生成产品 TaskFrame/Step/Receipt ID。
- `context.historicalContext` 接收 Java 的历史版本快照；`context.longTermMemory` 优先复用现有安全信封。真实产品 ID/身份不能由 demo document_id 推断。
- Python → Java：复用 `/internal/v1/agent-engine/tasks/{taskId}` 下模型、tools、tool-calls、workspace/read、sandbox、receipts 等已有网关。第一批只读工具单独冻结 owned paths。
- 生产模型调用应适配现有 `model-completions` 网关，继续由 Java 持有用户 key、模型路由、用量账本。开发 ChatOpenAI 直连不是生产凭证方案。
- taskGrant 是短期调用能力，只在执行时注入/刷新，不放在 checkpoint、语义 digest 或日志。网关地址来自服务配置，不来自模型。
- 现有 tools 目录的冻结、按需 schema 加载、允许列表必须保留。四个 demo 工具不能被当作生产工具实现。
- 计划修订的产品映射需先决定：Python 内部工作计划和现有 persistent Plan 如何关联；未经明确合同不能创建第二套权威 Step 状态。

生产兼容验收必须运行已有 `agent-engine-contract/conformance` 正反 fixtures，并加入实现行为测试。
本阶段测试覆盖的是 `python-dev/1`，不宣称已通过完整生产 Engine 兼容性。

## 事件、幂等和提交协议

后续生产事件应映射为现有 `status/message/question/tool/delivery`，遵循已有 schema、sequence、Last-Event-ID 与 15 秒心跳语义。
开发 `progress` 不能作为未知事件直接发送给现有前端。计划展示如需新事件，需要后续合同版本与消费者能力协商。

建议 Java 事件消费使用 `(engineInstance, taskId, sequence)` 幂等键，校验 task owner/version 和事件连续性；乱序或缺口先补读，不能先创建成功消息。
最终交付仅在正式 Receipt 完整、无未知影响、发布条件满足后由 Java 提交一条 assistant message。重复 delivery 不能重复插入消息。
事件终态与用户可见交付不同：任务失败/取消仍需终态，但不能伪装成成功消息或发布。

后续需正式冻结 outbox/inbox 或等价事务协议，保证消息提交、消费游标和失效通知可恢复。阶段一没有修改或新增产品数据库表。

## 持久化与恢复边界

- Java 持久化产品事实，Python 持久化编排状态。Python checkpoint 中保存引用、预算、剩余计划、结果摘要；不能覆盖 Java 的权威完成记录。
- 每个模型/工具调用在调用前保存稳定 callId/requestDigest。恢复先向 Java 查询/精确重放原调用，未知沙箱执行不得重新提交。
- 单纯 LangGraph checkpoint 无法提供跨服务 exactly-once；必须结合 Java 的现有幂等回执、租约/fence、执行状态查询。
- 多实例需要明确任务所属引擎和 lease/fence。重启只恢复显式分配给 Python 的任务，不能扫描 TS 活动任务抢占。
- 版本发布仍要求最终成功 sandbox run 与精确 Candidate 字节绑定；旧不可变版本保留。模型 complete 或 Python succeeded 均不替代这个证明。
- 运行配置/图版本/模型版本应被冻结并支持兼容恢复；先安排 drain/recovery 协议，再考虑滚动部署。

## 与会话缓存工作的协作协议

当前另一任务正在改造 Java 会话缓存与前端历史加载，本提案不固定其内部 Redis key 或要求其倒退实现。

建议依赖语义边界而非 Redis 实现：

1. Python 只发带任务身份和单调 sequence 的事件，不写会话消息、列表缓存或历史分页。
2. Java 在自身事务中幂等保存 message/task 投影；事务回滚不发有效失效通知。
3. **提交成功后**，由 Java 现有 owner/session scoped invalidation 路径推进会话 generation（以及需要的列表/摘要 generation）。同一事务合并重复失效。
4. 前端根据服务端历史版本重新加载相应会话，并拒绝旧 generation 请求回填。Python 不负责客户端分页合并。
5. 重复 engine 事件无新增业务写入时不重复推进缓存版本。Redis 不可用时回源业务数据库；消费重试不重复插入消息。
6. 删除会话由 Java 先校验 owner 并撤销/取消任务；删除墓碑必须挡住晚到事件重建消息。记忆权限撤销也不能因 Python 旧快照被绕过。

后续必须测试：提交成功/回滚、重复事件、乱序补读、Redis 故障、并发历史加载、会话删除后的晚到事件、跨 owner 拒绝。
若未来 demo 确实需要 Redis，只允许 `paperagent:python:dev:` 前缀；禁止 FLUSHDB/FLUSHALL、全库遍历或其他前缀操作。

## 分阶段建议

1. 本 Issue：独立服务、离线/模拟 provider 验证、协议提案。
2. 后续只读集成：兼容 Engine API、Java 模型/工具网关、真实记忆信封、SSE/提问/取消与双方契约测试；仅显式分配测试任务。
3. 后续受控写入：Workspace → sandbox → exact-content publication；回归幂等、恢复、回滚。
4. 同模型同预算跑真实复杂任务对照，再通过单独配置/部署 Issue 决定切流。保留 TS 活动任务原执行者，避免双执行。
