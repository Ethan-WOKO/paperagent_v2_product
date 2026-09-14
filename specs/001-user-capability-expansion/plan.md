# Implementation Plan: 用户能力与后台体验完善
**Branch**: codex/issue-185-react-optimization | **Date**: 2026-09-09 | **Spec**: [spec.md](spec.md)

## Summary
复用 SkillsService、PaperTaskService/PaperOrchestrator、ToolRegistry 与现有消息表。新增能力在产品模块完成，不修改 agent-v2 核心。共享工具策略和 ReactPlan 网关由主实施者统一集成，避免并行覆盖。

## Technical Context
Java 17 / Spring / JPA / MySQL / Flyway；Vue 3 / TypeScript / Naive UI；Node ReAct Engine。
测试：JUnit/Mockito/H2，Vitest，前端 build，相关 Engine 测试。真实模型与服务联调另记可用性。

## Constitution Check
用户明确授权当前本地 185 开发；四个边界分别有 Issue 与验收。复用既有权限和任务持久化。
本次不改变 ProjectVersion 发布、原始文件、V2 依赖方向、Broker 协议，不迁移既有数据。
已授权直接实施，先形成可审阅 spec/plan/tasks，独立审查覆盖隔离、幂等和兼容性。

## Project Structure / Owned Paths
- US1: yanban-api 的 skills 包及其测试；个人 Skill 新 Flyway migration；frontend/src/api/skills.ts、SettingsPage.vue 与独立 Skill 安装组件及测试。
- US2: yanban-api/agent 论文工具、新论文任务适配及测试；必要论文层任务幂等路径和 migration（先协调版本）；输入页面组件如必要。
- US3: yanban-api/agent/history 新服务与工具及测试；yanban-core/agent 的 owner-scoped 消息仓库查询；ReactPlan 历史结果源。
- US4: frontend/src/views/AdminPage.vue，定向布局验收。
- Shared: AgentToolPolicyEngine、AgentStrategySelector 的本次能力可达性、LangChain4jToolProvider/Strategy、AgentRuntimeRequest 与 AgentService/PlanAgentService 的稳定请求/步骤身份绑定、ToolExecutionContext、工具注册配置、ReactPlan registered gateway、agent-engine-reactplan/src/engine.ts/types.ts 及对应 tests、当前 specs。

## Design Decisions
- Skill owner-qualified DB 存储，独立 ID，严格大小与工具声明验证；已启动任务继续使用冻结快照。
- 安装格式同时符合现有 ReAct 提交契约：完整 prompt 不超过 32,000 Unicode 码点且文件不超过 64 KiB，工具名仅小写 snake_case（最多 64 字符）；不放宽 Engine schema。
- 论文 start 复用持久任务，输入必须经过 owner/scope 校验；服务器生成重放键；禁止将任务排队描述为润色完成。
- 历史工具搜索/详情返回安全消息投影，认证上下文提供 userId，拒绝模型传入身份参数；包含 ReactPlan 最终交付。
- 新工具通过已有 registry/policy 暴露两条链路；普通聊天现有文献 task deny 边界不变。
- 工作区忽略旧 PAPER_REVISION 的页面导航建议，继续进入正常工具策略与模型；保留既有文献检索/确认快捷返回。覆盖真实 sendMessage 入口，包括附件指导文本误触导航的回归。
- 普通聊天 AUTO 在有本次个人能力和预算时进入已有 native-tool 模型轮次，模型可零工具直接回答，不新增意图路由调用；否则“润色论文”“上次的方案呢”会被旧关键词选择器误送到无工具 DIRECT。显式 DIRECT、空 Skill 和 Project 的计划边界不变。代价是这类普通回答也携带工具描述，增加少量上下文 token。
- 后台限制聊天列表视口，保留展开和删除语义，并验证移动端。

## Independent Review Resolutions
- ToolExecutionContext 增加仅服务端写入、finally 清理的 invocationScope（直接受影响的共享 owned path）。Project 使用固定 taskId；普通聊天用 sessionId + HTTP clientRequestId 摘要（无请求键时退到持久 AgentTurn ID）；旧 Plan 步骤用持久 planId+stepId，跨 attempt 稳定。其他内部入口使用一次 runtime 固定 UUID，所有 request.with... 复制保留。不使用可由 X-Trace-Id 影响的 traceId 作为幂等身份。HTTP key 仅作本人请求幂等键，模型工具不能填写，也不能代替身份授权。论文每个 scope 最多一个启动，改变输入冲突；V103 记录 owner/scope/inputDigest/task，跨新 turn 和进程重放不依赖聊天内存缓存，并验证并发与创建后分发恢复。
- Project 润色的 expectedProjectVersion 由网关覆盖注入；模型不能提供 request ID、身份或存储路径。
- 个人 Skill：SKILL.md 文件选择、可选 skill.yaml；上限分别 64KiB/16KiB；最大 100 项。声明缺省工具采用明确的保守默认值，空列表拒绝工具；最终语义在安装界面展示。运行时检查冻结 Skill 工具交集。
- 历史必须可搜索仅存在于 ReAct delivery 的词；稳定游标同时覆盖两类来源，搜索后会话删除时详情拒绝。
- 管理后台使用单个聊天列表滚动区域避免展开消息再形成第二个滚动层。
- Paper 状态按目标参数摘要持久限制轮询，不按工具名整体隐藏；一个目标终态不妨碍同批另一目标，重复目标只返回非执行反馈。既有 literature 状态抑制边界不改变。

## Failure / Compatibility
非法 Skill 不写入；未知/禁用/他人 Skill 拒绝。论文越权或无效输入在创建前拒绝；重复请求回放。
历史查询参数错误、无权限及空结果区分明确，内容标识 untrusted。数据库失败不得返回他人内容。
不承诺模型自然语言选择完全确定；补充固定人工/真实模型场景供联调。

## Verification / Rollback / Restart
运行新增和直接受影响 Java 测试，frontend build 与行为测试，Engine typecheck/相关测试。
数据库迁移追加新表/索引，不改旧迁移；回滚应用时保留新表。撤销各功能提交可独立回退。
后端必须重启，Engine 提示词/工具行为修改后重启 Engine；前端重新构建或开发热更新。
论文任务联调需要数据库、现有任务分发和存储服务；不重构 Broker。
