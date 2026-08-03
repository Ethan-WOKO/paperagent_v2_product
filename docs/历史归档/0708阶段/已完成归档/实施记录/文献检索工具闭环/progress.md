# 文献检索工具闭环执行进度

> 文档分类：**已完成归档**。这里的“未开始/下一步”是历史状态，不自动进入当前待办。

## 记录说明

这个文件用于记录后续开发过程、执行结果、评测结果和遗留问题。

每完成一个阶段，都需要在这里补充：

1. 做了什么。
2. 改了哪些能力。
3. 当前效果如何。
4. 测试或评测结果如何。
5. 还剩什么问题。
6. 下一步要做什么。

## 当前状态

创建时间：2026-07-09

当前阶段：方案整理完成，尚未开始代码实施。

当前结论：

1. 现有系统已经有基础文献检索和推荐能力。
2. 后续重点不是新增很多工具，而是把现有文献检索工具做成闭环。
3. 第一优先级是任务类型识别和统一输出结构。
4. 第二优先级是补引用闭环。
5. 第三优先级是补综述和替换文献。
6. 用户反馈、文献库沉淀和评测回归必须同步规划。
7. 2026-07-09 已补充 AI 开发者拆 issue 规则和第一批建议 issue，降低自动拆分时的歧义。

## 阶段一：任务类型识别

状态：未开始

计划目标：

1. 识别普通找文献。
2. 识别补引用。
3. 识别补综述。
4. 识别替换文献。
5. 识别检查已有参考文献。

计划结果：

系统能在调用文献检索前，知道这次任务到底属于哪一类。

执行记录：

暂无。

验收结果：

暂无。

遗留问题：

暂无。

## 阶段二：统一输出结构

状态：未开始

计划目标：

1. 统一文献基础信息。
2. 统一推荐理由。
3. 统一匹配对象。
4. 统一可信程度。
5. 统一用户操作状态。
6. 统一 BibTeX 和元数据检查。

计划结果：

不同文献任务都能使用同一套结果结构，方便前端展示、后端存储和后续复用。

执行记录：

暂无。

验收结果：

暂无。

遗留问题：

暂无。

## 阶段三：补引用闭环

状态：未开始

计划目标：

1. 找出论文中可能缺引用的句子。
2. 为每个句子生成检索问题。
3. 调用文献检索工具。
4. 判断候选论文是否能支撑该句。
5. 输出推荐理由。
6. 用户确认后生成引用建议和 BibTeX。
7. 记录该文献已用于该论文。

计划结果：

用户能看到“哪句话缺引用、推荐哪篇文献、为什么推荐、是否采用”。

执行记录：

暂无。

验收结果：

暂无。

遗留问题：

暂无。

## 阶段四：补综述闭环

状态：未开始

计划目标：

1. 分析论文主题。
2. 分析已有相关工作覆盖情况。
3. 找出缺失研究类别。
4. 按类别调用文献检索。
5. 按类别输出推荐文献。
6. 给出文献放置建议。

计划结果：

系统不再只是返回论文列表，而是告诉用户“相关工作缺哪一块，每一块应该补哪些文献”。

执行记录：

暂无。

验收结果：

暂无。

遗留问题：

暂无。

## 阶段五：替换不合适文献

状态：未开始

计划目标：

1. 获取引用所在句子。
2. 获取旧文献信息。
3. 判断旧文献是否合适。
4. 如果不合适，生成替换检索问题。
5. 检索候选文献。
6. 比较新旧文献。
7. 输出替换建议。
8. 用户确认后记录替换结果。

计划结果：

系统能说明“原引用为什么不合适，新文献为什么更合适”。

执行记录：

暂无。

验收结果：

暂无。

遗留问题：

暂无。

## 阶段六：用户反馈和文献库沉淀

状态：未开始

计划目标：

1. 支持收藏。
2. 支持拒绝。
3. 支持已引用。
4. 支持不再推荐。
5. 支持加入文献库。
6. 后续推荐读取用户历史反馈。

计划结果：

系统能记住用户认可和拒绝过的文献，并影响下一次推荐。

执行记录：

暂无。

验收结果：

暂无。

遗留问题：

暂无。

## 阶段七：评测回归

状态：未开始

计划目标：

1. 建立普通找文献测试。
2. 建立补引用测试。
3. 建立补综述测试。
4. 建立替换文献测试。
5. 建立跑偏案例测试。
6. 建立真实外部检索测试。

计划结果：

每次修改文献检索相关逻辑后，都能知道质量有没有变好或变差。

执行记录：

暂无。

验收结果：

暂无。

遗留问题：

暂无。

## 总体风险

当前已知风险：

1. 外部论文源不稳定。
2. 不同学科的文献推荐标准不一样。
3. 补引用容易误判作者自己的观点。
4. 补综述容易推荐数量太多但结构不清楚。
5. 替换文献容易错误替换经典文献。
6. 用户反馈如果不沉淀，系统会一直停留在一次性搜索工具阶段。
7. 没有评测回归时，排序逻辑很容易越改越乱。

## Issue #91：文献检索基础能力收口

执行时间：2026-07-09

分支：`codex/literature-base-closure`

状态：已完成代码实施和固定样例验证。

完成内容：

1. 收口主题文献检索第一版输出协议，复用现有 `recommend_literature` 和 `/literature` 检索入口。
2. `recommend_literature` 结果新增匹配对象、排序依据、引用信息状态、元数据风险等级、元数据风险说明、去重键、重复合并状态和重复来源。
3. `/literature` 快捷检索结果同步新增引用信息状态、元数据风险、排序依据和去重说明，并在聊天文本里展示。
4. DOI、arXiv、OpenAlex、Semantic Scholar ID 和标题指纹继续用于去重；重复候选不作为多条独立推荐出现，入选结果会记录合并来源。
5. 已有 BibTeX 继续通过 `alreadyPresent` 标记，不把已有参考文献当成全新推荐。
6. `recommend_literature` 工具说明已明确限定为主题检索第一版，不承诺全文缺引用检测、综述缺口诊断、替换旧文献或修改正文。

关键改动：

1. `LiteratureRecommendationService` 增加重复来源追踪和推荐条目的引用/风险/去重解释字段。
2. `AdHocLiteratureSearchService` 增加同类输出字段，保持后台任务 materialize 的 `cardId` 兼容。
3. `ConversationIntentRouterService` 在 `/literature` 文本结果中展示 citation status、metadata risk、deduplication 和 ranking basis。
4. `RecommendLiteratureToolExecutor` 更新工具描述，避免越界承诺。
5. 补充服务和工具执行器测试，覆盖去重、已有文献、引用状态、元数据风险和工具 JSON 字段。

测试结果：

1. `mvn --% -pl yanban-paper -am -Dtest=LiteratureRecommendationServiceTest,AdHocLiteratureSearchServiceTest,LiteratureRecommendationEvaluationTest -Dsurefire.failIfNoSpecifiedTests=false test`
   - 结果：通过，11 tests, 0 failures, 0 errors。
2. `mvn --% -pl yanban-api -am -Dtest=RecommendLiteratureToolExecutorTest,ConversationIntentRouterServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
   - 结果：通过，6 tests, 0 failures, 0 errors。
3. `powershell -ExecutionPolicy Bypass -File docs/evaluation/run-literature-recommendation-eval.ps1`
   - 结果：通过，并生成 `yanban-paper/target/literature-recommendation-eval/report.json` 和 `yanban-paper/target/literature-recommendation-eval/report.md`。

未解决问题：

1. 本次不新增持久化用户反馈、不做长期个性化推荐，也不做完整 Project 文献库。
2. 本次不做全文自动缺引用检测、不做自动综述缺口诊断、不做自动替换旧文献。
3. 本次不自动修改论文正文，也不自动插入引用。
4. 外部论文源仍可能失败或返回不完整元数据；当前策略是返回部分结果和风险提示。

是否影响后续 issue：

1. 影响：后续补引用、补综述、替换文献和前端决策面板可以复用新增的统一解释字段。
2. 风险：新增字段属于输出协议扩展，应避免其他并行分支再次发明不兼容字段。

## 下一步建议

下一步优先做：

1. 定义任务类型。
2. 定义统一输出结构。
3. 先实现补引用最小闭环。
4. 补一个前端结果展示入口。
5. 建立第一批补引用评测用例。

## 2026-07-09 issue #94 evaluation baseline

Goal:
- Establish the first repeatable baseline for literature recommendation quality checks.

Completed:
- Reused `LiteratureRecommendationEvaluationTest` as the mandatory fixture-backed literature baseline.
- Added the combined runner `docs/evaluation/run-paper-quality-baseline-eval.ps1`.
- Added the dated report `docs/evaluation/reports/paper-quality-baseline-eval-20260709.md`.

Evaluation:
- Command: `docs/evaluation/run-paper-quality-baseline-eval.ps1`
- Literature report output:
  - `yanban-paper/target/literature-recommendation-eval/report.json`
  - `yanban-paper/target/literature-recommendation-eval/report.md`

Scope boundary:
- This baseline does not make live external paper sources the only mandatory gate.
- This baseline does not implement a full Agent eval or broader literature workflow changes.
## 2026-07-09 Issue #90 统一任务状态和事件体验

目标：
- 将文献检索任务接入第一版统一任务状态和事件约定。

本次完成：
- 复用现有 `literature_search_tasks`、`agent_tasks`、`agent_task_events`，未新增数据库迁移。
- 文献检索任务状态常量收口到统一 `AgentTaskStatus`，事件类型收口到 `AgentTaskEventTypes`。
- 统一任务状态接口在有 `resultJson` 且存在 `sourceFailuresJson` 时标记 `partialResultAvailable=true`。
- 文献检索工具结果同步输出 `partialResultAvailable`，避免部分来源失败的结果被误读成完整最终结果。

测试：
- 已执行统一任务/事件目标测试命令。
- 结果：通过，44 tests, 0 failures, 0 errors。

风险：
- 本次改变了公共状态响应字段和任务状态机常量引用，但未改变已有状态值。

下一步：
- 后续 UI 可按 `partialResultAvailable` 展示部分结果提示；本 issue 不改 UI。
