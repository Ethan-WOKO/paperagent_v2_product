# Executor 评测集合

当前集合为 `executor-decisions-v1`，由 23 个场景组成，目标是检查执行器在不启动
后端的情况下能否完成以下判断：

- 四种输出形式都能在相应阶段出现；
- 16 种只读、检索、分析和执行工具能够相互区分；
- 普通搜索与跨材料搜索、四种 LaTeX 工具、文档与表格、读取与运行等相近工具
  不发生混淆；
- 已有成功回执时不重复调用工具；
- 失败重试能够绑定上一次正式动作和错误；
- 缺少权限或待确认信息仍不完整时能够正式阻断。

评测数据位于后端测试资源中的
`executor-evaluation/executor-decisions-v1.json`。普通测试只校验集合本身的完整性，
不访问模型，不产生费用。

真实模型评测需要显式提供模型密钥并开启开关：

```powershell
mvn -pl yanban-api -am `
  "-Dtest=com.yanban.api.agent.v2.chain.context.ExecutorDecisionEvaluationTest" `
  "-Dexecutor.eval.enabled=true" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

可使用 `-Dexecutor.eval.ids=exact-text-read,sandbox-java-run` 只运行指定场景，或使用
`-Dexecutor.eval.limit=3` 限制数量。报告输出到
`yanban-api/target/executor-evaluation/report.json`。

这套集合评测的是模型选择和结构输出，不替代后端权限、参数、工作区、回执和恢复
逻辑的自动化测试。
