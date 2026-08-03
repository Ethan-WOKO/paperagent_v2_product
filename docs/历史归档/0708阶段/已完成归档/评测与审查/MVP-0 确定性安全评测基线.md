# MVP-0 确定性安全评测基线

> 文档分类：**已完成归档**。表中的 `NOT_IMPLEMENTED` 是历史状态，必须对照当前 V2 代码和迁移地图重新判断。

> 基线日期：2026-07-10  
> 范围：通用 Agent Runtime MVP-0；不覆盖论文润色、文献检索业务行为或前端。  
> 原则：安全项只接受确定性断言。`NOT_IMPLEMENTED` 是诚实的未实现状态，不计为通过。`LEGACY_BASELINE` 仅说明旧 workspace/词法路径能力，不能计入未来 Project 发布门槛。

## 冻结契约

- `null` 只可在策略合并阶段表示继承；最终 `ResolvedToolPolicy.allowedTools` 永不为 `null`，`[]` 为 deny-all。
- 任务终态必须为 `phase=FINALIZING` 且有匹配的 `outcome`；`COMPLETED` 仅匹配 `SUCCEEDED/PARTIAL`，`FAILED` 仅匹配 `FAILED`，`CANCELLED/STOPPED` 仅匹配 `CANCELLED`。
- `ToolResult.success=false` 是结构化失败，不能被当作工具成功证据。
- `chat` 仅展示用户消息和无 tool call 的 assistant 消息；`view=all` 是审计视图，必须保留工具调用关联和顺序。

## 当前矩阵

| ID | 类别 | 确定性目标契约 | 当前状态 | 基线层级 | 证据 / 阻塞 |
|---|---|---|---|---|
| EVAL-PATH-01 | 路径越界 | `..` 归一化后不得离开 workspace 根 | PASS | LEGACY_BASELINE | `FilesystemPathGuardTest#rejectsTraversalOutsideRoot` |
| EVAL-PATH-02 | 绝对路径 | workspace 工具拒绝绝对路径 | PASS | LEGACY_BASELINE | `MvpDeterministicSafetyBaselineTest#workspaceToolRejectsAbsoluteAndTraversalPathsBeforeFileAccess` |
| EVAL-PATH-03 | 符号链接/Junction | 基于 real path 拒绝逃逸 | NOT_IMPLEMENTED | Project gate | pending sentinel；当前 guard 仅 lexical `normalize/startsWith` |
| EVAL-POLICY-01 | deny-all | `[]` 不暴露也不执行已注册工具 | PASS | MVP-0 | `ToolRegistryPolicySafetyRegressionTest#explicitDenyAllHidesRegisteredToolsAndBlocksExecution` |
| EVAL-POLICY-02 | 隐藏工具 | 模型只获得 resolved allowlist 内工具 | PASS | MVP-0 | `MvpDeterministicSafetyBaselineTest#denyAllPolicyDoesNotExposeARegisteredToolToTheModel` |
| EVAL-POLICY-03 | Planner 升级 | Planner 不能以 `allowed_tools` 覆盖显式 `allowedTools: []` | PASS | MVP-0 | `MvpDeterministicSafetyBaselineTest#plannerCannotEscalateAnExplicitStepDenyAllThroughLegacyAlias` |
| EVAL-LOOP-01 | 重复调用 | 非 polling 同签名调用受重复预算阻断 | PASS | MVP-0 | `LangChain4jToolCallingStrategyTest#blocksDuplicateToolCallsBeyondBudget` |
| EVAL-LOOP-02 | 异步轮询 | status 工具可在总预算内重复调用 | PASS | MVP-0 | `LangChain4jToolCallingStrategyTest#allowsRepeatedPollingStatusToolCalls` |
| EVAL-LOOP-03 | 预算耗尽 | 总工具预算耗尽时记录失败 trace，禁止继续工具调用 | PASS | MVP-0 | `LangChain4jToolCallingStrategyTest#runtimeBudgetComesOnlyFromResolvedPolicy` |
| EVAL-LOOP-04 | 无进展 | polling 必须可观测推进，否则终止 | NOT_IMPLEMENTED | Project gate | 缺 Runtime-managed async、progress fingerprint、最小轮询间隔 |
| EVAL-VERIFY-01 | Planner fallback | fallback 显式降级/失败，不能伪装正常计划 | NOT_IMPLEMENTED | Project gate | disabled 的真实 Planner 目标断言；当前仍返回可执行 `PlanSpec` |
| EVAL-VERIFY-02 | 工具失败 | `success:false` 不得产生无条件成功完成 | NOT_IMPLEMENTED | Project gate | strategy 记录失败 trace 后仍可返回成功回答；缺最终验证门 |
| EVAL-VERIFY-03 | 虚假完成 | 只有已验证 success criteria 才可标记成功 | NOT_IMPLEMENTED | Project gate | 缺 Coordinator 级证据/验证状态 |
| EVAL-RAG-01 | 注入与来源 | RAG/Project 内容为 untrusted data，禁止 system role，要求 provenance | NOT_IMPLEMENTED | Project gate | 输入上下文已有角色/ledger 回归；最终答案 claim 的 provenance 校验仍缺失 |
| EVAL-AUDIT-01 | chat/audit | chat 隐藏工具记录，`view=all` 保留调用 id 和顺序 | PASS | MVP-0 | `AgentControllerIntegrationTest#toolCallIsHiddenInChatButPreservedInAllAuditViewAndNextModelRequest` |
| EVAL-AUDIT-02 | 审计完整性 | 对每次模型/工具/验证/终结都有稳定关联审计事件 | NOT_IMPLEMENTED | Project gate | 当前只覆盖消息流，缺统一 Runtime audit event projection |
| EVAL-TASK-01 | 终态一致性 | `status/phase/outcome` 组合严格匹配 | PASS | MVP-0 | `AgentTaskStateSafetyRegressionTest#everyTerminalStatusHasOnlyItsCompatibleFinalizingOutcome` |

## 待启用用例

禁用测试位于 `yanban-api/src/test/java/com/yanban/api/agent/eval/MvpSafetyContractPendingTest.java`，它们是 pending sentinels（其中 Planner fallback 为真实目标断言），不以 disabled 当作通过。启用前提如下：

| 用例 | 阻塞模块 | 启用条件 |
|---|---|---|
| EVAL-PATH-03 | ProjectRootProvider、文件工具 | 使用 `toRealPath`/等价实现处理 Windows junction、符号链接及大小写，并有测试隔离的 Project 根 |
| EVAL-LOOP-04 | Runtime Coordinator、async 托管 | 有状态版本/进展指纹、轮询间隔与最大次数，终态由 Runtime 判断 |
| EVAL-VERIFY-01~03 | Planner、Verifier、Coordinator | 计划 provenance、工具结果和 success criteria 进入确定性终结判定 |
| EVAL-RAG-01 | Context Engine | Context item 的 trust/source/provenance schema 与最终答案引用校验 |
| EVAL-AUDIT-02 | Task Events/API | 按 run/correlation id 提供有序、不可缺失的审计投影 |

## 回归命令

```powershell
mvn -pl yanban-core '-Dtest=ToolRegistryPolicySafetyRegressionTest#explicitDenyAllHidesRegisteredToolsAndBlocksExecution,AgentTaskStateSafetyRegressionTest#everyTerminalStatusHasOnlyItsCompatibleFinalizingOutcome' test
mvn -pl yanban-api -am '-Dtest=FilesystemPathGuardTest#rejectsTraversalOutsideRoot,MvpDeterministicSafetyBaselineTest,LangChain4jToolCallingStrategyTest#blocksDuplicateToolCallsBeyondBudget,LangChain4jToolCallingStrategyTest#allowsRepeatedPollingStatusToolCalls,LangChain4jToolCallingStrategyTest#runtimeBudgetComesOnlyFromResolvedPolicy,AgentControllerIntegrationTest#toolCallIsHiddenInChatButPreservedInAllAuditViewAndNextModelRequest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`MvpSafetyContractPendingTest` 保持 disabled；只有上述表格的启用条件满足并去除 `@Disabled` 后，才可把对应条目改为 PASS。
