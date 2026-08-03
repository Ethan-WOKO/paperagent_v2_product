# 科研 Project Agent 第一版实施计划

> 文档分类：**已被替代**。本文的 Worker 1–24、ReAct/Plan 旧链路和阶段排期已被当前 V2 持久化 Plan 架构替代，不得据此启动旧 Worker。尚有价值的记忆、上下文、科研工具和子 Agent 想法已提取到新的后续能力评估。

> 文档状态：当前执行权威计划
> 创建日期：2026-07-12
> 最近同步：2026-07-23
> 已审查工程基线：GitHub `origin/main` 最新提交（Worker 23 验收收口）
> Worker 1 验收基线：`e1f733d`（离线发布门与本地验收矩阵）
> Worker 2 契约工程基线：`8e274ab`（科研工具与结构化索引纯契约）
> Worker 3 只读工具工程基线：`1fc1e0f`（五个受治理科研工具与 Evidence 闭环）
> 当前工程基线：GitHub `origin/main` 最新提交（Worker 16 至 Worker 23 已完成两轮串行收口）
> Worker 启动基线：以串行任务包中冻结的完整 `HEAD` 为准
> 当前发布状态：`WORKER_23_ACCEPTED / FOUR_STAGE_ROADMAP_COMPLETED`
> 设计依据：《通用 Agent Runtime 设计》《Agent 对比分析与后续改造建议》

> 当前进度：Worker 1 至 Worker 7 已完成主对话复审。用户已完成 Project 文件树/预览、五个科研工具和 Plan 关键场景测试；`56e6b5c` 已加入浏览器文件夹上传、托管对象存储、Project 会话与 Plan 展示，并修复规划 JSON 截断、步骤 Verifier 截断、依赖证据复用和受控 PARTIAL。Worker 4 基线 `ff6f6e5` 统一了 Chat/ReAct/Plan 的 run identity、status/phase/outcome、canonical answer 与 PARTIAL/取消/失败语义。Worker 5 基线 `1c40159` 在该投影上增加 L0 Task Workspace，保存目标、成功条件、计划引用、观测步骤摘要、剩余工作和有界短期记忆；任意 JSON 快照中的记忆只能降级为明确标记的非权威审计摘要，不能伪造 Evidence、Candidate、Artifact、失败结果或工具观察。`823a820` 在不扩权的前提下完成 Worker 5 后本地回归闭环。Worker 6 基线 `956ce42` 以服务端 manifest 的 portable relative path、文件大小和 SHA-256 内容哈希确定性派生 ProjectVersion，并将 Project Evidence、Plan 持久化 Evidence、Candidate 与 Artifact 绑定到同一版本；旧 Evidence 缺少完整版本、范围或 parser provenance 时保持 fail-closed，不能伪造 VERIFIED。Worker 7A 至 7D 完成长短期记忆治理、受信只读接入、用户确认/拒绝/纠正/删除和双语治理界面。Worker 8A 基线 `d4970cd` 冻结受信 ProjectVersion 沙箱快照、不可变 UTF-8 全文替换 Candidate、base/result hash、EvidenceRefs、审查 diff、预算和 `NOT_APPLIED` 验证契约。Worker 8B 基线 `83c6b56` 将该契约接入服务端受信工作副本：只读取 Candidate 与 Evidence 涉及的文本文件，读取前后对完整 manifest 二次校验，任意未请求文件并发变化也返回 409；Candidate 只能由显式结构化 intent 和当前受信 Evidence ledger 生成，公共 artifact API 不能伪造保留类型，持久化后每次读取都重新物化、证明和验证，始终保持 `NOT_APPLIED`。Worker 8C 基线 `8ff3339` 新增受治理的 `project_propose_candidate` 生产入口，并通过当前轮真实工具结果和服务端 artifact 再认证投影 Candidate；Project 页从真实 API 展示多文件变更、ProjectVersion、指纹、验证状态、review diff 与 Evidence provenance，始终保持 `NOT_APPLIED`。主对话独立验证 Worker 8C 定向后端 66/66、完整 reactor 889 项零失败且 9 项既有条件跳过、前端 3/3、生产构建及 `git diff --check` 通过。Worker 8 不包含真实 Project 写入、命令、外部网络、migration 或自动应用；真实模型生成、多文件展示、409 STALE 和 422 INVALID 仍由用户进行本地 UI 验收。MVP 发布门脚本仍有基线遗留的绝对路径创建用例禁用治理项，发布前必须单独收口。该结论不表示用户本地科研验收完成，也不表示持久化 checkpoint/重启恢复、多版本历史与导出或安全应用已经完成。

> 进度更正（2026-07-16）：Worker 9 已在 `f2ee67d` 完成受治理 Candidate 应用、不可变 Project revision 历史、回滚和 ZIP 导出；上段末尾关于“多版本历史与导出或安全应用尚未完成”的描述已被本次工程实现取代。持久化 checkpoint/重启恢复、命令执行沙箱、外部网络和 Pro 权限仍未开放。该结论不表示用户本地科研验收完成。

> Worker 10 工程进度（2026-07-16）：`4a85b09` 已完成受治理的 `AUTO` 策略选择与论文/LaTeX、代码、实验配置和 BibTeX 跨材料编排基础。Project 普通请求默认保持 ReAct；只有服务器受信信号同时表明跨材料、多阶段/验证需要，且现有工具覆盖与调用预算满足时才选择 Plan。显式策略、受信 Plan 能力和精确 `/plan reflect` 路由保持原语义。主对话已完成独立静态审查、132 项 Worker 10 定向测试和 648 项完整 reactor 测试，均为零失败、零错误；后续用户本地验收已于 2026-07-18 收口，见下方验收记录。本结论不表示整个科研 Project Agent 已完成最终验收。

> Worker 11 工程进度（2026-07-16）：`1d488f0` 已在 Worker 10 的 AUTO Direct/ReAct/Plan 路由上增加受治理的跨材料完成校验、结构化一致/不一致结果和最多一次有界 Reflection。校验只接受当前受信材料、工具结果、版本化 Evidence 与已完成 Plan 步骤；失败后只有同一工具/材料范围内的后续受信成功，或受信 `plan_repaired` 替换步骤完成，才能恢复旧失败。当前唯一可确定性 VERIFIED 的跨材料规则是用户明确要求的文件内容哈希/字节一致性；论文、代码和实验之间的一般语义一致性仍必须保持 `UNRESOLVED/PARTIAL`，不能由模型推断冒充 VERIFIED。主对话已完成独立静态审查、200 项定向测试和 685 项完整 reactor 测试，均为零失败、零错误，8 项条件跳过；`git diff --check` 通过。后续用户本地验收已于 2026-07-18 收口，见下方验收记录。本结论不表示整个科研 Project Agent 已完成最终验收。

> Worker 10/11 本地验收收口（2026-07-18）：用户已确认 Worker 10 与 Worker 11 本地验收完成；验收修复提交为 `394ae75`，覆盖服务器 canonical answer、Plan 生命周期与执行 outcome 分离、受治理 PARTIAL、显式 Project 文件范围、预算终止、一次 Reflection、Plan Evidence 范围以及前端历史消息/Plan/Candidate 刷新与内部 Evidence 标记清理。主对话独立复跑后端定向测试 240/240、前端定向测试 8/8 和生产构建，均通过。该结论仅表示 Worker 10/11 的本地验收完成，不等于整个科研 Project Agent 的最终验收完成。

> Worker 12A 合同工程进度（2026-07-18）：`7362fde` 定义了受控只读 Worker 的任务包、材料分配、预算、受信服务器 authority/attestation、结果/receipt、Evidence 引用、覆盖率和跨材料差异报告契约；`4a66952` 已将其与 Worker 10/11 验收基线合并。父 Agent 仍是唯一 canonical answer 与写入决策者，Worker 只允许 `READ_ONLY`、当前 ProjectVersion、受信相对路径和原策略 `allowedTools` 的交集，Candidate 继续保持 `NOT_APPLIED`。12A 没有生产 executor、调度接线、Controller、前端、migration、命令、外部网络或自由多 Agent。组合基线完整 reactor 722 项零失败、零错误、8 项条件跳过，前端生产构建和 `git diff --check` 通过。12A 契约现已由 Worker 12B 在一个严格受限场景中接入生产 Runtime。

> Worker 12B 工程进度（2026-07-18）：`e9998f4` 已接入一个由服务器 AUTO 策略触发的论文/LaTeX 与代码/配置双 Worker 只读跨材料场景。它复用既有持久化 Plan 生命周期、run identity、事件、Evidence 与 canonical answer，不创建第二套生命周期；显式相对路径优先，并与当前 manifest、ProjectVersion、原始 `allowedTools` 和任务预算求交。Worker 结果作为 `UNTRUSTED_WORKER_DATA` 交给父 Agent，不能通过提示文本提升权限或伪造 applied/VERIFIED；只有父 Agent 生成 canonical answer，Candidate 继续为 `NOT_APPLIED`。受控 dispatch 使用现有 `AgentPlan.rawPlanJson` 持久化非敏感 envelope，使 execute/retry/restart/async 路径在重新校验用户、Project、版本、哈希、工具和预算后恢复；缺失、篡改、STALE 或工具撤销均 fail-closed。主对话两轮 P1 复审修正了 Plan 持久化绕过、Worker 摘要信任边界和仅内存 dispatch 恢复问题；独立完整 reactor 共 1046 项，零失败、零错误、9 项条件跳过，前端生产构建与 `git diff --check` 通过。该状态是工程验收，不表示用户真实模型或本地 UI 验收完成，也不表示已开放通用自由多 Agent、写入、命令、网络、沙箱或 Pro 权限。

> Worker 12 本地验收收口（2026-07-18）：用户已确认 Worker 12 验收完成，收口提交为 `78a6d0b`。真实 Project 会话 107、Plan 60 以 `COMPLETED / PARTIAL` 完成；AUTO 只调度 `paper_injection.tex` 的 `project_latex_outline` 与 `implementation.py` 的 `project_code_symbols`，未访问明确禁止的第三个文件。父合成保持 `allowedTools=[]`、`maxToolCalls=0`、`maxSteps=1`，语义一致性维持 `UNRESOLVED/PARTIAL`，Candidate 为 0，ProjectVersion 未改变，6 条 Evidence 均为 CURRENT；刷新后会话、Plan、Evidence 与 canonical answer 从后端恢复。验收修复还补齐服务器 token 预算、受控 Plan 精确工具持久化、父 Agent canonical answer 文案、Project 会话 URL 恢复和 Project 文件名 Markdown 误链接。主对话独立复跑后端定向 21/21、前端 15/15、完整 reactor 1048 项零失败零错误且 9 项条件跳过、前端生产构建与 `git diff --check`，均通过。该结论仅表示 Worker 12 的受控只读场景完成本地验收，不等于自由多 Agent、完整科研 Project 或 Pro 模式验收完成。

> Worker 15 与 E2B 部署收口（2026-07-21）：Candidate 沙箱验证、显式接受后生成新 ProjectVersion 的闭环已进入 GitHub `main`；E2B 作为新增 Provider 接入，原有 `docker-sbx` 保留。远端基线 `db36b82` 包含 Candidate E2B 验证、一键服务器部署和 HTTP 沙箱确认修复。下一轮不继续扩 Provider，而是按阶段 8 至阶段 11 收口统一入口、LLM 路由、记忆、界面、Evidence 分层、工具自修复及后续复杂执行范式。

> Worker 18 验收收口（2026-07-22）：普通 DIRECT 不再被 Project Evidence 误阻断，Project 事实及 Candidate、沙箱、Apply/回滚/导出硬边界保持不变；工具校验错误获得脱敏 `RepairContext` 并仅允许一次改变参数或方法的有界修复，相同失败调用复用失败结果且不重复执行。`FAILED/TIMED_OUT`、`CANCELLED`、`PARTIAL` 已贯通 Plan、任务投影、事件、UI 与唯一助手消息。真实 E2B 超时会话 193 / Plan 193 验证等待确认时无内部错误串，超时后原 assistant 行就地收敛为终态，连续 GET、刷新和 API 重启后仍保持 1 user + 1 assistant。主对话独立复跑 handoff、timeout 与控制器集成后端 28/28、前端 14/14，均通过；Worker 完整离线 Maven 1230 项、前端 43+6 项、类型检查、生产构建和 `git diff --check` 均通过。

## 1. 目标与边界

第一版要把当前只读 Project MVP 建设为可审查、可恢复、可安全修改的科研 Project Agent：

1. 理解用户上传的完整科研 Project。
2. 联合分析论文、代码、实验结果、配置、报告和文献。
3. 生成带证据的论文、代码、BibTeX 和实验配置候选修改。
4. 所有修改先进入隔离工作副本，不覆盖原始文件。
5. 用户能够查看、接受、拒绝、回滚并导出新的 Project 版本。

第一版不追求无人监督的自主科研循环。Pro 模式只能在沙箱、生命周期、证据、验证和回滚通过真实验收后启动。

目标产品不是单一论文润色器，也不是只会聊天和调用少量工具的助手，而是：

> 以用户授权的科研 Project 为中心，使用 ReAct、Plan-and-Execute、受限 Reflection 和后续受控 Worker 完成可审计科研任务，在沙箱中生成并验证候选修改，由用户决定如何形成新的 Project 版本。

## 2. 使用模式

### 2.1 普通对话

普通对话只能使用用户上传文件、知识库、受控长期记忆和允许的外部检索工具，不能根据用户电脑上的任意绝对路径读取文件。

### 2.2 Project 第一版

Web 服务以“上传文件夹或 ZIP”为主要入口：

1. 浏览器上传目录并保留相对结构。
2. 后端保存不可变原始版本并生成 manifest。
3. 系统建立结构化科研索引。
4. Agent 在只读原始版本和隔离任务工作区中执行。
5. 修改形成 Candidate ChangeSet。
6. 用户确认后生成新的 Project 版本。

本地单机部署可以保留绝对路径绑定，但它属于部署能力，不是 Web 模式读取用户电脑文件的通用方案。

### 2.3 Pro 模式

Pro 模式允许系统围绕用户初始目标，在隔离工作副本中自主修改论文、代码、实验配置和科研材料，运行受控实验并迭代优化，最终形成论文与可复现 Project。

Pro 模式仍必须具备：

- 不可变原始版本。
- 隔离分支或沙箱副本。
- 命令、网络、资源和时间预算。
- 阶段检查点。
- 基线 hash 与冲突检测。
- 实验数据真实性约束。
- 全量审计、自动回滚与人工接管。

## 3. 当前能力与缺口

| 能力 | 当前状态 | 第一版要求 |
|---|---|---|
| Runtime Coordinator | 已完成基础统一与确定性 AUTO 选择 | 改为 LLM 提出策略、Runtime 校验能力并执行 |
| ReAct | 已有 `SINGLE_STEP_REACT`、科研语义工具与停止条件 | 在统一入口中复用并补齐跨轮错误自修复 |
| Plan-and-Execute | 已有受信 Plan、步骤验证和持久化恢复 | 先统一入口与记忆，后续再升级步骤内 ReAct |
| Reflection | 已有最多一次不扩权修复 | 后续改为失败、结果不足或冲突时事件触发 |
| 多 Agent | 一个受控双 Worker 只读跨材料场景已接入 | 先完成真实模型与本地 UI 验收，再决定是否扩展更多受控场景 |
| Project 工具 | manifest/read/search 与首批科研语义工具已接入 | 根据真实任务补充检索质量，不继续盲目扩工具 |
| Project 索引 | manifest、版本证据和科研结构索引已有基础 | 后续按真实检索缺口增强 |
| Project 输入 | 浏览器文件夹上传、托管存储和不可变 ProjectVersion 已接入 | 保持多版本历史、导出和回滚回归 |
| 文件修改 | Candidate、沙箱验证、显式应用和回滚已完成闭环 | 保持默认 `NOT_APPLIED`，优化用户审查体验 |
| 生命周期 | L2 Task Run、lease、checkpoint 与重启恢复已接入 | 在统一入口下保持状态与 canonical answer 一致 |
| 短期记忆 | Task Workspace、摘要和 ContextPackage 已接入 | 与统一入口及 Plan 展示保持一致 |
| 长期记忆 | 已有治理与普通 Chat 只读接入，Plan 注入不一致 | 贯通全局偏好、Planner、步骤与最终总结 |
| 沙箱 | Docker 与 E2B Provider 已接入，Candidate 验证闭环已部署 | 保持 Provider 抽象并以真实用户旅程持续验收 |
| 评测 | 确定性回归与 Worker 14/15 用户旅程已有基础 | 为阶段 8 至阶段 11 增加真实用户旅程 |

## 4. 冻结安全原则

1. 原始 Project 版本不可变。
2. 第一版不允许模型直接覆盖用户真实文件。
3. 所有写入只发生在沙箱或受控工作副本。
4. Candidate ChangeSet 默认 `NOT_APPLIED`。
5. 应用前验证 projectId、相对路径、base hash、用户身份和确认记录。
6. 论文、代码、实验和工具输出均为不可信数据，不能提升系统权限。
7. 命令执行、网络访问和密钥使用必须由策略授权。
8. 实验结果必须绑定命令、代码版本、配置、输入和产物 hash。
9. 不得把未运行实验写成已完成，不得生成虚假数据填入论文。
10. Worker 默认只读，同一文件任何时刻只能有一个写者。
11. 用户外部修改优先；基线变化后候选修改必须标记 `STALE`。
12. 自然语言回答不能覆盖结构化失败、证据不足或预算终止状态。

## 5. Agent 执行模型

### 5.1 策略选择

```text
LLM Router 判断无需工具且上下文充分 -> DIRECT
Project 需要文件、工具、执行或 Candidate -> PLAN_EXECUTE
Project 存在多目标、依赖步骤或跨材料交付 -> PLAN_EXECUTE
普通非 Project Chat -> 保留现有 REACT 兼容路径
高价值产物 -> 确定性验证 + 最多一次 Reflection
独立、可验证且资源隔离的子目标 -> 后续受控 Worker
```

LLM Router 负责理解任务结构并为 Project 提出 `DIRECT / PLAN_EXECUTE`，不能只根据“难度”或用户使用了“计划”“分析”等词分类。普通非 Project Chat 继续保留现有 ReAct 兼容路径；Project 的步骤内 ReAct 延后到阶段 11。Runtime 不再承担主要语义分类，但必须校验 Project capability、工具权限、沙箱确认、预算和策略可用性；模型选择不可执行策略时只能明确降级或失败，不能扩大权限。策略选择与修改权限相互独立。

### 5.2 完成定义

只有以下条件同时满足时才能声明完成：

- 必要步骤已进入终态。
- 必需工具真实成功。
- 证据引用存在且版本有效。
- 候选修改通过结构和领域规则验证。
- 用户确认或明确保持 `NOT_APPLIED`。
- 最终产物和审计事件已经持久化。

## 6. 科研语义工具

### 6.1 第一批只读工具

- `project_latex_outline`：章节、公式、标签、引用和浮动体目录。
- `project_bibtex_audit`：条目、重复、缺字段、未引用和缺失引用键。
- `project_code_symbols`：类、函数、入口、参数和模块关系。
- `project_experiment_summary`：配置、CSV 指标、报告和结果文件摘要。
- `project_cross_material_search`：跨论文、代码、配置和报告查找同一概念。

### 6.2 第二批分析能力

- 论文公式与代码实现对照。
- 论文参数与实验配置对照。
- 论文表格与结果文件对照。
- 文献证据与具体论断对照。
- LaTeX 结构与引用完整性验证。
- 代码静态检查结果标准化。

### 6.3 候选修改能力

- 论文局部补丁。
- 代码补丁。
- BibTeX 变更。
- 实验配置变更。
- 带 base hash 和 EvidenceRefs 的 Candidate ChangeSet。

候选修改能力不等于应用能力。

## 7. 结构化 Project 索引

每个 Project 版本至少维护：

```text
ProjectVersion
FileManifest
LatexSectionIndex
LatexFormulaAndReferenceIndex
BibEntryIndex
CodeSymbolIndex
CodeDependencyIndex
ExperimentAssetIndex
CrossMaterialLinkIndex
```

索引项必须携带 Project 版本、相对路径、文件 hash、范围、解析器版本、来源和置信度。大文件不得整文件无差别发送给模型。

## 8. Task Workspace 与生命周期

Project 是长期材料空间；Task Workspace 是一次 Agent 执行的隔离工作空间，保存：

- 目标、成功条件和 Project 基线版本。
- 计划、步骤、已读取文件和证据。
- 可审计事实和待验证假设。
- 工具输出引用、候选修改和验证结果。
- 用户确认、最终产物和审计事件。

目标生命周期：

```text
CREATED
-> PLANNING
-> RUNNING
-> WAITING_INPUT / WAITING_CONFIRMATION
-> PAUSED / RESUMING
-> VERIFYING
-> FINALIZING
-> COMPLETED / PARTIAL / FAILED / CANCELLED
```

第一版需要从 L1 逐步升级到可重启恢复的持久化托管，包括任务队列、Worker lease、heartbeat、checkpoint、幂等执行和卡死任务回收。

## 9. 记忆系统

### 9.1 短期工作记忆

只保存可审计内容，不保存模型内部思维链：

- 当前目标和计划。
- 已确认事实与证据。
- 待验证问题。
- 候选修改。
- 失败原因和剩余工作。

### 9.2 长期记忆

分为用户偏好、Project 事实与术语、用户确认的研究决策、经验证的领域知识和可复用任务经验。

每条长期记忆必须包含 scope、source、provenance、confidence、projectVersion、userConfirmed 和过期策略。默认不自动保存模型猜测。

## 10. 沙箱与安全修改

第一版沙箱至少提供：

- 基于 Project 版本创建工作副本。
- 文件读写范围。
- 命令 allowlist。
- CPU、内存、时间和输出预算。
- 默认关闭网络。
- 环境变量和密钥隔离。
- 进程终止、临时文件清理和产物收集。

安全修改流程：

```text
原始 Project 版本
-> 沙箱工作副本
-> Agent 修改
-> 静态检查/测试/可选编译
-> Candidate ChangeSet
-> 用户逐条确认
-> base hash 再校验
-> 生成新 Project 版本
-> 保留回滚点
```

## 11. 联合任务示例

用户上传论文、BibTeX、算法代码、实验代码、CSV 结果和实验报告，并要求检查方法、公式、代码与实验是否一致。

Plan：

1. 解析论文方法、公式和贡献。
2. 解析代码入口、目标函数、约束和参数。
3. 解析实验配置、CSV 和报告。
4. 建立公式、代码与实验关联。
5. 判断冲突来源，不能默认论文或代码任一方正确。
6. 生成论文、代码或配置的候选修改方案。
7. 在沙箱中验证修改。
8. 输出 diff、理由、证据和未解决问题。

系统必须允许结论为：

- 修改论文以准确描述当前实现。
- 修改代码以符合论文方法。
- 同时修改代码、论文和实验配置。
- 证据不足，需要用户确认研究意图。

## 12. 多 Agent 边界

第一版前期不实现自由多 Agent 调度。单 Agent 工具、生命周期和修改闭环稳定后，首个受控场景为：

- Worker A 只读分析代码。
- Worker B 只读分析论文。
- Worker C 只读分析实验与文献。
- 父 Agent 对照结果并生成唯一 Candidate ChangeSet。

禁止 Worker 自由创建无限子 Worker、复制整个主会话、并行修改同一文件或自行宣布父任务完成。

## 13. 实施阶段

### 阶段 0：只读 MVP、本地验收与基线稳定

状态：`ACCEPTED_FOR_FIRST_VERSION_FOUNDATION`

- 保留自动测试主体通过的证据；在遗留禁用用例收口前，不宣称完整发布门 GO。
- 用户已完成 Project 文件浏览、科研工具、ReAct 和 Plan 关键场景验收；后续阶段继续保留真实科研回归。
- 建立缺陷清单与回归用例。
- 修复 P0/P1 后形成新的稳定基线 commit。

退出条件：

- 当前开发对话全部结束。
- 变更完成主对话复审。
- 工作区无未归属修改。
- 形成新的基线 commit。

### 阶段 1：科研语义工具与索引

状态：`ACCEPTED_READ_ONLY_FOUNDATION`

冻结工具契约和索引模型，实现第一批只读科研工具并接入 ToolDescriptor、策略、证据和预算。不包含写文件、命令执行和多 Agent。

### 阶段 2A：统一 Task Run 与持久化生命周期

状态：`ACCEPTED_L0_L1_RUN_LIFECYCLE_FOUNDATION`

统一普通 Chat、Project Chat、ReAct 和 Plan 的单次运行抽象、权威状态、phase、outcome、事件和 canonical answer，建立取消、暂停、恢复、checkpoint 和重启恢复边界。

当前只完成统一语义与最小生产投影；持久化 checkpoint、lease、跨进程恢复和重启恢复仍属于后续实施范围，不得因状态模型已经统一而视为完成。

### 阶段 2B：Task Workspace 与短期工作记忆

保存当前目标、成功条件、计划、步骤、受信 Evidence、工具观察、失败原因、候选产物和剩余工作；统一上下文裁剪、观察复用和 checkpoint 恢复，不保存模型内部思维链。

### 阶段 2C：ProjectVersion 与版本证据

在现有文件夹上传和对象存储基础上增加不可变 ProjectVersion、版本化 manifest、索引版本、Evidence 绑定、版本比较和导出基础。任何 migration 必须先单独审查。

### 阶段 2D：长期记忆治理与受控接入

先完成用户可见的来源、scope、provenance、confidence、修正、删除、过期和注入审计，再把经过确认的长期记忆只读接入主链。禁止自动保存模型猜测或直接打开全量历史注入。

### 阶段 3：沙箱与候选修改

建立工作副本，支持论文、代码、BibTeX 和配置候选修改，完成结构、版本和证据验证，仍不直接应用真实 Project。

### 阶段 4：用户确认、版本应用与回滚

实现逐条 diff 审核、接受、拒绝、冲突检测、原子生成新 Project 版本、回滚和导出。

### 阶段 5：统一策略选择与跨材料科研编排

已完成确定性 AUTO ReAct/Plan 选择、论文代码实验文献联合分析、领域 Verifier 和受限 Reflection 基础。该确定性选择器是历史交付，不代表后续继续以关键词承担主要语义分类；阶段 8 将改为 LLM Router 提出策略、Runtime 校验执行。

### 阶段 6：受控 Worker 验证

实现只读 WorkerTaskPacket、单写者父 Agent、结构化 WorkerResult 和跨材料差异报告。

### 阶段 7：Pro 模式研究（历史编号，顺序后移）

该阶段保留历史编号，但实际顺序后移到阶段 11 之后。只有第一版体验、可靠性和复杂执行范式通过真实科研 Project 验收后才能开始。

### 阶段 8：统一入口、LLM Router 与记忆贯通

状态：`COMPLETED`

- Project 页面只保留一个用户输入入口，不再要求用户在 Chat 与 Plan 之间手动选择。
- LLM Router 根据任务结构、工具依赖、步骤依赖和交付目标为 Project 提出 `DIRECT / PLAN_EXECUTE`；后端只校验候选策略是否符合当前 capability、权限、工具、沙箱确认和预算。普通非 Project Chat 的 ReAct 兼容路径保持不变。
- 已确认的语言、格式等全局用户偏好不经过相似度召回，必须进入 DIRECT、Planner、Plan 步骤和最终总结；其他长期记忆继续按 scope 与相关性检索。
- 两种 Project 顶层策略共享同一 session、run、ContextPackage、事件语义和 canonical answer；一次用户请求只能发布一条正式最终回答。
- 本阶段复用现有 Plan 执行器，不引入新的 Plan-and-Execute、ReAct 或 Reflection 循环。

完成记录：Worker 16 已完成 Project 单输入入口、结构化 LLM Router、全局确认偏好贯通、Plan 确认/恢复不重复消息、自然语言 Candidate 与沙箱执行意图路由，以及外部 E2B 环境下的测试隔离。真实用户旅程覆盖无工具 DIRECT、单文件读取、多文件比较、E2B 确认后执行和沙箱关闭时普通问答；后端完整测试 1197 项零失败，前端定向测试 2/2 与生产构建通过，根目录完整 Maven 回归通过。

退出条件：已满足。默认中文偏好在两种 Project 顶层策略中一致生效；简单知识问题进入 DIRECT，Project 文件、执行和 Candidate 工具任务进入 PLAN_EXECUTE；策略越权被 Runtime 拒绝或确定性降级；刷新后无重复消息、重复计划或重复最终回答。

### 阶段 9：Project 页面与执行过程展示整理

状态：`COMPLETED`

- 保留项目、会话、文件三个区域平均分配页面高度的现有结构，禁止修改三区域的 flex 比例、最小高度和总体布局算法。
- 标题栏高度与内边距保持稳定，展开/收起只改变区域内容可见性；文字箭头替换为标准图标按钮。
- 移除用户层面的 Chat/Plan 双标签和 Plan 独立输入框，Plan 作为同一会话中的可折叠执行卡展示。
- 默认只展示状态、步骤进度、耗时、等待确认和失败原因；工具参数、原始输出与内部过程进入运行详情，不作为普通聊天气泡刷屏。
- “预览 / 证据 / 改动 / Versions”只保留一组标签，修复选中态、文字包裹和点击区域，不保留重复导航。

完成记录：Worker 17 在不改变项目、会话、文件三区域 `25% / 25% / 50%` flex 与最小高度规则的前提下，稳定标题栏尺寸，使用现有 UI 图标替换字符箭头，移除用户层面的 Chat/Plan 双标签和重复检查器导航，并把 Plan 整理为同一会话中的默认折叠执行卡。桌面、窄屏和沙箱确认三条真实浏览器旅程通过；主对话独立复跑 4 个前端测试文件共 16 项、类型检查、生产构建与 `git diff --check`，均通过。

退出条件：已满足。桌面与窄屏下标题不跳动、文字不溢出、三区域比例不变；Project 的 DIRECT/PLAN 均从同一输入框完成；Plan 过程可展开查看但默认不污染会话；检查器不存在重复标签，沙箱确认按钮保持可用。

### 阶段 10：Evidence 分层、工具自修复与状态语义

状态：`COMPLETED`

- 普通知识问答、产品说明和操作咨询不因缺少 Project Evidence 被阻断；只有引用当前 Project 事实、Candidate 验证/应用和其他外部影响操作进入对应硬校验。
- 工具失败后向模型提供结构化、脱敏的工具名、参数、错误码、错误消息和剩余尝试次数，使下一次调用能够修正参数或改变方法。
- 参数或输入校验错误允许有界重试；完全相同的失败调用不得循环；权限拒绝、用户取消、Provider 关闭和不可恢复错误不得自动重试。
- 对齐后端、Event、前端与 canonical answer 的 `PARTIAL / FAILED / CANCELLED / TIMED_OUT` 语义。
- 原始 stdout/stderr 可查看；只读总结必须标明“基于输出、未独立验证”，且不能覆盖执行事实。

完成记录：Worker 18 引入小型脱敏 `RepairContext`，复用现有步骤与工具预算完成一次参数/方法修复；完全相同的失败调用不再执行，不可恢复错误不重试。普通 DIRECT 的 Evidence 例外保持为服务端验证的无工具知识路径，Project 文件事实仍要求当前受信 Evidence。沙箱等待确认使用安全 handoff，Plan 终态通过 Plan ID 绑定原地更新同一 assistant，不新增第二条消息；真实超时、取消、失败、刷新与重启恢复已验收。

退出条件：已满足。普通问答不再被 Evidence 闸门误伤；可恢复工具错误能够通过修改参数恢复；重复失败有确定终止条件；相同终态在 API、事件、界面和最终回答中含义一致。

### 阶段 11：Plan-and-Execute、步骤内 ReAct 与事件触发 Reflection

状态：`COMPLETED`

- Planner 生成目标、依赖步骤、成功条件和预算；每个可执行步骤内部使用受预算约束的 ReAct 获取真实结果。
- 仅在步骤失败、结果不足、结果冲突或确定性验证不通过时触发 Reflection；不得每一步固定反思。
- Reflection 只能修改尚未执行的步骤和剩余工作，不能改写已确认执行事实、扩大工具权限或绕过用户确认。
- 设置总工具预算、最大步骤数、最大重规划次数和无进展终止条件；权限拒绝、用户取消、Provider 不可用等外部阻断不触发模型反思循环。
- 本阶段开始前必须根据阶段 8 至阶段 10 的真实运行数据重新审查方案，不提前冻结复杂实现细节。

完成记录：Worker 19 保持 Project 顶层仅 `DIRECT / PLAN_EXECUTE`，在 Plan 步骤内部复用现有有界 ReAct，并将 Planner 的步骤目标、依赖、成功条件和预算持久化。Reflection 仅由步骤失败、Evidence 不足/冲突或确定性 verifier 失败触发，全局最多一次；已完成事实不可变，过时 pending 步骤可被明确 supersede，相同失败和等价剩余计划均有确定终止。真实用户旅程覆盖 DIRECT、步骤内参数修复、一次 Reflection/replan、无进展停止、沙箱确认与失败回执；主审发现并关闭了“沙箱真实失败被静态 Reflection 升格为成功”的 P1，最终失败回执保持 Plan `FAILED`、Reflection 为零且只更新同一 assistant。

退出条件：已满足。中间结果不足时能够调整剩余计划；无关步骤可被取消；已完成步骤不重复；失败不会形成 Reflection 循环；复杂任务仍只发布一个 canonical answer；沙箱权威失败不会被静态分析覆盖或升级为成功。

### 阶段 12：结果语义与证据分层底座

状态：`COMPLETED`

- 分离 `executionOutcome`、`taskOutcome` 和 `answerStatus`，避免执行成功、任务完成和回答可信度被一个状态混用。
- 证据采用类型与状态两个维度：执行事实、当前 Project 证据、外部来源、推理和未验证输入；状态限定为 `VERIFIED / SUPPORTED / INFERRED / UNVERIFIED / CONFLICTING / STALE`。
- receipt 只证明执行事实；stdout/stderr 只证明本次输出字节；Project hash 只证明当前版本内容。任何推理和自由文本都不能覆盖 Provider 状态、退出码、超时或取消。
- 搜索摘要和未知外部来源保持 `UNVERIFIED`；只有明确已打开的外部来源可成为 `SUPPORTED`，且不能升级为 `VERIFIED`。

完成记录：Worker 20 以无 migration 的 `FinalSynthesisInput` 投影稳定输出三层结果、分层证据和验证范围，并保持旧 Plan/API JSON 兼容。普通代码执行使用代码快照、命令与 receipt/output 的最小材料，不再要求无关 `EXPERIMENT_CONFIG`。真实旅程覆盖 E2B Java 成功、受控非零失败、取消和 DIRECT；Windows Broker 仅继承 Python 初始化所需的 `SystemRoot`，敏感业务环境不继承。

退出条件：已满足。成功 receipt 不被后续语义校验降级为执行失败；失败、超时和取消不被模型推理升级；搜索摘要不会进入受支持依据；DIRECT 和 Project 顶层 `DIRECT / PLAN_EXECUTE` 边界不回归。

### 阶段 13：受控 Final Synthesis

状态：`COMPLETED`

- 在 Plan 终态后由无工具、无写权限、无网络的只读总结阶段综合用户问题、受信代码/步骤结果、receipt、原始 stdout/stderr、限制和受治理语言偏好。
- 最终回答必须直接回答用户问题，并明确区分已验证事实、受支持结论、推理和未验证限制；模型不可用时使用可读的确定性回退。
- 只能原地形成一个 assistant 和一个 canonical answer，不能触发 Project/Plan/Evidence 写入、Candidate 应用或后续执行。

完成记录：Worker 21 在 Plan 终态增加唯一、无工具、只读且限时的 Final Synthesis，服务端有界组装用户问题、受治理偏好、Worker 20 事实证据、可信 Project 片段、步骤结果和原始 stdout/stderr。Provider、status、exitCode 和 timedOut 只取 VERIFIED ExecutionFact；模型事实标签冲突、工具调用、空结果、异常或超时时整段回退为确定性答案。正常终态只给模型一次综合机会，GET/list 和重启缺口不调用模型，并通过数据库锁内首个 canonical 胜出实现并发幂等。真实旅程覆盖 E2B Java 成功、非零失败、中文偏好、恶意 stdout、连续刷新和 API 重启恢复，始终只有一个正式 assistant 和一个 canonical answer。

退出条件：已满足。聊天区直接回答用户问题，不再只提示查看 Plan 卡；失败、取消和超时不会被写成成功；成功 receipt 不会因语义未独立验证而降级；DIRECT、Project 顶层 `DIRECT / PLAN_EXECUTE`、Candidate 和 Evidence 边界不回归。

### 阶段 14：结果展示与状态降噪

状态：`COMPLETED`

- 聊天区展示最终答案；Plan 卡默认折叠展示状态、进度、耗时和确认，内部 reason code、hash、receipt 细节进入执行详情。
- 将执行结果、任务结果、回答依据和限制转换为一致的用户文案；Evidence 数量与分类含义可解释。
- 保持项目、会话、文件三区域高度算法和单输入入口不变，完成桌面与窄屏真实浏览器验收。

完成记录：Worker 22 保持唯一 canonical assistant 答复，将 Plan 终态整理为默认折叠的执行卡，并把 `executionOutcome / taskOutcome / answerStatus` 分别映射为执行结果、用户任务结果和回答依据。Evidence 按执行事实、当前 Project、已打开外部来源、搜索摘要/未知来源、推理、未验证、冲突和过期分组；reason code、hash、receipt、完整 stdout/stderr 与步骤原始记录进入可展开详情。真实浏览器覆盖 DIRECT、E2B Java 成功、非零失败、等待确认、取消、刷新/API 重启及桌面/390px 窄屏；三区保持 `25% / 25% / 50%`，单输入、单 Plan、单 assistant 和四个唯一检查器入口不变。主审另关闭了“正常答案只因出现 `DOMAIN_*` / `SANDBOX_*` 术语便被误删”的过度降噪 P1。

退出条件：已满足。成功 receipt + `SUPPORTED` 不再显示为未通过；失败、超时和取消不会冒充成功；确认态保持可操作且不显示红色内部错误串；原始输出和证据限制仍可追溯；普通术语说明、代码块和日志不会被错误折叠。

### 阶段 15：编码任务闭环与固定验收集

状态：`PENDING`

- 固化解释代码、编译运行、参数修复、编译失败定位、Candidate 验证、显式应用、重新测试、超时/取消/恢复等真实任务。
- 每条旅程检查任务结果质量、证据等级、单 assistant、单 canonical answer、Candidate `NOT_APPLIED` 和 ProjectVersion 恰好一次写入。
- 只对真实旅程暴露的问题做最小修复，不扩展自由多 Agent、Pro 自主循环或新的顶层策略。

## 14. 串行 Worker 开发协议

每个 Worker 启动前记录：

```text
baselineCommit
objective
allowedFiles
forbiddenFiles
frozenContracts
requiredTests
stopConditions
```

current baseline:

```text
Worker 20 frozen baseline: 64f872b
```

任何前序 Worker 的变更必须先完成主对话复审并提交，才能成为下一 Worker 的基线。

串行流程：

```text
Worker 开发
-> Worker 报告
-> 主对话静态审查
-> 主对话独立测试
-> CHANGES_REQUIRED 或 ACCEPTED
-> 修正与复审
-> 形成新 commit
-> 启动下一 Worker
```

同一工作区禁止两个开发 Worker 同时修改。主对话在 Worker 运行期间只允许只读监督，或修改明确不相交的独立规划文档。

出现 P0、契约冲突或大范围回归时停止新 Worker，使用该任务的 baseline commit 判断回滚范围；不使用未经审查的 `reset --hard`。

## 15. 当前串行执行队列

### 已完成：Worker 1 至 Worker 5

- Worker 1：`ACCEPTED_ENGINEERING_GATE`，MVP 本地验收矩阵和离线发布门隔离完成。
- Worker 2：`ACCEPTED / CONTRACT_ONLY`，科研工具和结构化索引契约完成，基线 `8e274ab`。
- Worker 3：`ACCEPTED_READ_ONLY_IMPLEMENTATION`，五个受治理科研工具完成，基线 `1fc1e0f`。
- 2026-07-15 收口基线：`56e6b5c`。
- Worker 4：`ACCEPTED_L0_L1_RUN_LIFECYCLE_FOUNDATION`，统一 Task Run 与生命周期投影完成，基线 `ff6f6e5`。
- Worker 5：`ACCEPTED_L0_TASK_WORKSPACE_FOUNDATION`，Task Workspace 与受治理短期工作记忆完成，基线 `1c40159`。
- Worker 5 后本地回归闭环：`ACCEPTED_PROJECT_RUNTIME_REGRESSION`，Workspace Chat 装配、Project 上传诊断、受控 PARTIAL、单文件字面量检索与自适应预算治理完成，基线 `823a820`。

### Worker 4：统一 Task Run 与生命周期契约/最小骨架

状态：`ACCEPTED`

- 普通 Chat、Project Chat、ReAct、Plan 的统一 run 语义。
- 唯一权威 status/phase/outcome 映射。
- 统一 run trace、事件、canonical answer 和错误分类。
- 暂停、取消、恢复、重试和 checkpoint 边界。
- 只允许契约和最小骨架；如需 migration，先停下并单独评审。
- 已验证：定向 158/158；根聚合 8/8 模块通过，`yanban-api` 541 项零失败、8 项既有条件跳过。
- 已知发布门治理项：遗留绝对路径创建测试为 `@Disabled`，导致零跳过发布门保持 NO_GO；不得归因于 Worker 4，也不得在本 Worker 范围外顺手修改。

### Worker 5：Task Workspace 与短期工作记忆

状态：`ACCEPTED_L0_TASK_WORKSPACE_FOUNDATION`

- 当前目标、成功条件、计划、步骤和剩余工作。
- 受信 Evidence、工具观察、失败结果和 Candidate 引用。
- 上下文裁剪、观察复用、会话摘要和非权威快照的降级恢复边界。
- 不保存或展示模型内部思维链。
- 已按统一 run identity/status/phase/outcome/canonical answer 接入普通 Chat、ReAct、Project 和受信 Plan 路径，不改变 tool policy。
- 当前只保存计划引用与观测步骤摘要，不宣称恢复完整结构化 Plan。
- 持久性固定为 `L0_REQUEST_BOUND`；`checkpointAvailable=false`、`restartResumable=false`，不提供跨进程 checkpoint。
- 无完整性证明的 JSON 快照记忆统一降级为 `AUDIT_SUMMARY` 并标记 `recovered/untrusted`，不能恢复为权威 Evidence、Candidate、Artifact、失败或工具观察。
- 已验证：定向 29/29；Spring/Controller/Project 垂直回归 63/63；完整 `yanban-api` reactor 聚合 552 项零失败、8 项既有条件跳过；CLI 构建通过。

### Worker 6：ProjectVersion 与版本化 Evidence

状态：`ENGINEERING_ACCEPTED`

- ProjectVersion 由服务端对排序后的 portable relative path、文件大小和文件 SHA-256 确定性派生；支持空 manifest，拒绝重复路径和非 portable path，不依赖 `modifiedAt`。
- Project 只读工具在单次调用中只读取一次受信 manifest；根输出、所有 search hit 及零命中结果共享同一 server-owned ProjectVersion。
- Evidence 完整绑定 `projectVersion/fileHash/range/parserVersion/provenance`，显式区分 `VERIFIED/STALE/LEGACY_UNVERSIONED`。
- 旧 Evidence 缺失完整版本字段时 fail-closed；只有服务器当前 manifest 明确再认证后才可形成 VERIFIED，不能由客户端或历史 JSON 自行升级。
- Plan 持久化保留全部版本字段；Candidate/Artifact 同时校验 ProjectVersion 与文件 hash，继续保持 `NOT_APPLIED`。
- Chat/ReAct/Plan 未增加 allowedTools、写入、命令、网络或模型权限；未新增 migration、Controller、前端或对象迁移。
- 主对话独立验证：最新完整 `mvn -o -pl yanban-api -am test` 共 573 项零失败、8 项既有条件跳过；`git diff --check` 通过。
- 当前完成的是版本身份和版本化证据工程基础；持久化多版本历史、比较、导出和回滚仍属后续任务，不开放直接覆盖用户 Project。

### Worker 7：长期记忆治理与只读接入

状态：`WORKER_7_ENGINEERING_FOUNDATION_ACCEPTED / LOCAL_ACCEPTANCE_INCOMPLETE / DEFERRED_GAPS_RECORDED`

- Worker 7A 已完成 USER 范围只读基础：只选择服务器受信 userId 下 ACTIVE、显式用户确认来源、无 projectId、允许类型、合法标签、达到置信度且与任务相关的记忆。
- Worker 7A 已实现敏感信息和绝对路径整条拒绝、确定性排序、内容去重、5 条/1600 字符预算，以及“辅助且不作为 Evidence”的注入标记。
- Worker 7A 不读取 Project 范围记忆，不新增写入、Controller、前端、migration、工具、命令、网络或模型权限；最新完整 reactor 579 项零失败、8 项既有条件跳过。
- Worker 7B 已完成独立 confirmation、provenance、expiry、invalidation、projectVersion 治理字段和 fail-closed 数据契约；旧数据默认未确认，旧 Project 记忆不得自动升级。V33 与 H2 镜像迁移、实体、仓储、USER/Project 选择器契约及测试已经通过，未开放公共确认写接口。
- Worker 7B 只接受 `USER_ACTION + USER_MESSAGE` 受信 provenance，未知、模型、审计摘要、recovered/untrusted 值 fail-closed；候选扫描最多 10 页/400 行、接纳 100 条，防止无效记录挤出合法候选且不允许无界查询。
- Worker 7B 主对话独立验证：定向 42/42；完整 reactor 585 项零失败、8 项既有条件跳过；`git diff --check` 通过。真实 MySQL 8 的 V33 迁移已在 Worker 7D 本地联调中验证通过。
- Worker 7C 已完成受信后端确认、拒绝、修正、软删除、过期和 ProjectVersion 校验闭环；修正创建服务器确认的新版本并使旧版本失效，不自动保存模型推测。Project 记忆确认/修正对旧版本 fail-closed，拒绝、过期和删除仍先校验当前用户对 Project 的所有权。
- Worker 7C 使用悲观写锁保证并发状态转换确定性；重复确认、拒绝和删除具有受控幂等语义，冲突转换返回 409，客户端提供的 source/provenance 不会成为受信来源。主对话独立验证：定向 26/26；完整 reactor 594 项零失败、8 项既有条件跳过；`git diff --check` 通过。
- Worker 7D 已实现双语长期记忆治理界面，覆盖查看、创建、确认、拒绝、修正、过期和软删除；状态动作矩阵与后端一致，SUPERSEDED/DELETED/invalidated 维持只读，PROJECT stale 409 显式提示刷新且不伪造成功。
- Worker 7D 已在真实 MySQL 8 上验证 V33，从真实后端和真实 API 完成 USER 范围治理闭环，并在 `1920x1080`、`1440x900`、`1280x800` 复审中文界面、长内容和动作可达性。主对话独立验证：前端 11/11、后端 29/29、生产构建和现场 DOM 均通过；仅保留既有大 chunk 警告及列表 200 条上限。
- 本地验收遗留 1（已确认延期）：受治理长期记忆目前尚未完整接入 Project Plan 的规划、执行和 Reflection 上下文。后续接入必须沿用 trusted user/project identity、ProjectVersion、scope、provenance、confirmation、expiry 和预算边界，不能让记忆替代 Evidence 或扩大 Plan 的工具与写入权限。
- 本地验收遗留 2（已确认延期）：普通会话和 Project 会话中识别出的长期记忆候选目前尚未自动沉淀到“待用户确认”的治理队列。后续只能保存结构化候选及来源引用，不得自动确认为 ACTIVE，不得保存思维链、敏感信息或绝对路径，也不得跨用户/Project 串用。
- 上述两项均为 Worker 7 本地验收发现的真实功能缺口，本轮按用户决定暂不实现。它们不推翻 Worker 7 已完成的治理契约、后端接口和界面基础，但在收口并通过真实验收前，不得宣称 Worker 7 或长期记忆闭环完整通过。
- 后续恢复这两项时必须创建独立串行任务，记录 baseline、文件所有权、迁移/API 需求和回归矩阵；不得在 Worker 10 或其他任务中无记录地顺手实现。

### Worker 8：沙箱与 Candidate ChangeSet

状态：`LOCAL_UI_ACCEPTED`

- Worker 8A 已冻结受信 ProjectVersion 沙箱快照、不可变 UTF-8 全文替换 Candidate ChangeSet、base/result hash、EvidenceRefs、审查 diff、逐项/总量预算和 `NOT_APPLIED` 验证契约；ADD/MODIFY/DELETE 对目标存在性、路径大小写、base hash、Evidence 文件与哈希和结果内容哈希均 fail-closed。
- Worker 8A 的快照身份由 `ProjectManifestIdentity` 确定性派生；服务器证明必须同时匹配 `ResearchRuntimeScope` 的 projectId、ProjectVersion 和既有 `research:project-read` 能力。运行时身份、能力、证明和验证决策不可被 Candidate、快照或审查 diff 序列化后伪造。
- Worker 8A 主对话独立验证：定向 18/18、core 全量 85/85、完整 reactor API 聚合 594 项零失败且 8 项既有条件跳过；`git diff --check` 通过。基线提交为 `d4970cd`。
- Worker 8B 已完成受信只读沙箱物化、显式结构化 intent 到多文件 Candidate 的适配、保留 artifact 类型持久化边界和逐次再认证；读取请求文件后必须重新比较完整 manifest，Candidate 只能引用当前用户、当前 Project、当前 ProjectVersion 的受信 Evidence ledger。公共 artifact 创建不能伪造 Candidate，旧格式和未知 schema fail-closed。主对话独立验证：定向 62/62、完整 reactor 607 项零失败且 8 项既有条件跳过；`git diff --check` 通过。基线提交为 `83c6b56`。
- Worker 8C 已提供生产可用但不扩权的 `project_propose_candidate` 结构化 Candidate 生成入口，并将 Project 页 Candidate 审阅升级为多文件、版本化、状态可解释的真实 API 体验。后端只接受当前受信 user/project/allowlist、当前 manifest 和 portable Evidence selector，持久化后由 artifact 服务逐次再认证；前端重新读取 artifact API，展示 `NOT_APPLIED`、VALIDATED/STALE/INVALID、Evidence provenance、逐文件 ADD/MODIFY/DELETE 与审查 diff。主对话独立验证：定向后端 66/66、完整 reactor 889 项零失败且 9 项既有条件跳过、前端 3/3、生产构建和 `git diff --check` 通过。基线提交为 `8ff3339`。
- 2026-07-16 本地真实验收通过：真实模型先读取当前 Project manifest、标题与摘要 Evidence，再生成两文件 Candidate；页面确认 `VALIDATED / NOT_APPLIED`、4 条 Evidence provenance 与 STRUCTURE/VERSION/EVIDENCE/CONTENT_HASH/BUDGET 全部通过。另以隔离 Project 验证 STALE、INVALID、409、422、长 portable path、8.3 KB/131 行长 diff、空状态、loading 与 error 页面，未出现页面横向溢出或不可操作控件。
- 未经专门审查，不开放宿主机任意命令、外部网络、密钥、自动应用或多 Agent 并行写入；Candidate 必须始终从 `NOT_APPLIED` 开始，不能覆盖用户当前 Project。

### Worker 9：用户确认、Project revision 应用、回滚与导出

状态：`LOCAL_UI_ACCEPTED`

- 仅对当前用户拥有、使用 `MINIO_OBJECTS` 托管存储且仍匹配当前 ProjectVersion 的 Project 开放应用；旧绝对路径 Project、跨用户/跨 Project、STALE/INVALID Candidate、非 portable path、哈希或 manifest 不一致均 fail-closed。
- Candidate 仍保持不可变和 `NOT_APPLIED`。用户可逐文件选择接受项，服务端在新的 MinIO revision 前缀中复制完整快照并执行精确 ADD/MODIFY/DELETE，复核文件哈希和 manifest 后才通过悲观锁与版本条件原子切换当前 revision 指针。
- 应用和回滚均使用服务端受信 project/user/candidate/revision identity、`If-Match` 与 idempotency key；规范化的预期版本参与请求指纹，同一 key 携带不同 `If-Match` 不会复用旧成功结果，而是返回 409。
- revision 历史不可变；回滚只切换到已验证的历史 revision，不覆盖或删除旧版本。既有 V34 前 Project 在首次读取版本列表时由服务端锁内验证当前 manifest 和文件哈希，并且仅补建一次 `UPLOAD` revision。
- ZIP 导出只读取选定 revision 的受信 manifest，逐文件复核对象、portable relative path、大小与哈希，并受 Project 存储预算限制；未增加 Agent allowedTools、命令、外部网络、模型、MCP、多 Agent 或 Pro 权限。
- Project 页面提供逐文件选择、显式二次确认、Versions 历史、回滚确认、ZIP 下载以及 409/422 可解释提示；不把 Candidate 状态伪装为已应用。
- 主对话独立验证：Worker 9 定向后端共 71 项，其中 70 项通过、1 项条件跳过；完整 reactor 共 627 项，其中 618 项通过、8 项条件跳过，唯一错误为测试环境 `localhost:9092` 不可用导致既有 `TaskControlControllerIntegrationTest.retryPendingLiteratureDeliveryThroughUnifiedEndpoint` Kafka 发送超时，与 Worker 9 Project revision 路径无关；前端 revision/candidate API 5/5、Markdown 6/6、生产构建和 `git diff --check` 通过。
- 2026-07-16 本地真实验收通过：用户逐文件应用后仅选中文件进入原子生成的新 ProjectVersion，revision 1/2 均保留且可双向回滚；回滚按设计只切换 Current 指针，不额外制造 revision。旧 `If-Match` 返回 409，同一幂等键安全重放并复用同一 operation/result revision，并发应用恰有一个 200、另一个 409，非法 Candidate 返回 422，STALE Candidate 返回 409。
- 真实 MySQL 8 已确认 V34 成功及 `project_revisions`、`project_revision_operations` 的 revision、operation、幂等唯一约束和关联数据；真实 MinIO revision 前缀中的 manifest 与 3 个文件对象逐项 SHA-256 一致。真实 API 导出的 ZIP 含同一 3 个文件，其大小与 SHA-256 再次全部匹配 manifest。前端 Candidate/revision API 5/5 与生产构建通过。
- 按用户要求，Worker 9 完成后停止，不自动启动 Worker 10。

### Worker 10：统一策略选择与跨材料科研编排基础

状态：`LOCAL_UI_ACCEPTED`

- 新增服务器受治理的 `AUTO` 策略选择。Chat 只在 Direct/ReAct 中决策；Project 在 Direct/ReAct/Plan 中决策，普通 Project 请求默认 ReAct，避免因缺少材料关键词而退化为无工具 Direct。
- 只有受信请求上下文同时识别跨材料范围、多个执行阶段或独立验证需求，并确认现有只读科研工具覆盖与预算足够时，才自动选择 Plan。材料范围只复用现有论文/LaTeX、代码、实验配置和 BibTeX 工具，不新增 executor、命令、网络、模型、MCP、多 Agent 或 Pro 权限。
- 策略选择输出结构化审计信息，包括 `SERVER_AUTO`、`EXPLICIT_OVERRIDE`、`EXPLICIT_FALLBACK`、`TRUSTED_CAPABILITY` 来源、确定性 signals、reason codes 和材料需求；不保存模型思维链，也不接受客户端伪造受信 identity 或 capability。
- 显式服务器允许的策略优先；受信 Plan capability 继续进入 Plan；精确 `/plan reflect` 仍是唯一 Reflection 路由。所有自动 Plan 工具策略与原始 runtime policy 求交，不能扩大 `allowedTools`、写权限或网络权限。
- AUTO Plan 复用既有持久化 Plan 生命周期并同步执行，canonical answer 来自真实步骤结果；内部执行不重复沉淀会话摘要，外层 Chat 仍是唯一规范用户/助手消息。只有 `PLAN_CREATED` 被视为未执行的 PARTIAL，失败与部分完成不会伪装成功，既有 planId 执行不会重复创建 Plan。
- Worker 7 本地验收遗留的“Plan 长期记忆接入”和“会话候选自动沉淀”继续后置，Worker 10 未顺手实现，也不得据此宣称长期记忆闭环完整通过。
- 主对话独立验证：Worker 10 与 Worker 4-9 关键回归定向测试 132/132；`mvn -o -pl yanban-api -am test` 完整 reactor 648 项零失败、零错误、8 项条件跳过；`git diff --check` 通过。工程基线提交为 `4a85b09`。
- 2026-07-18 用户本地验收通过；相关收口修复提交为 `394ae75`。验收覆盖普通 Project 路由、跨材料 Plan、受治理 PARTIAL/失败、canonical answer、长回答和前端状态刷新。本结论不表示整个科研 Project Agent 已完成最终验收。

### Worker 11：受治理跨材料校验与有界 Reflection

状态：`LOCAL_UI_ACCEPTED`

- 在 Worker 10 的受治理 AUTO Direct/ReAct/Plan 路由之后增加确定性的完成校验，核对服务器识别的材料覆盖、实际工具结果、当前 ProjectVersion 的 Evidence provenance，以及 Plan 步骤和依赖是否真实完成；校验结果进入统一 run/canonical answer，不创建第二套生命周期。
- 跨材料结果使用结构化 `VERIFIED_CONSISTENT`、`VERIFIED_INCONSISTENT`、`UNRESOLVED` 和对应 reason code，不能把空结果、失败工具调用、旧 Evidence、跨 Project Evidence、未完成 Plan 步骤或模型文字判断提升为 VERIFIED。
- 当前唯一支持确定性 VERIFIED 的领域规则是用户明确要求的文件内容哈希/字节一致性。该规则只比较服务器当前 Evidence 中的文件哈希，能证明文件字节相同或不同，但不能证明论文、代码、实验配置在科研语义上等价或一致。
- 一般论文/代码/实验/BibTeX 的语义一致性尚无受信领域解析器和规则引擎，必须返回 `UNRESOLVED/PARTIAL` 并保留已获得结果；不能为了给出完整答案而伪造 VERIFIED。后续扩展必须为具体科研关系增加确定性规则、受信 provenance 和独立测试。
- 工具结果带执行 attempt。旧失败只有在同一工具和材料范围出现后续受信成功时才可恢复；Plan 旧失败只有经受信 `plan_repaired` 替换映射且替换步骤完成时才可恢复。无关成功、仅有模型说明或未完成替换不能掩盖失败。
- 失败或证据不足时最多触发一次权限不变、工具不变、预算不增加的有界 Reflection；Reflection 不自动创建或复制 Plan，不修改 `allowedTools`，不增加写入、命令、网络、模型、MCP、多 Agent 或 Pro 权限。再次不足时保持 PARTIAL/INSUFFICIENT，不循环重试。
- Worker 4 至 Worker 10 的 run identity、status/phase/outcome、canonical answer、ProjectVersion、Evidence、长期记忆、Candidate `NOT_APPLIED`、revision 与策略选择契约保持不变；未新增 Controller、migration 或前端。
- Worker 7 本地验收遗留的“Plan 长期记忆接入”和“会话候选自动沉淀”继续后置，Worker 11 未顺手实现，也不得据此宣称长期记忆闭环完整通过。
- 主对话独立验证：Worker 11 与 Worker 4-10 关键回归定向测试 200/200；`mvn -o -pl yanban-api -am test` 完整 reactor 685 项零失败、零错误、8 项条件跳过；`git diff --check` 通过。工程基线提交为 `1d488f0`。
- 2026-07-18 用户本地验收通过；相关收口修复提交为 `394ae75`。验收确认普通语义一致性不伪造 VERIFIED、确定性文件一致性保持受信边界、Reflection 有界、PARTIAL/Evidence/错误状态可解释。本结论不表示整个科研 Project Agent 已完成最终验收。

### Worker 12A：受控 Worker 契约层

状态：`CONTRACT_ENGINEERING_ACCEPTED / NOT_PRODUCTION_WIRED`

- 在 `yanban-core` 中定义 `WorkerTaskPacket`、`WorkerMaterialAssignment`、`WorkerBudget`、`WorkerResult`、`WorkerExecutionReceipt`、服务器 attestation、结果校验、任务覆盖率与跨材料差异报告，形成可序列化、可校验的受控 Worker 边界。
- task/result/receipt 的受信字段由服务器 authority 生成或验证；客户端反序列化不能伪造 server-only authority、attestation、receipt 或 VERIFIED Evidence。相对路径、ProjectVersion、用户/Project identity、工具与预算均为显式合同字段。
- Worker 仅允许 `READ_ONLY`；有效工具集必须是父任务策略、材料允许工具和 Worker task allowlist 的交集。Worker 不得扩大写入、命令、网络、模型、MCP、沙箱或 Pro 权限，不得直接应用 Candidate，也不得成为第二个 canonical answer 写入者。
- 父 Agent 负责聚合 Worker 结果、未覆盖材料和不一致项；合同层支持确定性去重、排序、覆盖计算与完整性校验，但不把一般科研语义推断自动提升为 VERIFIED。
- 代码提交 `7362fde`，合并提交 `4a66952`。独立合同测试 19/19、core 104/104；合并后完整 reactor 722 项零失败、零错误、8 项条件跳过，前端生产构建与 `git diff --check` 通过。
- 12A 不包含生产 executor、运行时注册、调度接线、Controller、前端或 migration，因此不得宣称多 Agent 已可用。Worker 12B 才允许在现有 Runtime 内接入一个受控、只读、跨材料场景，并必须继续父 Agent 单写者与权限不扩张原则。

### Worker 12B：受控双 Worker 只读生产接入

状态：`LOCAL_UI_ACCEPTED`

- 仅对服务器受信 AUTO 策略识别出的论文/LaTeX 与代码/配置跨材料请求启用，固定最多两个 Worker；普通 Direct/ReAct/Plan、显式策略和已有 planId 路径保持原语义。
- 调度器使用当前 ProjectVersion、manifest、文件哈希/大小、portable relative path、父任务只读工具策略与预算生成两个精确任务。论文 Worker 只允许 `project_latex_outline`；代码/配置 Worker 只允许 `project_code_symbols` 和按材料需要的 `project_experiment_summary`。
- Worker 执行继续使用既有模型端点，但工具、路径、文件数量、字节数、步骤、调用次数和 token 预算均由任务包限制；拒绝递归 Worker、越界路径、越权工具、重复调用和任何写入/命令/网络能力。
- 受控任务通过 `PlanRuntimeAdapter` 与 `PlanAgentService` 创建真实持久化 Plan，并沿用 `plan_created`、`plan_started`、`step_started`、`evidence`、`degraded`、`plan_completed` 事件和 canonical answer；没有第二套用户可见生命周期。
- dispatch 的非敏感 envelope 持久化在现有 `AgentPlan.rawPlanJson`，并绑定 schema、计划步骤和摘要。execute/retry/restart/async 恢复时重新读取受信 owner/session/project、当前 manifest 与工具策略；版本、哈希、大小、预算、工具或 envelope 任一不符即 fail-closed。普通 Plan 即使使用相同步骤名称，也不能冒充受控执行。
- Worker 自然语言摘要始终作为 `UNTRUSTED_WORKER_DATA` 注入父 Agent 用户数据区；固定系统策略只信任服务器重新验证的 ProjectVersion、路径、哈希、范围、parser 与 execution receipt。Worker 文本不能改变工具策略、声称已应用 Candidate 或把一般科研语义提升为 VERIFIED。
- 父 Agent 是唯一 canonical answer 写入者。Evidence 必须重新通过当前版本、文件哈希、范围、parser 与 provenance 校验；一般论文/代码语义差异继续保持 `UNRESOLVED/PARTIAL`，Candidate 继续保持 `NOT_APPLIED`。
- 主对话独立验证：`mvn -o -pl yanban-api -am test` 完整 reactor 共 1046 项，零失败、零错误、9 项条件跳过，其中 core 104、knowledge 34、paper 147、mcp 3、skills 1、api 757；前端 `npm run build` 和 `git diff --check` 通过。实现提交为 `e9998f4`。
- 2026-07-18 用户本地真实验收通过：AUTO 触发、双 Worker 精确路径和工具、父 Agent canonical answer、Evidence/PARTIAL、预算终止、错误边界与刷新恢复均已覆盖；收口提交为 `78a6d0b`。该结论不扩展到通用多 Agent。

### Worker 13：L2 持久化 Task Run、checkpoint 与重启恢复

状态：`LOCAL_ACCEPTED / WORKER_14_READY`

启动代码基线：`78a6d0b`；本次实际 Worker 冻结 baseline/HEAD 为 `5e1fad64907fe24fe9acfa79bbde4035af43e906`。

工程集成 baseline：`2ac4e5d090534b6f8870e63b271f9b2e02495c1b`；主对话已完成独立静态审查、定向回归、Worker 12 retry/restart/async 回归、V35/H2 迁移验证和完整 reactor，尚未宣称用户本地重启/恢复验收完成。

- 目标：将受治理的 Project Plan/受控 Worker 长任务从当前 L0/L1 边界提升为可重启恢复的 L2 托管，覆盖持久化 claim lease、heartbeat、checkpoint、幂等恢复、超时/卡死回收和确定性终态；前端断开或服务重启不能制造重复执行、重复 canonical answer 或权限扩张。
- 第一阶段仅接入现有 Project Plan 与 Worker 12 受控只读 Plan。普通同步 Chat/ReAct、Paper/Literature 业务任务和 Candidate 应用不在本 Worker 内迁移为新执行器；不得创建第二套 Plan、run、Evidence 或 canonical answer 生命周期。
- checkpoint 只保存服务端可重建、可审计的结构化状态：trusted user/project/session/plan identity、ProjectVersion、步骤与依赖、预算消耗、工具执行 receipt、版本化 Evidence 引用、失败/取消信息和剩余工作。不得保存 API key、模型内部对象、思维链、绝对路径、未净化文件内容或可伪造的 VERIFIED 状态。
- 恢复时必须重新校验用户与 Project 所有权、当前 ProjectVersion、文件 hash、Plan/envelope 完整性、当前 tool policy、剩余预算和取消状态；STALE、工具撤销、版本不符、lease 冲突、checkpoint 篡改或无法确定副作用时一律 fail-closed。
- lease/heartbeat 必须使用数据库时间和原子 compare-and-set/悲观锁语义；同一 run 同时最多一个 owner。过期 lease 可被受信实例重新认领，未过期 lease、终态 run 和已取消 run 不得被抢占或重复执行。
- 恢复只能从明确 checkpoint 边界继续。已登记且幂等的只读工具结果可以复用；未知完成状态不得猜测成功，必须标记需要安全重试或明确失败。canonical answer 只能写入一次，事件、Evidence 与步骤结果需可幂等重放。
- 允许文件所有权：`yanban-core` 的 Agent Task/Run 持久化模型与仓储、`yanban-api` 的 Agent lifecycle/Plan/受控 Worker L2 适配、必要的 Flyway V35 与 H2 镜像、对应测试和本计划文档。禁止修改 frontend、Project revision/Candidate 应用、Paper/Literature 生产业务、工具权限、外部网络、MCP、命令执行和 Pro 模式。
- migration 若确有必要，只允许新增向后兼容的 lease/checkpoint/recovery 字段与索引；不得删除、重命名或重写已有任务、Plan、事件和 Project 数据。必须同时提供 MySQL/H2 迁移验证、旧行默认语义和回滚/兼容说明。
- 必测矩阵：单实例 claim/renew/release、并发 claim 恰有一个成功、lease 过期重认领、服务重启恢复、checkpoint 篡改/缺失/STALE/跨用户/跨 Project fail-closed、取消与恢复竞争、预算不扩张、工具撤销、已完成步骤不重复、canonical answer exactly-once、事件/Evidence 幂等、Worker 12 retry/restart/async 回归及完整 reactor。
- 停止条件：需要开放写文件、命令、网络、密钥、真实模型依赖或跨模块大规模迁移；无法证明已有工具调用是否产生副作用；需要改变 Worker 10-12 的策略/工具/Evidence/Candidate 安全契约；或发现当前工作区存在另一开发 Worker。
- 实际接入沿用现有 `AgentPlan`、`AgentPlanStep`、`AgentPlanEvent`、Project envelope、Evidence ledger 与 canonical answer；Project Plan 和 Worker 12 受控只读 Plan 创建时标记 `L2_DURABLE`，普通 Plan 保持 `L1_PERSISTED`，未创建第二套 run 或终态生命周期。
- Flyway V35 仅向 `agent_plans` 增加 persistence、lease/fence/heartbeat、checkpoint/recovery 与 canonical answer 字段和可恢复 run 索引，向 `agent_plan_events` 增加幂等键及 `(plan_id, idempotency_key)` 唯一索引；旧行默认 L1，没有删除、重命名或重写既有数据，并提供 MySQL 脚本与 H2 测试镜像。
- claim/renew/release、取消、checkpoint、步骤/事件写入和确定性终态均通过数据库时间、悲观行锁、owner token 与单调 fence 约束；启动和 15 秒扫描器仅调度无有效 lease 或 lease 已过期的 `RUNNING` L2 run，旧 owner 在 fence 变化后不能继续提交。
- checkpoint 使用版本化、哈希覆盖的净化 JSON，绑定 trusted user/session/plan/project、当前 ProjectVersion、相对路径文件清单及 hash、Plan/envelope 摘要、allowedTools、原始预算上限与消耗、步骤结果 hash、工具/Evidence receipt；恢复重新校验这些边界，缺失、篡改、STALE、跨 identity、工具撤销、版本或预算不符均 fail-closed。
- 已完成步骤和已登记只读 receipt 在恢复时复用；未知完成状态只在剩余尝试预算内安全重试，否则以 `UNKNOWN_COMPLETION` 直接失败，不进入 repair/degrade 或再次 dispatch。事件使用语义幂等键，Evidence 按受信 observation 复用，canonical answer 在持久化 Plan 上 exactly-once 发布。
- 2026-07-19 P1 收口在每次 L2 step/batch dispatch 前，按持久化受信 `step_tool_observation` receipt 重新计算原始总工具预算并收紧当前策略；预算耗尽时在 runtime/受控 Worker 前 fail-closed，L2 batch 串行分配该全局预算。耗尽尝试的 durable retry 在 retry boundary 以确定性冲突拒绝，不重置尝试次数或进入 240 秒空转。
- 恢复 claim 后重新校验 session、Project、skill/tool policy、受控 envelope、模型端点、manifest 与 checkpoint；永久身份/权限失效通过当前 fence 终止为 `FAILED/RECOVERY_REJECTED`，数据库/HTTP 5xx 等瞬时基础设施错误仍释放为 `INTERRUPTED` 等待过期 lease 重排。Run/Workspace 的 L2 能力仅来自服务端持久化 `L2_DURABLE` 元数据，历史 AGENT_PLAN 行投影为 `L1_PERSISTED` 且不可 checkpoint/restart，不再依据 `projectId` 推断。
- 验证结果：P1 与 Worker 12/Plan 回归聚焦套件 106/106；`mvn -o -pl yanban-api -am test` 最终完整 reactor 1079 项、零失败、零错误、9 项条件跳过（core 111、knowledge 34、paper 147、mcp 3、skills 1、api 783）。生产 MySQL V35 未连接真实数据库执行，H2 从 V1 到 V35 的镜像迁移已通过；最终 `git diff --check` 通过。
- 2026-07-19 用户可见本地重启验收通过：在专用只读 Project `#36`、session `#111` 上由 UI 创建 `planId=61`，停机前为 `RUNNING/L2_DURABLE/CHECKPOINTED`、fence `1`、checkpoint version `2`；后端在有效 lease 内停止，lease 过期后新实例以不同 owner、fence `2` 重认领同一 plan，并完成为 `COMPLETED`。终态仅有 1 个 `plan_completed`、1 个 `step_project_evidence`、1 个 canonical answer hash，事件幂等键无重复，Candidate 保持 `NOT_APPLIED`；checkpoint 哈希匹配且敏感/绝对路径/思维链/伪造 VERIFIED 模式未命中。Worker 13 定向负向矩阵 25/25、完整离线 reactor 1079 项零失败零错误（9 项条件跳过：knowledge 1、api 8）通过；本地启动实际验证了生产 MySQL V35 从 34 到 35 的向后兼容新增迁移。状态更新为 `LOCAL_ACCEPTED / WORKER_14_READY`，但未启动 Worker 14。

### Worker 14：Docker Sandboxes 受治理执行沙箱

状态：`LOCAL_USER_JOURNEY_ACCEPTED / WORKER_15_READY`

- 真实执行沙箱必须由部署环境显式设置 `YANBAN_SANDBOX_ENABLED=true` 才能启用；默认关闭时不探测、不连接、不启动 Sandbox Provider，现有 Chat、ReAct、Plan、Worker、Candidate、Project revision、Paper/Literature 和基础设施服务保持原行为。
- `enabled=true` 只授予进入受治理 Sandbox Provider 的资格，不授予宿主机命令、Docker socket、任意网络、密钥或 Project 真实写入。Provider 或 broker 不可用时，沙箱任务必须以明确 `SANDBOX_UNAVAILABLE` fail-closed，禁止回退到宿主机 Shell、普通容器或跳过验证后声称成功。
- `required-at-startup=false` 时，沙箱不可用不得阻止 API 和其他服务启动；`required-at-startup=true` 仅供专用 Sandbox Worker 节点使用。API Compose 容器不得直接获得宿主机 `/dev/kvm`、Docker socket 或 `sbx` 管理权限，生产接入通过受限 Sandbox Broker/Worker 边界完成。
- 第一版资源默认单并发、2 CPU、4 GiB 内存、15 分钟执行超时和 20 MiB 输出上限，网络默认关闭；部署可进一步收紧，不得通过客户端请求或模型文本扩大。
- 已交付共享强类型 contract、独立低权限 Sandbox Broker/Worker、MySQL `yanban_sandbox` 持久状态机、API 事务性 outbox、异步 dispatch/poll/cancel、lease/fence/checkpoint、receipt/Event/Evidence/canonical exactly-once 投影，以及 Plan/Worker 13 重启恢复接线。API 容器不获得 Docker socket、`/dev/kvm`、`sbx` 或 Broker 数据库权限；生产 Broker 以 Linux 宿主机专用非 root systemd 服务运行，仅加入 `kvm` 组，并使用受信绝对路径的官方 `sbx`。
- 每次执行使用服务端 ProjectVersion、当前 skill/tool policy、命令 profile和预算重新校验；工作副本限制为受信 Project 相对路径，命令为结构化 argv，环境默认空、网络显式 deny-all，资源硬上限为单并发、2 CPU、4 GiB、15 分钟和 20 MiB。取消、超时、异常和重启统一进入可恢复 cleanup；cleanup 未确认不得发布成功 Evidence。Candidate 始终 `NOT_APPLIED`。
- receipt 与 canonical request digest 绑定 user/project/session/plan/step/fence/ProjectVersion/policy；同 key 异 digest 冲突。API receipt 审计与 fenced Plan 投影分离，确定性撤销/STALE fail-closed，瞬时投影失败仅重试 projection，不重新执行 Broker 任务。checkpoint 不保存密钥、原始环境、宿主绝对路径、无界输出或思维链。
- 离线工程验证已覆盖默认关闭、配置、迁移、Provider 不可用、路径/symlink、命令/env/network、资源/输出、取消/cleanup、并发幂等、lease/fence/reclaim、receipt 恶意响应、authority 撤销、Project STALE、Worker 13 恢复与 canonical exactly-once；完整 Maven reactor 与 `git diff --check` 已通过。
- 2026-07-20 已完成真实本地 Provider E2E 与完整用户旅程：真实登录、Project 普通问答、沙箱确认/拒绝、成功、非零失败、超时、取消、重复确认、刷新与重启恢复、沙箱关闭不影响普通问答、原始 stdout/stderr 与只读分析摘要展示均已实际验证。完整 Maven reactor 828 项零失败零错误、8 项条件跳过；独立定向后端 65 项、前端确认测试与 `vue-tsc`、`git diff --check` 均通过；临时服务已停止，专用 Provider 无残留 sandbox。

### Worker 15：Candidate 沙箱验证与显式应用闭环

状态：`DEPLOYED_ON_GITHUB_MAIN / POST_MVP_OPTIMIZATION_READY`

- 目标是连接已经交付的 Candidate、Worker 14 沙箱和 Worker 9 revision：Agent 生成的 Candidate 始终先保持 `NOT_APPLIED`，在受信 ProjectVersion 的沙箱工作副本中运行必要验证，向用户展示 diff、原始 stdout/stderr、执行事实与只读分析摘要；只有用户显式接受后，才沿 Worker 9 既有流程生成新的不可变 ProjectVersion。
- 第一版仅实现最短闭环：选择一个现有 Candidate、物化原始 ProjectVersion 与 Candidate 到隔离工作副本、执行服务端允许的编译/测试 profile、保存与 Candidate 绑定的验证 receipt、展示验证结果、接受或拒绝、接受后应用并保留历史/回滚/导出。不得自动应用，不得绕过用户确认，不得直接修改当前 ProjectVersion。
- 执行事实只来自 Broker 的 `status/exitCode/timedOut/provider`；原始输出可以交给无工具、无写权限、无网络、不能触发后续动作的只读分析 Agent 总结。摘要必须标明“基于输出、未独立验证”，不能覆盖执行事实，也不能自行写入 Project/Plan/Evidence 或启动后续执行。
- 沿用 Worker 14 的最小安全边界：功能默认关闭、用户确认、结构化命令与受信相对路径、默认禁网、资源/超时/输出上限、敏感环境变量不注入、取消与 cleanup。禁止为第一版新增 HMAC、复杂历史 provenance、攻击矩阵或其他会阻塞基本闭环的生产级过度保护。
- Candidate 内容或 ProjectVersion 变化后，旧验证结果不得用于新的应用；但不扩展为通用恶意代码判定系统。验证失败可以保留 Candidate 和诊断结果供用户继续修改，不得伪装为成功或自动降级为未验证应用。
- 所有权限定为 Candidate 验证编排、验证结果投影、Project 页面审查/确认交互及必要的向后兼容迁移和测试。不得修改 Worker 10-14 的工具权限、Evidence 语义、持久化恢复、Broker 隔离、Candidate `NOT_APPLIED` 默认状态和 Worker 9 revision/rollback/export 契约。
- 必测最小矩阵：验证成功、编译/测试失败、超时、取消、重复请求幂等、刷新/重启后结果可见、Candidate 或 ProjectVersion 变化使旧验证失效、拒绝不写 Project、接受后恰好生成一个新 ProjectVersion、回滚/导出不回归、沙箱关闭或不可用时普通问答与 Candidate 审查仍可用且验证明确不可用。
- 停止条件：需要任意宿主机命令、开放网络或密钥、真实模型/MCP 扩权、自动应用、跨 Worker 大范围重构、删除/覆盖用户文件、不可逆迁移或产品方向取舍。遇到这些情况必须回主对话确认。

- 完成记录：Candidate 沙箱验证与显式应用闭环已进入 `afafe73`；E2B Provider 由 `c751a42` 接入并通过 PR #101 合并，一键部署与 HTTP 确认修复分别进入 `8f680d1`、`db36b82`。E2B 是新增 Provider，原有 `docker-sbx` 保留；后续不得把业务层绑定到单一 Provider。

### Worker 16：统一入口、LLM Router 与长期记忆贯通

状态：`COMPLETED`

- 对应阶段 8。已合并 Project Chat 与 Plan 的用户入口，由 LLM Router 输出 `DIRECT / PLAN_EXECUTE` 结构化策略建议，Runtime 校验 capability、工具、预算与确认边界后执行；普通非 Project Chat 保留 ReAct。
- 全局用户偏好已贯通 DIRECT、Planner、步骤和最终总结；一次请求只能生成一条正式最终回答。
- 复用现有 Runtime、Plan、Task Run 和 ContextPackage，不创建第二套路由、会话、状态或 canonical answer 生命周期。
- 验收完成：Project 两种顶层策略路由、中文偏好、非法/不可用 Router 降级、普通问答不调用工具、Plan 沙箱确认与恢复、刷新后不重复；真实 E2B 执行与沙箱关闭边界均已覆盖。

### Worker 17：Project 页面与执行过程展示整理

状态：`COMPLETED`

- 对应阶段 9。只整理 Project 页面入口、标题栏、展开图标、Plan 执行卡和检查器标签。
- 冻结项目、会话、文件三区域平均分配页面高度的现有结构；禁止修改三区域 flex 比例、最小高度与总体布局算法。
- 验收完成：桌面/窄屏视觉回归、标题位置稳定、单输入入口、Plan 详情折叠、无重复标签、无文字溢出和沙箱确认卡均已通过；无后端、Provider、migration 或 Evidence 逻辑改动。

### Worker 18：Evidence 分层、工具自修复与状态语义

状态：`COMPLETED`

- 对应阶段 10。普通问答不受 Project Evidence 硬闸门阻断；Project 事实声明和外部影响操作继续使用对应校验。
- 工具错误以结构化、脱敏上下文反馈给模型，允许改变参数或方法的有界重试，禁止相同失败循环和不可恢复错误重试。
- 对齐 `PARTIAL / FAILED / CANCELLED / TIMED_OUT`，保留原始输出和明确标注的只读总结。
- 验收完成：参数修复、相同失败阻断、不可恢复错误停止、DIRECT Evidence 分层、沙箱确认/超时/取消和单 assistant 终态恢复均已通过自动测试与真实用户旅程；未开放 Project ReAct 或第二套重试系统。

### Worker 19：Plan-and-Execute、步骤内 ReAct 与事件触发 Reflection

状态：`COMPLETED`

- 对应阶段 11。只能在 Worker 16 至 Worker 18 验收并取得真实运行数据后启动，启动前由主对话重新冻结设计和预算。
- Planner 负责目标、依赖和成功条件；步骤内部使用 ReAct；Reflection 只由失败、结果不足、冲突或验证不通过触发。
- 必须有最大重规划次数、总预算、无进展检测和明确停止条件，不能宣称该范式保证所有任务成功。
- 验收完成：步骤预算与权限取交集，步骤内 ReAct 复用既有 Runtime；Reflection 仅事件触发且最多一次，只替换未执行工作。DIRECT、RepairContext、Reflection、no-progress、沙箱确认/失败和重启恢复均完成自动测试与真实旅程；沙箱失败保持 `FAILED`，不会被 Reflection 升格为成功。

### Worker 20：结果语义与证据分层底座

状态：`COMPLETED`

- 对应阶段 12。提供 `FinalSynthesisInput` 事实与证据底座，不提前实现总结 LLM 或 UI。
- 验收完成：三层结果、证据类别/状态、Project 版本失效、外部来源访问层级、最小代码执行材料和旧数据兼容均通过自动测试；真实 API -> Broker -> E2B Java 成功/失败/取消和 DIRECT 通过。
- 主审关闭了搜索摘要被错误标为 `SUPPORTED` 的 P1；最终只有显式 `OPENED` 来源为 `SUPPORTED`，`SEARCH_SUMMARY / UNKNOWN` 均为 `UNVERIFIED`。

### Worker 21：受控 Final Synthesis

状态：`COMPLETED`

- 对应阶段 13。服务端只读组装用户原始问题、必要步骤/代码内容、长期语言偏好与 Worker 20 的事实证据底座，输出唯一最终回答。
- 验收完成：无工具 Final Synthesis、权威事实包装、冲突模型输出回退、模型失败回退、单 handoff/canonical、并发恢复幂等、刷新重启稳定以及真实 E2B 成功/失败旅程均通过。主审关闭了 GET/list 重复调用模型及模型正文伪造 receipt 字段两项 P1。

### Worker 22：结果展示与状态降噪

状态：`COMPLETED`

- 对应阶段 14。只整理最终答复、Plan 卡和执行详情的用户展示，不改变三区域布局算法或后端权限。
- 验收完成：74 个 TypeScript Vitest、11 个 memory 测试、6 个 markdown 测试、`vue-tsc`、生产构建和 `git diff --check` 通过；真实桌面/窄屏旅程覆盖成功、失败、确认、取消、刷新和重启恢复。主审关闭一项过宽内部错误识别 P1，最终仅结构化失败 envelope 会被折叠。

### Worker 23：编码任务闭环与固定验收集

状态：`COMPLETED`

- 对应阶段 15。建立可重复的真实编码任务集，并在现有权限和状态边界内关闭最后的闭环缺陷。
- 验收完成：固定 14 项真实旅程覆盖 DIRECT、当前版本只读、顶层 Plan E2B 成功/失败、真实 `RepairContext` 在同一 step 改参成功、自然语言 Candidate、Candidate 成功/失败验证、拒绝/接受、恰好一个新 ProjectVersion、新版本 Evidence 隔离、rollback/export、确认/取消、Broker 不可用降级、刷新/API 重启以及桌面/390px 窄屏。
- 主审确认成功 Plan 的权威 receipt 为 `provider=e2b / status=SUCCEEDED / exitCode=0 / timedOut=false`，原始 stdout 为 `WORKER23_CANDIDATE_SUM=42`；唯一中文 canonical answer 与三层结果在刷新后稳定。真实工具修复先以 `VALIDATION_ERROR / retryable=true / remainingAttempts=1` 失败，再携带 `relativePaths` 改参成功，相同签名未循环且未触发 Reflection。
- 收口修复保持最小范围：纯 DIRECT 的显式 no-read 不再误生 Evidence；执行/修改请求仍保留 CODE 与版本边界；并列否定执行意图不再误触发沙箱；`.class` 不再被解析成 `.c`；无路径 sandbox follow-up 仅在 Plan goal 恰有一个显式材料路径时回退；重复的精确 Candidate Evidence 选择最新等价观测，不接受版本/hash/range/parser 不匹配。
- 独立门禁通过：完整 API reactor 1282 项零失败零错误、10 项条件跳过；Broker reactor、前端 74 项 Vitest、6 项 markdown、`vue-tsc`、生产构建和 `git diff --check` 均通过。固定流程记录于 `docs/process/project-coding-acceptance.md`。

### Worker 24：统一同会话上下文包与临时调试视图

状态：`IN_PROGRESS_USER_VALIDATION`

- 冻结范围：只处理同一个 session 内的跨轮次上下文，不实现跨 session 任务恢复、任务账本或旧会话自动继承。
- 目标：复用并扩展现有 `ContextPackage`，稳定组装当前用户原文、最近完整 canonical 轮次、较早滚动摘要、当前 ProjectVersion、有效 Evidence 引用和受治理长期偏好。
- 最近对话必须按完整轮次保留；滚动摘要只压缩更早语义，不能替代路径、版本、hash、Plan、Candidate、确认和执行事实。
- 前端增加临时、可折叠的上下文调试视图，重点展示实际内容、分区、来源、截断与丢弃原因。该视图仅用于开发调试，后续可隐藏或删除；不得展示密钥、环境变量、内部思维链或未授权内容。
- 用户验收中发现的执行闭环缺陷纳入本 Worker 收口：Broker 在完整执行生命周期内连续续租；区分 Provider 故障与用户代码编译/运行失败；将真实编译错误作为结构化 `RepairContext` 返回 LLM；LLM 只修改 `NOT_APPLIED` Candidate，并最多进行 2 次有界修复重试；成功后返回经过沙箱验证的代码，仍不得修改当前 Project。
- 使用 `Sort.java` 的同 session 多轮请求完成真实验收：能够引用上一轮目标文件，读取当前版本，创建未应用 Candidate，确认后执行；缺失依赖或无用 import 导致编译失败时反馈真实 stderr，修复并重试；最终只保留一个 canonical answer。
- 当前实现边界：Agent 不直接增删改 Project 文件；它可以在受治理 Candidate 中表达 `ADD / MODIFY / DELETE`，Candidate 必须先保持 `NOT_APPLIED`。沙箱只执行由服务端事件、依赖关系、ProjectVersion 和权限共同确认的 Candidate 覆盖层；只有用户显式接受后，既有 revision 流程才可以创建新 ProjectVersion。
- 当前已实现并待用户终验的修复包括：Broker 全执行周期连续续租；Provider 故障与用户代码编译/运行失败分层；E2B 用户程序真实非零退出与 stderr 保留；编译错误进入结构化 `RepairContext`；最多 2 次 Candidate 修复；修复步骤恢复 `project_propose_candidate` 但不得超出原始用户变更授权；成功后返回实际执行的完整 Candidate 代码；Project 输入框支持 Enter 发送、Shift+Enter 换行并兼容输入法组合态。
- 当前未关闭的验收缺口：真实请求仍曾出现 `INSUFFICIENT_EVIDENCE: Project step completed without a current authorized file observation.`。这表示文件相关步骤没有绑定当前 ProjectVersion 下、路径/hash/range 匹配的受权 `project_read_file` 观测，或 Planner/依赖链没有把该观测传给后续步骤。该错误必须通过重新读取、修正步骤依赖或有界重规划恢复，不能只返回内部错误，也不能使用旧 Evidence 放宽校验。
- Worker 24 只有在重启加载最新后端后，用全新 session/Plan 完成下列真实链路才可验收：读取当前 `Sort.java` -> 创建加入归并排序且不应用的 Candidate -> 确认后执行 -> 首次因坏 import 编译失败 -> 将真实 stderr 反馈给修复步骤 -> 创建移除坏 import 且保留归并排序的新 Candidate -> 再次执行成功 -> 返回实际验证过的完整代码；全过程 ProjectVersion 不变、仅一个 canonical answer、无旧文件偷跑。终态旧 Plan 不会自动使用后续代码修复，禁止用旧 Plan 复测冒充通过。
- 本 Worker 不定义 TaskFrame，不改变 Project 顶层 `DIRECT / PLAN_EXECUTE` 路由，不增加任意 shell、自动联网安装依赖、Provider 权限、migration 或 Candidate 自动应用能力。
- 必测：当前消息完整；最近多轮 canonical 原文顺序稳定；process/临时消息不污染；较早内容进入滚动摘要；预算截断保留完整轮次；ProjectVersion/Evidence/长期偏好分区正确；刷新和 API 重启内容稳定；DIRECT、Plan、Candidate、沙箱确认和单 canonical answer 不回归；桌面与窄屏调试视图可读且无溢出。

### Worker 25：最小 TaskFrame 与同会话指代解析

状态：`PLANNED_AFTER_WORKER_24`

- 基于 Worker 24 的统一上下文包解析目标、动作、对象、约束、交付物、歧义和来源轮次。
- 建立同一 session 内 active entity/target；“这份代码”“刚才那个结果”“继续”等只能绑定当前版本中唯一有效对象，否则询问用户。
- 区分命令、询问、确认、拒绝和澄清；不实现跨 session 任务恢复。

### Worker 26：统一 TaskFrame 消费与目标漂移约束

状态：`PLANNED_AFTER_WORKER_25`

- Router、Planner、Plan Step、Reflection 和 Final Synthesis 消费同一份已验证 TaskFrame。
- Planner 只拆解任务；步骤语义、工具权限、预算和成功条件必须一致。
- Repair/Reflection 只能修复参数、范围、步骤和方法，不得改变目标对象、语言、读写边界或交付物。

### Worker 27：短期工作记忆与滚动摘要收口

状态：`PLANNED_AFTER_WORKER_26`

- 在同一 session 内持久化滚动摘要、活跃任务、活跃对象和未完成要求，并在 API 重启后恢复。
- 摘要只在唯一 canonical answer 后更新，不能把等待确认、Candidate 或推断写成已完成事实。
- 长期记忆继续只承载用户确认的稳定偏好和事实，不承担当前任务状态，不跨 session 自动恢复任务。

### 后续独立阶段：确定性 Project 文件差异工具

状态：`PLANNED_AFTER_WORKER_27`

- 新增只读 `project_compare_files`，用于同一 ProjectVersion 或两个明确 ProjectVersion 中的文件比较。输入至少绑定左右路径、版本和 hash；输出结构化行级 diff hunk、增加/删除/修改范围、截断状态和续页游标。
- 大文件不得继续采用“分别摘要两个文件，再比较摘要”的方式冒充文件差异。首版先提供确定性行级 diff；后续可在不替代行级事实的前提下增加 symbol/AST 级比较。
- 大文件默认先返回变更范围、符号和必要上下文，LLM 只解释结构化 diff Evidence，不得从文件大小、文件名或两份独立摘要推断“主要差异”。
- 工具只读、无网络、无写入权限，不产生 Candidate；Evidence 必须绑定左右 ProjectVersion、path/hash 和实际比较范围。

### 后续独立阶段：沙箱输入、执行配置与 Provider 可观测性

状态：`PLANNED_AFTER_FILE_COMPARE`

- 保留 Java、Python、JavaScript 等单文件直接执行；增加受控 stdin、参数和测试夹具，使 `Scanner`、命令行参数和固定输入测试可以由结构化配置驱动，而不是任意 shell 文本。
- 统一区分 `MATERIALIZE / DEPENDENCY_INSTALL / BUILD / COMPILE / EXECUTE / COLLECT` 与 Provider 建箱、续租、网络、回执故障。每阶段保存权威 status、exitCode、timedOut、stdout/stderr 和诊断码。
- Provider 间歇失败、空 receipt、Broker 续租和恢复必须有可定位诊断；用户代码错误不得被归类为 Provider 故障，Provider 故障也不得触发 LLM 随意改代码。
- 继续坚持确认后执行、Candidate 覆盖层、不修改 Project、单 canonical answer 和有界修复。

### 后续独立阶段：联网依赖安装与完整项目执行

状态：`DEFERRED_AFTER_WORKER_27`

- 该阶段不属于 Worker 24，也不得插入或替代 Worker 25 至 Worker 27 的同会话任务理解与记忆治理顺序。
- 保留现有单文件 Java、Python、JavaScript 等直接执行能力；联网和构建工具是增量能力，不得把小文件强制升级为完整项目构建。
- LLM 只负责提出结构化执行方案；服务端根据当前 ProjectVersion 和受信文件识别 Maven、Gradle、`requirements.txt`、`pyproject.toml`、`package.json` 等项目清单，并校验准备命令、构建命令和运行命令。
- 用户确认后，将完整项目或最小必要文件集物化到临时 E2B 沙箱，按 `DEPENDENCY_INSTALL -> BUILD -> EXECUTE` 分阶段运行。允许联网获取依赖，但不得把本机 `.env`、数据库配置、密钥或无关文件传入沙箱。
- Maven、Gradle、pip、uv、Poetry、npm 等依赖安装必须由明确命令触发；上传项目或源码中的 `import` 本身不会自动下载依赖。
- 每个阶段分别保存 status、exitCode、timedOut、provider、stdout/stderr 和依赖清单来源。安装失败、编译失败和程序失败必须区分，不能被 LLM 推理覆盖。
- 失败时复用受控 `RepairContext`；只允许修改 Candidate 或执行方案，不自动修改 Project。需要新增依赖时必须能追溯到当前受信构建清单或用户明确要求。
- 首版不要求依赖缓存、任意 shell、复杂网络治理或多语言全覆盖；优先支持单文件直接执行、Maven 项目和 Python requirements/pyproject 项目。

推荐串行顺序：Worker 24 用户终验 -> Worker 25 TaskFrame -> Worker 26 全链路消费与防漂移 -> Worker 27 短期工作记忆 -> 确定性文件差异工具 -> 沙箱输入与 Provider 可观测性 -> 联网依赖安装与完整项目执行 -> 固定真实用户验收矩阵。任何阶段均不得跳过上一阶段的用户验收门。

## 16. 审查与停止条件

主对话发现以下任一情况必须停止自动推进：

- 当前工作区存在另一开发任务正在运行。
- 变更文件超出 Worker 所有权。
- 基线 commit 与启动记录不一致。
- 冻结契约被改变。
- 测试依赖真实外部模型才能判断。
- 发现 P0 安全问题。
- 需要 migration 但方案未审查。
- 需要开放写文件、命令、网络或密钥权限。
- Worker 把 mock、预置数据或自然语言回答伪装成真实完成。
- 无法确认用户现有修改来源。

## 17. 第一版完成标准

1. 能上传并版本化完整文件夹。
2. 能结构化理解论文、代码、实验和文献。
3. Project 由 LLM Router 在 `DIRECT / PLAN_EXECUTE` 中提出合适策略，Plan 步骤内部可使用有界 ReAct；普通非 Project Chat 保留兼容 ReAct，并统一由 Runtime 在现有权限和预算内校验执行。
4. 结论均带可追溯证据和文件版本。
5. 能生成论文与代码 Candidate ChangeSet。
6. 候选修改在沙箱中通过必要验证。
7. 用户可以逐条接受、拒绝和回滚。
8. 能生成新的完整 Project 版本并导出。
9. 长任务能够暂停、恢复、取消和明确失败。
10. 不发生越权读取、未授权写入、虚假实验或虚假完成。

阶段 8 至阶段 15 已完成两轮体验、可靠性、结果语义和编码闭环收口。真实使用进一步暴露了同一 session 内上下文组装、任务理解、跨轮次指代和短期工作记忆缺口，后续按 Worker 24 至 Worker 27 串行收口。Pro 模式的自主研究循环仍不属于本版，必须另行规划、冻结权限和验收矩阵后才能启动。
