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
