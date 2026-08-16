# ReAct Agent 本地启动与测试

## 现在能测试什么

这条链路已经支持：认证用户提交 Project 任务、读取该 Turn 冻结的 ProjectVersion、让模型多轮选择工具、通过产品沙箱 broker 执行 Java、保存正式 Receipt、查看状态/SSE、取消、回答追问以及 Engine 重启恢复。

P1 是只读链路：不会修改、发布或回滚 Project。

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
$env:AGENT_ENGINE_DATA_DIR = ".data"
$env:AGENT_ENGINE_PROVIDERS_JSON = '{"deepseek":{"baseUrl":"https://api.deepseek.com","apiKeyEnv":"DEEPSEEK_API_KEY"}}'
$env:DEEPSEEK_API_KEY = "your-deepseek-key"
npm install
npm start
```

看到 `agent-engine-reactplan listening on 127.0.0.1:8092` 即启动成功。

## 4. 提交 Sort.java 测试

先在正常产品页面登录，创建或选中包含 `Sort.java` 的 Project，并进入绑定该 Project 的 Agent Turn。准备登录 access token 与 `turnId`：

```powershell
$api = "http://127.0.0.1:8080"
$accessToken = "paste-your-product-access-token"
$turnId = 123
$headers = @{ Authorization = "Bearer $accessToken" }
$request = @{
  instruction = "读取 Sort.java，在沙箱中用 yanban-runner java Sort.java 编译或运行，并根据正式回执解释结果。"
  provider = "deepseek"
  model = "deepseek-chat"
} | ConvertTo-Json
$task = Invoke-RestMethod -Method Post -Uri "$api/api/v1/react-agent/turns/$turnId/tasks" -Headers $headers -ContentType "application/json" -Body $request
$task
```

复制返回的 `taskId`，查看状态：

```powershell
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
.\agent-engine-reactplan\scripts\submit-sort-task.ps1 -TurnId 123
```

## 恢复验证

任务运行中停止 Engine，再用完全相同的 `AGENT_ENGINE_DATA_DIR` 和环境变量启动。然后用完全相同的 Turn 和 instruction 再执行一次“提交任务”请求：Java 会重放同一个 Plan，并签发新的短期 grant；Engine 会识别同一 task/digest、恢复执行而不新建任务。重新请求状态或 SSE 时 sequence 不会倒退或重复。短期 grant 不写入磁盘，因此恢复必须经过这次认证重放，不能绕过产品权限。

## 常见失败

- `MODEL_CREDENTIAL_UNAVAILABLE`：模型 key 没有放进 `apiKeyEnv` 指向的环境变量。
- `TASK_GRANT_*`：Java 和 Engine 之间的任务授权失效；重新提交任务。
- `SANDBOX_SYSTEM_ERROR`：broker/E2B 故障，不代表 `Sort.java` 编译失败。
- Receipt 状态 `FAILED` 且有 javac stderr：链路正常，被测代码编译失败；这是可信任务结果。
