# Project 页引擎对照（实验功能，#233）

日期：2026-09-19。当前默认运行时仍是 TypeScript ReAct。Python 使用 LangGraph Plan-and-Execute；本次只开放 Project 文件的只读分析。此文覆盖产品接入，README 中的 `/dev/v1` 仍是隔离 demo。

## 开关与权限

- Project 页输入框上方选择 TS / Python；提交时冻结到任务 intake。切换只影响之后提交的任务。刷新后根据会话历史恢复选择；旧记录默认为 TS。
- Python 服务未启用时，选择 Python 提交会明确失败，不会自动降级到 TS。已有任务的查询、SSE、取消始终按照已冻结的引擎路由。
- Python 仅注册列目录和按 hash 读取文件两个工具。Java 签发的任务 grant 同时禁止 Workspace 写入、sandbox 执行和发布。Python 不开放技能选择、润色修改或执行代码。
- 模型通过 Java 现有模型网关调用，沿用用户选择的 provider/model、配额与调用事实；真实 Project 请求不使用 DemoModel，不把产品密钥交给 Python。
- Java 继续拥有会话、消息、任务事件、用量结算和缓存失效。Python 通过现有 Java task-state 写入协议、事务事件及终态结算路径持久化产品事实，不直连产品数据库，不操作 Redis。

## 本地启用

本次开发没有启动或替换任何服务，也没有对共享数据库执行迁移。启用前先停止并按已有本地开发流程重启 Java；由 Flyway 正常执行新增 V108（intake.engine，旧行默认 TS）。不要手动删除迁移历史。

Java 启动进程需要以下环境变量，并保留原有 TS 引擎配置：

```powershell
$env:YANBAN_AGENT_PYTHON_ENABLED = 'true'
$env:YANBAN_AGENT_PYTHON_SERVICE_TOKEN = [guid]::NewGuid().ToString('N')
# 默认 YANBAN_AGENT_PYTHON_ORIGIN=http://127.0.0.1:8097
# 按现有项目启动流程启动 Java，使上述变量生效。
```

Python 启动时自动读取本目录 `.env`，只需配置一次（该文件已被 Git 忽略）。首次复制 `.env.example` 为 `.env`，已有文件不要覆盖，然后填写：

```dotenv
PAPERAGENT_PYTHON_TOKEN=与Java的YANBAN_AGENT_PYTHON_SERVICE_TOKEN一致
PAPERAGENT_PYTHON_JAVA_SERVICE_TOKEN=Java现有的ReAct引擎service-token
```

只读取 Python 服务目录的 `.env`，不会向上查找其他服务的配置。已有终端/系统环境变量优先于文件，token 按原样读取；修改文件后需重启 Python。Java 不会读取此文件，Java 侧仍需在自己的启动配置中设置对应值。

之后每次直接启动：

```powershell
cd agent-engine-python
uv sync --locked
uv run --frozen paperagent-python serve-product
```

Python 固定监听 `127.0.0.1:8097`，Java 网关固定 `127.0.0.1:8080`，单 worker。本地 LangGraph checkpoint/调用日志存于忽略提交的 `.product-data/`，与 demo `.data/` 分开。服务启动不扫描或接管既有任务；只有显式提交给 Python 的任务进入执行队列。TS 的 claim/recovery 排除 Python，Python 只 claim 指定的 Python task，仍遵守 Java 的总并发、用户并发及 fencing lease。

关闭实验入口时把 Java 的 `YANBAN_AGENT_PYTHON_ENABLED` 改为 `false` 并按正常流程重启。应先让在途 Python 任务完成或取消；关闭后在途任务不会被 TS 接管。保留 V108 和本地数据，以便读取历史或以后恢复。

## 云服务器 Compose 部署

`docker-compose.prod.yml` 已包含 Python 服务、独立持久化卷、健康检查和内部通信配置。服务器只在**仓库根目录 `.env`** 配置一次：

```dotenv
YANBAN_AGENT_PYTHON_ENABLED=true
YANBAN_AGENT_PYTHON_SERVICE_TOKEN=自行生成的至少32字符独立服务器密钥
COMPOSE_PROFILES=sandbox,reactplan,python
```

保留已有 TS/Java 网关配置。Compose 自动把新 token 同时传给 Java 和 Python，并用现有 `YANBAN_AGENT_REACTPLAN_ENGINE_SERVICE_TOKEN` 配置 Python→Java 认证；不用再填一份 Python token，也不复制本地 Python `.env`。如果已有其他 profiles，应保留并追加 `python`。

更新代码后执行原一键部署流程即可；直接使用 Compose 时运行：

```bash
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml logs -f agent-engine-python
```

`scripts/server` 启动/更新脚本会根据 enabled 开关自动加 Python profile、校验 token 并等待健康检查。Java 地址为 `http://agent-engine-python:8097`，Python 网关地址为 `http://api:8080`，固定服务名列入白名单；不向宿主机发布 Python 端口。镜像以非 root 用户运行，`.env`、本地数据和开发虚拟环境不进入构建上下文。

Docker 使用 `python_engine_data` 卷和单 worker；重建容器保留状态，但不会自动恢复任务，已有恢复限制仍适用。不要用 `down -v` 更新。单独停止引擎用 `docker compose -f docker-compose.prod.yml stop agent-engine-python`；看日志时 Ctrl+C 仅退出日志。完整步骤见 [服务器脚本说明](../scripts/server/README.md#optional-python-project-engine)。

编排使用标准 [Compose profiles](https://docs.docker.com/compose/how-tos/profiles/)，镜像依赖安装采用 [uv Docker 集成](https://docs.astral.sh/uv/guides/integration/docker/) 的锁定、非 editable 安装方式。

## 协议和恢复边界

- 复用 `/v1/tasks`、任务 GET、SSE（Last-Event-ID）、取消协议。共享 submission schema 将 executeSandbox 扩展为布尔权限；TS 行为不变。新增内部 `POST /internal/v1/agent-engine/task-state/python/tasks/{taskId}/claim`，使用已有服务认证。
- 产品 checkpoint/event 始终先持久化到 Java 后再公开；返回的 succeeded 不是仅存在 Python 内存中的状态。没有额外会话缓存或 Redis 前缀，沿用现有终态事务触发的会话汇总和缓存失效。
- graph checkpoint、工具/模型调用 journal 在本地 SQLite；已知调用结果回放，不重复调用。崩溃造成的未知结果按失败关闭处理，不能承诺 exactly-once 外部模型调用。
- 进程重启不自动恢复任务。显式重发原来的 session task POST（原 clientRequestId、instruction、engine、provider/model）才会尝试恢复；模型路由或 ProjectVersion 已改变时可能被摘要冲突拒绝。当前 UI 没有单独“恢复任务”按钮。不要删除 `.product-data/`；Java 产品投影不能重建丢失的 LangGraph 执行状态。
- 工具读取支持文本分段和结构化文档 continuation；超限 manifest（100 文件）、模型/工具预算或解析失败会明确停止。任何没有 continuation 的截断都必须在回答中说明。
- 当前限制：单 worker、输入最多 2000 字符、不支持交互提问、图和模型上下文大小有限；历史/记忆只接入 Java 快照（有长度/数量上限），不写回长期记忆。无自动后台恢复和多实例部署保证。

## 对照方法

按用户要求，本次不使用真实 Project 做测试，不调用付费模型。后续准备专用测试 Project，使用同一不可变 ProjectVersion、同一 provider/model、相同只读问题、相同用户记忆设置。每次新建会话，TS/Python 交替执行，避免历史及先后顺序偏置。不要在测试期间编辑项目或记忆；分别记录预热与正式轮次，每类问题建议至少 10 对样本。

建议问题覆盖：单文件事实抽取、跨文件一致性、证据不足处理、长文分页、多步只读分析、取消。质量人工评分至少包含事实正确性、证据可追溯性、遗漏、是否夸大已读范围。先满足质量要求，再比较总耗时、Token、模型调用和失败率。Python 的工具集合更小；这衡量当前产品实现的表现，不能单独证明语言或框架更快。

创建本地样本清单（不要提交真实 task 或用户数据）：

```json
[
  {"engine":"TS","case":"cross-file-1","turnId":1,"taskId":"task.<64位hash>","qualityScore":4},
  {"engine":"PYTHON","case":"cross-file-1","turnId":2,"taskId":"task.<64位hash>","qualityScore":4}
]
```

在本目录运行只读采集工具：

```powershell
# PAPERAGENT_EVAL_ACCESS_TOKEN：该测试用户的产品访问 token，显式通过环境提供。
uv run --frozen python -m paperagent_engine.compare .eval/samples.json --output .eval/report.json
```

工具只 GET 已有 Java trace，不创建会话、不提交任务、不调用模型。输出任务级指标、路由记录、质量备注及按引擎统计的成功数和 p50/p95。引擎/case 标签来自清单，须核对历史。失败保留在分母，耗时分位仅计算成功样本；`firstObservableMillis` 是首个可见事件，不是首个实质性答案。小样本 p95 不稳定；须核对模型 fallback 与问题配对，不可只比较均值。

验证命令和证据见 [VERIFICATION.md](VERIFICATION.md)。
