# PaperAgent V2 后续优化主对话交接

更新时间：2026-08-03

正式仓库：`C:\java_file\private_helper_Agent\paperagent_v2_product`

## 1. 本文用途

本文给新的主对话使用，目标是让后续开发直接从当前真实状态继续，重点补齐：

1. V2 上下文治理与语义压缩；
2. V2 会话摘要和长期记忆闭环；
3. V2 产品 Skills 与受控外部工具边界；
4. 可持久化、可恢复、受权限约束的子 Agent；
5. 建立在上述基础上的论文质量工具和复杂工作流。

本文不是新的实现合同。开始每个能力前，仍须创建独立 Issue，冻结范围、基础提交、所有权路径、验收命令和非目标。

## 2. 新主对话开始时必须先做

1. 只在正式仓库工作，不要把旧目录 `paperagent_v2` 当成产品基线。
2. 完整阅读：
   - `AGENTS.md`
   - `docs/当前有效/架构设计/v2-agent-core-integration.md`
   - `docs/README.md`
   - `docs/当前有效/开发流程/verification-matrix.md`
   - `docs/当前有效/架构设计/MIGRATION_MAP.md`
   - `docs/当前有效/文档治理/document-classification-20260803.md`
   - `docs/当前有效/文档治理/pending-capability-assessment-20260803.md`
   - `docs/历史归档/0708阶段/README.md`
   - 本文
3. 执行只读 Git 核验：当前分支、HEAD、`git status -sb`、远端地址和 `origin/main`。先 `fetch`，不能只相信本文中的提交号。
4. 检查是否有其他对话正在使用本地前端、后端或 Broker。未经协调不要重启或占用端口。
5. 不读取、输出、复制或提交 `.env`、Key、凭据、用户文件、本地验收数据和 Provider 原始响应。
6. 保留所有未提交工作；不能用 reset、checkout 或清理命令处理不属于当前 Issue 的改动。

本文创建前的本地核验基线为：

- 分支：`main`
- HEAD：`25e9ac82f34d165cc2dcd05a0947f4638d302fab`
- 本地 `origin/main`：同一提交
- 工作区：干净
- 正式远端：`https://github.com/Ethan-WOKO/paperagent_v2_product.git`

该提交号只是交接快照。新对话必须重新 fetch 和核验；网络失败时不能声称远端仍未变化。

本次文档整理时还完成了已合并分支清理：

- `codex/frontend-layout-polish-20260802`：PR #132 已合并，本地和 `origin` 分支已删除；
- `codex/issue-130-sandbox-broker-build`：PR #131 已合并，本地和 `origin` 分支已删除；
- 已合并且工作区干净的历史 product worktree 已全部移除；遗留的 `frontend/node_modules` 空壳目录也已清理；
- `upstream/main` 是旧仓库引用，不属于产品仓库的待清理功能分支，仍保留。

整理后正式仓库只保留 `main` worktree，`origin` 也没有未合并或状态不确定的功能分支。文档整理本身尚未因此自动提交或推送；接手时仍应以 `git status` 的实时结果为准。

## 3. 不得改变的产品和架构决定

### 3.1 V2 执行模型

- 顶层路由只有 `DIRECT` 和 `PERSISTENT_PLAN_EXECUTE`。
- Project 文件、Candidate、revision、RAG、联网、工具、沙箱、编译、测试、验证和任何持久化修改都必须进入持久化 Plan。
- DIRECT 只能回答完全不需要当前可变 Project 状态或外部能力的问题，结果也必须持久化。
- Planner 由模型生成 `requirements` 和路由，后端做确定性要求审计。暂时不要增加大批硬编码语义分类规则。
- TaskFrame 冻结目标、对象、交付物、约束、ProjectVersion 和权限档位。
- Plan 可以 replan；已接受的 Step Result、Receipt 和其他权威事实只能追加，不能改写。
- Reflection 负责语义判断；后端只审计 Receipt、Step、Workspace、Candidate、权限、退出码等确定性事实。
- 最终回答由 Final Synthesis 根据全部已接受 Step Result 生成并持久化，不能拿最后一个工具输出冒充最终回答。

### 3.2 Project 修改边界

- 文件修改只能发生在隔离 Workspace。
- Candidate 是待审查产物，不是已经应用的 Project 修改。
- 用户确认前不得修改原 ProjectVersion。
- Agent 自动 E2B 验证与“创建新版本前的确认验证”是两种状态；自动验证不能替代 Candidate apply gate。
- Candidate apply 后，页面必须展示已创建的新 revision，不能继续显示“原项目尚未修改”的过期状态。

### 3.3 V1/V2 共存边界

- Project 页面保持 V2-only，不恢复 Project 内的 V1 输入框、V1/V2 切换或 Project V1 WebSocket。
- 工作区 `/chat` 及其所需的旧 Agent/Plan 编排已经按用户决定恢复，当前必须保留。
- `/chat` 的旧编排不是 V2 运行时的设计模板。不得把旧 Planner、CompletionVerifier、固定工具链或旧 PlanAgentService 复制进 V2。
- 不能根据类名含 `Agent`、`Plan`、`V1` 就删除代码。会话、消息、摘要、记忆、RAG、Provider、Project、Candidate、论文、文献和 E2B 能力仍可能是共享产品能力。
- 不删除旧数据库记录或迁移；代码退役不自动授权数据清理。

### 3.4 工具接入边界

- V2 工具通过框架无关的 `V2ProductToolCatalog`、严格 JSON Schema、能力元数据和产品适配器注册，不使用 LangChain4j 注解作为 V2 权威注册表。
- LangChain4j 可以继续作为模型或格式适配组件，但不能成为 Plan、权限、持久化、上下文或工具执行的事实源。
- 用户希望以后一个 Issue 可以包含 4–5 个紧密相关工具，但只有在它们共享同一权限、读写副作用、执行目标和验证边界时才允许成组。

### 3.5 Skills 与 MCP 边界

- 产品 Skill 是“有版本的工作方式说明 + V2 工具范围”，不是第二套工具注册表，也不是权限来源。
- 当前 `skillId` 和 prompt 已进入 V2 intake，但旧 `allowed_tools` 尚未约束 V2 autonomous catalog；在完成 V2 Skill 合同前，不能宣称 Skill 已形成完整权限闭环。
- MCP 是外部工具传输协议，不是产品能力本身。现有 MCP 注册进入 legacy `ToolRegistry`，不能直接视为 V2 工具。
- Project V2 不允许使用 filesystem MCP 读取服务器路径或用户本地路径。用户文件必须先上传，Project 内容只能通过冻结 ProjectVersion 和隔离 Workspace 工具读取。
- 可以退役 filesystem MCP 的产品入口和旧 Skill 引用，但必须先检查 `/chat` 调用关系；不要因此删除通用 `yanban-mcp` 通信模块，它以后仍可服务经过批准的 GitHub、文献或其他外部只读能力。
- 外部 MCP 若接入 V2，必须映射为 V2 `ToolDescriptor`，经过 EffectIntent、确定性权限审计、标准 Receipt、输出预算、恢复和必要确认，不能把动态发现到的任意工具直接暴露给模型。

## 4. 当前已经完成的能力

### 4.1 V2 主链路

当前链路能够完成：

```text
自然语言问题
  -> DIRECT 或持久化 Plan
  -> 动态选择工具
  -> 隔离 Workspace
  -> Candidate / E2B / 其他 Receipt
  -> Reflection / 必要时 replan
  -> 不可改写的 Step Result
  -> Final Synthesis
  -> 持久化结果和刷新恢复
```

已经修复过的重要缺陷包括：

- DIRECT 错误地依赖 Project 是否存在，而不是当前请求是否需要 Project/工具；
- DIRECT 结果未稳定持久化；
- 模型没有调用工具时错误重放上一次成功工具；
- 纯模型分析结果被丢弃并触发 `REFLECTION_NO_PROGRESS`；
- Step 完成后没有把模型提出的 Step Result 持久化为事实；
- E2B 异步恢复后 Workspace provider 丢失；
- 已成功的沙箱 Receipt 未被 Reflection 当作当前 Step 的完成证据；
- Candidate 已存在时整条任务致命失败；
- 失败任务页面仍显示正在执行；
- Candidate 创建新版本后最终结果仍显示旧的待确认提示。

当前结果模型包含 `StepResultDecision`、持久化 Step Result、Receipt 证据绑定、Reflection 审计和基于已接受结果的最终综合。后续不能退回“工具输出就是步骤结果”或“最后一个工具输出就是最终回答”的做法。

### 4.2 Project 页面和 Candidate

- Project 页面已有持久化 V2 任务列表。
- 一条用户问题对应一条最终、等待确认或失败结果。
- 执行过程默认折叠，可展开查看。
- 新任务刷新后可以恢复；未对旧测试历史做日期分界或回填。
- Candidate 面板分别显示 Agent 自动验证和创建新版本前的确认验证。
- 明确确认并通过 apply gate 后才创建新 Project revision。

### 4.3 当前 V2 工具目录

当前统一目录共有 17 个工具：

1. `literature.search`
2. `project.read`
3. `project.search`
4. `project.document.extract`
5. `project.spreadsheet.inspect`
6. `project.latex.outline`
7. `project.bibtex.audit`
8. `project.latex.crossref.audit`
9. `project.latex.float.audit`
10. `project.latex.protected.inventory`
11. `project.paper.acronym.audit`
12. `project.paper.language.stats`
13. `project.code.symbols`
14. `project.experiment.summary`
15. `project.cross-material.search`
16. `project.candidate.compose`
17. `sandbox.execute`

PDF、DOCX 和 XLSX 已能作为受限、不可变的 Project 二进制资产进入同一个 ProjectVersion/Workspace 事实模型。现有解析工具不执行 OCR、宏、公式或外部链接。

现有工具已经能完成有边界的论文—代码闭环：读取论文/报告与代码，运行或测试代码，使用真实 Receipt 对照文档结论，在隔离 Workspace 中修改已有 UTF-8 代码或 `.tex/.md/.txt` 文本，再次验证并生成 Candidate。该能力不是无限制的全文自动改写：PDF、DOCX、XLSX 当前只读；一次 Candidate 最多修改 4 个已有文本文件，单文件不超过 64 KiB；图片内容理解、OCR、LaTeX 渲染和大项目上下文仍是后续能力。

### 4.4 部署和运行基线

- 生产仓库已经从旧仓库切换到 `paperagent_v2_product`，用户已确认上线正常。
- Compose 项目名和持久化卷保持不变；升级没有通过删除 volume 或重建数据库清空数据。
- API 和 Sandbox Broker 的 Dockerfile 都已复制 V2 Maven 模块，不能回退该修复。
- E2B 模板包含 Java 17、Maven、Python 3、gcc/g++ 和 git。
- Python 当前只保证基础解释器和标准库；不能假设 numpy、pandas、matplotlib 等科学依赖已安装，也没有通用 Python 依赖声明协议。
- 本地前端、后端和 Broker 在本次交接前已按用户要求关闭。生产服务是另一套环境，不能与本地状态混为一谈。

## 5. 记忆与上下文的真实现状

这里最容易误判。仓库已有不少能力，但 V2 闭环并未完成。

### 5.1 已存在并可复用的基础

- `AgentContextBuilder` 已能：
  - 读取和规范化会话消息；
  - 对 Project V2 使用完整的 canonical user/assistant turn；
  - 注入 runtime identity guard、会话摘要、长期记忆、RAG、工具证据和当前 Project 状态；
  - 按消息数量和字符预算裁剪；
  - 记录 section 和 dropped-item 元数据。
- `agent_session_summaries` 及 `AgentSessionSummaryService` 已存在。
- `agent_long_term_memories`、用户 CRUD、确认/拒绝、软删除、纠正链、过期和 ProjectVersion 绑定已存在。
- `/settings/memory` 页面已存在。
- 长期记忆检索只注入当前用户拥有、ACTIVE、已确认、未失效、满足安全和来源治理的记录；默认最多 5 条、1600 字符。
- `agent_context_snapshots`、查询 API 和 Project 上下文查看能力已有基础实现。
- V2 intake 确实会读取当前 session summary 和用户级长期记忆，并调用 `AgentContextBuilder`。

### 5.2 V2 尚未完成的部分

1. **V2 不会更新自己的会话摘要。** 代码中只有旧 `AgentService` 在成功后调用 `AgentSessionSummaryService.upsert(...)`；V2 DIRECT、Plan 成功、等待确认和失败结果没有统一推进 summary coverage。
2. **V2 没有保存自己的上下文快照。** `AgentContextSnapshotService.saveSnapshot(...)` 目前也只由旧 `AgentService` 调用。
3. **当前主要是窗口裁剪，不是真正的语义压缩。** V2 intake 固定最近 12 条消息和 12000 字符；传给 adaptive execution 的单条上下文还会被直接截到 2000 字符。
4. **预算以字符估算为主。** 没有统一的 provider/model token window、输出保留、工具 schema 成本和不同调用阶段预算模型。
5. **恢复时辅助上下文可能漂移。** V2 resume 会重新读取当前 summary 和 memory，再重建上下文。若同一持久化 turn 运行期间记忆被修改，恢复后的模型上下文可能与最初不同。必须明确“冻结本轮上下文 revision”还是“允许显式追加新 revision”，不能静默变化。
6. **Project-scoped memory 还没有接入当前 Agent 调用链。** 检索服务中的 Project 方法明确标记为尚未接线。
7. **没有自动记忆提取闭环。** 当前主要依靠用户在设置页创建、确认和纠正。不能把模型自动抽取直接写成 ACTIVE 权威记忆。
8. **摘要和记忆仍可能污染事实层。** 需要正式规定它们只是有来源的辅助上下文，不得覆盖 TaskFrame、Plan、Step Result、Receipt、Candidate、Workspace 或 ProjectVersion。
9. **执行上下文没有统一 ledger。** Planner、Step 决策、Reflection 和 Final Synthesis 各自有局部边界与上限，但还没有一个可解释、可重放的 V2 Context Revision 贯穿整轮执行。

相关代码入口：

- `yanban-api/src/main/java/com/yanban/api/agent/AgentContextBuilder.java`
- `yanban-api/src/main/java/com/yanban/api/agent/AgentService.java`
- `yanban-api/src/main/java/com/yanban/api/agent/v2/intake/V2NaturalLanguageTurnService.java`
- `yanban-api/src/main/java/com/yanban/api/memory/LongTermMemoryService.java`
- `yanban-api/src/main/java/com/yanban/api/memory/LongTermMemoryRetrievalService.java`
- `yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/reflection/ReflectionContext.java`
- `yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveExecutionCoordinator.java`
- `yanban-api/src/main/java/com/yanban/api/agent/v2/adaptive/V2AdaptiveFinalSynthesisService.java`

## 6. 子 Agent 的真实现状

V2 当前没有真正的子 Agent 系统。

- `AgentTask` 和 task registry 是论文/文献任务的统一状态镜像，不是父子 Agent 调度模型。
- 旧 `PlanAgentService` 的提示词里有 “isolated worker sub-agent”，但它只是旧 Plan 单步模型角色，不具备独立持久化任务、权限委派、租约、取消、并发或结果接受协议。
- V2 当前仍由一个主 Plan 的 Step 循环串行推进。

因此，不能通过多调用几次模型或复制旧 worker prompt 来宣称已实现子 Agent。

## 7. 推荐的后续实施顺序

### Issue A：冻结 V2 Context Assembly 合同

先设计和测试，不急着做自动摘要或子 Agent。

目标：

- 盘点 Planner、Step model、Reflection、Replan、Final Synthesis 的所有输入来源和预算。
- 定义 `V2ContextRevision` 或等价稳定合同，记录 section 类型、来源引用、版本、摘要/截断状态、预算和 digest。
- 明确哪些是不可压缩权威事实，哪些是可压缩辅助上下文。
- 明确同一 turn 恢复时使用原 Context Revision，还是通过显式 revision 追加变化。
- 只保存安全元数据和必要的受控文本；默认不保存完整 prompt、Key、用户文件或 Provider 正文。

必须永远保留或通过稳定引用加载：

- TaskFrame；
- 当前 Plan/revision 和 active Step；
- 已接受 Step Result；
- 完成判断需要的 Receipt；
- Candidate、Workspace、Artifact 引用；
- 冻结 ProjectVersion 和权限档位；
- 当前用户请求。

可以压缩：

- 已被摘要覆盖的早期普通对话；
- 与当前 Step 无关的旧工具观察；
- 低相关 RAG 和长期记忆；
- 已有权威持久化引用的重复展示文本。

### Issue B：V2 上下文快照与确定性预算

目标：

- 将现有 context snapshot 能力接入 V2，而不是创建第二套调试表。
- 为一次 turn 的初始构建和后续 revision 设计明确的追加模型；不能悄悄覆盖同一快照。
- 使用 model/provider-aware token 估算；保留输出预算、工具 schema 预算和格式修复余量。
- 每个 section 给出 included/dropped/truncated 原因和来源引用。
- 删除单纯 `substring(0, 2000)` 造成的结构截断风险，结构化数据必须按字段和项目边界裁剪。
- 日志只记录阶段、数量、预算、digest 和错误来源，不记录正文。

### Issue C：V2 滚动摘要与语义压缩

目标：

- 复用 `agent_session_summaries`，不要新建重复 summary 表。
- 从持久化的完整终态 turn 增量更新摘要，覆盖 DIRECT、Plan 成功、等待确认和失败；失败只能记录状态与未完成事项，不能变成已验证事实。
- 用 `covered_message_id` 或更强的 CAS/version 机制避免并发覆盖。
- 摘要生成失败不能使主任务失败；恢复时必须知道摘要覆盖范围。
- 摘要只承载对话语义，不承载不可改写的执行事实。执行事实继续由 Step Result、Receipt 和 Artifact 保存。
- 为长对话加入分段压缩和重建测试，验证近期追问、刷新和服务重启。

### Issue D：长期记忆闭环

先复用当前 CRUD 和 governed retrieval，再补缺口：

- 接通 Project-scoped memory，但必须由服务端解析 Project 所有权和当前 ProjectVersion。
- 自动提取只能生成 `MemoryProposal` 或 UNCONFIRMED 记录；用户确认后才能参与默认检索。
- 去重、纠正、冲突、过期、版本失效和来源链必须可见。
- Project 文件、RAG、工具输出中的指令不能升级成用户全局记忆。
- 记忆命中必须写入 V2 context snapshot，并说明选中和省略原因。
- 用户删除或纠正后，未来 turn 立即停止使用；对已运行中的 frozen context 如何处理必须有明确规则。

### Issue E：V2 产品 Skill 合同

先统一现有 Skill 与 V2 tool catalog，不新增执行器：

- 区分产品 Skill、Codex Skill 和 V2 产品工具；
- 为 Skill 定义稳定 revision/digest，并将所选 revision 冻结到 turn/Plan 上；
- 将 `allowed_tools` 改为受校验的 V2 ToolId 或产品 alias，不接受 legacy/MCP 工具名冒充 V2 能力；
- Planner、Step、Reflection、Final Synthesis 和恢复使用同一份 Skill 快照；
- 后端只暴露“产品 catalog ∩ 用户权限 ∩ Skill 范围”的工具，提示词不能扩大权限；
- 先迁移一个只使用现有 17 个工具的论文或代码审查 Skill，验证禁用、恢复和工具拒绝路径。

### Issue F：受控只读 MCP 试点（有真实用例时才启动）

MCP 不是后续主链的必做前置。只有出现现有 17 个工具无法覆盖的明确外部工作流时，才选择一个只读 server/tool 集合接入：

- 持久化 server/tool manifest revision、schema digest 和本次 turn 可见工具；
- 外部工具映射到 V2 `ToolDescriptor` 与能力元数据；
- 所有调用进入持久化 Plan，产生标准 Receipt，并限制返回大小；
- 网络、用户账号、凭据、提示注入、超时、取消和恢复分别测试；
- 第一版不接 filesystem MCP，不开放任意 Server，不允许写 GitHub 或其他外部状态。

如果没有真实 MCP 用例，跳过 Issue F，直接进入 Issue G。

### Issue G：只读子 Agent 基础合同

子 Agent 应建立在稳定的 Context Revision、Step Result 和 Receipt 之上。第一版只做只读，不允许 Candidate 写入或嵌套子 Agent。

建议最小合同包含：

- `SubAgentTaskId`、parent Plan/Step/turn；
- 冻结的子目标、交付物、完成条件；
- 输入 Context Revision 引用；
- 不超过父任务的能力/工具 allowlist；
- ProjectVersion、Workspace 读取权限和明确禁止的副作用；
- 最大模型调用、工具调用、token、时间、并发和重试预算；
- `PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED` 持久化状态；
- 租约/fencing、取消、超时和恢复；
- 结构化结果、证据 Receipt、来源和 digest；
- 父 Agent 的接受/拒绝记录。

关键不变量：

- 子 Agent 不能扩大父 Agent 权限。
- 子 Agent 不能修改原 ProjectVersion。
- 第一版不能生成或应用 Candidate。
- 子 Agent 的成功结果只是提交给父 Agent 的提案；经过确定性证据审计和父 Step 接受后，才成为 completed fact。
- 主 Agent 只接收有长度上限的结构化结果和证据引用，不接收完整子 Agent transcript；否则无法达到降低主上下文的目标。
- 每次 effect 必须支持 exact replay，服务重启不能重复执行同一子任务。
- 并发完成顺序不能改变最终事实排序，应使用 Plan/子任务定义顺序或明确稳定排序。

第一批适合的子 Agent 场景：

- 分章节只读论文结构/语言审查；
- 分文件代码结构和实验配置审查；
- 论文、代码、实验报告三个材料的并行事实提取；
- 多组文献的独立只读筛选与有来源摘要。

暂不适合第一版：并发改文件、自动合并多个 Candidate、子 Agent 自行联网扩权、递归子 Agent、自动 apply。

### Issue H：子 Agent 调度和前端展示

在基础合同通过后再增加：

- 持久化队列、有限并发、lease、取消和恢复；
- 父 Plan 等待多个子任务时的状态；
- 部分失败、超时和降级策略；
- 页面在“查看执行过程”下折叠展示子任务，不新增第二套任务首页；
- 主 Agent 只在结果齐备或达到明确终止条件时继续 Reflection。

### 后续论文能力

基础设施稳定后，再按共同权限和执行边界分批增加：

- 图片/图表内容理解以及正文—图表一致性审查；
- LaTeX 编译、日志结构化和渲染差异；
- Python 科学计算依赖与可复现环境合同；
- DOI/citation 元数据核验和有来源网络证据；
- 语法、逻辑、术语和章节一致性审查；
- 实验复现、图表数据与代码输出一致性；
- 论文、代码、报告和实验产物的多材料综合。

不要添加与现有 17 个工具功能重叠、仅改名字的新工具。工具执行器先做直接合同测试，再进入完整 Agent 链路。

## 8. 上下文层级建议

建议新 V2 上下文按以下层级组装：

```text
不可裁剪的运行合同
  1. safety / identity / authority
  2. TaskFrame + current Plan/revision + active Step
  3. 当前请求

不可改写的事实引用
  4. accepted Step Results
  5. required Receipts
  6. ProjectVersion / Workspace / Candidate / Artifact refs

可预算的辅助上下文
  7. frozen session summary revision
  8. governed memory hits
  9. RAG/evidence snippets
  10. recent canonical conversation turns
  11. relevant non-authoritative tool observations
```

压缩的目标是减少重复文本，不是压缩事实源。模型看到摘要后仍应能通过稳定引用获得完成当前 Step 所需的原始权威事实。

## 9. 测试原则和最小不变量

先测试合同和状态机，再做浏览器真实验收。不要依靠不断模拟用户来偶然发现架构缺口。

### 上下文与摘要

- 超长会话不超过模型输入预算，当前用户请求不丢失。
- tool call/result 保持协议配对，结构化 section 不会被截成非法 JSON。
- 同一 turn 刷新、恢复和服务重启得到同一 Context Revision。
- 摘要覆盖范围单调推进，并发更新不会倒退或覆盖新摘要。
- 摘要错误不能修改 accepted Step Result 或制造成功事实。
- DIRECT、Plan 成功、等待确认、失败都形成可恢复的一问一结果。

### 长期记忆

- 只检索当前用户、允许 scope、ACTIVE、已确认、未过期、未失效记录。
- 删除、拒绝、纠正和 ProjectVersion 变化立即影响未来检索。
- 敏感信息、绝对路径和不可信 Project/tool 指令不能进入记忆上下文。
- 相同请求、相同 memory revision 的命中排序稳定。

### 子 Agent

- 权限和工具集合只能收窄，不能扩大。
- 同一 subtask 重放不会启动第二个执行。
- lease 过期、接管、取消、超时和进程重启都有确定终态。
- 一个子任务失败不会把父页面留在假 RUNNING。
- 父 Agent 只能接受有来源、通过审计的子结果。
- 并发子结果按稳定顺序注入，不能因返回时序改变最终答案。
- 子结果压缩后仍保留证据引用和未解决问题。

每个 Issue 按 `docs/当前有效/开发流程/verification-matrix.md` 选择 focused tests。只有直接影响 API/UI 或到了阶段验收，才启动前后端、Broker 和真实 Provider/E2B。不要无理由运行整仓、全部 RAG、全部论文润色或全部浏览器流程。

## 10. 已知风险与不要顺手处理的事项

- GitHub Actions 曾出现一次并发测试偶发失败，重跑通过。除非它在当前基础提交上稳定复现，否则不要夹在记忆/上下文/子 Agent Issue 中顺手修改。
- `docs/历史归档/历史评测/报告/v2-user-journey-stabilization-20260729.md` 末尾记录的 Candidate 自动验证展示缺口是历史记录；后续 UI 已增加“Agent 自动验证”和“创建新版本前的确认验证”，不要按旧报告重复修复。
- 旧设计文档中的 `AgentContextBuilder`、summary、memory 主要起源于成熟 `/chat` 链路。复用时必须增加 V2 的持久化、恢复和事实边界，不能直接宣称 V2 已完成。
- 不要在本轮基础设施工作中顺手重做前端布局。当前布局已经单独打磨并合入 main。
- 不要删除已恢复的 `/chat`，不要恢复 Project V1 页面。
- 不要改变 Candidate apply 权限，不要允许 Agent 自动创建 Project revision。
- 不要新增第二套 ProjectVersion、Workspace、Candidate、Receipt、memory 或 session summary 表，除非设计审查证明现有模型无法表达且获得明确批准。
- 不要把提示词当作权限控制；提示词只引导模型，后端持久化事实和确定性审计才是权限边界。

## 11. Git、并行开发和服务纪律

- 一个 Issue 对应一个独立 worktree、`codex/` 分支和 Draft PR。
- 子对话可以实现、测试、提交、推送和创建 Draft PR，但不得自行合并；主对话负责合同、审查、合并顺序和阶段验收。
- 新 Issue 必须从 fetch 后的最新 `origin/main` 创建，不从过期本地分支继续堆改动。
- 其他对话的未合并前端或后端分支不能清理；只有确认已合并且无人使用的分支才可删除。
- 当另一个对话用正式根目录后端调试前端时，当前对话不得擅自重启后端或 Broker。
- 生产已经有真实用户数据。部署更新必须保留 `.env`、Compose 项目名和持久化卷，先备份再升级，绝不使用 `down -v` 或相当的数据清理命令。

## 12. 给新主对话的建议首个任务

不要直接实现子 Agent。先创建“V2 Context Assembly 与恢复不变量审计”Issue，只做以下成果：

1. 画清 Planner、Step、Reflection、Replan、Final Synthesis 的上下文数据流；
2. 列出每个阶段当前的输入上限、截断方式、事实来源和恢复方式；
3. 冻结 V2 Context Revision/ledger 合同及非目标；
4. 给出如何复用现有 summary、memory 和 snapshot 表的迁移结论；
5. 用纯单元/仓储测试证明恢复、预算和事实不可改写不变量；
6. 审核通过后再进入实现 Issue。

这个顺序能同时为记忆、语义压缩和子 Agent 提供共同基础，避免分别做三套上下文模型。

## 13. 参考文档

- `docs/当前有效/文档治理/document-classification-20260803.md`：文档的中文分类与适用状态。
- `docs/当前有效/文档治理/pending-capability-assessment-20260803.md`：从旧计划提取、对照当前代码重新判断后的未完成能力。
- `docs/当前有效/架构设计/v2-agent-core-integration.md`：当前 V2/product 单向集成架构。
- `docs/当前有效/架构设计/MIGRATION_MAP.md`：每项旧能力的复用、适配、恢复或退役结论。
- `docs/待重新评估/chatmemory-context-management.md`：旧上下文/记忆设计基线；需要按 V2 事实模型重新评估。
- `docs/当前有效/产品能力/long-term-memory-crud.md`：现有长期记忆 CRUD 边界。
- `docs/当前有效/产品能力/long-term-memory-context-injection.md`：现有用户级记忆检索边界。
- `docs/待重新评估/context-debug-snapshots.md`：安全的上下文调试快照设计。
- `docs/历史归档/已被替代/旧架构与路线/paper-agent-next-steps-roadmap.md`：论文产品能力的历史路线，未完成部分已提取到待办评估文档。
- `docs/历史归档/历史评测/报告/v2-user-journey-stabilization-20260729.md`：V2 真实链路修复与历史验收记录。
- `docs/当前有效/开发流程/verification-matrix.md`：按变更范围选择测试。

## 14. 当前停止位置

本文及配套文档治理只完成了状态核验、旧文档分类、未完成想法提取和交接，不包含记忆、压缩、V2 Skill、MCP 或子 Agent 实现。已经确认现有 17 个工具足以支持有边界的论文—代码—运行—文本修复闭环，也确认 Project V2 不再需要 filesystem MCP。

新的主对话应从第 12 节的 Context Assembly 审计 Issue 开始。用户审核该 Issue 的合同和分阶段方案后，再进入代码修改。Context、摘要和记忆稳定后实施 V2 产品 Skill；没有明确外部只读用例时跳过 MCP 试点，再进入只读子 Agent。
