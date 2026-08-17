# ReAct Workspace 自动发布

日期：2026-08-17
状态：Issue #167 冻结实现

## 用户行为

用户明确要求修改 Project 时，ReAct 可以在隔离 Workspace 中 ADD/MODIFY 文本文件。
最终修改通过沙箱验证后，系统直接发布新的不可变 ProjectVersion，不再要求用户确认。
旧 revision 始终保留，用户可使用现有回滚能力恢复。

分析、问候、无实际 diff、沙箱失败、Receipt 缺失或内容不一致时，不发布。

## 确定性终结器

`project.publish` 只是一种服务端事件名，不进入模型工具列表。Engine 在交付前提交：

- 冻结 task 与 ProjectVersion；
- 当前完整 Workspace diff；
- 最后一次精确成功验证的正式 Receipt 引用；
- diff 与 Receipt 语义的 canonical digest。

Java 产品重新读取 Workspace 文件，验证 UTF-8 正文与 after hash；读取持久 Receipt，
验证状态为 `SUCCEEDED`、exitCode 为 0，且每个 changed path 的 after hash 都出现在该
Receipt 的精确输入中。产品还会在发布事务中确认冻结 ProjectVersion 仍为当前版本。

全部成立后，`ProjectRevisionWorkflowService` 复制未修改文件、写入已证明的 Candidate
正文、校验新 manifest，再原子切换 current revision 指针。发布幂等键由 taskId
确定，同任务重试只返回原 operation/revision，不会创建第二个版本。

## 恢复与交付

Engine 持久化 publication fact，并写入 append-only `project.publish` 事件。最终
Delivery 带 base/published ProjectVersion、revisionId 和 Receipt 引用；用户可见结论
由服务端追加真实发布结果，模型不能自行编造版本号。
