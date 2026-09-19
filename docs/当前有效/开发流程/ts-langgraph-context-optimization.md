# TS LangGraph 与上下文优化：诊断和实现（#185）

## 本轮合同

按用户要求，保留 TS Agent，在已确认的沙箱失败和 token 浪费上引入 LangGraph / LangChain Core。仅修改 TS 引擎、依赖、直接回归测试和说明；不改变 Java 权限、记忆优先级、会话缓存、沙箱网络政策、精确验证后发布或不可变版本回滚。不新增 Python 服务，不继续 #228，不改变真实用户文件或重新运行付费任务。

## 已确认的问题

对用户提供的 2026-09-19 20:58:56–21:01:53 日志及对应任务 checkpoint 做了只读核对。请求是给现有排序类增加插入排序。

第一次沙箱命令为 `mvn -o test`，exitCode=1。输出明确表示离线仓库缺少 `net.bytebuddy:byte-buddy:1.17.8`、`net.bytebuddy:byte-buddy-agent:1.17.8` 和 `commons-logging:commons-logging:1.3.6`，无法访问 Maven Central。这是依赖解析失败，不是新增排序方法的编译错误。随后同一文件、同一完整 hash 通过 `yanban-runner java <path>` 成功执行；这只验证该独立类，不能声称整个 Maven 项目测试通过。

第一次沙箱从 requested 到 failed 约 109 秒，receipt 起止约 98 秒，但 Maven 自报构建仅 0.556 秒。现有证据不足以将其余耗时精确分配到环境准备、broker 调度或状态轮询，不将 109 秒全部归因于 Maven 编译。第二次 requested 到 succeeded 约 22 秒。

12 次模型调用累计输入 102,063 token、输出 2,247 token，模型耗时总计 42.491 秒；所有调用成功且未回退模型。这是累计输入，并不是一条 10 万 token 的输入。

| 轮次 | 行为 | 输入 token |
| --- | --- | ---: |
| 1 | 列文件 | 3,701 |
| 2 | 读源码 | 5,024 |
| 3 | 搜索工具 | 6,481 |
| 4 | 加载写工具 | 7,397 |
| 5 | 写完整文件 | 7,652 |
| 6 | diff 未加载，被拒绝 | 9,230 |
| 7 | 加载 diff | 9,343 |
| 8 | 读取 diff | 9,493 |
| 9 | 加载沙箱工具 | 9,724 |
| 10 | Maven 离线执行 | 10,255 |
| 11 | 改用独立源码执行 | 11,592 |
| 12 | 回答并确定性发布 | 12,171 |

5 轮用于工具发现/加载或处理 schema 未加载拒绝。每轮重新发送历史：系统指令、会话上下文、工具搜索描述、原始文件、完整写入参数、执行结果逐步累积。文件只有少量改动，但完整旧源码与新写入内容在后续多轮重复输入。

## 实现

- LangGraph JS `StateGraph` 编排串行 tools → model → tools 循环；等待用户/终态时结束图，恢复仍由现有 Java checkpoint/租约驱动。保留模型调用 20 次上限、确定性 call ID、pending call 游标、取消、失败恢复和发布门槛。图不另设持久化数据库。
- LangChain Core 的系统、用户、助手、工具消息类型负责模型投影边界。保留原始 tool call ID、参数字符串及非法参数的原有修复流程。
- 只压缩发给模型的副本：成功写入的完整正文替换为路径/hash/明确省略提示；已被写入替代的旧读取正文移除；工具搜索描述缩短。失败输出、新鲜读取、分页元数据、当前指令、长期记忆、完整持久化历史及证据不被裁剪。再次需要正文时，模型可按精确 hash 重新读取。
- `load_tool` 支持一次加载最多六个已发现的工具 schema，原 `name` 参数仍兼容；不越过 Skill/权限，也不会因此自动执行工具。提示词建议一次加载本次所需的写入/diff/验证 schema。
- 提示词明确独立 Java 类优先选择相应源码 runner；存在根 pom 本身不是选择整项目验证的充分理由。没有自动下载依赖、放开网络、修改依赖版本或把失败改成成功。
- 每轮输出无正文的 `reactplan_model_context` 统计：原始/投影消息字符、schema 字符、实际输入输出 token。字符数不是 token 数，不输出源码、提示词或凭证。
- 新 pending model call 冻结 `compact-v1` 策略。没有该标记的旧未完成调用保留旧投影及旧 load schema，避免升级改变已提交请求摘要。新策略下的恢复也保持消息/schema 与 call ID 一致。

## 离线对照

只读获取同一已完成任务，在每个 assistant 调用之前重建当时的消息前缀及已经成功的写入证据，不调用模型或工具。固定原有 12 轮时，累计消息 JSON 字符从 **282,547** 降到 **203,345**，减少 **28.0%**。

这仅衡量消息副本，排除额外 evidence ledger 与 tool schema；保留旧提示词以隔离压缩影响。它不等于计费 token 降低 28%，也未计入批量加载可能减少的轮次。新的批量 schema 略增加单轮工具描述，实际节省必须通过相同模型、权限、输入的在线对照验证。没有复制真实源码、凭证或 checkpoint 到仓库。

可对自行导出的 checkpoint 运行 `npm run build` 后执行 `node scripts/context-report.mjs <checkpoint.json>`，只输出每轮字符统计和工具名，不访问网络、不调用模型、不输出正文。报告按当时的消息前缀恢复写入事实，不用最终状态提前隐藏尚未发生修改的文件。

## 验证与限制

在 `agent-engine-reactplan/` 运行 `npm run typecheck`、`npm run build`、`npm test -- --reporter=dot`：类型检查及构建通过，最终 **6 个测试文件、87 项测试全部通过**。原有 79 项测试在图编排替换后通过；新增投影不变性、重复 call ID 关联、失败证据保留、批量发现门槛、新旧 checkpoint 恢复和本地快照并发顺序回归。`git diff --check` 通过。

中间一次批量加载测试没有给 fixture 写权限，修正测试条件后通过；取消回归还暴露了本地 checkpoint 并发 rename 的 EPERM，以及轮询等待期间取消到达的时序窗口。已将测试/本地 `TaskStore` 的同任务快照串行写入，并在沙箱轮询等待后检查取消信号；新增并发快照顺序测试。没有修改生产 `HttpTaskStore` 或 Java 持久化。未重启运行中的 TS 服务，未做真实模型 A/B 或新沙箱执行；未修改 Java/前端，因此不重跑无关 Maven/前端构建。

这是第一阶段框架接入和确定性上下文投影，不是完整长期记忆系统，也不能保证模型每次都选对验证范围或使用批量加载。上下文收益来自具体策略，LangGraph 本身不会自动减少 token。

参考：[LangGraph JS 图 API](https://docs.langchain.com/oss/javascript/langgraph/graph-api)、[LangChain JS 消息](https://docs.langchain.com/oss/javascript/langchain/messages)。
