# 第一阶段验证记录

日期：2026-09-19。分类：实现验收。适用：Issue #231；非目标：生产切流和真实模型质量验收。

环境：Windows PowerShell，CPython 3.11.7，uv 0.11.6。依赖由 `uv.lock` 锁定。

## 已执行

以下命令在 `agent-engine-python/` 执行：

| 命令 | 结果 |
| --- | --- |
| `uv sync` | 建立目录内隔离虚拟环境、生成 lock；未安装到全局环境 |
| `uv run --frozen pytest -q` | **52 passed**；1 条第三方 Starlette/AnyIO BlockingPortal 弃用警告 |
| `uv run --frozen ruff check src tests` | 通过 |
| `uv run --frozen ruff format --check src tests` | 15 个 Python 文件格式通过 |
| `uv run --frozen paperagent-python demo` | succeeded；2 个完成步骤、3 个本地证据引用、16 条事件；退出且不留后台服务 |
| `uv build` | sdist 与 wheel 构建成功；产物位于被忽略的 `dist/`，不提交 |

仓库根目录执行：`git diff --check`、`git diff --cached --check`，以及提交范围检查。
仅新增 `agent-engine-python/**`。虚拟环境、缓存、SQLite、打包产物均不暂存。

## 行为覆盖

- 真实 LangGraph + SQLite 的端到端计划执行和步骤切换。
- 重规划保留已完成结果，禁止已完成 key 重写，环/无效依赖拒绝。
- 步骤预算、任务模型预算与失败前 reservation 持久化。
- 同请求重放/冲突、单调事件、游标重放、终态幂等。
- 重启后继续；模型结果已记账但 checkpoint 未落盘不重复调用。
- 模型结果未知时停止；终态 checkpoint 与事件不同步时只投影一次交付。
- 并发推进拒绝、共享目录双实例拒绝、进行中取消阻止后续工具与交付。
- 证据伪造拒绝、非允许工具拒绝、路径/未知参数拒绝、文档分页。
- 上下文步骤隔离、任务间隔离、只读 MemoryPort 冻结召回。
- API 认证、请求体上限、参数错误去敏、GET 不执行、旧 sequence 不推进。
- 使用 HTTP MockTransport 经过真正 ChatOpenAI/LangChain 请求和响应解析，验证结构化计划、native tool call、finish_step 与 synthesis。无外部模型请求。
- 预期外模型错误在进入 LangGraph 失败 checkpoint 前去敏。

## 跳过与原因

- Java/Maven、TypeScript、前端和共享数据库验证：没有修改或接入这些边界。
- 现有生产 Engine conformance：开发 API 明确不兼容该协议；生产适配是后续任务，不能用当前测试冒充通过。
- Redis 测试：本阶段没有 Redis 客户端或共享缓存行为。
- 真实 provider 和真实复杂任务对照：未使用用户凭证、未发起付费调用，现阶段不能声称复杂任务质量已优于 TS。
- 常驻 Uvicorn 和产品端到端：遵守不启动接管进程要求；HTTP 通过 FastAPI TestClient 测试，未启动监听服务。

## 剩余限制

单进程、本地单调用者开发信任模型；无多租户权限映射、多副本 lease、后台调度或跨服务 exactly-once。
SSE 是已存事件快照，不是生产 live-follow；未实现 waiting_user/answer、动态工具目录、沙箱、发布。
仅记忆读取适配接口，尚未连接长期记忆服务或实现自动蒸馏。字符预算不是 token 预算，summary 截断可能损失信息。
证据门只验证引用存在和本地工具成功，不能自动证明语义结论；真实任务需另建 eval。
未完成任务恢复须保持相同模型/图版本；SQLite journal 与 checkpoint 跨库通过显式恢复对账，不具备分布式事务。
