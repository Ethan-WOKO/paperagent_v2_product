# 本地 Merge Gate

本文档定义 PR 合并前默认需要执行的本地检查。

目标不是让每个小改动都跑完整测试，而是让每个 issue 都能被验证、复现，并诚实说明风险。

## 每个 PR 都要执行

每个 PR 都应执行：

```powershell
git status --short
git diff --check
```

push 前还要确认目标仓库：

```powershell
git remote -v
git branch --show-current
```

本项目预期 remote 为：

```text
https://github.com/Ethan-WOKO/paperagent_redo.git
```

## 仅文档变更

适用于 Markdown、GitHub 模板、流程文档和规划文档：

```powershell
git diff --check
```

仅文档变更可以不跑完整后端或前端构建。如果跳过，PR 中必须说明原因是没有修改运行时代码。

## 后端基线检查

适用于后端代码、Maven 配置、migration、模型/runtime 逻辑、RAG、论文、知识库、认证或 API 变更：

```powershell
mvn -q -DskipTests validate
```

根据影响模块运行 focused tests：

```powershell
mvn -pl yanban-core test
mvn -pl yanban-knowledge test
mvn -pl yanban-paper test
mvn -pl yanban-api test
```

如果是大范围或跨模块改动，运行：

```powershell
mvn test
```

如果完整 `mvn test` 无法执行，PR 必须写明：

1. 精确失败信息或跳过原因。
2. 已执行的 focused tests。
3. 剩余风险。
4. 后续补验证计划。

## 前端基线检查

适用于前端代码、路由、UI 状态、API client 或构建配置变更：

```powershell
cd frontend
$env:CI='true'
pnpm build
```

`CI=true` 可以避免 pnpm 在非交互环境中重建依赖时失败。

如果 UI 工作流发生变化，应补充手动验证说明；必要时提供截图。

## 数据库和 Migration 变更

适用于 Flyway migration、entity 或 repository 变更：

```powershell
mvn -pl yanban-api test
```

至少需要确认：

1. MySQL migration 命名和顺序正确。
2. 需要时同步 H2 test migration。
3. 现有 repository 测试仍然通过。
4. PR 中说明回滚或 forward-fix 方案。

## Agent、RAG、Tool 和 Memory 变更

涉及 Agent、Harness、工具调用、RAG、文献推荐、论文润色质量或记忆系统时，单元测试不够。

必要检查包括：

1. 覆盖被修改 service 的 focused unit/integration tests。
2. 行为质量可能变化时执行 eval case。
3. 验证最终用户可见回答不会重复。
4. 验证工具 trace 不会污染聊天气泡。
5. 验证失败或 degraded 输出能被用户理解。

在正式 eval runner 建立前，PR 中应包含一张小型手动 eval 表。

## 长任务变更

涉及论文润色、文献检索、Kafka 任务分发、任务生命周期、取消或事件流时，需要验证：

1. 创建任务会返回 task id。
2. 任务状态可以查询。
3. 取消或停止请求会被受理。
4. 已取消任务不会自动重试。
5. 部分产物会标记为 partial 或 cancelled。
6. 重连或刷新不会产生重复最终回答。

## PR 报告要求

每个 PR 都必须报告：

```text
执行的命令：
- ...

跳过的检查：
- ...

原因：
- ...

剩余风险：
- ...
```

不要只写“测试通过”，必须列出精确命令。
