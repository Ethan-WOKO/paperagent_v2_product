# TypeScript ReAct 项目执行链路

状态：当前架构；2026-09-19；关联 Issue #228。

Project 页面使用 `agent-engine-reactplan` 的 TypeScript ReAct 执行链路。Java 产品层负责认证与授权、任务持久化、可信项目版本、Workspace、工具/沙箱网关以及验证后发布和回滚。不能将现行 Project 执行链称为旧 Java V2 plan-and-execute。

## 三条路径的边界

- Project ReAct：当前项目页的发送、事件、取消与结果入口，TS Engine 运行模型/工具循环。
- 工作区普通聊天与 Plan Mode：仍使用现有产品服务；`PlanAgentService` 是独立路径，本次不移除。
- 旧 Java V2：Planner/Step/Reflection/Final Synthesis 编排进入退役流程。旧包名不等于全包无用。

## #228 当前落地范围

- 旧自然语言、项目分析、Candidate 新建 HTTP 入口返回 410，原因以 `LEGACY_V2_EXECUTION_RETIRED` 开头。
- 项目页移除旧 V2 的自动恢复和发送处理；当前 ReAct 发送不变。
- 历史分析/Candidate GET 只返回保存状态，不再重新执行。保留原有身份和配置边界；历史 RUNNING 状态不会被伪改为成功。
- 沙箱验证失败仍返回真实失败与证据，不再创建旧 V2 自动修复计划。
- 可发现的旧兼容执行能力只保留工作区文献检索；文献结果查询/取消、普通工作区 Plan Mode 继续保留。
- ReAct 使用的确定性 Plan 初始化在 `ProjectPlanBootstrapConfiguration` 单独装配；旧执行装配仍保留兼容，不能宣布整个 Java V2 已物理删除。

## 必须保留的权威

ReAct 仍使用历史 V2 命名下的 contracts、Plan bootstrap/persistence、可信上下文和 Workspace。隔离 Workspace 的精确文件内容必须与成功验证证据绑定后才能发布；旧不可变版本和回滚继续保留。历史 API/表/包的 `v2` 作为兼容标识，不为文案统一而改数据库或破坏接口。

## 后续退役与发布门槛

本轮不读取生产数据库，不删除历史数据、不改历史 Flyway。部署前必须盘点旧 intake/adaptive/delivery/repair 非终态记录以及对应沙箱执行者；有活动执行时先 drain/cancel，禁止直接改成成功或删除记录。

旧编排私有实现、历史实体及兼容测试仍保留，待非终态处置和依赖验证完成后进一步裁剪。不要关闭 #228 或将旧链路描述为已经全部退役。后续清理须保留历史查询、会话删除、文献能力及 ReAct 的初始化、工具权限、精确验证/发布和回滚。

## 开发与验证

新 Project 功能以 `agent-engine-reactplan`、Java `agent/reactplan`、当前前端入口为基准。DSH 与 `agent-engine-codex` 不是待接入路线。测试须区分当前 ReAct、复用的历史基础设施和待退役的旧编排。

本次入口变化需验证停用 POST 不调用模型/创建任务，历史 GET 不调用执行服务，后台失败不启动旧修复，并回归 Spring 装配、ReAct Workspace/发布、工作区 Plan Mode、文献和前端构建。具体执行证据在对应 PR 中记录。
