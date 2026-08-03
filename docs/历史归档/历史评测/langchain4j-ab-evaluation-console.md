# LangChain4j A/B Evaluation Console Design

> 关联阶段：`Phase 4: LangChain4j RAG And Memory Spike`
>
> 本文定义 LangChain4j 在本项目中的 A/B 接入方式、前端调试台形态、后端模式开关、评测维度和 issue 拆分建议。当前文档只做设计沉淀，不直接替换生产主链路。

## 1. 背景

当前 `private-helper-agent-v1` 已具备以下主链路能力：

```text
ChatPage / WebSocket / HTTP fallback
  -> AgentController / AgentService
  -> AgentToolPolicyEngine
  -> AgentRuntimeService
  -> CurrentHarnessAdapter
  -> HarnessEngine
  -> ModelProvider / ToolRegistry / Task tools / RAG context / memory context
```

现状特点：

1. 生产主链路以 Harness 为核心执行器。
2. 会话持久化、任务治理、事件流、幂等、权限和可观测性均由业务系统自己负责。
3. 仓库中已经存在 LangChain4j RAG spike 资产，但目前只在 `yanban-knowledge` 的 test/eval 层验证。
4. 用户希望在不破坏现有功能的前提下，把 LangChain4j 显式接入到调试链路中，观察其在 RAG、ChatMemory、Tool Calling 和结构化输出上的效果，并与当前实现做 A/B 对照。

因此，本阶段目标不是“迁移到 LangChain4j”，而是“把 LangChain4j 变成一个可切换、可观测、可评测的实验能力层”。

## 2. 设计目标

1. 在前端提供显式的 LangChain4j 调试入口，而不是隐式替换主链路。
2. 在后端把实验模式参数化，允许一次请求显式指定：
   - runtime mode
   - rag mode
   - memory mode
   - tool calling mode
   - debug flags
3. 在不改变业务事实源的前提下，对比 LangChain4j 与现有实现的能力差异。
4. 让每次实验都能输出可复查的上下文、检索结果、工具轨迹和指标。
5. 保证默认用户链路仍然是当前稳定的 Harness 主链路。

## 3. 非目标

1. 当前阶段不把 LangChain4j 作为唯一 Agent Runtime。
2. 当前阶段不让 LangChain4j 接管会话持久化。
3. 当前阶段不让 LangChain4j 接管任务生命周期、取消、重试、事件流和幂等。
4. 当前阶段不让 LangChain4j 接管长期记忆存储。
5. 当前阶段不直接替换生产 RAG。
6. 当前阶段不改写论文润色、文献检索、知识库入库等既有业务语义。

## 4. 边界原则

### 4.1 Harness 与 LangChain4j 的关系

正确边界应为：

```text
Harness 负责：
- 任务和会话治理
- 工具协议与执行顺序控制
- 事件流和可观测性
- 幂等、取消、重试、超时和降级

LangChain4j 负责：
- 降低 RAG 组件接入复杂度
- 降低 ChatMemory 窗口管理复杂度
- 降低 tool binding / structured output 接入复杂度
- 作为实验性 runtime adapter 或辅助组件层
```

### 4.2 业务事实源保持不变

以下数据仍以业务系统为事实源：

1. `agent_sessions`
2. `agent_messages`
3. `agent_turns`
4. session summary
5. long-term memory records
6. literature search tasks
7. paper polish tasks

LangChain4j 可以读这些数据、变换这些数据、裁剪这些数据，但不直接成为这些数据的持久化来源。

## 5. A/B 维度设计

建议把实验显式拆成 4 个维度，不使用单一的“LangChain4j 开关”。

### 5.1 Runtime Mode

用于控制主回答链路由谁驱动。

```text
HARNESS
LANGCHAIN4J_EXPERIMENTAL
```

说明：

1. `HARNESS` 为默认模式。
2. `LANGCHAIN4J_EXPERIMENTAL` 初期可以只是局部能力接入，不要求一开始就完整替换 Agent loop。

### 5.2 RAG Mode

用于对比检索和注入链路。

```text
BASELINE
LANGCHAIN4J_ADAPTER
LANGCHAIN4J_AUGMENTOR
```

说明：

1. `BASELINE`：当前 `KnowledgeSearchService + AgentContextBuilder`。
2. `LANGCHAIN4J_ADAPTER`：复用当前检索结果，但转为 LangChain4j `Content/TextSegment` 结构，用于评测 metadata 保真和注入方式。
3. `LANGCHAIN4J_AUGMENTOR`：实验性引入 LangChain4j retrieval augmentor、content injector、query transform 等组件。

### 5.3 Memory Mode

用于对比短期上下文管理。

```text
BASELINE
LANGCHAIN4J_CHAT_MEMORY
HYBRID
```

说明：

1. `BASELINE`：当前 `AgentContextBuilder + summary + recent messages`。
2. `LANGCHAIN4J_CHAT_MEMORY`：用 LangChain4j 管理 session-scoped 窗口。
3. `HYBRID`：业务系统仍产出原始消息和 summary，由 LangChain4j 只负责窗口裁剪与格式组织。

### 5.4 Tool Calling Mode

用于对比工具调用能力。

```text
HARNESS_NATIVE
LANGCHAIN4J_TOOL_BINDING
```

说明：

1. 首期只对照少量工具：
   - `search_web`
   - `search_knowledge`
   - `literature_search_start`
   - `literature_search_status`
   - `literature_search_result`
2. 不在首期一次性迁移全部工具。

## 6. 前端调试台设计

建议新增一个独立的实验控制台，而不是把所有开关散落在普通聊天 UI 中。

建议位置：

1. 聊天页右侧调试面板扩展区，或
2. 单独调试页 `/agent-lab`

首版更建议直接放在当前 ChatPage 的“高级调试面板”中，避免额外导航成本。

### 6.1 控件

最少需要以下显式控件：

1. `Runtime Mode` 下拉框
2. `RAG Mode` 下拉框
3. `Memory Mode` 下拉框
4. `Tool Calling Mode` 下拉框
5. `Show Retrieved Chunks` 开关
6. `Show Injected Context` 开关
7. `Show Tool Trace` 开关
8. `Show Memory Window` 开关
9. `Show Raw Prompt` 开关
10. `Persist Eval Record` 开关或按钮

### 6.2 结果区

建议展示 5 个固定结果区：

1. `Final Answer`
2. `Retrieved Chunks`
3. `Injected Context`
4. `Tool Trace`
5. `Metrics`

### 6.3 实验记录

每次请求建议显示并可导出：

1. request id
2. session id
3. selected modes
4. latency
5. token usage
6. retrieved chunk count
7. tool call sequence
8. final citations
9. error or fallback reason

## 7. 后端接口设计

### 7.1 请求 DTO 扩展

建议在聊天请求中新增一个实验配置对象，而不是把所有字段平铺。

示意：

```json
{
  "content": "...",
  "ragDisabled": false,
  "skillId": null,
  "clientRequestId": "...",
  "experiment": {
    "enabled": true,
    "runtimeMode": "HARNESS",
    "ragMode": "LANGCHAIN4J_ADAPTER",
    "memoryMode": "HYBRID",
    "toolCallingMode": "HARNESS_NATIVE",
    "debugFlags": [
      "SHOW_RETRIEVED_CHUNKS",
      "SHOW_INJECTED_CONTEXT",
      "SHOW_TOOL_TRACE"
    ]
  }
}
```

### 7.2 建议新增的枚举

1. `AgentRuntimeMode`
2. `AgentRagMode`
3. `AgentMemoryMode`
4. `AgentToolCallingMode`
5. `AgentDebugFlag`

### 7.3 响应调试结构

建议在实验模式下扩展响应，返回一份 `debug` 字段，而不是污染普通用户响应主体。

建议包含：

1. `selectedModes`
2. `retrievedChunks`
3. `injectedContext`
4. `toolTrace`
5. `tokenUsage`
6. `latencyBreakdown`
7. `memoryWindow`
8. `fallbacks`

## 8. 服务分层建议

### 8.1 建议新增的后端边界

```text
AgentService
  -> AgentExperimentSelector
  -> AgentRuntimeService
       -> CurrentHarnessAdapter
       -> FutureLangChain4jRuntimeAdapter
  -> RagComparisonService
  -> MemoryComparisonService
  -> ToolCallingComparisonService
```

### 8.2 RAG 层建议

建议新增：

1. `RagRunner` 接口
2. `BaselineRagRunner`
3. `LangChain4jAdapterRagRunner`
4. `LangChain4jAugmentorRagRunner`

说明：

1. `LangChain4jAdapterRagRunner` 可复用当前 test/eval spike 中已经存在的 adapter 思路。
2. 生产实验模式下也应保持业务 metadata 不丢失：
   - `documentId`
   - `filename`
   - `chunkIndex`
   - `citationId`
   - `source`
   - `visibility`

### 8.3 Memory 层建议

建议新增：

1. `MemoryWindowBuilder`
2. `BaselineMemoryWindowBuilder`
3. `LangChain4jChatMemoryAdapter`
4. `HybridMemoryWindowBuilder`

要求：

1. 输入仍来自数据库中的消息、summary 和长期记忆。
2. LangChain4j 只负责窗口裁剪和消息组织，不接管事实源。

### 8.4 Tool Calling 层建议

建议新增：

1. `ToolCallingStrategy`
2. `HarnessToolCallingStrategy`
3. `LangChain4jToolCallingStrategy`

首期只绑定少量稳定工具，优先看：

1. 参数绑定正确率
2. 非法调用率
3. 重复调用率
4. 伪 tool-call 文本出现率

## 9. 评测指标

### 9.1 通用指标

1. 首 token 时间
2. 总完成时间
3. token 使用量
4. 工具调用次数
5. 重复调用次数
6. 失败率
7. 回退率

### 9.2 RAG 指标

1. Recall@K
2. MRR
3. citation coverage
4. citation correctness
5. metadata 保真度
6. answer faithfulness

### 9.3 Memory 指标

1. follow-up 问题回答稳定性
2. token 控制效果
3. summary 利用效果
4. 旧上下文污染率

### 9.4 Tool Calling 指标

1. 工具选择正确率
2. 参数解析正确率
3. 非法调用率
4. 伪工具文本输出率

## 10. 推荐实验问题集

建议至少固定以下四类问题：

1. 知识库问答
2. 多轮追问
3. 时效性搜索
4. 文献检索任务

后续可以增加：

1. 计划型问题
2. 论文润色任务
3. 工具密集型问题

## 11. 开发顺序建议

### Phase A: 实验开关打通

1. 前端调试面板
2. 请求 DTO 扩展
3. 后端模式枚举
4. 实验参数透传

### Phase B: RAG A/B

1. `BASELINE` vs `LANGCHAIN4J_ADAPTER`
2. 展示 retrieved chunks 和 injected context
3. 保存实验记录

### Phase C: Memory A/B

1. `BASELINE` vs `HYBRID`
2. 再做 `LANGCHAIN4J_CHAT_MEMORY`

### Phase D: Tool Calling A/B

1. `search_web`
2. `search_knowledge`
3. `literature_search_*`

### Phase E: Runtime A/B

1. 仅在实验模式引入 `LANGCHAIN4J_EXPERIMENTAL`
2. 不替换默认主链路

## 12. issue 拆分建议

建议按“一个 issue 一个明确功能”拆分：

1. `design: LangChain4j A/B evaluation console`
2. `implementation: add agent experiment request schema and enums`
3. `implementation: add chat debug controls for experiment modes`
4. `implementation: add baseline vs langchain4j adapter RAG switch`
5. `implementation: expose retrieved chunks and injected context in debug response`
6. `implementation: add hybrid chat memory comparison mode`
7. `implementation: add langchain4j tool binding experiment for selected tools`
8. `implementation: persist experiment records for comparison`
9. `decision: summarize LangChain4j A/B findings and recommend next migration step`

## 13. 验收标准

1. 普通聊天主链路默认不受影响。
2. 调试模式下可以显式切换 RAG/Memory/Tool Calling 实验模式。
3. 至少可以稳定对比 `BASELINE` 与 `LANGCHAIN4J_ADAPTER` 的 RAG 差异。
4. 前端能看到 retrieved chunks、injected context、tool trace 和关键指标。
5. 每个实验模式都有独立测试和最小回归验证。
6. 最终能输出一份基于真实样本的评测结论，而不是仅凭主观体验判断。

## 14. 决策建议

当前推荐路线：

1. 先做前端显式调试台和后端实验参数透传。
2. 先落地 RAG A/B，再做 ChatMemory A/B。
3. LangChain4j 在本项目中优先承担能力组件角色，而不是产品运行治理角色。
4. 在缺少真实对照数据之前，不把生产主链路切换到 LangChain4j。
