# 真实评测报告：2026-06-30

## 结论

本次在本地真实后端、真实 MySQL/Redis/Elasticsearch/Kafka/MinIO 环境、DeepSeek 模型配置下运行核心评测集，结果为：

| 项目 | 结果 |
|---|---:|
| 评测用例总数 | 19 |
| 通过 | 19 |
| 失败 | 0 |
| 总耗时 | 72 秒 |
| 原始结果 JSON | `docs/evaluation/runs/run-20260630233034.json` |
| 原始结果 Markdown | `docs/evaluation/runs/run-20260630233034.md` |

当前项目已经达到“本地真实链路可用、可进入内部试用 / 小范围试点”的水平。还不建议直接承诺正式付费生产 SLA，因为这次评测仍然是小样本、单用户低并发、DeepSeek 单 provider，并且 Plan 只验证了计划生成，没有验证长计划执行的稳定性。

## 本次覆盖的能力

| 能力 | 结果 | 说明 |
|---|---|---|
| 健康检查 | PASS | `/actuator/health` 返回 `UP` |
| 未登录拦截 | PASS | 未授权访问会话接口返回 401 |
| 用户隔离 | PASS | Bob 不能访问 Alice 会话 |
| 私有知识库上传 | PASS | 3 个私有 Markdown 样本文档上传后状态为 READY |
| 私有知识库检索 | PASS | Alice 可检索自己的私有文档，Bob 检索不到 |
| 普通聊天 | PASS | 约 2.3 秒返回有效回答 |
| RAG 忠实回答 | PASS | 能根据知识库回答导师、组会时间、论文流程步骤 |
| RAG 不编造 | PASS | 手机号未记录时没有编造号码 |
| RAG 关闭开关 | PASS | 关闭 RAG 后没有泄露私有知识库事实 |
| Web 搜索工具 | PASS | 能搜索并总结 RAG 评估指标，耗时约 32 秒 |
| 工具调用后续追问 | PASS | 修复后同一会话继续追问不再因工具历史 500 |
| Plan 生成 | PASS | 能在约 5.5 秒生成 3 步 RAG 学习计划，状态为 REVIEWING |

## 本次修复的问题

真实评测前发现一个关键 bug：模型发生工具调用后，`tool` 消息再次进入下一轮历史时丢失 `tool_call_id`，DeepSeek/OpenAI 兼容协议会报：

```text
messages[n]: missing field `tool_call_id`
```

修复内容：

| 文件 | 变更 |
|---|---|
| `yanban-core/src/main/java/com/yanban/core/agent/AgentMessage.java` | 增加 `toolCallId` 字段和 getter |
| `yanban-api/src/main/java/com/yanban/api/agent/AgentService.java` | 保存和恢复历史时保留 `ChatMessage.toolCallId()` |
| `yanban-api/src/main/java/com/yanban/api/agent/AgentMessageResponse.java` | 响应中暴露 `toolCallId` 便于调试 |
| `yanban-api/src/main/resources/db/migration/V13__add_agent_message_tool_call_id.sql` | MySQL 迁移新增字段 |
| `yanban-api/src/test/resources/db/migration-h2/V13__add_agent_message_tool_call_id.sql` | H2 测试迁移新增字段 |
| `AgentControllerIntegrationTest` | 新增回归用例：工具消息持久化后，下一轮模型请求仍带 `tool_call_id` |

验证结果：

```text
mvn -pl yanban-api -am "-Dtest=AgentControllerIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 耗时观察

| 链路 | 耗时 |
|---|---:|
| 完整真实评测集 | 72 秒 |
| 普通聊天 | 2.3 秒 |
| RAG 单问 | 2.1-2.9 秒 |
| RAG 关闭后普通问答 | 13.8 秒 |
| Web 搜索工具 | 32.0 秒 |
| 工具调用后续追问 | 2.0 秒 |
| Plan 生成 | 5.5 秒 |

Web 搜索是当前最慢的核心链路，原因是模型会触发外部搜索工具，并等待搜索结果与二次总结。后续如果要上线，建议给工具调用设置更清晰的前端进度展示、超时提示和结果降级文案。

## 上线判断

| 场景 | 判断 |
|---|---|
| 本地继续开发 | Go |
| 团队内部试用 | Go |
| 小范围客户试点 | Conditional Go |
| 正式付费生产上线 | No-Go |

小范围客户试点前建议至少补齐：

1. 用 GLM 再跑同一套评测，确认之前的 `PrematureCloseException` 是否仍然高频出现。
2. 增加 Plan 执行评测，不只测创建计划，还要测执行、失败恢复、降级和取消。
3. 增加 3-5 个真实 PDF/DOCX 样本，不只测 Markdown 小样本。
4. 增加并发与长会话测试，特别是工具调用历史、RAG 上下文累积和超时控制。
5. 补生产部署闭环：备份恢复、日志脱敏、指标监控、模型错误率统计。

## 复跑方式

确保后端和中间件已启动后运行：

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-local-eval.ps1
```

如需附带 GLM smoke test：

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-local-eval.ps1 -IncludeGlm
```
