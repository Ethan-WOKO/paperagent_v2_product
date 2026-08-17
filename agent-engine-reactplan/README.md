# agent-engine-reactplan

这是轻量 ReAct 运行器。它不直接读取产品数据库或持有 broker 凭证；它只接收产品签发的短期任务授权，并通过 Java 产品网关读写隔离 Workspace、执行沙箱、取得正式 Receipt。修改内容精确验证成功后，由服务端确定性终结器自动发布新 ProjectVersion。

## 本地校验

```powershell
npm install
npm run typecheck
npm test
npm run build
```

## 启动所需环境变量

```powershell
$env:ENGINE_SERVICE_TOKEN = "replace-with-at-least-32-characters"
$env:PRODUCT_GATEWAY_ORIGIN = "http://127.0.0.1:8080"
$env:AGENT_ENGINE_PROVIDERS_JSON = '{"deepseek":{"baseUrl":"https://api.deepseek.com","apiKeyEnv":"DEEPSEEK_API_KEY"}}'
$env:DEEPSEEK_API_KEY = "your-key"
npm start
```

`npm start` 会先自动构建。默认监听 `127.0.0.1:8092`，任务和事件写入 `.data/`。完整启动顺序及测试请求见 `docs/当前有效/开发流程/react-agent-local-run.md`。

## 运行保证

- 同一 `taskId` + 相同 authority 摘要是幂等重放；不同内容返回冲突。
- 事件 sequence 单调递增，SSE 支持 `Last-Event-ID` 续传。
- 服务重启后从任务 JSON 和 append-only 事件恢复未完成任务。
- 模型最多调用 20 次；除按 hash 读取和白名单沙箱外，还会冻结并复用产品注册表中当前任务获准的只读 Project 工具。带 `writeWorkspace` 权限的任务额外获得隔离 Workspace 的 ADD/MODIFY 与 diff 工具。
- 发布不是模型工具。只有实际 ADD/MODIFY diff 被成功 Receipt 的精确输入 hash 全量覆盖后，服务端才自动创建不可变版本；无需二次确认，旧版本保留用于回滚。
- session 级入口由产品确定性创建 Turn；相同 `clientRequestId` 与内容精确重放，不会创建第二个 Turn。
- 编译失败仍可形成可信交付；broker、超时等系统故障不会伪装成任务结论。
