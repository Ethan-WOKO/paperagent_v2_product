# ReAct Agent 本地启动与测试

## 现在能测试什么

这条链路已经支持：认证用户从 Project session 直接提交任务、由产品创建幂等 Turn、读取该 Turn 冻结的 ProjectVersion、让模型从现有只读 Project 工具、隔离 Workspace ADD/MODIFY/diff 工具和沙箱工具中多轮自主选择、保存正式 Receipt、查看状态/SSE、取消、回答追问以及 Engine 重启恢复。

P1 仍是只读链路。Issue #165 增加的修改只发生在 task 隔离 Workspace；当前仍不会发布或回滚 Project。

## 一次性准备

需要 Node.js 20+、Java/Maven、产品已有的 MySQL/MinIO 等依赖，以及一个可运行的 sandbox broker。真实模型测试还需要 DeepSeek API key；真实 Java 执行需要 broker 已配置 E2B。

以下三个值请自己生成，不要提交到 Git：

```powershell
$engineToken = "replace-engine-token-at-least-32-chars"
$grantSecret = "replace-grant-secret-at-least-32-chars"
$brokerToken = "replace-broker-token-at-least-32-chars"
```

下面会使用这三个 PowerShell 变量。因为每一步在新终端运行，请在每个新终端先重复设置相同的三个值。

## 1. 启动 sandbox broker（8091）

在仓库根目录设置 broker 原有的数据库、E2B 环境变量，然后：

```powershell
$env:YANBAN_SANDBOX_BROKER_ENABLED = "true"
$env:YANBAN_SANDBOX_BROKER_TOKEN = $brokerToken
$env:E2B_API_KEY = "your-e2b-key"
mvn -pl yanban-sandbox-broker spring-boot:run
```

若你已有独立 broker，跳过本步，保留它的 URL 和 token 即可。

## 2. 启动 Java 产品后端（8080）

另开终端，在仓库根目录设置产品原有环境变量，再增加：

```powershell
$env:YANBAN_AGENT_REACTPLAN_ENABLED = "true"
$env:YANBAN_AGENT_REACTPLAN_ENGINE_ORIGIN = "http://127.0.0.1:8092"
$env:YANBAN_AGENT_REACTPLAN_ENGINE_SERVICE_TOKEN = $engineToken
$env:YANBAN_AGENT_ENGINE_GATEWAY_ENABLED = "true"
$env:YANBAN_AGENT_ENGINE_GATEWAY_TASK_GRANT_SECRET = $grantSecret
$env:YANBAN_SANDBOX_ENABLED = "true"
$env:YANBAN_SANDBOX_BROKER_URL = "http://127.0.0.1:8091"
$env:YANBAN_SANDBOX_BROKER_TOKEN = $brokerToken
mvn -pl yanban-api spring-boot:run -Dspring-boot.run.profiles=dev
```

这里的短期 grant secret 只在 Java 内使用；Engine 永远拿不到 broker token。

## 3. 启动 ReAct Engine（8092）

再开一个终端：

```powershell
Set-Location agent-engine-reactplan
$env:ENGINE_SERVICE_TOKEN = $engineToken
$env:PRODUCT_GATEWAY_ORIGIN = "http://127.0.0.1:8080"
npm install
npm start
```

模型密钥只配置给 Java 产品后端；不要把 `DEEPSEEK_API_KEY` 或其他模型凭证传给
Engine。看到 `agent-engine-reactplan listening on 127.0.0.1:8092` 即启动成功。

## 4. 提交 Sort.java 测试

先在正常产品页面登录，创建或选中包含 `Sort.java` 的 Project，并创建绑定该 Project 的 Agent session。用户不需要提前创建 Turn；新入口会为这次请求确定性创建。准备登录 access token 与 `sessionId`：

```powershell
$api = "http://127.0.0.1:8080"
$accessToken = "paste-your-product-access-token"
$sessionId = 123
$headers = @{ Authorization = "Bearer $accessToken" }
$request = @{
  clientRequestId = "request.manual-test-0001"
  instruction = "读取 Sort.java，在沙箱中用 yanban-runner java Sort.java 编译或运行，并根据正式回执解释结果。"
  provider = "deepseek"
  model = "deepseek-chat"
} | ConvertTo-Json
$task = Invoke-RestMethod -Method Post -Uri "$api/api/v1/react-agent/sessions/$sessionId/tasks" -Headers $headers -ContentType "application/json" -Body $request
$task
```

复制返回的 `taskId`，查看状态：

```powershell
$turnId = $task.turnId
$taskId = $task.taskId
Invoke-RestMethod -Uri "$api/api/v1/react-agent/turns/$turnId/tasks/$taskId" -Headers $headers
curl.exe -N -H "Authorization: Bearer $accessToken" -H "Last-Event-ID: 0" "$api/api/v1/react-agent/turns/$turnId/tasks/$taskId/events"
```

若事件出现 `question`，用其中的 `questionId` 回答：

```powershell
$answer = @{ questionId = "question.from-event"; answer = "请测试 Sort.java" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$api/api/v1/react-agent/turns/$turnId/tasks/$taskId/answer" -Headers $headers -ContentType "application/json" -Body $answer
```

取消任务时，客户端请求 ID 要保持稳定：

```powershell
$cancel = @{ clientRequestId = "cancel.manual-test-0001" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$api/api/v1/react-agent/turns/$turnId/tasks/$taskId/cancel" -Headers $headers -ContentType "application/json" -Body $cancel
```

也可以用仓库内验收脚本自动提交、轮询并回放事件（token 放环境变量，脚本不会打印它）：

```powershell
$env:PAPERAGENT_ACCESS_TOKEN = "paste-your-product-access-token"
.\agent-engine-reactplan\scripts\submit-sort-task.ps1 -SessionId 123
```

## 有限对话与事实约束验证

Issue #163 后，在同一个 Project session 连续新建两个 ReAct task：

1. 第一轮：`检查 Sort.java，看看其能否正常编译。`
2. 第二轮：`继续刚才的检查，只告诉我最终原因和依据。`

第二轮应能使用第一轮的用户任务和最终结论，但仍须重新通过当前 task 的 Project 工具
确认文件事实；历史结论不能替代当前 ProjectVersion 证据。切换 session 或 Project 后，
不应看到第一轮内容。

同一 session 的两轮任务卡片应同时显示，并在刷新后继续保留。前端最多保存最近 12 个
ReAct task record；开始第 13 个后只移除最早的展示投影，不删除 Engine task、Receipt
或产品数据。

对只包含 `Sort.java` 的 Project，最终回答不得声称 `pom.xml`、`build.gradle` 等未在
manifest 或其他 Project 工具结果中出现的文件声明了依赖。Engine 会拒绝这种候选
回答并允许模型在同一 task 内修正；连续无法修正时以 `MODEL_GROUNDING_FAILED`
失败闭合。

若只验证了原始文件，回答也不得把“移除某行后即可编译”写成已证明结论；应明确写为
预期修复，并说明需要对修改后的文件重新执行沙箱验证。

## 恢复验证

任务运行中停止 Engine，然后使用相同的服务配置重新启动 Engine。checkpoint 与有序事件保存在产品 MySQL 中；Engine 启动后会通过 Java 内部网关读取未完成任务，并在 Java 重新核对原始 Turn、session、ProjectVersion 和 request digest 后取得新的短期 grant，随后自动继续，不需要用户重新发送任务。重新请求状态或 SSE 时 sequence 不会倒退或重复。短期 grant、服务 token 和模型 key 都不会写入 checkpoint。

注册工具由 Java 根据当前 Project 权限动态筛选并在任务第一次模型调用前冻结，继续只暴露 `NONE/READ_ONLY` 类型，例如 `project_manifest`、`project_search`、`project_read_file` 和已注册的只读分析工具。Workspace ADD/MODIFY/diff 是单独的 Runtime 工具，只在 task grant 带 `writeWorkspace` 时由 Engine 暴露；外部副作用工具仍不进入本链路。沙箱命令从 Project 根目录开始，子目录源码必须使用完整 Project 相对路径，例如 `yanban-runner java services/order-service/Sort.java`。

## 常见失败

- `MODEL_CREDENTIAL_UNAVAILABLE`：模型 key 没有放进 `apiKeyEnv` 指向的环境变量。
- `TASK_GRANT_*`：Java 和 Engine 之间的任务授权失效；重新提交任务。
- `SANDBOX_SYSTEM_ERROR`：broker/E2B 故障，不代表 `Sort.java` 编译失败。
- Receipt 状态 `FAILED` 且有 javac stderr：链路正常，被测代码编译失败；这是可信任务结果。
