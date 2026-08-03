# MVP 发布评测与发布门禁报告

> 文档分类：**已完成归档**。报告只证明当时基线，不代表当前 main 的验证结果。

> 评测日期：2026-07-11  
> 范围：通用 Agent Runtime 最小只读 MVP 的离线、确定性发布门禁。  
> 结论：**READY_FOR_LOCAL_ACCEPTANCE**。P0 已以最小生产修复清除；Windows junction fallback 下两项关键反例均实际执行并通过。此结论仅代表离线确定性自动门，**不代表用户真实科研验收已完成**。

## 1. 冻结范围与方法

实施依据是《通用 Agent Runtime 设计》《通用 Agent Runtime 实施方案与进度》以及已接受的 MVP-0、Project 只读边界、工具治理、Context/Evidence、Runtime Coordinator、统一 Plan Adapter、MVP-3A 和 MVP-4A 契约。本次仅修改 Project 路径防护的两处生产类；没有修改其他 Runtime、论文/文献实现、前端或 migration，也没有增加写入、命令执行或 apply endpoint。

新增的离线门禁脚本为 [`scripts/Invoke-MvpReleaseGate.ps1`](../scripts/Invoke-MvpReleaseGate.ps1)。它固定列出确定性 JUnit 类，以 `mvn -o` 运行；缺少缓存依赖会失败，绝不回退到网络。它从本轮 Surefire XML 汇总 `tests/failures/errors/skips`：

- Maven failure、failure 或 error：退出 `1`；
- 未产生新 Surefire XML：退出 `2`；
- 任一 skip：退出 `3`，因此关键安全项不会静默放行；
- 只有 `0 failures / 0 errors / 0 skipped` 才退出 `0`。

当前受限桌面会话不能把 JUnit 临时目录创建在 `%LOCALAPPDATA%\Temp`。实际运行前将 `TEMP`、`TMP` 和 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<workspace>\.mvp-release-tmp` 指向工作区临时目录；这只改变测试进程临时位置，不改变产品行为或网络策略。链接 fixture 先调用 `Files.createSymbolicLink`；Windows 无 `SeCreateSymbolicLinkPrivilege` 时，仅在该 JUnit 临时目录内调用 PowerShell `New-Item -ItemType Junction`，并验证为实际 reparse point、目标为期望目录，清理也限定在同一临时目录。

## 2. 固定发布门矩阵与证据

| 门禁类别 | 状态 | 确定性证据 |
|---|---|---|
| `null` 继承、`[]` deny-all、动态模型工具暴露 | PASS | `MvpDeterministicSafetyBaselineTest`、`ToolRegistryPolicySafetyRegressionTest`、`AgentToolPolicyEngineTest` |
| Planner 不能以旧别名提升 deny-all | PASS | `MvpDeterministicSafetyBaselineTest#plannerCannotEscalateAnExplicitStepDenyAllThroughLegacyAlias`、`PlanningAgentPlannerTest#explicitCamelCaseDenyAllCannotBeOverriddenByLegacyAlias` |
| 注解工具第二门禁 | PASS | `AgentLangChain4jToolsPolicyGateTest`（无 policy、用户不匹配、未知/未允许、确认需求均 fail-closed） |
| Project 绝对路径、`..`、归一化越界、资源上限、跨用户/跨 Project | PASS | `ProjectServiceTest`、`ProjectControllerIntegrationTest`、`ProjectReadToolExecutorTest`、`ProjectReactVerticalTest` |
| 符号链接/junction/reparse-point alias | PASS | `ProjectServiceTest#rejectsSymbolicLinkEscape`（项目内 junction 指向项目外）及 `#internalProvisioningRejectsSymbolicLinkRoot`（provisioning root 自身为 junction）均实际执行并通过；还覆盖 configured root、已持久化 root alias 与普通嵌套目录正例。 |
| 当前版本 evidence、历史/伪造/旧 hash | PASS | `ProjectCurrentTurnEvidenceTest`、`CompletionVerifierTest`、`PlanCompletionEvidenceVerticalTest` |
| RAG/Project provenance 与不可信角色隔离 | PASS（注入边界） | `AgentContextBuilderTest#injectsRagProjectWebAndToolEvidenceAsUntrustedDataWithCompleteProvenance`；Project 当前证据的最终验证见上述 verifier 垂直集 |
| Planner 显式失败、不能伪装正常计划 | PASS | `PlanningAgentPlannerTest#createPlanReturnsExplicitFailureWhenModelCallFails`、`#plannerFailuresAreClassifiedWithoutExecutableFallback` |
| 无进展/终态轮询、重复调用、预算停止 | PASS | `LangChain4jToolCallingStrategyTest#unknownAsyncStateCannotAuthorizeAnotherPoll`、`#terminalAsyncStateCannotBePolledAgain`、`#blocksDuplicateToolCallsBeyondBudget`、预算 stop tests |
| `ToolResult.success=false` 与虚假完成不得 `VERIFIED` | PASS，虚假完成数为 0 | `LangChain4jToolCallingStrategyTest` 的失败 trace，`CompletionVerifierTest#projectClaimWithoutCurrentFileEvidenceCannotBeVerified` |
| DIRECT / REACT / PLAN_EXECUTE 的统一 outcome / verifier | PASS | `AgentRuntimeCoordinatorTest`、`AgentRuntimeServiceTest`、`CompletionVerifierTest#reactAndPlanExecuteBothCrossTheSameVerificationGate` |
| Reflection 最多一次、不扩权、deny-all/零预算不 repair | PASS | `CompletionVerifierTest#reflectionExecutesAtMostOneRepairTurn`、`#repairRequestRetainsAuthorityAndOnlyReducesBudgets`、`#projectDenyAllAndToolBudgetExhaustionDoNotRunRepair` |
| CandidateChangeSet：`NOT_APPLIED`、`STALE`、跨用户拒绝 | PASS | `CandidateChangeArtifactServiceTest`、`CompletionVerifierTest#explicitModificationIntentPersistsCandidateWhileReadOnlyIntentDoesNot` |
| 无 Project apply/write endpoint | PASS（静态 API 审查） | `ProjectController` 仅含 manifest/read/search `GET`；`AgentArtifactController` 对 candidate 仅暴露 `GET /{artifactId}/candidate`，`CandidateChangeSet` 无文件句柄且固定 `NOT_APPLIED`。注册的旧 `write_file` 注解入口由 `AgentLangChain4jToolsPolicyGateTest` fail-closed 覆盖，未成为 Project endpoint。 |
| 普通 CHAT、论文润色、文献工具兼容 | PASS（自动回归） | 门禁内 `CompletionVerifierTest#regularChatWithoutEvidenceRemainsCompatible`、`PaperControllerIntegrationTest`、`PaperTaskToolExecutorTest`、`LiteratureSearchTaskToolExecutorTest` 与 `LiteratureSearchTaskScannerTest` |

## 3. MVP-0 原 7 个 `NOT_IMPLEMENTED` 映射

`MvpSafetyContractPendingTest` 的七个 historical disabled sentinel 是占位映射，不是当前功能状态的权威来源，也不计为通过或未实现项。当前真正的 `NOT_IMPLEMENTED` 仅为 `EVAL-AUDIT-02`；泛化的 RAG 最终文本强制引文校验是后续范围。后续冻结实现的权威启用测试如下：

| 原 ID | 当前分类 | 当前实现/权威测试 | 是否阻塞最小 MVP |
|---|---|---|---|
| EVAL-PATH-03 | PASS | `ProjectServiceTest` 的两项真实目录链接/reparse point 反例，以及 configured/persisted root alias 反例 | 否。两项真实反例均实测通过且未 skip。 |
| EVAL-LOOP-04 | PASS | `LangChain4jToolCallingStrategyTest` 的 unknown state、terminal state 和 budget 反例 | 否。 |
| EVAL-VERIFY-01 | PASS | `PlanningAgentPlannerTest` 的 model/empty/invalid/no-step 显式失败 | 否。 |
| EVAL-VERIFY-02 | PASS | 工具失败 trace + `CompletionVerifierTest` 的无当前证据非 VERIFIED 反例 | 否。 |
| EVAL-VERIFY-03 | PASS | `CompletionVerifierTest`、`PlanCompletionEvidenceVerticalTest` | 否；本门禁中的虚假完成为 0。 |
| EVAL-RAG-01 | PASS（MVP 注入/ledger 范围） | `AgentContextBuilderTest` 的 RAG/Project/Web/Tool 均作为不可信数据并含 provenance | 否；泛化的“最终文本逐项 RAG 引文强制校验”不属于当前只读 MVP 自动门，列为后续 P2。 |
| EVAL-AUDIT-02 | NOT_IMPLEMENTED（P2） | 当前保留 chat/audit 消息链路，但没有每次模型、工具、验证、终结事件共享稳定 correlation-id 的统一投影 | 否；不得将它描述为已实现或计为 PASS。 |

根聚合的 API skip 中这七个 sentinel 仍显式可见；发布门禁使用上表的启用权威测试，而非把 disabled 当通过、当作当前 `NOT_IMPLEMENTED`，或混入 PASS 证据。

## 4. 实际执行命令和结果

所有 Maven 命令均使用 `-o`，没有调用真实模型、Embedding、Redis、外网或人工输入。

| 集合 | 实际结果 |
|---|---|
| 修复前发布门禁 | 145 tests，1 failure，0 errors，0 skipped；`ProjectServiceTest#internalProvisioningRejectsSymbolicLinkRoot` 失败，exit 1。 |
| 修复后发布门禁 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-MvpReleaseGate.ps1`（工作区临时目录） | **146 tests，0 failures，0 errors，0 skipped；exit 0**。 |
| `ProjectServiceTest` 定向实跑 | **12 tests，0 failures，0 errors，0 skipped**；两个真实 junction/reparse-point 反例均通过。 |
| Project/Runtime 定向集 | **63 tests，0 failures，0 errors，0 skipped**。 |
| 根目录 `mvn -o test` | **545 tests，0 failures，0 errors，8 skipped，8 模块 `BUILD SUCCESS`**（耗时 03:44）。 |

根聚合逐模块 Surefire 汇总：core 41/0/0/0，knowledge 34/0/0/1，paper 121/0/0/0，mcp 3/0/0/0，skills 1/0/0/0，api 345/0/0/7，cli 0/0/0/0（格式为 tests/failures/errors/skipped）。knowledge skip 是显式 opt-in 的真实 Elasticsearch E2E（需 `-Dyanban.real-es-e2e=true`）；它和 API 历史 disabled sentinel 均不进入离线 MVP 发布门。

本机链接结果：`Files.createSymbolicLink` 如预期因 Windows 权限失败后，directory junction fallback 成功创建并被验证为真实 reparse point。项目内 junction 指向项目外目录和 internal provisioning root 自身为 junction 的用例均通过，均没有 skip。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Invoke-MvpReleaseGate.ps1
```

该命令现已返回 `0`，且两项链接用例实际执行通过（门禁 `failures=0/errors=0/skipped=0`），P0 已清除。若 CI 使用空 Maven 缓存，应先由受控构建缓存准备依赖；门禁本身继续使用 `-o`，不允许联网下载。

## 5. GO / NO_GO 规则和本次判定

`GO / READY_FOR_LOCAL_ACCEPTANCE` 的必要条件是：自动门为 0 failures、0 errors、0 critical skips；路径越界/未授权写入阻断率 100%；虚假完成数 0；没有 Project write/apply endpoint；并且根聚合成功。任何 P0 安全反例未执行、失败或被静默 skip 都是 `NO_GO`。

本次扩展后的发布门包含先前遗漏的 `AgentToolPolicyEngineTest`、`ProjectControllerIntegrationTest`、`ProjectCurrentTurnEvidenceTest`、`AgentRuntimeServiceTest`、`PaperTaskToolExecutorTest` 和 `LiteratureSearchTaskToolExecutorTest`，因此上述 PASS 测试证据均由发布门实际执行（根聚合一项已明确单列）。修复后门禁实际得到 146 tests、0 failures、0 errors、0 skips、exit 0，路径越界硬门槛满足；根聚合亦成功。因此自动发布状态为 **READY_FOR_LOCAL_ACCEPTANCE**。这不是用户真实科研验收完成的声明。

## 6. 最小 MVP 支持与不支持

当前最小 MVP 支持：授权 Project 的只读 manifest/read/search；相对路径、版本 hash 与证据；受治理的 REACT 和受信 PLAN_EXECUTE；统一完成验证、至多一次不扩权 Reflection；以及仅保存为 `NOT_APPLIED`、可因版本变化变为 `STALE` 的候选修改产物。

当前明确不支持：Project 自动写入/删除/移动/重命名、命令执行、candidate apply endpoint、论文自动编译、服务重启后的 L2 恢复、通用异步托管、自由多 Agent，以及真实模型概率型评测作为自动发布依据。

非确定性模型评测不进入自动发布门：本门禁刻意使用 stub/fake provider 和本地临时文件，确保结论可重复且无网络。真实模型的 provider、model、prompt、工具版本和重复稳定率应作为后续人工评测记录，不能用一次“模型回答看起来正确”替代上述安全门。

## 7. 残余风险和下一步

- P0 已清除：`ProjectPathGuard` 以 `NOFOLLOW_LINKS` 的 `BasicFileAttributes` 同时拒绝 symbolic link 与 `isOther()` reparse-point alias；`LocalServerProjectRootProvider` 在 configured root、provisioning candidate 和 persisted canonical root 解析前逐段应用同一保护。
- **P2**：EVAL-AUDIT-02 的统一稳定 correlation-id 审计投影尚未实现；当前 audit 消息视图不应被夸大为完整事件投影。
- **P2**：泛化的最终文本 RAG 引文强制校验不在当前自动门范围；当前保证的是 provenance 角色隔离与 ledger。
- 测试运行仍出现 Mockito 动态 agent 与 Surefire fork 退出延迟警告；全套测试与发布门均为成功退出，警告不改变本次结果。

在 P0 清除并由主对话复审后，用户才执行以下本地真实科研验收清单：

1. 授权一个本地 Project，确认只显示允许的相对路径文件，并读取一个代码、科研报告或论文文件。
2. 让 Agent 对跨代码/报告/论文的多个文件进行只读分析，确认每项结论能回到 `projectId + relativePath + hash/version` 证据。
3. 分别运行 ReAct 和受信 Plan，确认无证据时显示限制/失败而非“已完整审查”。
4. 在当前 Project 中请求“建议修改”，确认得到候选产物但用户文件没有落盘；修改原文件后重新查看候选，确认其为 `STALE`。
5. 制造工具失败、无进展或预算停止，确认结果明确说明限制；恢复后再次发起只读任务，确认不使用旧 hash 冒充当前证据。
6. 验证普通 CHAT、论文润色和文献工具仍可用且不获得 Project 写权限。

该清单不包含论文自动编译，也不包含 Project 自动写入或 candidate apply；完成它需要用户本地环境和真实科研样例，不能由本报告代为宣布完成。

## 8. 本轮 P0 实际修改文件

- `yanban-api/src/main/java/com/yanban/api/project/ProjectPathGuard.java`
- `yanban-api/src/main/java/com/yanban/api/project/LocalServerProjectRootProvider.java`
- `yanban-api/src/test/java/com/yanban/api/project/ProjectServiceTest.java`
- `0708后续计划及实施详情/MVP 发布评测与发布门禁报告.md`
- `0708后续计划及实施详情/通用 Agent Runtime 实施方案与进度.md`
