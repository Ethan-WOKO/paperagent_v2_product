# agent-engine-reactplan

这是轻量 ReAct 运行器。它不直接读取产品数据库或持有 broker 凭证；它只接收产品签发的短期任务授权，并通过 Java 产品网关读写隔离 Workspace、执行沙箱、取得正式 Receipt。修改内容精确验证成功后，由服务端确定性终结器自动发布新 ProjectVersion。

执行循环现在由 LangGraph JS 的 tools/model 节点编排，LangChain Core 消息类型用于实验性模型上下文投影。Java 的 checkpoint、租约、事件和回执仍是唯一持久化权威；没有新增 Python 服务或另一套图状态数据库，也没有启用 LangSmith 上传。详见 [本轮诊断与验证](../docs/当前有效/开发流程/ts-langgraph-context-optimization.md)。

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
npm start
```

`npm start` 会先自动构建。默认监听 `127.0.0.1:8092`，模型和任务持久化均通过 Java 网关；生产入口使用 `HttpTaskStore`，本地 `TaskStore` 仅供隔离测试。模型配置与密钥由 Java 管理。

## 运行保证

- 同一 `taskId` + 相同 authority 摘要是幂等重放；不同内容返回冲突。
- 事件 sequence 单调递增，SSE 支持 `Last-Event-ID` 续传。
- 服务重启后通过 Java 租约领取 checkpoint 并结合 append-only 事件恢复未完成任务。
- 模型最多调用 20 次；除按 hash 读取和白名单沙箱外，还会冻结并复用产品注册表中当前任务获准的只读 Project 工具。带 `writeWorkspace` 权限的任务额外获得隔离 Workspace 的 ADD/MODIFY 与 diff 工具。
- 发布不是模型工具。只有实际 ADD/MODIFY diff 被成功 Receipt 的精确输入 hash 全量覆盖后，服务端才自动创建不可变版本；无需二次确认，旧版本保留用于回滚。
- session 级入口由产品确定性创建 Turn；相同 `clientRequestId` 与内容精确重放，不会创建第二个 Turn。
- 编译失败仍可形成可信交付；broker、超时等系统故障不会伪装成任务结论。

## 上下文实验与观测（2026-09-25）

默认关闭压缩和批量 schema 加载，保留单工具按需加载。可在 **TS 引擎进程环境**中分别设置 `REACTPLAN_COMPACT_CONTEXT=true`、`REACTPLAN_BATCH_TOOL_LOADING=true`；其他值视为关闭。入口不自动读取根 `.env`，需要由启动器传入。当前 Compose 未透传这两个可选开关，默认均关闭；容器实验需显式提供环境覆盖。

新任务冻结开关，重启或改变环境不会改变它；旧任务先按原请求恢复未完成调用，再在下一次新模型调用冻结新策略。`stable-v2` 保留消息历史，在末尾追加发生变化的服务器事实；不再每轮在前部重建事实。首次请求仍将当前用户指令放在末尾。schema 按需变化仍可能影响供应商缓存，不能保证命中。

日志包含模型输入/输出、缓存命中/未命中 token（缺失为 null）、请求往返耗时、模型失败、网关每次尝试、工具执行耗时、沙箱 receipt 起止时长/轮询次数，以及累计相同参数读取请求数。工具 `completed` 表示执行处理完成，不代表业务成功；以工具事件和 receipt 为准。网关尝试时长包含该次失败后的退避；模型往返耗时包含网关网络和重试，不能等同供应商推理时长。日志不包含正文或密钥。

```powershell
node scripts/usage-report.mjs <engine-jsonl.log>
node --test scripts/usage-report.test.mjs
```

报告按 taskId/callId 去重，排除仅重放的调用，列出缓存数据不可用的调用数；不把总输入 token 当成未缓存计费 token，不估算实际账单。Java 原有 token 配额结算逻辑未改动。

完整边界、验证与剩余工作见 [缓存观测与保守策略](../docs/当前有效/开发流程/ts-react-cache-observability.md)。
