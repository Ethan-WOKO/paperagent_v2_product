# 上线前本地验收评测

这套评测用于回答一个很实际的问题：当前 Agent 到底能不能稳定给客户试用。它不是只看模型回答是否聪明，而是同时检查 DeepSeek/GLM 同套能力、真实文件知识库、Plan 执行链路、并发稳定性和基础运维闭环。

## 前置条件

- 后端已启动并可访问 `http://localhost:8080`。
- 本地依赖服务已启动，推荐使用 `docs/docker-compose.yml`。
- DeepSeek 验收需要配置 `DEEPSEEK_API_KEY`。
- GLM 验收需要配置 `GLM_API_KEY`，默认真实调用 `glm-4.5-air`。
- 缺少某个 provider key 时，脚本会把该 provider 记为 `SKIP`，不会伪装通过。

脚本会优先读取项目根目录 `.env`，也支持直接使用当前 shell 环境变量。

## 快速命令

代码级回归：

```powershell
mvn -pl yanban-api -am "-Dtest=PlanAgentControllerIntegrationTest,AgentControllerIntegrationTest,PlanAgentServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

DeepSeek 本地验收：

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-local-eval.ps1 -Providers deepseek -FixtureMode mixed -RunPlanExecution
```

GLM 同套验收：

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-local-eval.ps1 -Providers glm -FixtureMode mixed -RunPlanExecution
```

双 provider 验收：

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-local-eval.ps1 -Providers deepseek,glm -FixtureMode mixed -RunPlanExecution
```

并发验收：

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-concurrency-eval.ps1 -Concurrency 5 -Requests 10
```

运维闭环：

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-ops-eval.ps1
```

## 评测内容

`run-local-eval.ps1` 覆盖核心产品能力：

- 用户注册、登录、模型配置、会话创建。
- 真实知识库文件上传，`markdown` 模式上传 Markdown，`mixed` 模式上传 PDF、DOCX、Markdown。
- `/api/v1/search` 可检索上传文件中的唯一事实 key。
- RAG 聊天可命中私有知识库事实。
- 普通聊天和禁用 RAG 场景不会被知识库污染。
- 工具搜索任务应能停止并输出最终回答，而不是无限搜索。
- Plan 创建默认检查计划结构；开启 `-RunPlanExecution` 后会执行计划并校验终态、步骤状态和事件链。

`run-concurrency-eval.ps1` 覆盖低成本并发路径：

- 默认 5 并发、10 个请求。
- 每个请求独立创建会话并完成普通聊天和 RAG 检索。
- 额外检查 Bob 用户不能检索 Alice 的私有知识库事实。
- 成功标准为无 5xx、无越权读取、所有请求有最终响应、p95 小于 60 秒。

`run-ops-eval.ps1` 覆盖非破坏性运维闭环：

- Docker Compose 服务状态可读取。
- 后端 health 可访问。
- MySQL schema dump 可生成到本次 run 目录。
- Elasticsearch、MinIO、Kafka 可访问或给出明确失败结果。
- 报告中不应泄露关键配置明文。

## 结果文件

所有脚本默认把结果写入：

```text
docs/evaluation/runs/
```

每次运行会生成 JSON 和 Markdown 两份报告。单条结果 schema 统一为：

```json
{
  "id": "P0-RAG-SEARCH",
  "provider": "deepseek",
  "status": "PASS",
  "pass": true,
  "score": 1.0,
  "durationMs": 1234,
  "note": "short explanation",
  "excerpt": "short response excerpt",
  "error": null
}
```

`PASS` 表示该项达到门槛，`FAIL` 表示必须修复，`SKIP` 表示条件不足但不算通过。

## 上线门槛

- 指定 Maven 回归测试必须通过。
- DeepSeek mixed fixtures + Plan execution 必须通过。
- 如果客户会使用 GLM，GLM mixed fixtures + Plan execution 必须通过；如果 GLM key 缺失，只能说明“未验收 GLM”，不能标记为已上线就绪。
- 并发验收必须无 5xx、无越权读取，p95 小于 60 秒。
- 运维闭环必须至少满足后端 health、Docker Compose 状态、MySQL dump、关键配置脱敏检查通过。
- 不允许出现跨用户私有知识泄露。
- 不允许出现长时间无最终响应且报告中没有明确失败原因。

## 建议判定

如果所有 P0 项通过、P1 项没有系统性失败，可以进入小范围试用。如果 Plan execution 或 GLM 只是不稳定，建议把 Plan/GLM 标成 beta 或灰度能力，不要作为客户承诺的核心卖点。

如果 RAG 真实文件解析、会话隔离、并发或运维闭环任何一项失败，不建议上线给客户使用。这些不是“回答质量问题”，而是产品可靠性和数据安全底线。
