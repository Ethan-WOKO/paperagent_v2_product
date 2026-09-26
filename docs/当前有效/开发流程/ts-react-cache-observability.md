# TS ReAct 缓存观测与保守策略

日期：2026-09-26。分类：开发流程。关联 #185 / Draft PR #235。

## 当前合同

按用户确认，保留 LangGraph 调度与已有故障修复，上下文压缩保持默认关闭；批量工具加载入口已移除；先建立测量依据，再决定是否推广。继续使用本地 185 checkout，不切换分支、不合并、不处理 #228，不修改其他对话的工作。

变更范围：TS 引擎、模型网关响应契约、Java ReAct 网关及直接相关 provider usage 解析、测试和文档。`yanban-core` 的最小扩展是必要的：供应商缓存字段在这里被丢弃，仅修改 TS 无法恢复。没有修改数据库表、Redis 缓存、前端、部署文件、计费或配额算法。

## 策略与恢复

- 仅保留 `REACTPLAN_COMPACT_CONTEXT`，值为 `true` 时开启。批量加载环境开关已移除，新模型请求只暴露 `load_tool(name)`，仍可在一条模型回复中发出多个独立调用。历史 pending 请求按原批量许可完成恢复，后续新请求忽略旧 experiments 中的批量标记。
- `stable-v2` 不改写历史正文。首次请求把工具目录/事实放在当前用户指令之前；后续仅在内容变化时追加快照，明确新快照代表最新观测。请求前缀相等是应用层验证，不是供应商缓存命中保证。已有 memory/current instruction 优先级不变。
- `compact-v2` 才启用原有正文投影省略，仍可能破坏前缀并引起额外读取。当前没有引入累计 schema 预算或自动卸载。
- 已持久化的无策略标记或 `compact-v1` 待完成调用保持原消息排列和 load_tool schema；新策略的未完成调用也按冻结策略恢复。旧任务在完成已有待执行工作后，下一次新模型调用才采用并冻结当前配置。
- Java checkpoint、租约、权限、Workspace、receipt、精确验证后发布及回滚保持原权威。没有新增图数据库。

## 测量含义

DeepSeek 的 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`、兼容接口的 `prompt_tokens_details.cached_tokens` 经统一解析传至 Java 网关 response JSON，再传至 TS。兼容格式的 miss 仅在 prompt 和 hit 都已知时取差值。缺失缓存计数为 null；负数、非整数、溢出或矛盾计数不能被当成有效命中。旧存储响应没有这些字段时仍可重放，新字段无需迁移即可随已有 response JSON 保存。

新增或扩充的 JSONL 事件：

| 事件 | 用途与限制 |
| --- | --- |
| `reactplan_model_context` | callId、实际 provider/model、输入输出与缓存 token、schema 数量/字符、消息字符、往返耗时、重放标记、实验策略、累计相同参数读请求数 |
| `reactplan_model_failure` | 失败调用的 callId、错误码和耗时；失败的实际供应商消耗可能不可用 |
| `reactplan_gateway_attempt` | taskId、接口类别、尝试序号、HTTP 状态、取消状态、耗时；含该次失败退避，不输出 URL/授权头 |
| `reactplan_tool_timing` | 每次工具执行处理的耗时；completed 不代表业务成功，仍看工具事件/receipt |
| `reactplan_sandbox_timing` | receipt 起止时长和轮询次数；不等同 Maven/编译命令自身耗时 |

`node scripts/usage-report.mjs <engine-jsonl.log>` 按 taskId/callId 去重，避免重放累计为新的 provider usage，单独报告缓存不可用调用。不输出正文或凭证，不访问网络，不提供未经核实的费用估计。供应商失败、内部 fallback 的失败尝试可能已经产生费用，现有响应无法完整量化；报告不能代替账单。各层耗时存在包含关系，不应简单相加。

缓存字段参考：[DeepSeek API](https://api-docs.deepseek.com/api/create-chat-completion/)、[兼容缓存字段说明](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/prompt-caching)。框架接入本身不保证费用或延迟降低。

## 验证

在 `agent-engine-reactplan/`：

```powershell
npm run typecheck
npm run build
npm test -- --reporter=dot
node --test scripts/usage-report.test.mjs
```

类型检查/构建通过，7 个 Vitest 文件 94 项测试通过；报告工具 1 项 Node 测试通过。新增四种开关组合的实际 HTTP engine→模拟 gateway→engine/SSE 链路，验证请求摘要、前缀、缓存缺失/null 与已知计数、503 重试、权限 token 不泄露；不调用外部模型。恢复测试覆盖 legacy、compact-v1、stable-v2、compact-v2。

仓库根目录：

```powershell
mvn -o -pl yanban-api -am '-Dtest=ProviderUsageTest,ProviderUsageHttpTest,DeepSeekModelProviderTest,GlmModelProviderTest,AgentEngineModelGatewayTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
python agent-engine-contract/conformance/validate_contract.py
git diff --check
```

Java 23 项测试通过，零失败/跳过；三个 provider 的普通 HTTP 与 SSE 均通过本地模拟供应商验证。加强存储重放测试后另运行同命令但 `-Dtest=AgentEngineModelGatewayTest`，4 项通过，包含没有缓存字段的旧响应。契约验证通过：8 schemas、15 operations、13 positive / 5 negative fixtures、5 ordered events、16 scenarios。

为启动后端运行 `mvn -o -pl yanban-api -am -DskipTests install`，构建成功。启动本次构建的后端 jar（dev，18080）与 Vite（15173），后端 `/actuator/health` 为 UP，前端 HTTP 200。启动检查用进程参数关闭 ReAct 调度、记忆提炼、Kafka listener，并取消 sandbox startup-required；未修改配置文件。后端仍执行原有启动初始化器，因此这不是只读数据库进程；没有提交真实任务或修改现有任务/消息数据。

检查后已停止这两个由本次启动的进程。HTTP engine 测试使用随机独立端口与临时目录，测试后关闭服务。未启动真实 broker、未执行云沙箱、未运行付费模型或真实 Project A/B：本次没有改动 broker，且用户暂不使用 Project 测试；不以服务健康检查冒充完整产品端到端测试。前端无代码修改，未重跑前端全量构建。

## 下一次比较

历史四组对照包含默认、仅压缩、仅批量、两者开启。批量入口移除后，后续只对照压缩开关，使用同样模型、任务、权限、工具目录和起始版本。同时记录任务成功率、实际费用/缓存覆盖、总耗时、模型轮次、重复读取和沙箱耗时。冷/热缓存分别测量，避免固定实验顺序产生偏差。未经实际结果支持，不宣称之前 28% 字符减少对应费用节省，也不默认推广压缩或批量加载。
