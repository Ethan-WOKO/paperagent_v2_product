# 论文润色质量闭环 implementation

> 文档分类：**已完成归档**。已实现和仍有价值的后续项已在新的能力评估中分别记录。

## 目标

把当前普通 LaTeX 论文润色从“任务能跑完、能生成产物”，升级为“配置真实生效、章节结果可解释、修改可对比、建议可采纳、质量可评测”的闭环。

本阶段只处理普通单入口 `.tex` 论文润色。暂不做复杂多文件 Project、完整 LaTeX 编译沙箱、自动改图、自动新增实验数据、代码仓库协同改论文。

## 当前项目现状

当前已有基础：

1. 后端已有论文任务主链路：
   - `PaperTaskService`
   - `PaperOrchestrator`
   - `PaperSectionPolishService`
   - `PaperAssembleService`
2. 数据库已有：
   - `paper_tasks`
   - `paper_sections`
   - `paper_task_artifacts`
   - `suggestions`
3. 章节表已有字段：
   - `polish_status`
   - `review_json`
   - `diff_json`
   - `original_object_key`
   - `polished_object_key`
4. 分章润色服务已有：
   - LaTeX placeholder 保护
   - 结构命令保护
   - unexpected translation 拦截
   - review
   - repair
   - 简单 diff summary
5. 前端 `PaperPage.vue` 已经展示并提交：
   - `scoreThreshold`
   - `maxRounds`
   - `innerMaxAttempts`
   - 文献数量参数
   - 文献-only 模式
6. 前端已能查看：
   - 任务状态
   - 章节列表
   - suggestions
   - artifacts
   - live task events

## 核心缺口

### 1. 用户配置没有真实进入异步执行

接口请求已经接收 `scoreThreshold / maxRounds / innerMaxAttempts`，但 `PaperTask` 没有保存这些字段。

当前 `PaperOrchestrator.polishSections` 中仍使用硬编码：

```java
sectionPolishService.polishSection(taskId, section, targetLanguage, 0.7, 1);
```

这会导致前端显示的配置和后端实际执行不一致。

需要解决：

1. 给 `paper_tasks` 增加持久化字段：
   - `score_threshold`
   - `max_rounds`
   - `inner_max_attempts`
2. `PaperTask` 增加字段和 getter/setter。
3. `PaperTaskService.createTask` 保存归一化后的参数。
4. `PaperTaskResponse.from` 返回任务真实参数。
5. `PaperOrchestrator` 从 `PaperTask` 读取参数后传给 `PaperSectionPolishService`。
6. 幂等 key 纳入这些参数，避免同一文件但不同润色配置命中旧任务。

### 2. 评分尺度需要统一

前端是 0 到 100 分，`PaperSectionPolishService` 测试和 review 逻辑也更接近 0 到 100 分。但编排器硬编码 `0.7`，会造成阈值语义混乱。

需要解决：

1. 统一 `scoreThreshold` 为 0 到 100。
2. 默认值建议为 80。
3. 请求校验增加 `@Max(100)`。
4. 禁止比例阈值进入主链路。
5. prompt 中明确 review score 必须为 0 到 100。
6. 测试覆盖：
   - 阈值 80，88 分通过。
   - 阈值 90，88 分不通过。

### 3. 章节失败原因还没有稳定协议

现在失败信息散落在 `review_json` 中，前端和评测不容易稳定统计。

需要解决：

1. 短期先规范 `review_json`：
   - `reasonCode`
   - `reasonMessage`
   - `score`
   - `passed`
   - `attempts`
   - `lintIssues`
   - `protectionTriggered`
2. 中期可考虑给 `paper_sections` 增加结构化字段：
   - `failure_reason_code`
   - `failure_reason_message`
   - `attempts`
   - `review_score`
3. 收口章节状态枚举：
   - `PENDING`
   - `SKIPPED`
   - `POLISHED`
   - `REVIEW_FAILED`
   - `FAILED_KEEP_ORIGINAL`
   - `MODEL_FAILED`
   - `PROTECTION_REJECTED`
4. 前端章节区域展示：
   - 状态
   - 分数
   - 尝试次数
   - 失败原因

### 4. diff 目前只是摘要，不是用户可读差异

当前 `diff_json` 主要包含：

1. word count
2. changed
3. commonPrefixWords
4. lengthDelta
5. changeRatio

这对调试有价值，但用户无法看到“具体改了哪里”。

需要解决：

1. 保留当前 summary。
2. 新增可展示 diff：
   - 最小版：按行 diff。
   - 更好版：按句子/词级 diff hunks。
3. 将 diff 写入：
   - `paper_sections.diff_json`
   - 或新增 `section_diff` artifact。
4. 前端在章节详情里展示前后差异。
5. review report 中增加章节 diff 摘要。

### 5. 采纳建议后没有重新生成产物

前端可以 Accept/Reject suggestions，后端也能更新 `Suggestion.status`，但这只是状态标记。当前组装逻辑不会因为用户采纳/拒绝而重新应用正文 patch。

需要解决：

1. 明确 suggestions 默认只是建议，不自动写入正文。
2. 新增应用建议动作：
   - `POST /api/v1/paper/tasks/{taskId}/apply-suggestions`
3. 只应用满足以下条件的建议：
   - `status = ACCEPTED`
   - `applicable = true`
   - patch 可解析
   - evidence 达标
   - 不破坏 LaTeX 保护规则
4. `REJECTED` 建议禁止进入新版产物。
5. 应用结果生成新 artifact version：
   - `polished_tex v2`
   - `review_report v2`
6. patch 应用失败时保留原文并记录原因，不硬改。

### 6. 修改意见和文献推荐边界需要更清楚

文献推荐可以生成 `suggested.bib`，但不应默认插入正文。修改意见也不应在用户未确认时自动改变最终稿。

需要解决：

1. 明确建议类型：
   - 表达润色
   - 结构建议
   - 论证建议
   - 引用需求
   - 文献推荐
2. 文献推荐默认只进入：
   - `suggested.bib`
   - `suggested-novel.bib`
   - review report
3. 只有用户明确采纳正文 patch，才改正文。
4. review report 区分：
   - 已自动润色的章节
   - 待用户采纳的修改意见
   - 已采纳并应用的建议
   - 已拒绝建议
   - 推荐但未插入正文的文献

### 7. 论文润色评测集需要成为门禁

已有若干单元测试，但还缺稳定的论文润色质量评测报告。

需要解决：

1. 固定样例：
   - 英文普通论文
   - 中文普通论文
   - 含公式、引用、label、ref 的论文
   - 容易被错误翻译的英文论文
2. 固定指标：
   - 章节总数
   - 成功润色数
   - 保留原文数
   - 失败原因分布
   - 平均 review score
   - LaTeX 保护失败数
   - 是否出现英文整段翻译成中文
   - artifact 是否生成
3. 固定命令：
   - `docs/evaluation/run-paper-polish-eval.ps1`
4. 固定报告位置：
   - `docs/evaluation/reports/paper-polish-quality-YYYYMMDD.md`

## 推荐实施顺序

### Issue 1：论文润色参数真实生效

目标：

1. 持久化 `scoreThreshold / maxRounds / innerMaxAttempts`。
2. 统一评分尺度为 0 到 100。
3. 编排器不再硬编码 `0.7, 1`。

主要修改：

1. 新增 migration：
   - main migration
   - H2 test migration
2. 修改：
   - `PaperTask`
   - `PaperTaskService`
   - `PaperTaskResponse`
   - `PaperProcessRequest`
   - `PaperOrchestrator`
3. 补测试：
   - `PaperTaskServiceTest`
   - `PaperOrchestrator` 参数传递测试
   - API integration 测试

验收：

1. 创建任务后 `GET /paper/tasks/{id}` 返回真实配置。
2. 异步执行时 `PaperSectionPolishService` 收到用户配置。
3. 阈值按 0 到 100 判断。
4. 不同配置不会错误复用同一个幂等任务。

### Issue 2：章节失败原因记录协议

目标：

1. 每章都能解释处理结果。
2. 失败、保留原文、保护规则触发、模型异常、review 未通过都能稳定统计。

主要修改：

1. 规范 `review_json` 元信息。
2. 必要时增加章节结构化字段。
3. 前端章节列表展示失败原因。
4. review report 增加失败原因分布。

验收：

1. 每章有 `polishStatus`。
2. 非 `POLISHED` 章节有 reason code。
3. review report 能列出失败原因分布。

### Issue 3：用户可读 diff

目标：

1. 用户能看到每章具体改动。
2. review report 和前端都能展示差异。

主要修改：

1. 新增 diff builder。
2. 扩展 `diff_json` 或新增 diff artifact。
3. 前端章节详情展示 diff。

验收：

1. 成功润色章节有可读 diff。
2. 保留原文章节显示无改动或失败原因。
3. diff 不破坏 LaTeX 命令展示。

### Issue 4：采纳建议后重新生成

目标：

1. 用户采纳/拒绝建议会影响后续产物。
2. 旧产物不覆盖，新产物以版本保存。

主要修改：

1. 新增 apply suggestions API。
2. 实现 accepted patch 应用。
3. 应用后重新 assemble。
4. 记录哪些建议被应用、跳过、失败。

验收：

1. `ACCEPTED` 建议进入新版产物。
2. `REJECTED` 建议不进入新版产物。
3. patch 失败不会破坏最终稿。
4. artifacts 版本递增。

### Issue 5：论文润色评测门禁

目标：

1. 论文润色质量变化可重复比较。
2. 后续改 prompt、review、repair、assemble 时必须有报告。

主要修改：

1. 增加固定样例。
2. 增加 eval 脚本。
3. 增加报告模板。
4. 文档写明什么时候必须跑 eval。

验收：

1. 有可重复运行的命令。
2. 有固定报告输出。
3. PR 中能附评测结果。

## 和文献检索质量闭环的关系

论文润色质量闭环会使用文献检索/推荐结果，但不应该被文献检索闭环阻塞。

强相关点：

1. `RETRIEVE` 阶段会影响后续 Gap 分析。
2. `suggested.bib` 和 `suggested-novel.bib` 依赖文献推荐结果。
3. 证据卡片会影响建议是否可采纳。
4. citation-related suggestions 会依赖文献推荐协议。

弱相关或独立点：

1. 润色参数生效。
2. 评分尺度统一。
3. 分章状态和失败原因。
4. LaTeX 保护。
5. 用户可读 diff。
6. 章节 review/repair。

结论：

1. 两个闭环可以并行，但要拆清边界。
2. 论文润色闭环第一批 issue 应优先做与文献无关的底层可靠性：
   - 参数生效
   - 评分尺度
   - 章节失败原因
   - diff
3. 涉及 `suggestions`、`PaperAssembleService`、文献证据、`suggested.bib` 的任务，应等文献检索闭环接口稳定后再做，或者明确依赖关系。

## 并行开发注意事项

如果文献检索质量闭环和论文润色质量闭环并行：

1. 不要同时修改同一个核心文件：
   - `PaperAssembleService`
   - `PaperGapAnalysisService`
   - `Suggestion`
   - `SuggestionRepository`
   - `LiteratureService`
   - 文献相关 migration
2. 论文润色参数生效可以独立推进，主要触碰：
   - `PaperTask`
   - `PaperTaskService`
   - `PaperTaskResponse`
   - `PaperProcessRequest`
   - `PaperOrchestrator`
   - `paper_tasks` migration
3. 文献检索闭环可以主要触碰：
   - `LiteratureRecommendationService`
   - `LiteratureService`
   - literature source/rerank/eval
   - literature recommendation tool
4. migration 必须提前认领版本号，避免冲突。
5. 如果两边都要改 `PaperAssembleService`，应串行：
   - 先合文献协议/产物约定。
   - 再做建议采纳后重新生成。
6. 如果两边都要改 `suggestions` 表或 `Suggestion` 实体，也应串行。
7. 每个 PR 都要写清楚：
   - 是否影响论文主链路
   - 是否影响文献推荐主链路
   - 是否需要跑 paper polish eval
   - 是否需要跑 literature recommendation eval

## 第一批建议任务

推荐先开：

1. `论文润色参数真实生效`
2. `论文润色章节失败原因协议`
3. `论文润色用户可读 diff`

暂缓：

1. `采纳建议后重新生成`
2. `文献推荐驱动正文 patch`
3. `论文润色 UI 重做`

原因：

1. 前三项更独立，适合和文献检索闭环并行。
2. 后三项会碰到 suggestions、evidence、assemble，与文献检索闭环耦合更高。
