# 确认废弃

当前明确不再采用的方案：

1. 把 DIRECT、ReAct、Plan-and-Execute 作为三个顶层产品模式；V2 顶层固定为 `DIRECT` 和 `PERSISTENT_PLAN_EXECUTE`。
2. 复制旧 Planner、CompletionVerifier、固定工具链或旧 PlanAgentService 到 V2。
3. 让模型、提示词或 LangChain4j 内存对象成为权限和持久化事实源。
4. Agent 自动应用 Candidate 或在用户确认前修改原 ProjectVersion。
5. 为 V2 再建一套 ProjectVersion、Workspace、Candidate、Receipt、summary 或 memory 事实表。
6. 恢复 Project 页面 V1 输入和 V1/V2 切换。
7. 仅根据历史日期区分或回填旧 V1/V2 消息。

这些结论保留在版本库中，不通过删除历史材料掩盖决策过程。
