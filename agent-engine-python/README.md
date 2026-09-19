# Python Plan-and-Execute 独立服务

产品实验接入已在 #233 增加：见 [Project 页引擎开关与对照说明](PRODUCT-INTEGRATION.md)。以下内容描述阶段一的独立 demo 模式；`serve-product` 是单独的显式入口，权限、持久化和启动方式以接入说明为准。

日期：2026-09-19。分类：开发 demo。Issue：[#231](https://github.com/Ethan-WOKO/paperagent_v2_product/issues/231)。

适用范围：未来替换当前 TypeScript Project ReAct 的第一阶段技术验证。所有新增内容仅在本目录。
非目标：生产接入、现有会话/消息/任务数据写入、缓存改造、前端切流、#228 退役、Workspace 修改、沙箱执行、发布。

当前实现是可独立运行的 FastAPI 微服务，使用真实 LangGraph StateGraph/SqliteSaver 编排和持久化、LangChain StructuredTool 及 ChatOpenAI 进行工具和模型适配。
默认 `demo` 模型是明确标识的离线脚本，验证链路，不代表语义分析质量。可显式选用真实 OpenAI-compatible 模型，但不读取产品模型密钥或调用 Java 网关。

## 快速验证

在本目录运行，需要 Python 3.11–3.13 和 uv：

```powershell
uv sync --locked
uv run --frozen pytest -q
uv run --frozen paperagent-python demo
```

demo 使用代码生成的两份虚构研究材料，在临时目录运行后退出，不打开端口、不留下后台服务。
结果包含两个已完成步骤、三个本地证据引用和 `succeeded` 状态。脚本结果不会声称完成了真实论文语义分析。

## 手动启动独立服务

```powershell
# 使用独立随机开发 token；不要复用产品凭证。
$env:PAPERAGENT_PYTHON_TOKEN = [guid]::NewGuid().ToString('N')
uv run --frozen paperagent-python serve
```

固定监听 `127.0.0.1:8097`，单进程。端口占用时启动失败，不停止其他服务。
SQLite 数据只写本目录 `.data/`；没有 Redis 依赖、共享 Redis 操作、数据库连接或产品目录访问。
同一数据目录用进程锁防止双实例。启动只打开本地存储，不扫描或推进任何任务。
Swagger 位于 `/docs`，OpenAPI 位于 `/openapi.json`；开发接口认证使用 `Authorization: Bearer <独立 token>`。

生成并提交虚构请求：

```powershell
$headers = @{ Authorization = "Bearer $env:PAPERAGENT_PYTHON_TOKEN" }
$body = uv run --frozen python -c "from paperagent_engine.cli import demo_submission; print(demo_submission().model_dump_json())"
$accepted = Invoke-RestMethod http://127.0.0.1:8097/dev/v1/tasks -Method Post -Headers $headers -ContentType 'application/json' -Body $body
$task = $accepted.task
$url = "http://127.0.0.1:8097/dev/v1/tasks/$($task.task_id)"
# 每次显式推进一个图节点；重复到终态。GET 不触发执行。
$advance = @{ expected_sequence = $task.last_sequence } | ConvertTo-Json
$task = Invoke-RestMethod "$url/advance" -Method Post -Headers $headers -ContentType 'application/json' -Body $advance
```

服务不会自动跑完已提交任务。调用者必须显式调用 `advance`；不需要后台 worker。
HTTP 超时后先 GET 检查最新 sequence；旧 sequence 返回 `409 STALE_ADVANCE`，避免自动重试悄悄多执行一个节点。

## 真实模型（可选）

```powershell
$env:PAPERAGENT_PYTHON_MODEL = 'your-tool-capable-model'
$env:PAPERAGENT_PYTHON_MODEL_KEY = 'your-independent-development-key'
# 仅在需要 OpenAI-compatible 服务时设置；不读取产品配置。
# $env:PAPERAGENT_PYTHON_MODEL_BASE_URL = 'https://your-provider.example/v1'
uv run --frozen paperagent-python demo --model openai
# 或：uv run --frozen paperagent-python serve --model openai
```

需要 provider 支持结构化输出和工具调用。模型请求 timeout=30s、自动重试=0、max_tokens=4096。
模型和服务凭证不进入任务请求、checkpoint 或事件。CLI 禁用环境继承的 LangSmith tracing；图执行也显式关闭 tracing。
真实模式会向显式配置的 provider 发送开发任务内容。没有运行真实付费调用的默认行为。
恢复未完成任务时必须沿用同一模型配置和软件版本；阶段一尚不支持滚动升级或模型切换迁移。

## 实现范围

```text
bootstrap(memory snapshot)
  → plan → act → tool → act ...
                 ↓
               finish → next step / replan / synthesize → END
```

- 每次计划 1–5 个串行步骤，校验唯一 key、前向依赖和环；累计已完成加剩余步骤不超过 8。
- 最多 2 次重规划，保留已完成事实，禁止复用已完成步骤 key。
- 每步骤最多 10 次模型动作，任务最多 20 次模型调用、24 次工具调用。预算在调用前持久化，重启不重置。
- 四个 LangChain 只读工具：`list_documents`、`read_document`、`search_documents`、`search_memory`。只操作显式提交的合成文档与记忆，不读取宿主文件。
- 单次读取最多 2000 字符，显式分页。未知工具、越界/额外参数被拒绝，模型可从失败观察调整。
- 任务目标、约束、完成结果、当前步骤观察、记忆分别组装。frame 最多 6000 字符，模型上下文最多 24000 字符；这是字符预算，不是 token 精确估计。
- 旧步骤只传递结果摘要和证据引用，当前步骤较早读取移除正文、保留分页信息。原始结果仍在本地 operation journal/checkpoint。
- `MemoryPort` 是只读适配接口；默认冻结请求中的记忆，恢复不重新召回。跨会话持久记忆服务接入及写入蒸馏留到下一阶段。
- 完成步骤必须引用本步骤实际成功的非记忆工具结果。此检查证明引用存在，**不证明模型 summary 或 done_when 的语义正确性**。
- 取消终态幂等；已在进行的模型 HTTP 调用允许在超时前返回，后续工具/交付被阻止。不是 provider 侧强制中断。
- 每次 `advance` 只运行一个图节点，checkpoint 同步持久化；已知结果重放，调用后尚未记账的未知结果以 `OPERATION_OUTCOME_UNKNOWN` 失败，不猜测重试。

## 模块

| 文件 | 职责 |
| --- | --- |
| `contracts.py` | 严格输入、Plan、步骤动作合同 |
| `runtime.py` | 图节点、上下文、证据门、显式推进与恢复 |
| `storage.py` | 独立 SQLite 任务元数据、事件、调用 journal |
| `tools.py` | 只读 demo 工具与 MemoryPort |
| `models.py` | ModelPort、离线模型、LangChain 模型适配 |
| `api.py` / `cli.py` | 开发 HTTP API、单进程服务与一次性 demo |

[开发协议](docs/development-protocol.md)、[后续接入方案](docs/integration-proposal.md)、[验证记录](docs/verification.md)。

参考：[LangGraph Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)、[持久化](https://docs.langchain.com/oss/python/langgraph/persistence)、[LangChain Models](https://docs.langchain.com/oss/python/langchain/models)。
