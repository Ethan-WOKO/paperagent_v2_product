# #233 验证记录

日期：2026-09-19。基线为本地 `codex/issue-185-react-optimization` 的 `9c670db7`（包括已完成的 Redis/会话历史工作），先以 `f117e8c5` 引入 #231 独立 Python demo，再增加本次实验入口。

## 已执行

根目录：

```powershell
mvn -q -pl yanban-api -am "-Dtest=ReactPlanEngineSelectionTest,ReactPlanRuntimeServiceTest,ReactPlanEngineClientWiringTest,ReactPlanTurnIntakeServiceTest,ReactPlanTurnIntakePersistenceTest,ReactPlanTaskSchedulerServiceTest,ReactPlanTaskStateServiceTest,ReactPlanTaskStateControllerTest,AgentEngineTaskGrantServiceTest,ReactPlanSessionTaskQueryServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -q -pl yanban-api -am "-Dtest=ReactPlanEngineMigrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
uv run --project agent-engine-python python agent-engine-contract/conformance/validate_contract.py
git diff --check
```

Java 合计 **39 tests，0 failures/errors/skips**。覆盖默认/冻结路由、所有任务操作的目标 origin/token、禁用不降级、真实 H2 查询排除 Python、跨引擎 claim 拒绝、容量/fencing、只读 grant、现有 task-state/intake/history 行为。V108 SQL 在独立 H2 MySQL 模式执行，旧任务保留并默认 TS；主迁移与 H2 迁移内容一致。没有连接共享 Redis/MySQL。

共享契约验证：**8 schemas、15 operations、13 positive fixtures、5 negative fixtures、5 ordered events、16 runtime scenarios** 全部通过。

`agent-engine-python/`：

```powershell
uv sync --locked
uv run --frozen ruff check src tests
uv run --frozen ruff format --check src tests
uv run --frozen pytest -q
```

**62 tests passed**。产品适配使用真实 LangGraph 与 LangChain，Java/model HTTP 由 MockTransport 模拟；覆盖整个规划→读文件→结论→Java checkpoint/events 投影、shared JSON schema 校验、取消、鉴权/只读拒绝、持久化失败后显式重试且不重复模型调用、重启不接管、游标分页、敏感 grant 不落盘，以及对照统计的失败分母。保留 1 条 Starlette/AnyIO 弃用警告，不影响通过。

`frontend/`：

```powershell
pnpm exec vitest run src/views/__tests__/ProjectPreviewPageReactPlan.test.ts src/utils/__tests__/reactPlanTask.test.ts
$env:CI='true'
pnpm build
```

**30 tests passed**，vue-tsc 与 Vite 构建通过。Vite 提示主 bundle 超过 500 kB；本次未扩大到无关分包优化。前端测试覆盖入口源码约束与状态/历史转换，并非浏览器端到端测试。

## 未执行及限制

- 用户明确暂不使用真实 Project：未跑真实模型、未进行质量/性能 A/B，不宣称 Python 更快或更准确。
- 未启动/切换 Java、TS 或 Python 服务；未对共享产品数据库执行 V108，未运行 MySQL 全量迁移升级测试。启用需按接入说明配置、迁移并重启。
- 未做真实浏览器→Java→Python→provider 的全栈端到端验证；当前证据为各边界的契约/行为测试和构建。
- 不运行全仓库测试或 #228 退役测试，本次未修改该路径；仅扩大到直接涉及的 Java 路由/授权/迁移、前端与共享协议。
- Python 目前单 worker、本地 SQLite 图状态；进程重启需要显式重发原任务才能恢复，不支持自动接管和多副本。Java 持久化异常可能暂时保留 running，不能将未知结果包装为成功。没有独立 UI 恢复按钮。
- 对照工具只采集现有 trace；provider/model span 是现有 Java 观测字段，不保证包含完整实际 fallback 路由。正式对照须另核对模型网关调用事实，保持模型一致。

本次没有修改会话缓存实现、根部署配置、`agent-v2/` 或 `agent-engine-reactplan/`，没有清空或写入共享 Redis。工作区既有 `.runtime/` 不纳入提交。

## 本地 .env 启动配置补充

按用户后续要求，CLI 增加显式服务目录的 `.env` 自动读取及 python-dotenv 依赖。执行 `uv sync --project agent-engine-python`、`uv run --project agent-engine-python ruff check agent-engine-python/src/paperagent_engine/cli.py` 和 `git diff --check` 均通过。用临时目录、隔离进程环境和替换 CLI 文件路径的 Python smoke assertions 验证：UTF-8 BOM 可读、token 不做变量插值、进程变量优先、不读取上级 `.env`、本目录文件不存在时仍不向上搜索。

`git check-ignore agent-engine-python/.env` 确认本地 token 文件被忽略；只提交空值 `.env.example`。没有启动服务、运行真实任务，也未因这次仅启动配置调整重跑无关 Java/前端测试。上述 131 项测试记录对应此前引擎接入验证。

## Compose 部署补充验证

用户后续明确要求接入 `docker-compose.prod.yml`。新增 Python 镜像、profile、数据卷和健康检查，同时适配 Java/Python 固定 Compose 服务名、服务器启用/状态脚本及根环境示例。没有部署云端或启动现有产品栈。

```powershell
docker build -t paperagent-python:issue233-smoke agent-engine-python
uv run --project agent-engine-python ruff check agent-engine-python/src agent-engine-python/tests
uv run --project agent-engine-python pytest -q agent-engine-python/tests
mvn -q -pl yanban-api -am "-Dtest=ReactPlanEngineSelectionTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
git diff --check
```

结果：镜像构建成功，Python **68 tests passed**，Java **3 tests passed**，lint 通过。新增测试验证 CLI 容器绑定/目录/网关配置、Compose token 同源、私有端口及持久化卷，并拒绝任意外部 origin。

另以临时目录复制 Compose 和环境示例，使用 `docker compose --env-file <synthetic-env> -f <temporary-compose> config --format json` 验证开/关 profile 均可解析且 token 映射一致；未读取或输出真实根 `.env`。容器内 `bash -n` 校验脚本；用纯参数打印函数替代 docker 验证 profile 自动启用/禁用、健康等待、缺失 token 和禁用 Java 路由拒绝。

隔离容器 smoke：`--network none`、合成 token、无 host ports、随机独立 volume；验证 `/healthz`、UID=10001、镜像无本地 `.env`、`docker stop --time 60` exit 0、删除并替换容器后卷内容仍在。临时容器及其专属卷均已清理。没有创建 Project 任务或连接模型、Redis、MySQL。

未验证真实云端全栈网络/认证和付费模型；本次没有改前端，不重复其构建。自动重启仅重启服务，不自动接管中断任务。

## 产品 Python ReAct 对齐验证（2026-09-19）

按用户后续要求，产品 Python 改为参考现有 TS 提示词与工具消息循环的只读 ReAct；独立离线 demo 保留 Plan-and-Execute。Java 仍持有任务、权限、事件和缓存边界。Python 新任务仅调用所选主模型，失败直接投影可读的脱敏错误，不再静默切换模型。该范围属于 #233 产品集成，不涉及 #228。

仓库根目录执行：

```powershell
uv run --project agent-engine-python ruff format agent-engine-python/src agent-engine-python/tests
uv run --project agent-engine-python ruff check agent-engine-python/src agent-engine-python/tests
uv run --project agent-engine-python pytest -q agent-engine-python/tests
uv run --project agent-engine-python python agent-engine-contract/conformance/validate_contract.py
mvn -q -pl yanban-api -am "-Dtest=ReactPlanRuntimeServiceTest,AgentEngineModelGatewayTest,ModelFailureDiagnosticTest,AgentEngineGatewayContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
git diff --check
```

Python **74 tests passed**。Java 实际执行三个 suite，共 **13 tests passed**（6 + 5 + 2），无失败/跳过；命令中的 AgentEngineGatewayContractTest 不存在，未产生测试，不能视为验证证据。共享协议验证通过：8 schemas、15 operations、13 positive / 5 negative fixtures、5 ordered events、16 scenarios。lint 通过。

在 `frontend/` 执行：

```powershell
pnpm exec vitest run src/views/__tests__/ProjectPreviewPageReactPlan.test.ts src/utils/__tests__/reactPlanTask.test.ts
$env:CI='true'
pnpm build
```

前端 **30 tests passed**，vue-tsc / Vite 构建通过。仍有既有 Starlette/AnyIO 弃用提示及 Vite bundle 体积提示。

新增回归覆盖：带 Sort.java 历史的问候、身份和通用问题只调用一次模型且不读 Project；原生 assistant/tool 消息关联；完整 manifest hash 传递；重复读取缓存与循环上限；供应商失败经 Java/Python 投影到任务/SSE 并在重启后保留；旧版本未完成任务明确拒绝继续。提示词参考 `agent-engine-reactplan/src/engine.ts` 当前请求优先规则及其身份问答回归，但不宣称所有 TS 工具或场景等价。

未使用真实 Project 或付费模型，工具/model HTTP 为 MockTransport；测试证明控制流与协议行为，不证明真实模型始终遵循提示词，也不构成性能/质量 A/B。没有重启用户服务、重建此版本 Docker 镜像或执行云部署；没有修改 Redis 会话缓存。用户需更新并重启 Java/Python、刷新前端，使用新任务测试；旧 Plan-and-Execute 未完成图不能迁移为新图。Python 仍为单 worker、本地 SQLite 和有界上下文，尚无完整 TS 功能覆盖及复杂长期记忆策略。
