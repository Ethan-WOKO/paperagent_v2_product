# 论文润色质量闭环 progress

> 文档分类：**已完成归档**。本文中的时间点、分支和下一步只用于历史追溯。

## 记录规则

每次推进都追加一条记录，格式如下：

```text
## YYYY-MM-DD HH:mm

目标：
- ...

本次完成：
- ...

修改文件：
- ...

测试：
- 已执行：...
- 结果：...
- 未执行：...
- 原因：...

风险：
- ...

下一步：
- ...
```

## 2026-07-09 初始调研

目标：

- 阅读当前项目与 `0708后续计划及实施详情` 中的计划文件。
- 明确普通论文润色质量闭环缺什么、如何补、与文献检索质量闭环是否冲突。

本次完成：

- 确认项目是 Spring Boot 多模块科研 Agent 平台，论文相关核心模块在 `yanban-paper`，API 聚合在 `yanban-api`，前端在 `frontend`。
- 阅读：
  - `0708后续计划及实施详情/后续计划及实施详情.md`
  - `0708后续计划及实施详情/多对话并行协作流程.md`
- 检查论文润色主链路：
  - `PaperTaskService`
  - `PaperOrchestrator`
  - `PaperSectionPolishService`
  - `PaperAssembleService`
  - `PaperTask`
  - `PaperSection`
  - `PaperTaskResponse`
  - `PaperProcessRequest`
  - `PaperPage.vue`
- 确认当前最大缺口：
  - 前端和接口已传 `scoreThreshold / maxRounds / innerMaxAttempts`。
  - `PaperTask` 未持久化这三个参数。
  - `PaperOrchestrator` 仍硬编码 `0.7, 1` 调用分章润色。
  - 当前评分尺度存在 0-100 与 0-1 混用风险。
  - 章节状态、review、diff 已有基础，但失败原因协议和用户可读 diff 不完整。
  - suggestion accept/reject 目前主要是状态变更，尚未形成重新生成产物闭环。

修改文件：

- 新增 `0708后续计划及实施详情/论文润色质量闭环/implementation.md`
- 新增 `0708后续计划及实施详情/论文润色质量闭环/progress.md`

测试：

- 已执行：未执行代码测试。
- 结果：本次仅为文档沉淀和方案设计。
- 未执行：Maven 测试、前端 build、论文润色 eval。
- 原因：尚未修改代码。

风险：

- 当前工作区位于 `main` 且已有大量未提交改动，后续实现前需要先确认分支和改动归属。
- 文献检索质量闭环已有文件夹和疑似相关未提交改动，涉及 suggestions、literature、assemble 的任务需要避免并行冲突。

下一步：

- 建议先开独立 issue：`论文润色参数真实生效`。
- 从最新可控分支开始实现。
- 优先修复参数持久化、评分尺度统一、编排器传参和幂等 key。

## 2026-07-09 issue #94 evaluation baseline

Goal:
- Establish the first repeatable baseline for paper polish must-not-regress checks.

Completed:
- Added `PaperPolishBaselineEvaluationTest`.
- Reused existing fixed LaTeX samples under `yanban-paper/src/test/resources/paper-quality-samples/`.
- Generated paper polish baseline reports under `yanban-paper/target/paper-polish-baseline-eval/`.
- Added the combined runner `docs/evaluation/run-paper-quality-baseline-eval.ps1`.
- Added the dated report `docs/evaluation/reports/paper-quality-baseline-eval-20260709.md`.

Baseline cases:
- `POLISH-EN-001`: English section preserves citation, reference, and section structure.
- `POLISH-ZH-001`: Chinese sample preserves citation, figure, label, ref, graphics, and math spans.
- `POLISH-NEG-001`: unsafe output that drops protected placeholders is rejected and keeps the original text.

Evaluation:
- Command: `docs/evaluation/run-paper-quality-baseline-eval.ps1`
- Paper polish report output:
  - `yanban-paper/target/paper-polish-baseline-eval/report.json`
  - `yanban-paper/target/paper-polish-baseline-eval/report.md`

Scope boundary:
- This baseline validates guardrails and reportability with a deterministic model stub.
- It does not judge full academic writing quality or replace manual review.

## 2026-07-09 Issue #90 统一任务状态和事件体验

目标：
- 将论文润色任务接入第一版统一任务状态和事件约定。

本次完成：
- 复用现有 `paper_tasks`、`paper_task_artifacts`、`agent_tasks`、`agent_task_events`，未新增数据库迁移。
- 论文任务状态常量收口到统一 `AgentTaskStatus`，事件类型收口到 `AgentTaskEventTypes`。
- 统一状态接口新增最近事件字段，并继续返回失败原因、取消原因、partial artifact 计数。
- 论文取消的 `cancelReason` 写入统一 `agent_tasks.cancellation_reason` 镜像，不新增 `paper_tasks` 字段。
- 论文任务工具输出 artifact 的 `artifactStatus`，且 `PARTIAL` artifact 不再标记为 downloadable。

测试：
- 已执行统一任务/事件目标测试命令。
- 结果：通过，44 tests, 0 failures, 0 errors。

风险：
- 本次改变了公共状态响应字段和任务状态机常量引用，但未改变已有状态值。
- 取消原因通过统一镜像表保存，不扩展 `paper_tasks` 表结构。

下一步：
- 后续 UI 可按 `artifactStatus` 和最近事件字段优化展示；本 issue 不改 UI。

## 2026-07-09 11:33

目标：

- 处理 GitHub issue #92：论文润色配置真实生效、评分尺度统一、章节状态和失败原因记录。
- 严格不做 diff 展示、采纳/拒绝闭环、全文缺引用检测、综述 gap 诊断、旧文献替换、正式综述段落生成、自动插入引用或修改正文引用。

本次完成：

- 新增 `paper_tasks.score_threshold / max_rounds / inner_max_attempts` 持久化字段和 H2 同步 migration。
- `PaperTaskService` 创建任务时保存规范化配置，查询任务返回真实配置，幂等 key 纳入润色配置。
- `PaperOrchestrator` 从任务读取配置传给分章润色，并在章节异常时写入 `FAILED_KEEP_ORIGINAL` 和可读 `reviewJson`。
- `PaperSectionPolishService` 改为 `maxRounds` 外轮 + `innerMaxAttempts` repair 内尝试，统一 0-100 评分，拒绝 0-1 比例评分，并规范 `reasonCode/reasonMessage/keptOriginal/protectionTriggered` 等元信息。
- `section-review.md` 明确评分必须为 0-100。
- review report 增加章节失败原因分布，只做状态摘要，不做用户可读 diff。
- 指定必读文件 `0708后续计划及实施详情/AI开发者执行规则.md` 在当前仓库不存在；已按 issue 内统一约束、并行协作文档和 `memory-bank/agent-product-reconstruction-roadmap.md` 同名章节执行。

修改文件：

- `yanban-api/src/main/resources/db/migration/V29__add_paper_polish_config.sql`
- `yanban-api/src/test/resources/db/migration-h2/V29__add_paper_polish_config.sql`
- `yanban-paper/src/main/java/com/yanban/paper/domain/PaperTask.java`
- `yanban-paper/src/main/java/com/yanban/paper/service/PaperTaskService.java`
- `yanban-paper/src/main/java/com/yanban/paper/service/PaperOrchestrator.java`
- `yanban-paper/src/main/java/com/yanban/paper/service/PaperSectionPolishService.java`
- `yanban-paper/src/main/java/com/yanban/paper/service/PaperAssembleService.java`
- `yanban-paper/src/main/java/com/yanban/paper/web/PaperProcessRequest.java`
- `yanban-paper/src/main/java/com/yanban/paper/web/PaperTaskResponse.java`
- `yanban-paper/src/main/resources/prompts/section-review.md`
- `yanban-paper/src/test/java/com/yanban/paper/service/PaperTaskServiceTest.java`
- `yanban-paper/src/test/java/com/yanban/paper/service/PaperSectionPolishServiceTest.java`
- `yanban-paper/src/test/java/com/yanban/paper/service/PaperOrchestratorCancellationTest.java`
- `yanban-api/src/test/java/com/yanban/api/paper/PaperControllerIntegrationTest.java`

测试：

- 已执行：`mvn -pl yanban-paper -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=PaperTaskServiceTest,PaperSectionPolishServiceTest,PaperOrchestratorCancellationTest" test`
- 结果：通过，15 tests, 0 failures, 0 errors。
- 已执行：`mvn -pl yanban-api -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=PaperControllerIntegrationTest" test`
- 结果：通过，4 tests, 0 failures, 0 errors；Flyway H2 成功应用 29 个 migration，到 v29。受限网络下异步文献检索出现 OpenAlex DNS 警告，但测试已通过。
- 已执行：`mvn -pl yanban-paper -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=PaperQualitySampleTest" test`
- 结果：通过，3 tests, 0 failures, 0 errors。

风险：

- 新增数据库 migration，合并前需确认 V29 版本号没有与其他分支冲突。
- 章节失败原因仍存放在 `review_json`，未新增结构化字段；后续若要做统计查询，可单独 issue 增加列。

下一步：

- 跑 API/迁移集成测试。
- 若通过，提交、推送并创建 PR。

## 2026-07-09 18:02 PR #99 rebase cleanup

目标：

- 只修 PR #99：基于当前 `origin/main` 重新 rebase，清除已合并 #100 的重复提交和文件，恢复 #92 migration 为 V29，并确认 PR diff 只包含 #92 范围。

本次完成：

- 已基于 `origin/main` `f717917` 重新 rebase；该 main 已包含 #100。
- 已跳过本分支中重复的 #100 cherry-pick 提交，`pr-body-issue-90.md` 不再存在于本分支或 PR diff。
- 已跳过旧的 V30 调整提交，保留本 PR 的 `V29__add_paper_polish_config.sql` main/H2 migration；V30 留给后续 #97。
- 已解决 `progress.md` 冲突，并确认 `PaperOrchestrator`、`PaperTaskService` 自动合并后仍只保留 #92 的配置持久化、评分尺度、章节状态和失败原因相关改动。
- 已确认 PR diff 不包含 #100 的 agent/frontend/task 状态文件。

修改文件：

- `yanban-api/src/main/resources/db/migration/V29__add_paper_polish_config.sql`
- `yanban-api/src/test/resources/db/migration-h2/V29__add_paper_polish_config.sql`
- `yanban-paper/src/main/java/com/yanban/paper/service/PaperOrchestrator.java`
- `yanban-paper/src/main/java/com/yanban/paper/service/PaperTaskService.java`
- `0708后续计划及实施详情/论文润色质量闭环/progress.md`

测试：

- 已执行：`mvn -pl yanban-api -am clean "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=PaperControllerIntegrationTest" test`
- 结果：通过，4 tests, 0 failures, 0 errors；Flyway clean 环境成功应用 29 个 migration，到 v29。
- 已执行：`mvn -pl yanban-paper -am "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=PaperTaskServiceTest,PaperSectionPolishServiceTest,PaperOrchestratorCancellationTest,PaperQualitySampleTest" test`
- 结果：通过，19 tests, 0 failures, 0 errors。

风险：

- PR #99 当前只包含 #92 改动；若 main 后续再新增 migration，需要合并前再次检查版本号。
- 章节失败原因仍存放在 `review_json`，未新增结构化字段。

下一步：

- amend 当前提交，force-with-lease 推送，并确认 GitHub merge state 为 CLEAN/MERGEABLE 后请求复审。
## 2026-07-09 issue #93 final rebase after #99

Goal:
- Rebase PR #97 onto main after #99 and keep the scope limited to issue #93.

Completed:
- Kept #99 paper polish config/status logic as the main baseline.
- Kept issue #93 section revision status and diff acceptance loop.
- Resolved `PaperSectionPolishServiceTest` by preserving #99 review metadata assertions and #93 readable diff assertions.
- Kept migration order as V30 because #99 owns V29.
- Kept non-completed task protection so section/suggestion status updates do not assemble artifacts until the task is `COMPLETED`.

Scope boundaries:
- No #92 configuration/scoring/failure-reason feature work beyond conflict adaptation.
- No full-paper missing citation detection.
- No automatic citation insertion or literature replacement.
- No UI redesign.
