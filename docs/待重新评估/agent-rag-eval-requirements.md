# Agent 和 RAG 评测要求

> 文档分类：**待重新评估**。评测原则仍有价值，但 Agent 用例需更新为当前 TaskFrame、Step Result、Receipt、Context Revision 和恢复不变量。

传统单元测试是必要的，但不足以验证 Agent、RAG、论文润色、文献推荐、长期记忆和工具调用能力。

本文档定义会影响模型行为或检索质量的改动所需的最低 eval 要求。

## 什么时候必须做 eval

当 PR 修改以下内容时，必须运行或定义 eval cases：

1. Agent runtime、Harness loop、策略选择、planning、reflection 或工具策略。
2. RAG 检索、切块、embedding、rerank、上下文组装或引用格式。
3. 文献检索、文献卡片去重、排序或 BibTeX 处理。
4. 论文润色 prompt、章节改写、review、repair 或引用插入。
5. 长期记忆抽取、检索、更新、删除或上下文注入。
6. 可能导致重复最终回答或 trace 输出混乱的流式/事件行为。
7. 工具定义、工具 schema、工具结果归一化、重试行为或权限。

仅文档变更和纯 UI 样式变更通常不需要 Agent/RAG eval，除非它们改变了用户可见的任务行为。

## 最低 eval 类别

### 工具选择

检查 Agent 是否：

1. 在需要工具时调用正确工具。
2. 在直接回答足够时避免调用工具。
3. 不会无限循环。
4. 遵守工具预算和风险策略。
5. 只生成一个最终用户可见回答。

### RAG 检索

检查：

1. 相关片段出现在 top results 中。
2. 不发生跨用户数据泄漏。
3. 已删除或已废弃文档不会被检索。
4. ACTIVE 版本优先于过时版本。
5. 回答使用证据时能引用检索到的来源。

推荐指标：

```text
Recall@5
MRR
faithfulness
citation coverage
source correctness
```

### 文献推荐

检查：

1. 推荐论文真实存在。
2. 有 DOI、arXiv ID、URL、venue、year 或来源元数据时必须保留。
3. 用户上传 `.bib` 时，推荐结果会和已有文献去重。
4. 重复文献不会作为多篇独立推荐返回。
5. 低置信度或元数据不完整的文献会明确标注。
6. 模型不会编造引用。

### 论文润色

检查：

1. 原意被保留。
2. 技术 claim 不会在缺少证据时被强化。
3. 公式、引用、label 和 LaTeX 结构被保留。
4. 改写范围符合用户请求或任务范围。
5. 建议可解释，并且能被用户接受或拒绝。

### 长期记忆

检查：

1. 只有持久、有用的事实会写入长期记忆。
2. 敏感或偶然数据不会被存储。
3. 已删除记忆不会被检索。
4. 已废弃记忆不会被注入。
5. 记忆注入不会压过当前用户意图。

### 长任务和事件

检查：

1. 返回 task id。
2. 中间 trace 可见，但不会变成聊天最终回答。
3. 终态清晰。
4. 取消任务不会生成已完成产物。
5. 重连或刷新不会重复最终回答。

## Eval case 格式

在正式 eval runner 建立前，使用以下格式：

```text
Case ID:
Area:
Input:
Setup:
Expected behavior:
Observed behavior:
Pass/Fail:
Notes:
```

示例：

```text
Case ID: RAG-AUTH-001
Area: RAG retrieval
Input: "What is the private project deadline?"
Setup: User A has a private document. User B does not.
Expected behavior: User B must not retrieve or answer from User A's document.
Observed behavior:
Pass/Fail:
Notes:
```

## PR 报告要求

需要 eval 的 PR 必须包含：

```text
执行的 eval cases:
- ...

通过:
- ...

失败:
- ...

跳过:
- ...

风险:
- ...
```

如果跳过 eval，必须说明原因。只写“模型行为看起来正常”是不够的。

## Spike 要求

LangChain4j RAG spike 必须使用同一批样本，与当前 RAG 链路对照。

Spike 结论必须回答：

1. 检索质量是更好、更差还是相当？
2. 忠实度是更好、更差还是相当？
3. 能否保留用户、Project 和版本过滤？
4. 能否保留引用元数据？
5. LangChain4j 增加或减少了哪些接入复杂度？
6. 项目应保留当前 RAG、部分迁移，还是分阶段替换？

Spike 不得在没有后续实现 issue 的情况下直接替换生产 RAG 链路。
