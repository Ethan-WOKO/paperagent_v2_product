# MVP 本地验收矩阵

> 文档分类：**已完成归档**。当前测试选择以 `docs/当前有效/开发流程/verification-matrix.md` 为准。

> 适用基线：`f53313a8cc57bb254994faa93bef4662476ceab3`。
> 范围：Project 只读 MVP 的本地发布与科研用户验收；不授予文件写入、命令执行、网络、真实模型、Pro 或多 Agent 权限。
> 冻结契约：受信任的 authenticated principal 与 route-bound `projectId`；受控本地绑定可使用绝对路径，但 Agent/Evidence/Candidate/UI 的普通投影只使用 Project 内相对路径；Candidate 永远 `NOT_APPLIED`。

## 判定方式与失败等级

| 类型 | 结论用途 |
|---|---|
| 自动（发布门） | 可离线、无真实模型/网络/密钥；必须通过且不得 skip，才能计入工程门。 |
| 手工（本地 UI） | 用户在 IDEA/Vite 和其真实目录中验证；不启动或长期占用 `8080`/`5173`。 |
| 真实模型观察 | 仅验证模型输出质量、提示词服从与端到端体验；不能作为确定性发布门。 |

`P0` = 越权读取、写回或权限扩张；`P1` = 错误的 Project 绑定、证据/审计或安全失败；`P2` = 可用性、显示或格式问题。任何 P0/P1 立即停止验收并标记 `CHANGES_REQUIRED`；P2 可记录后复验。

## 自动（工程发布门）

| ID | 前置条件与真实输入 | 操作步骤 | 预期 API/UI/审计结果 | 对应确定性测试 | 失败等级 |
|---|---|---|---|---|---|
| A01 本地 Project 绑定 | 一个真实、可读的本地目录；另备普通子目录、缺失路径、普通文件路径。 | 绝对路径开关关闭时创建；打开受控 local mode 后创建；分别提交缺失路径、文件路径和相对路径。 | 关闭时绝对路径 fail-closed；打开后仅可信绝对目录被 canonicalize 绑定；普通目录走配置根下相对绑定；缺失/文件/空 include rules 均拒绝，调用者不能指定 owner。 | `ProjectServiceTest#internalProvisioningBindsOnlyTrustedAbsoluteProjectFoldersWhenLocalModeIsEnabled`；`#absoluteProjectFoldersFailClosedUntilControlledLocalModeIsExplicitlyEnabled`；`#projectCreationOnlyAuthorizesRelativeRootsBelowConfiguredRoot`；`ProjectControllerIntegrationTest#createUsesAuthenticatedOwnerAndNeverAcceptsCallerSelectedOwnership` | P0 |
| A02 路径与 reparse 逃逸 | Project 内 `notes.md`；Project 外私密文件；`..`、绝对路径、符号链接或 Windows junction/reparse fixture。 | 调用 manifest/read/search，并对 read 传入上述恶意路径；尝试以链接目录作为根。 | 只允许 Project 内普通相对文件；绝对/遍历路径、链接逃逸、根别名、UNC 与链接根拒绝；manifest 不枚举逃逸文件。 | `ProjectServiceTest#rejectsAbsoluteTraversalAndNormalizedEscapePaths`；`#rejectsSymbolicLinkEscape`；`#internalProvisioningRejectsSymbolicLinkRoot`；`#rejectsConfiguredOrPersistedProjectRootAliases`；`MvpDeterministicSafetyBaselineTest#workspaceToolRejectsAbsoluteAndTraversalPathsBeforeFileAccess` | P0 |
| A03 Project 相对投影 | Project 内含可读文本、`.env`、搜索关键字。 | 请求 manifest、read、search 与 HTTP read endpoint。 | 返回仅相对路径、内容/行号和 hash/version；不含服务器绝对根；忽略规则不暴露 `.env`。 | `ProjectServiceTest#manifestReadAndSearchExposeOnlyRelativePathsAndAuditableHashes`；`ProjectControllerIntegrationTest#normalReadReturnsRelativePathOnly` | P0 |
| A04 普通对话本地文件隔离 | 普通（非 Project）Agent 请求与工作区已有 `pom.xml`。 | 调用 annotation bridge 的 `read_file`，包括伪造 allowlist。 | 无 runtime policy 或无受治理 descriptor 时在任何文件访问前 fail-closed；普通对话不能读取任意本地路径。 | `AgentLangChain4jToolsPolicyGateTest#nonUserAnnotatedEntryFailsClosedBeforeAnyFilesystemRead`；`#allowlistedLocalAnnotationToolWithoutDescriptorCannotReadExistingFile` | P0 |
| A05 ReAct Project 只读链路 | 已认证 user/projectId；模型伪造不同 projectId；允许工具只含 `project_read_file`。 | 发起 SINGLE_STEP_REACT，令工具读取当前 Project；再令模型改写 projectId。 | 服务端 attested context 绑定原 project；真实读取带 provenance；伪造 projectId 不触及 Project service。 | `ProjectReactVerticalTest#coordinatorUsesTrustedProjectContextAndRealProviderReadReturnsUntrustedProvenance`；`#modelCannotSwitchProjectIdDuringActualReactToolCall`；`ProjectReadToolExecutorTest` | P0 |
| A06 Plan 执行与策略交集 | Project Plan step 设 `allowedTools=[]` 或仅 `project_read_file`；Planner 使用 legacy `allowed_tools` 试图扩权。 | 创建并恢复 Plan，再执行 step/async worker。 | `null` 才继承，`[]` 为 deny-all；step、Project、runtime 策略取交集并二次门禁；恢复时重绑 project context 和当前 evidence。 | `MvpDeterministicSafetyBaselineTest#nullIsOnlyMergeInheritanceAndRuntimeCompatibilityIsDenyAll`；`#plannerCannotEscalateAnExplicitStepDenyAllThroughLegacyAlias`；`ProjectPlanVerticalTest`；`ProjectPlanEnvelopeTest` | P0 |
| A07 Planner 失败不伪装正常计划 | fake Planner/model 抛出不可用异常。 | 执行 Plan 创建路径并检查降级/失败状态与用户展示。 | 显式 degraded/failed limitation，不把 fallback 当成正常可执行计划。 | `PlanningAgentPlannerTest`；`PlanReflectionRuntimeAdapterTest#reflectionSummaryExposesDegradedAndFailedLimitations` | P1 |
| A08 Evidence 与 Completion Verifier | 本轮授权的文件 read/hash；旧轮、跨 Project 或错误 hash evidence；仅 manifest 的回答。 | 对 ReAct 与 PLAN_EXECUTE 分别完成回答并进入 verifier。 | 只有本轮、当前、同 Project、同 hash 的 provenance 能验证 claim；历史/伪造 ledger 不可绕过；两策略走同一 verification gate。 | `ProjectCurrentTurnEvidenceTest`；`CompletionVerifierTest#projectClaimWithoutCurrentFileEvidenceCannotBeVerified`；`#historicalCrossProjectAndWrongHashEvidenceCannotPass`；`#rawLedgerCannotBypassTrustedCurrentObservationRequirement`；`#reactAndPlanExecuteBothCrossTheSameVerificationGate` | P1 |
| A09 Reflection 权限不扩张 | verifier 触发 repair；deny-all 或预算耗尽对照。 | 观察 repair 次数、projectId、allowed tools 和预算。 | 至多一次；保留 project identity/authority；仅减少预算；deny-all 或无预算不 repair。 | `CompletionVerifierTest#reflectionExecutesAtMostOneRepairTurn`；`#reflectionPreservesAuthorityBudgetAndProjectIdentity`；`#projectDenyAllAndToolBudgetExhaustionDoNotRunRepair`；`#repairRequestRetainsAuthorityAndOnlyReducesBudgets` | P0 |
| A10 Candidate 只读提案 | 产生修改意图的回答；随后改变文件 hash 或删除 Project 绑定。 | 获取 candidate，并尝试寻找 Apply 路由/写回副作用。 | Candidate 永远 `NOT_APPLIED`；hash 变化为 `STALE`，删除绑定为 `INVALIDATED`；无 Apply 操作且不写用户文件。 | `CompletionVerifierTest#explicitModificationIntentPersistsCandidateWhileReadOnlyIntentDoesNot`；`CandidateChangeArtifactServiceTest`；`CandidateChangeSet`/`AgentArtifactController` 的无 apply 契约 | P0 |
| A11 Project Chat canonical/audit | route-bound WebSocket Project；一次过程消息、一次 canonical completion、相对 evidence；另用含绝对路径的 runtime error。 | 发送消息，检查 `ack/process/chunk/done` 或 `error` 序列。 | 一条 canonical assistant answer；过程消息仅审计/进度；done 含相对 evidence；错误与 payload 不泄露绝对路径；无 handshake binding 拒绝。 | `ProjectAgentRuntimeStreamingTest#streamsOnlyCanonicalCompletionAndProjectsSafeCurrentEvidence`；`ProjectChatWebSocketHandlerTest`；`ProjectWebSocketHandshakeInterceptorTest` | P1 |
| A12 Markdown 格式回归 | 已记录的异常 Markdown（无空格标题/列表、嵌入 marker、code fence、长中文句）。 | 运行前端 Markdown normalization fixture。 | 只修复保守格式；不把段落升级为标题；不改 fenced/inline code。 | `frontend/tests/markdownNormalization.test.mjs` | P2 |
| A13 删除仅解绑 | 已绑定的真实本地目录和文件。 | 对拥有者删除 Project；再对不存在/非拥有 Project 删除。 | 仅删除数据库绑定；本地目录和文件保持；非拥有者得到同一 not-found 且无删除副作用。 | `ProjectServiceTest#deletingBindingLeavesTheBoundDirectoryAndFilesUntouched`；`#deletesOwnedBindingEvenWhenPersistedRootIsMissingWithoutResolvingIt`；`ProjectControllerIntegrationTest#deleteRemovesOnlyTheAuthenticatedUsersProjectBinding` | P0 |

发布命令（全离线；禁止用非离线 Maven 回退联网）：

```powershell
./scripts/Invoke-MvpReleaseGate.ps1
```

该门会运行 Maven 的确定性测试，并直接运行 `node --test frontend/tests/markdownNormalization.test.mjs`；任何 Java failure/error/skip 或前端非零退出均不得作为工程门通过。Windows 若无法创建符号链接且 junction fallback 也失败，gate 必须以 skip/exit `3` 阻断，而不是将 A02 记为通过。

## 手工（IDEA/Vite + 真实目录）

| ID | 前置条件与真实输入 | 操作步骤 | 预期 API/UI/审计结果 | 自动辅助 | 失败等级 |
|---|---|---|---|---|---|
| M01 创建与浏览 | 在用户机器准备普通目录、受控绝对路径目录、缺失路径、单个文件。 | 在 IDEA 启动后端、Vite 启动前端（端口由用户选择且结束后关闭），逐项创建/浏览 Project。 | 成功项只展示相对路径；失败项显示稳定且不含本机绝对根的错误；绝对路径开关关闭仍 fail-closed。 | A01–A03 | P0/P1 |
| M02 不可读目录 | 准备一个用户无读取权限的真实目录（不要改变受保护系统目录）。 | 尝试绑定、manifest/read/search；随后恢复权限。 | 明确拒绝或受控错误，不泄露根路径/目录内容，不造成部分绑定。 | 无跨平台无权限 fixture；A01/A03 覆盖其余路径边界。 | P1 |
| M03 reparse 真实环境 | Project 内建立指向 Project 外的 junction/symlink/reparse point（仅测试目录）。 | 重新创建/刷新 Project，执行 manifest/read/search。 | 外部内容不出现；读取被拒绝；UI 错误不泄露外部绝对路径。 | A02；Windows fixture skip 会阻断工程门。 | P0 |
| M04 长内容与状态 | 真实 Project 中放深层长相对路径、长文本/长回答、空目录；断开/恢复后端一次。 | 打开 Project Chat，检查 loading、empty、error、滚动与 Markdown。 | loading/empty/error 可理解；长路径不越界/泄露根；长 canonical answer 可阅读且不重复；异常 Markdown 不污染 code block。 | A11–A12 | P2（泄露则 P0） |
| M05 删除回归 | 已创建真实 Project 和本地文件。 | 从 UI 删除 Project 后在文件管理器核对目录。 | UI 移除绑定；磁盘目录和文件仍存在；重新创建时不以旧绑定越权。 | A13 | P0 |

## 真实模型观察（不得作为发布门）

| ID | 前置条件与真实输入 | 操作步骤 | 预期 API/UI/审计结果 | 失败等级 |
|---|---|---|---|---|
| R01 ReAct 研究问答 | 用户自有可读科研 Project，问题可由单个文件事实回答。 | 用户配置自己的模型与密钥后提问；只观察而不授予新工具。 | 回答引用当前相对路径/evidence；工具过程可审计，最终只有 canonical answer；模型不读取 Project 外文件。 | P1（越权为 P0） |
| R02 Plan 研究问答 | 同一 Project，需 manifest/search/read 多步的问题。 | 请求 PLAN_EXECUTE；检查 planner 失败、证据不足、repair 情况。 | 计划失败显式展示；步骤工具不超出只读交集；verifier/至多一次 reflection 不扩权。 | P1（扩权为 P0） |
| R03 Candidate 审阅 | 用户请求“建议修改”而非应用。 | 检查 candidate、变更文件 hash，再删除绑定。 | 仅展示提案；一直 `NOT_APPLIED`；hash 改变为 `STALE`，解绑后 `INVALIDATED`，用户文件不被写回。 | P0 |

## 当前结论记录模板

每次验收必须在本文件同级的发布记录中粘贴：基线 HEAD、`Invoke-MvpReleaseGate.ps1` 完整末行（Java tests/failures/errors/skips、frontend exit、脚本 exit）、手工项 ID/结果、真实模型项 ID/结果及模型/密钥是否实际使用。缺少任一 P0/P1 项证据时结论为 `CHANGES_REQUIRED`；未完成手工或真实模型观察只能说明“工程门已通过”，不能宣称完整本地科研验收通过。

## 本基线执行记录（2026-07-12）

| 命令 | 精确结果 | 判定 |
|---|---|---|
| `node --test frontend/tests/markdownNormalization.test.mjs` | tests=6, failures=0, errors=0, skips=0, exit=0 | PASS（A12） |
| `mvn -o -pl yanban-api -am '-Dtest=ProjectAgentRuntimeStreamingTest,ProjectWebSocketHandshakeInterceptorTest,ProjectChatWebSocketHandlerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | tests=9, failures=0, errors=0, skips=0, exit=0 | PASS（新增至门的 A11 覆盖） |
| 先前完整门记录 | 虽有 170 个 XML 绿灯，但 `PaperControllerIntegrationTest` 启动真实 `PaperOrchestrator` 异步链路；其 DeepSeek/文献 provider 调用和 Surefire 强杀 fork 使该记录无效。 | INVALID，不得作为 PASS。 |
| `PaperControllerIntegrationTest`（最终完整门内） | tests=4, failures=0, errors=0, skips=0；Controller 仍验证任务持久化、上传参数、任务读取和同请求幂等。`PaperOrchestrator` 只验证 after-persistence 的单次 `startTask` 委派；`PaperModelClient`、`LiteratureRecommendationService`、OpenAlex 和 arXiv `LiteratureSource` 均在每个用例后断言 zero interactions。 | PASS（隔离回归可见） |
| `./scripts/Invoke-MvpReleaseGate.ps1`（隔离后） | Java=170 tests, failures=0, errors=0, skips=0；frontend=6 tests, pass=6, fail=0, skipped=0；script exit=0。`PaperControllerIntegrationTest` 的 Surefire XML 不含 `yanban-paper-`、`Paper model call`、DeepSeek、OpenAlex、arXiv、recommendation 或 fork-kill marker；本次开始后新建 dump/dumpstream=0，残留 Maven/Surefire 进程=0。 | PASS（工程门） |

隔离实现位于 `PaperControllerIntegrationTest`：真实异步编排被 test double 取代，而 Controller、持久化和幂等契约仍通过 MockMvc/H2 验证。若未来将模型或文献调用绕过该边界，zero-interaction 断言会使发布门失败。手工与真实模型观察项仍不属于确定性工程门。
