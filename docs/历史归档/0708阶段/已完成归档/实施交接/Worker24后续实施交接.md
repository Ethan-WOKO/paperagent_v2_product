# Worker 24 后续实施交接

> 文档分类：**已完成归档**。Worker 24 之后的 V2、Candidate、E2B、Reflection 和真实链路修复已经继续推进；本文只保留当时停止位置和历史验收线索，不能作为当前任务入口。

更新日期：2026-07-23

## 1. 文档定位

本文用于新对话快速接手当前工作；正式阶段、范围和验收门仍以《科研 Project Agent 第一版实施计划.md》为准。

当前结论：Worker 24 尚未通过用户终验，禁止启动 Worker 25，禁止把当前累计改动宣称为已完成。

## 2. 当前工作区

- 根目录：`C:\java_file\private_helper_Agent\private-helper-test(0708)`。
- 当前阶段按用户要求直接在根目录开发，不再把后续修复仅留在 `.codex` 独立 worktree。
- 根目录存在 Worker 24 累计代码、测试和文档修改，也存在用户/验收数据：`.codex-worker13-acceptance/`、`test_data/` 和 PDF。不得 reset、覆盖或删除不属于本任务的内容。
- 当前累计修改尚未提交、推送或合入新的验收 commit。
- `.env` 与 `.env.sandbox.local` 只能只读使用，不得修改、复制、输出或持久化密钥。测试登录可用 `admin / 12345678`。
- 用户通常自行启动前端、API 和 Broker；除非用户明确要求，不要启停共享服务或用户进程。

## 3. Worker 24 已完成的主体

### 同会话上下文

- 统一 ContextPackage：完整 current user message、最近完整 canonical turns、较早滚动 summary、当前 ProjectVersion、Evidence 引用、受治理长期偏好。
- 当前消息不重复进入历史；最近轮次整轮保留；预算不足时按整轮淘汰。
- session/user/project 权限隔离；快照持久化并兼容旧格式。
- 前端提供临时只读上下文调试区，展示真实分区、来源、预算、丢弃和截断，不展示密钥、系统提示或思维链。

### Planner 与 Candidate 执行链

- Planner 首轮与 bounded repair 都显式携带完整当前请求。
- 结构化 PlannerRepairContext 可报告缺失 Candidate、sandbox、final synthesis 和错误依赖链。
- “修改并运行”计划必须形成：可信读取 -> 唯一 NOT_APPLIED Candidate -> 唯一 sandbox -> 最终综合。
- Candidate 可以表达 ADD、MODIFY、DELETE，但 Agent 不直接改 Project；显式接受前始终 NOT_APPLIED。
- sandbox 只能执行服务端根据 Plan 依赖、治理事件、ProjectVersion 和 artifact 解析出的 Candidate 覆盖层；模型文本本身没有执行权限。

### 沙箱与结果

- Broker 全执行周期连续续租。
- Provider 故障与用户代码编译/运行失败分开。
- E2B 用户程序实际非零退出、stdout/stderr 和阶段诊断可保留。
- 编译错误可转成 RepairContext；LLM 最多创建 2 次修复 Candidate。
- 修复步骤在原始用户已授权代码变更时可获得 `project_propose_candidate`，但不扩大其他权限。
- 成功时 Final Synthesis 应返回实际在沙箱执行并验证的完整 Candidate 代码，Project 保持不变。
- Project 输入框支持 Enter 发送、Shift+Enter 换行，并规避输入法组合态误发送。

## 4. 已执行的工程验证

- 最新 Candidate 修复相关定向回归：127 项通过。
- 最新完整 API reactor：1320 tests，0 failures，0 errors，10 skipped。
- Broker 既有完整回归：contract 2 + broker 25，0 failures/errors，1 个环境条件 skip。
- E2B Provider Python 定向：1/1 通过。
- 前端 Project 输入相关测试：2/2 通过；`vue-tsc` 通过。
- `git diff --check` 通过。

这些自动测试不能替代下面的用户真实终验。

## 5. 当前阻断与根因线索

### 仍需修复的 Evidence 闭环

用户真实测试出现：

`INSUFFICIENT_EVIDENCE: Project step completed without a current authorized file observation.`

含义不是“Agent 没有增删改能力”，而是某个文件相关步骤结束时，没有可用于当前 ProjectVersion 的受权文件观测。常见原因：

1. 该步骤没有实际调用 `project_read_file`。
2. 读取路径与后续目标路径不一致。
3. Evidence 属于旧 ProjectVersion、旧 hash 或错误 range。
4. Planner 的依赖链没有把读取步骤连接到 Candidate/分析步骤。
5. 模型只根据摘要作答，Verifier 却要求当前文件事实。

正确恢复方式是重新读取、修正依赖或 bounded replan；不能放宽 Evidence，也不能只把内部错误返回用户。

### 旧 Plan 不会继承新代码

后端修复后必须重启服务，并用全新 session 或至少全新 Plan 测试。已经终态失败的旧 Plan 不会自动获得新的工具权限、repair 规则或 Candidate 依赖。

## 6. Worker 24 必须完成的真实终验

使用真实 `Sort.java` 和一个全新 session/Plan：

1. 用户要求在原代码上加入归并排序，在沙箱验证，失败时修复，但不修改当前文件。
2. Agent 读取当前 `Sort.java`，Evidence 绑定当前 ProjectVersion/path/hash/range。
3. 创建唯一 `NOT_APPLIED` Candidate，包含归并排序。
4. 用户确认后，sandbox 执行 Candidate 覆盖层，不是旧 Project 文件。
5. 首次因坏 import 编译失败时，保存真实 stderr 和权威失败 receipt。
6. RepairContext 把编译错误交给修复步骤。
7. 新 Candidate 移除坏 import，同时保留归并排序；仍为 NOT_APPLIED。
8. 第二次执行成功，receipt 为成功且 stdout 能证明归并排序正确。
9. 最终只产生一个 canonical answer，并返回实际验证过的完整代码。
10. ProjectVersion 与 Project 文件不变；刷新/API 重启后不重复消息或结果。

同时抽查 DIRECT、纯只读文件问答、同 session 指代、等待确认、拒绝、失败、上下文调试区和窄屏。

用户亲自确认上述测试通过后，才允许提交、推送、更新 Worker 24 状态并启动 Worker 25。

## 7. 后续顺序

1. 收口 Worker 24 Evidence 与 Sort.java 真实闭环。
2. Worker 25：最小 TaskFrame 和同 session 指代解析。
3. Worker 26：Router、Planner、Step、Reflection、Final Synthesis 统一消费 TaskFrame并防止目标漂移。
4. Worker 27：同 session 短期工作记忆、活跃对象、未完成要求和滚动摘要。
5. 新增确定性 `project_compare_files`，以真实行级 diff 取代“两份摘要再推断差异”。
6. 增加结构化 stdin/参数/测试夹具和 Provider 全阶段可观测性。
7. 再做联网依赖安装与完整项目执行：受控 Maven/Gradle/pip/npm 等模板、分阶段 receipt、禁止上传本机密钥和无关配置。
8. 用固定真实用户矩阵收口全部能力。

## 8. 新对话建议起始指令

> 读取《Worker24后续实施交接.md》和《科研 Project Agent 第一版实施计划.md》，继续收口 Worker 24。先审查根目录实际 diff、当前服务状态和最新测试，不 reset、不提交、不推送、不启动 Worker 25。复现并修复 `INSUFFICIENT_EVIDENCE`，然后由我用 Sort.java 完成真实用户验收；只有我明确确认通过后，才能提交并进入 Worker 25。
