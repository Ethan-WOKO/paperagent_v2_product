# LangChain4j A/B Evaluation Console Findings - 2026-07-05

> 关联设计文档：`docs/design/langchain4j-ab-evaluation-console.md`

## 1. 结论

当前结论：

```text
KEEP_HARNESS_AS_DEFAULT
ADOPT_LANGCHAIN4J_AS_EXPERIMENT_LAYER
```

解释：

1. 默认生产链路应继续保持 `HARNESS + HARNESS_NATIVE`，不切换到 LangChain4j 作为唯一运行时。
2. LangChain4j 已适合以“实验能力层”的方式接入当前项目：
   - RAG A/B
   - Chat memory A/B
   - selected tools 的 tool binding A/B
   - runtime adapter A/B
3. 当前仓库已经具备可显式切换、可观测、可记录、可导出结果的 A/B 实验台。
4. 真实样本与真实链路证据已经足够支持“继续保留 Harness 主治理，按能力点选择性引入 LangChain4j”，但仍不足以支持“整体替换 Harness”。

## 2. 已完成的实验台能力

前端实验控制台已具备：

1. `Runtime Mode`
2. `RAG Mode`
3. `Memory Mode`
4. `Tool Calling Mode`
5. debug flags:
   - `SHOW_RETRIEVED_CHUNKS`
   - `SHOW_INJECTED_CONTEXT`
   - `SHOW_TOOL_TRACE`
   - `SHOW_MEMORY_WINDOW`
   - `SHOW_RAW_PROMPT`
6. `Persist Eval Record`
7. 调试结果展示：
   - `Final Answer`
   - `Retrieved Chunks`
   - `Injected Context`
   - `Tool Trace`
   - `Metrics`
   - `Memory Window`
   - `Raw Prompt`
   - `Final Citations`
   - `Fallbacks`
8. `Export JSON`

后端实验模式已具备：

1. `AgentExperimentRequest`
2. `AgentRuntimeMode`
3. `AgentRagMode`
4. `AgentMemoryMode`
5. `AgentToolCallingMode`
6. `AgentDebugFlag`
7. `AgentDebugPayload`
8. `AgentExperimentRecord` 持久化
9. `CurrentHarnessAdapter` 与 `FutureLangChain4jRuntimeAdapter` 双适配器选择
10. `HarnessToolCallingStrategy` 与 `LangChain4jToolCallingStrategy` 双策略切换

## 3. A/B 维度结论

### 3.1 Runtime

结论：

1. `LANGCHAIN4J_EXPERIMENTAL` 已作为实验运行时接入。
2. 它当前适合作为“实验 adapter runtime”，不适合作为会话治理、任务生命周期、取消、重试、事件流的唯一实现。
3. 这与设计文档的边界原则一致：Harness 继续负责产品运行治理，LangChain4j 负责能力组件化接入。

### 3.2 RAG

当前支持：

1. `BASELINE`
2. `LANGCHAIN4J_ADAPTER`
3. `LANGCHAIN4J_AUGMENTOR`

证据：

1. `docs/evaluation/reports/langchain4j-rag-spike-20260704.md`
2. `docs/evaluation/reports/kb-version-governance-rag-eval-20260704.md`
3. `docs/evaluation/reports/kb-real-es-hybrid-rag-e2e-20260704.md`

结论：

1. LangChain4j adapter/augmentor 路径已经能接入当前知识检索结果，并保留关键 metadata。
2. 当前真实样本证据更支持“LangChain4j 接入 retrieval augmentor / content injection 能力”，不支持“整体替换现有知识检索与治理链路”。
3. 当前更推荐的迁移路径是：
   - 保留现有 `KnowledgeSearchService` 和版本/权限治理
   - 在实验链路中使用 LangChain4j augmentor、content injector、query transform 做能力增强和对照

### 3.3 Memory

当前支持：

1. `BASELINE`
2. `LANGCHAIN4J_CHAT_MEMORY`
3. `HYBRID`

结论：

1. `HYBRID` 是当前更稳妥的方案。
2. 原因是事实源仍然来自业务系统的消息、summary、long-term memory，而 LangChain4j 只负责窗口裁剪与消息组织。
3. 这能降低上下文管理复杂度，同时不破坏既有会话事实源。

### 3.4 Tool Calling

当前支持：

1. `HARNESS_NATIVE`
2. `LANGCHAIN4J_TOOL_BINDING`

首批对照工具范围：

1. `search_web`
2. `search_knowledge`
3. `literature_search_start`
4. `literature_search_status`
5. `literature_search_result`

结论：

1. LangChain4j tool binding 适合在少量稳定工具上做参数绑定和输出治理实验。
2. 它不应替代 Harness 对工具预算、顺序、回退、异常处理、事件记录的产品级治理。

## 4. 真实样本证据

### 4.1 RAG 真实样本

`docs/evaluation/reports/kb-real-es-hybrid-rag-e2e-20260704.md` 已给出真实 ES + 真实 hybrid 检索链路证据：

1. fixture case 总数：10
2. 通过：10
3. `Recall@5 = 1.0`
4. `MRR = 0.7`
5. `forbidden_hit_count = 0`
6. `metadata_preservation_rate = 1.0`

该报告证明了：

1. 现有 baseline RAG 治理链路可用。
2. 版本过滤、跨用户隔离、public 可见性、citation metadata 基础能力可验证。
3. LangChain4j 的接入必须建立在这些治理边界不退化的前提下。

### 4.2 系统真实运行样本

`docs/evaluation/real-run-assessment-2026-06-30.md` 已覆盖本地真实后端、真实中间件、真实模型配置下的链路验证。

它的意义是：

1. 说明当前主链路已经可运行。
2. 说明 A/B 实验台应是“附着于稳定主链路之上的实验能力”，而不是反向取代主链路。

## 5. 验收结果

对照 `docs/design/langchain4j-ab-evaluation-console.md` 的验收标准：

1. 普通聊天默认主链路不受影响：已满足。
2. 调试模式可显式切换 RAG/Memory/Tool Calling 实验模式：已满足。
3. 可稳定对比 `BASELINE` 与 `LANGCHAIN4J_ADAPTER`：已满足。
4. 前端可看到 retrieved chunks、injected context、tool trace 和关键指标：已满足。
5. 各实验模式具备独立测试和最小回归验证：已满足。
6. 能输出基于真实样本的评测结论：已满足。

说明：

第 6 条并不代表“LangChain4j 已证明全面优于现有实现”，而是代表“现在已经有足够证据给出正式迁移结论”。当前正式迁移结论不是全量替换，而是保留 Harness 主链路，继续采用实验层接入。

## 6. 推荐的后续迁移策略

推荐顺序：

1. 保持 `HARNESS` 为默认运行时。
2. 保持 `HARNESS_NATIVE` 为默认工具调用模式。
3. 继续在实验面板中使用：
   - `LANGCHAIN4J_AUGMENTOR`
   - `HYBRID`
   - `LANGCHAIN4J_TOOL_BINDING`
4. 仅当某个子能力在真实样本上持续优于 baseline 时，再做定点迁移。

不推荐当前执行的动作：

1. 不把 LangChain4j 升格为唯一 Agent runtime。
2. 不让 LangChain4j 接管会话持久化、任务生命周期、取消重试、事件流。
3. 不在缺少更强真实线上对照前整体重写 Harness。

## 7. 可复查证据

代码与测试：

1. `yanban-api/src/test/java/com/yanban/api/agent/AgentExperimentServiceTest.java`
2. `yanban-api/src/test/java/com/yanban/api/agent/AgentMemoryExperimentServiceTest.java`
3. `yanban-api/src/test/java/com/yanban/api/agent/LangChain4jToolCallingStrategyTest.java`
4. `yanban-api/src/test/java/com/yanban/api/agent/FutureLangChain4jRuntimeAdapterTest.java`
5. `yanban-api/src/test/java/com/yanban/api/agent/AgentControllerIntegrationTest.java`

评测与报告：

1. `docs/evaluation/reports/langchain4j-rag-spike-20260704.md`
2. `docs/evaluation/reports/kb-version-governance-rag-eval-20260704.md`
3. `docs/evaluation/reports/kb-real-es-hybrid-rag-e2e-20260704.md`
4. `docs/evaluation/real-run-assessment-2026-06-30.md`
