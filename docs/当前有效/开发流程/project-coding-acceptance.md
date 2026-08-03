# Project 编码闭环固定验收集

本验收集固定 Project 顶层 `DIRECT / PLAN_EXECUTE`、Plan step 内有界 ReAct、事件触发 Reflection、Candidate 审查与沙箱验证、显式 revision 应用、Evidence 隔离和 Final Synthesis 的最小真实用户旅程。它不要求新建测试框架。

## 前置条件

- 从待验收提交建立 clean 独立 worktree；记录 `HEAD` 与 `origin/main`。
- 仅使用独立 frontend/API/Broker 端口和独立数据库、Project storage。
- 准备一个带唯一验收标记的只读 Project，至少含一个可独立编译运行的源文件；记录初始 ProjectVersion、文件 hash 和行数。
- 保留测试数据；不得读取、复制或输出环境机密。
- 浏览器旅程记录 session、Plan、Candidate、validation、revision/ProjectVersion id，以及 receipt 的 `status/exitCode/timedOut/provider/stdout/stderr`。

## 固定旅程

每条只发送一个用户输入，并等待唯一 canonical answer 或权威终态后再继续。

1. **DIRECT 常识**：要求一句常识回答并明确不使用工具。期望无 Plan/Project tool/Evidence 闸门，唯一 assistant。
2. **当前版本只读**：读取指定文件，要求 ProjectVersion、file hash、准确行范围。期望所有引用绑定当前版本。
3. **成功执行**：要求编译运行。确认前不得创建 execution；确认后 receipt 必须成功，结果卡展示原始 stdout/stderr、三层状态和中文 Final Synthesis。
4. **参数自修复**：制造一个 `retryable=true` 的参数失败。期望同一 step 收到 RepairContext 后仅改变参数一次并成功；相同 tool+args 不重复。
5. **失败执行**：制造真实非零执行。期望 receipt 保持 FAILED；Reflection/模型文本不得升级为成功；最终答复只解释受信事实。
6. **自然语言修改**：不使用 Candidate 术语提出代码修改。期望路由 PLAN，恰好一个 Candidate，状态先为 `VALIDATED / NOT_APPLIED`，Project 不变。
7. **Candidate 验证**：分别验证可成功和可失败的 Candidate。期望 receipt 绑定 Candidate fingerprint + ProjectVersion；版本或 Candidate 变化后旧验证显示过期。timeout/cancel 可由现有自动测试补齐。
8. **拒绝 Candidate**：拒绝后 ProjectVersion 数量、当前版本和文件内容均不变。
9. **接受 Candidate**：仅在成功验证后显式接受。期望复用 revision 流程，恰好新增一个 immutable ProjectVersion；刷新与 API 重启后稳定。
10. **新版本隔离**：在新版本重新读取/运行。期望旧 Evidence 计数清零或标为 stale，新的 version/hash/range 才可支持回答。
11. **rollback/export**：导出当前 revision，校验 HTTP 200、ZIP 头和预期 entries；rollback 只切换 current pointer，不删除历史或 Candidate，且可切回。
12. **状态一致性**：覆盖等待确认、拒绝取消、成功、失败以及自动测试中的 timeout。核对 API/Event/UI/canonical answer 的状态一致，刷新/API 重启不重复输入、assistant 或答案。
13. **Broker 不可用**：停止仅属于本验收进程的 Broker 后，重复 DIRECT 和只读文件审查。两者必须可用且不得要求 sandbox。
14. **响应式 UI**：桌面与 390px 窄屏检查结果卡、Evidence、原始输出和确认操作；`documentElement.scrollWidth` 必须不大于 `clientWidth`。

## 自动回归

至少运行：

```text
# 仓库根目录
mvn -o -pl yanban-api -am test
mvn -o -pl yanban-sandbox-broker -am test
git diff --check

# frontend/ 目录：TS Vitest 与 node:test mjs 分开执行
pnpm exec vitest run --exclude tests/markdownNormalization.test.mjs
pnpm run test:markdown
pnpm exec vue-tsc -b --pretty false
pnpm run build
```

此外定向覆盖 revision/Candidate sandbox/confirmation、DIRECT 与 PLAN 路由、step ReAct + RepairContext、Reflection、outcome/Evidence/Final Synthesis 和 Project 结果 UI。若 Broker 未参与真实链且未修改，可以在报告中说明为何省略其测试。

## 完成清理

- 停止本验收启动的 frontend/API/Broker 及其 launcher；不得触碰共享端口和用户进程。
- 确认独立端口无监听、没有非终态 Plan/Candidate validation 或活动 sandbox。
- 报告完整 diff、逐条结果、命令结果、截图路径、所有稳定 id、未覆盖项和遗留风险；保持未提交、未推送。
