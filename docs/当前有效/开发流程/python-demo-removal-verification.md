# Python demo 移除验证（2026-09-19）

用户撤回 #233 Python 引擎试验，决定先回到现有 TS 引擎，再讨论 LangGraph 优化。此决定替代此前启用 Python 的合同；本次不实施 LangGraph，不继续 #228，也不改变会话缓存实现。

## 最终行为

- 删除 Python demo 源码、依赖、测试、镜像及启动说明；移除前端选择器、Java Python 连接配置和专用 claim 入口、Compose 服务/profile/卷声明及服务器脚本分支。
- 根 `.env` 仅清除 Python 引擎变量及对应 profile；本地 demo `.env` 已清空凭证。没有输出或提交真实环境值。
- 本地已确认的 demo 进程 PID 28056 已停止，8097 不再监听；未发现运行中的 Python Compose 服务。未重启 Java、TS、前端或部署云端。
- 保留 V108 原始迁移及历史 intake engine 字段、历史查询和隔离防线。旧 Python 请求返回 410；不恢复到 TS，不获取恢复 grant，不占 TS 排队额度。前端显示停用历史，禁止旧任务重连/继续/取消，但允许发送新 TS 任务。旧任务原始状态不被伪改为终态。
- 本次没有操作 Java 会话、消息、任务记录或共享 Redis。

自动审批拒绝了 shell 删除操作，仅报告 blocked by policy，未提供更具体原因。已通过逐文件补丁删除所有受版本管理的 demo 文件；未跟踪的 `.venv`、本地运行数据和缓存保留，已从 Git 与 Docker 构建上下文排除。不存在可部署 Python 服务；未删除共享数据库历史或 Docker 数据卷。

## 验证命令

仓库根目录：

```powershell
mvn -q -pl yanban-api -am "-Dtest=ReactPlanEngineSelectionTest,ReactPlanRuntimeServiceTest,ReactPlanEngineClientWiringTest,ReactPlanTaskSchedulerServiceTest,ReactPlanTaskStateServiceTest,ReactPlanTaskStateControllerTest,ReactPlanTurnIntakeServiceTest,ReactPlanTurnIntakePersistenceTest,ReactPlanSessionTaskQueryServiceTest,ReactPlanEngineMigrationTest,AgentEngineTaskGrantServiceTest,AgentEngineModelGatewayTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
# 补充恢复拒绝与 H2 排队额度回归后，重跑直接受影响的两个 suite：
mvn -q -pl yanban-api -am "-Dtest=ReactPlanTaskSchedulerServiceTest,ReactPlanTaskStateServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
docker compose --env-file .env.example -f docker-compose.prod.yml --profile reactplan --profile sandbox config --services
& 'C:\software\Git\bin\bash.exe' -n scripts/server/common.sh
& 'C:\software\Git\bin\bash.exe' -n scripts/server/status.sh
git diff --check
```

`frontend/`：

```powershell
pnpm exec vitest run src/views/__tests__/ProjectPreviewPageReactPlan.test.ts src/utils/__tests__/reactPlanTask.test.ts
$env:CI='true'
pnpm build
```

Java 最终 12 个 suite、44 项测试全部通过，无失败、错误或跳过。第一次执行 42 项，增加两项后重跑 scheduler/state 的 13 项；去重后为 44 项。验证包括退役请求拒绝、TS 装配与路由、租约/队列、持久化/历史、原迁移兼容及模型网关。前端 30 项测试及 vue-tsc/Vite 构建通过，保留既有大 bundle 提示。Compose 解析中只有 TS 引擎及原产品服务；脚本语法通过。首次按常见安装路径查找 Bash 未找到，随后使用实际 Git 安装目录完成检查。环境文件只做键名/profile 断言，不输出值。

## 未执行与限制

不调用真实模型，不使用真实 Project，不跑浏览器全链路或云部署，不修改共享数据库。后端包含 H2 隔离验证，未执行 MySQL 迁移。没有修改 TS 引擎源码，因此未扩大到 TS 全部工具测试。现有 Java 进程需重启才能加载退役规则；前端需刷新，生产环境需重新构建/部署。此前服务器上若已部署 Python 容器，应单独停止旧容器并保留其卷，不对整栈执行 down -v。
