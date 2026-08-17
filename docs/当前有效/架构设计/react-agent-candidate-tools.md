# ReAct 隔离 Workspace 修改工具

状态：**CURRENT — Issue #165**

日期：2026-08-17

适用范围：`codex/issue-165-react-candidate-tools`，基线为 Issue #163 提交
`28cb03ae`。

## 决定

ReAct 的领域读取/分析能力继续动态复用产品 `ToolRegistry`。文件修改不是领域读取
工具，而是与沙箱相同的 Runtime 能力，因此通过产品工具网关调用稳定的
`WorkspacePort`：

- `write_workspace_file`：仅支持完整 UTF-8 文件的 `ADD` 或 `MODIFY`；
- `get_workspace_diff`：读取当前隔离 Workspace 相对冻结 ProjectVersion 的权威 diff；
- `read_project_file`、`list_project_files`：始终读取当前隔离 Workspace，写入前等于
  冻结 ProjectVersion，写入后反映 Candidate 内容；
- `execute_in_sandbox`：解析同一 Workspace 的当前精确字节和 SHA-256。

现有 `project_propose_candidate` 不接入本链路。它属于旧对话执行器，依赖
`CandidateProposalExecutionScope` 的 ThreadLocal transcript/evidence，并只创建评审
Artifact，不修改 ReAct Workspace。为复用它而伪造旧链上下文会重新耦合两条执行链。

## 权限

产品在 TaskFrame、Engine authority 和 HMAC task grant 中同时冻结
`WRITE_WORKSPACE/writeWorkspace=true`。Engine 只有在该权限存在时才向模型展示两个
Workspace 修改工具，网关在每次写入和 diff 读取时重新验证 grant、用户、Turn、
session、Project 和 ProjectVersion。

该权限只代表“最大允许能力”。system rule 要求没有明确修改指令时不得调用写工具。
模型决定是否调用以及调用顺序，服务端决定可用能力和每次调用是否有效。

## 写入合同

写入请求包含固定 `clientRequestId`、规范化 Project 相对路径、完整 replacement text
和服务端可复算的 canonical request digest。

- `ADD`：`baseSha256` 必须为 `null`，目标必须不存在；
- `MODIFY`：目标必须存在，`baseSha256` 必须等于当前 Workspace 文件 hash；
- 相同 task/call/digest 只产生一次写入并返回 replay；
- 相同 task/call 的不同 digest 冲突；
- 空变化、越界路径、超限内容和写后 hash/size 无法重新证明时失败关闭；
- 不支持 DELETE、RENAME、二进制写入或主机路径。

工具事件只公开 operation、相对路径和 hash 摘要，不公开 replacement text。模型内部
消息仍保留其自己生成的工具参数，以支持 Engine 精确恢复。

## Candidate 验证门禁

Engine 为每次成功写入增加单调 `workspaceRevision`，并在 task observation ledger 中
保留每个目标路径的基础 hash 和最新 after hash。最终回答只有同时满足以下条件才能
交付：

1. `get_workspace_diff` 已观察当前 `workspaceRevision`；
2. 当前 revision 之后存在成功沙箱 Receipt；
3. 该 Receipt 输入覆盖每一个 changed path；
4. 每个输入 hash 等于当前 Candidate 的最新 after hash。

不满足时，Engine 要求模型继续查看 diff 并执行精确验证；连续失败后以
`CANDIDATE_VALIDATION_REQUIRED` 结束。成功回答只能说“隔离 Candidate 已验证”，不能
说 ProjectVersion 已改变。

## 非目标与后续

本 Issue 不创建评审 Artifact、不发布新 ProjectVersion，也不提供 apply、rollback 或
DELETE 工具。确定性终结器、不可变版本发布和回滚属于后续独立 Issue；publish 永远
不是模型可选择工具。

当前 Workspace 写入 replay 状态由产品进程持有，Engine 重启可以恢复；产品后端进程
重启后的 Candidate Workspace/写入事实恢复仍是残余风险，必须在发布能力接入前持久化
解决。没有精确成功 Receipt 时，ProjectVersion 始终不变。
