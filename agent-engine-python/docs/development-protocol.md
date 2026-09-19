# 独立开发协议 python-dev/1

日期：2026-09-19。分类：开发合同。适用：#231。本协议不是现有 Engine `contractVersion=1.0` 的兼容实现；非目标为实际接入。

## HTTP

除 `/healthz`、文档/OpenAPI 外，接口都需要独立 Bearer token。无用户多租户认证；只允许本地受信任开发调用者。
生产 `/v1/tasks` 不暴露。请求体最大 160000 bytes，Pydantic 对象禁止未知字段；校验错误不回显提交正文。

| 方法/路径 | 行为 |
| --- | --- |
| GET `/healthz` | 返回 `production_connected:false` |
| POST `/dev/v1/tasks` | 接受 Submission，202 返回 `{replayed,task}`；不执行 |
| GET `/dev/v1/tasks/{task_id}` | 只读状态、当前计划、已完成结果、最终交付 |
| POST `.../advance` | `{expected_sequence:N}`；同步推进至下一个节点 checkpoint |
| POST `.../cancel` | 幂等取消；已终态不改写 |
| GET `.../events?after=N` | 只读 `{events:[...]}`，sequence 严格大于 N |
| GET `.../events.sse` | `Last-Event-ID:N`，返回当前已有事件的 SSE 快照后关闭；**不 live-follow、不发心跳** |

同一个 `client_request_id` 和相同完整请求（包括 frozen documents/memories）精确重放；任何变化返回 409 `REQUEST_CONFLICT`。
task_id 程序派生为 `pydev_...`，不接受或冒充生产 taskId。新任务 mode 只允许 `PERSISTENT_PLAN_EXECUTE`，权限只允许 READ_ONLY。

状态：`queued → running → succeeded|failed|cancelled`。终态不可恢复为运行，不改写为其他终态。
`advance` 可返回 409 `ENGINE_BUSY` 或 `STALE_ADVANCE`；重新 GET 后决定下一次显式推进。
模型/工具执行失败返回任务 failed 状态与固定错误码；原始异常不进入 API 或事件。

## 事件

每个事件含 `protocol, task_id, sequence, type, occurred_at`，sequence 从 1 单调递增。

- `status`：`status,error?`；accepted 首条及 terminal 最后一条。
- `progress`：`revision,completed_count,next`；每个已投影 checkpoint 至多一条。
- `delivery`：`conclusion,demo_evidence_refs`；只有成功末节点完成才追加一次。

不公开 raw graph stream、工具参数/正文、记忆或模型推理。最终 conclusion 是显式交付文本，可能合理引用文档内容；并非隐私过滤器。
`demo_evidence_refs` 只关联该任务的本地工具 journal，绝不是 Java 正式 Receipt。

## 持久化与恢复

本地 `python-dev.sqlite3` 保存请求/状态/事件/operation journal；`graph.sqlite3` 保存 LangGraph checkpoint。两库不参与分布式原子事务。

1. 先保存调用 reservation 和预算，再调用模型/只读工具；结果落 journal 后才能用于节点结果。
2. graph checkpoint 落盘后再投影事件，以 checkpoint_id 去重。
3. 结果已记账、节点未保存：显式恢复重放结果，不重复调用。
4. reservation 已保存、结果未记账：未知结果终止，不自动重试。
5. graph 终态已保存、元数据未更新：下一次显式 advance 对账后追加一次 delivery/terminal。
6. GET、重启和重复提交不做对账、不推进；崩溃窗口中允许暂时显示旧运行状态。

这是单进程开发方案。进程锁拒绝共享同一目录的第二实例，节点推进锁拒绝并发执行。
本地数据是敏感开发工件，位于被忽略目录；保留/清理策略、多副本 fencing、超时扫描、在线升级不在本阶段。
不读取环境中的数据库/Redis URL，不提供 taskGrant、sessionId、userId 或现有缓存 key 的输入入口。
