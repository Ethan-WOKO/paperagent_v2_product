# GitHub Spec Kit AI Coding 流程

状态：**CURRENT**
生效日期：2026-09-03
接入 Issue：#212

## 1. 适用范围

本仓库使用 GitHub Spec Kit 作为 AI 辅助开发的流程工具。Spec Kit 负责把需求、
设计、任务、实现和验证沉淀为可审查的仓库文件，不替代根目录 `AGENTS.md`、当前
架构合同、GitHub Issue 冻结契约或验证矩阵。

每项工作仍须先建立独立 Issue、`codex/` 分支、独立 worktree 和 Draft PR。未经
用户明确要求，实施 Agent 不合并 PR。

## 2. 安装与健康检查

项目当前使用 Spec Kit `1.0.4`、Codex Skills 集成和 PowerShell 脚本。新环境执行：

```powershell
uv tool install specify-cli==1.0.4
specify check
specify integration status
specify extension list
```

项目级文件位于 `.specify/`，Codex Skills 位于 `.agents/skills/`。升级前必须创建
独立接入 Issue 和可审查基线，不得直接使用 `--force` 覆盖存在未提交改动的工作区。

## 3. 新功能流程

在开始任何业务代码修改前，依次执行：

1. `$speckit-specify`：从冻结 Issue 生成用户行为、边界、非目标和验收场景。
2. `$speckit-clarify`：解决影响行为、兼容性、权限、持久化或失败路径的歧义。
3. `$speckit-plan`：基于当前代码生成技术方案、影响范围、测试和回滚计划。
4. `$speckit-checklist`：生成由用户或独立审查者确认的需求质量清单。
5. `$speckit-tasks`：把规格与方案拆成有依赖顺序、可验证的任务。
6. `$speckit-analyze`：只读检查 spec、plan、tasks 的冲突、遗漏和范围膨胀。
7. 人工门禁：规格、方案、清单和分析结果获准后才允许实施。
8. `$speckit-implement`：按任务修改代码并执行对应验证。
9. `$speckit-converge`：按原规格审查实现，追加遗漏任务，不掩盖未完成项。
10. 创建 Draft PR，记录精确命令、测试数量、跳过项、残余风险和回滚方式。

对于边界明确、风险很低的变更，可以减少文档篇幅，但不得跳过 Issue、验收条件、
影响分析、验证证据和 Draft PR。

## 4. Bug 流程

Bug 使用官方 `spec-kit-core` Bug Triage Workflow：

1. `$speckit-bug-assess`：只读复现、定位根因并形成修复建议。
2. 用户确认根因和方案后，运行 `$speckit-bug-fix` 修改评估拥有的路径。
3. `$speckit-bug-test`：只读重跑原始复现和新增回归测试。
4. 未实际运行原始复现时，只能报告 `partial`，不得报告 `verified`。
5. 创建 Draft PR，实施 Agent 不合并。

## 5. PaperAgent 强制门禁

- 根 `AGENTS.md` 的产品、V2、Workspace、ProjectVersion、Receipt、依赖方向和 Git
  纪律始终优先。
- 每个规格必须写明“不允许改坏的旧功能”和对应回归验证。
- LLM、Prompt、工具选择、RAG、长期记忆等非确定性能力必须增加固定场景评测，
  不能只依赖单元测试或一次人工对话。
- 数据库、API、权限、发布和跨服务变更必须明确失败行为、迁移兼容性、回滚方式和
  需要重启的服务。
- 任何超出冻结 Issue 的发现都应形成新 Issue，不得静默扩大当前实现范围。

## 6. 权威顺序

发生冲突时按以下顺序处理：

```text
用户当前明确决定
  -> 根 AGENTS.md
  -> .specify/memory/constitution.md
  -> 当前有效架构和产品合同
  -> 当前 Issue 的 Spec Kit 产物
  -> 其他文档和历史记录
```
