# 通用 Agent Runtime 实施方案与进度

> 文档分类：**已完成归档**。本文记录旧 Runtime 的阶段实施，不再决定当前 V2 开发顺序。

> 文档状态：执行中  
> 最近更新：2026-07-10  
> 设计依据：[通用 Agent Runtime 设计](./通用%20Agent%20Runtime%20设计.md)  
> 本文用途：记录实施顺序、并行协作规则、验收流程、当前进度和后续阻塞项。架构与产品契约仍以主设计文档为准。

## 1. 实施目标

本轮工作的目标不是一次性重写现有 Agent，而是在保持普通对话、论文润色和文献检索链路兼容的前提下，逐步建立一个可用于科研 Project 的通用 Agent Runtime。

最小 MVP 最终应达到：

1. 用户显式授权一个本地科研 Project。
2. Agent 只能通过 `projectId` 和 Project 相对路径访问授权范围。
3. Runtime 能根据任务选择 `DIRECT`、`REACT` 或受控的 `PLAN_EXECUTE`。
4. 模型看到的工具和执行器实际允许的工具来自同一份已解析策略。
5. Agent 能读取代码、论文、科研报告、实验材料和文献，形成带来源与版本的证据。
6. Agent 能完成跨文件只读审查，并生成候选 patch 或建议产物，但 MVP 不直接写入用户文件。
7. 任务结束必须有可验证结果，不能把模型回答流畅或 Planner 降级当成真实完成。
8. 普通对话与 Project 模式共用同一个 Runtime，通过能力配置和权限策略区分。

## 2. 组织方式

### 2.1 角色分工

| 角色 | 责任 | 无权自行决定的事项 |
| --- | --- | --- |
| 当前主对话 | 架构判断、任务拆分、跨模块契约、代码复审、回归验证、阶段验收 | 不在 worker 实施期间直接抢改同一批文件 |
| 独立 worker 对话 | 在指定文件所有权内实现、补测试、运行回归、提交实施报告 | 不得跨目录修改，不得自行宣布阶段验收通过 |
| 用户本地审查 | 产品方向确认、真实环境测试、接受或否决关键语义 | 不需要以单个组件测试代替完整业务验收 |
| 评测任务 | 建立确定性安全用例和基线矩阵 | 不得修改生产代码来掩盖失败 |

### 2.2 完成定义

worker 返回“已完成”只表示进入复审，不表示已经合格。一个任务必须依次经过：

```text
实现完成
  -> worker 定向测试
  -> 主对话代码复审
  -> 修正审查问题
  -> 主对话独立复跑测试
  -> 与其他模块联合回归
  -> 用户本地场景验收
  -> 状态冻结为 ACCEPTED
```

### 2.3 状态定义

| 状态 | 含义 |
| --- | --- |
| `NOT_STARTED` | 尚未启动 |
| `IN_PROGRESS` | worker 正在分析、编码或测试 |
| `SUBMITTED` | worker 已提交报告，等待主对话复审 |
| `CHANGES_REQUIRED` | 复审发现必须修正的问题 |
| `ACCEPTED` | 代码、测试和约定范围内的验收均已通过 |
| `BLOCKED` | 存在明确外部依赖或跨模块决策，当前不能继续 |

## 3. 实施原则

1. **先契约，后集成。** 先固定任务状态、工具结果、权限和错误语义，再建设 Coordinator。
2. **先只读，后写入。** Project MVP 不开放写入、删除、重命名和命令执行。
3. **先确定性安全，再依赖模型表现。** 路径越界、权限绕过、终态一致性等必须由确定性测试验证。
4. **并行只做可隔离的基础模块。** 共享核心入口的集成统一后置，避免多个 worker 同时修改 `AgentService`。
5. **fail-closed。** 缺少权限、元数据未知、策略冲突或路径无法证明安全时必须拒绝。
6. **不可信内容不得升级权限。** Project、RAG、网页和工具文本不能作为 system 指令注入。
7. **真实完成必须有证据。** 最终结论需要引用工具结果、文件版本、产物或验证记录。
8. **兼容现有垂直能力。** 通用 Runtime 的建设不能无意改变论文润色和文献检索的业务行为。
9. **不撤销其他对话的修改。** 共享工作目录出现陌生变更时先识别所有权，再继续工作。
10. **每阶段冻结已验收语义。** 后续 worker 不得自行重新解释前一阶段契约。

## 4. 总体实施阶段

### 4.1 MVP-0：契约与基线

目标：建立后续所有模块共同依赖的最小契约，修复当前 Plan、ReAct 和工具策略之间的语义分叉。

主要内容：

- 任务 `status / phase / outcome` 契约。
- typed `ToolResult`、`ToolErrorCode`、最小 `ToolDescriptor`。
- `ResolvedToolPolicy` 作为模型与执行器的唯一已解析策略。
- `null = 继承`、`[] = deny-all`。
- Plan 和 ReAct 使用相同工具策略边界。
- chat 可见消息与 `view=all` 审计消息分离。
- RAG provenance 进入实际注入上下文。
- 测试环境关闭 Demo 初始化和外部 Embedding 请求。

验收状态：`ACCEPTED`。

已独立验证：

- 契约及定向回归：48 tests passed，0 failures，0 errors。
- `AgentControllerIntegrationTest`：22 tests passed。
- `PlanAgentControllerIntegrationTest`：3 tests passed。
- 两组合计：73 tests passed，0 failures，0 errors。

冻结语义：

- Plan 中 `allowedTools=[]` 明确表示该步骤禁止全部工具。
- camelCase 字段存在时，旧别名不得覆盖其空集合。
- 空 Skill allowlist 为 deny-all。
- 结构化 `success:false` 必须记录为工具失败。
- 终态必须满足 `FINALIZING + 匹配 outcome`。
- 未声明副作用的工具默认为 `UNKNOWN`，不能按只读处理。
- `SendMessageResponse.messages` 只返回 chat 可见消息，过程和工具历史保留在审计链路。

### 4.2 第二阶段：四个基础模块并行

本阶段并行建设独立基础能力，但不直接完成 Runtime 总集成。四个任务全部使用同一本地工作目录，因此文件所有权是硬边界。

#### A. Project 只读边界

目标：建立 Project 实体、授权根目录和只读文件访问能力。

文件所有权：

- `yanban-api/src/main/java/com/yanban/api/project/**`
- Project 相关新增测试。
- 仅新增下一版本数据库 migration，不修改历史 migration。

必须覆盖：

- `ProjectRootProvider` 与 `LOCAL_SERVER_ROOT`。
- API 和模型只使用 `projectId + relativePath`。
- 拒绝绝对路径、`..`、normalize 越界、符号链接逃逸和未授权访问。
- 只读清单、读取、文本搜索。
- 文件哈希或等价版本标识。
- 不暴露写入、删除、移动和重命名。

当前状态：`ACCEPTED`。

任务对话：`Project 只读边界`，ID `019f4c37-1dd0-7e31-9a33-123d77ad4bcb`。

实时进度：三轮复审完成。资源预算、受信 provision、HTTP 错误契约、规则 fail-closed 和真实 MVC 覆盖均已收口；独立复跑 16 项为 0 failures、0 errors、2 skipped。符号链接逃逸用例仍须在支持该能力的 CI 环境实际通过后才能升级为发布门 PASS。

#### B. 工具治理与动态暴露

目标：将 `ToolDescriptor` 接入 ToolRegistry 和策略边界，形成确定性的动态工具暴露。

文件所有权：

- `yanban-core/src/main/java/com/yanban/core/tool/**`
- `AgentToolPolicyEngine`、`ResolvedToolPolicy`。
- 与 LangChain4j 工具暴露直接相关的 provider/strategy 类及测试。

必须覆盖：

- 名称、版本、能力域、副作用、权限、异步语义和资源范围元数据。
- `UNKNOWN` 副作用 fail-closed。
- CHAT/PROJECT、Skill、用户权限和步骤 allowlist 求交。
- 模型可见工具与执行器允许工具使用同一策略。
- 隐藏内部工具不能通过 Planner、模型参数或旧别名暴露。
- 重复调用规则能够区分真正重复、异步状态推进和幂等重试。

当前状态：`ACCEPTED`。

任务对话：`工具治理与动态暴露`，ID `019f4c37-3b60-7021-89ba-403302c8c887`。

实时进度：三轮复审完成。普通 CHAT 研究工具兼容性、受控执行二次门禁、外部只读语义、隐藏三段式工具、路由提示词和幂等重试计数均已收口；独立复跑 core 14 项、API 联合 56 项全部通过。

#### C. 上下文与证据

目标：建立最小 ContextPackage 和 EvidenceLedger，修复不可信内容使用 system role 的问题。

文件所有权：

- `AgentContextBuilder`、`AgentExperimentService`。
- `Agent*Context*` 相关类。
- 新增 evidence/context DTO、服务及测试。

必须覆盖：

- 只有 Runtime 自有策略、身份和安全约束使用 system role。
- RAG、Project 占位片段、网页和工具文本作为有来源边界的不可信数据注入。
- 保存适用的 `source / file / chunk / citation / version / selectionReason`。
- 上下文裁剪保留用户约束、确认决定、Project ID 和未完成任务。
- 建立最小 `EvidenceRef / EvidenceLedger`。
- 保持普通 CHAT 行为兼容。

当前状态：`ACCEPTED`。

任务对话：`上下文与证据`，ID `019f4c37-67c2-72f2-a5cd-c9b3e2d465ee`。

实时进度：核心实现和交叉聚合补充修正均已通过。两处旧 RAG 文本断言已改为确定性验证 mode、检索提示和 source/file/chunk/citation provenance；41 项定向回归及根目录全聚合通过，任务冻结为 `ACCEPTED`。

#### D. 评测与安全基线

目标：建立不依赖模型概率的确定性安全回归和当前能力矩阵。

文件所有权：

- `yanban-api/src/test/**` 下独立 eval 测试。
- `yanban-core/src/test/**` 下契约安全测试。
- 本目录下新增评测用例或基线报告。
- 默认不得修改生产代码。

必须覆盖：

- 路径逃逸和未授权访问。
- deny-all、隐藏工具和 Planner 权限升级。
- 重复调用、异步推进、预算和无进展。
- Planner fallback、工具结构化失败和虚假完成。
- Prompt Injection、provenance 和审计完整性。
- 任务终态一致性。

当前状态：`ACCEPTED`。

任务对话：`评测与安全基线`，ID `019f4c37-8b78-7f72-9481-1b0af51c8009`。

实时进度：首轮问题已修正并通过复审。10 项 PASS 均已映射到可重复测试，7 项未实现能力继续标记为 `NOT_IMPLEMENTED`/pending sentinel；独立复跑 core 2 项和 API 9 项全部通过。

### 4.3 第二阶段复审与集成门禁

四个 worker 完成后，不立即进入下一阶段。主对话按以下顺序复审：

当前状态：`ACCEPTED`，阶段结论为 `ACCEPTED_FOR_COORDINATOR`。

审查对话：`交叉集成审查`，ID `019f4c8e-58de-7f61-9418-3e59b1f6213a`。该任务只读检查和串行运行回归，只允许新增交叉审查报告，不修改生产代码或测试。

1. **Project 安全边界审查**：路径解析、符号链接、授权根、include/ignore 规则、版本标识。
2. **工具治理审查**：策略求交、UNKNOWN 副作用、内部工具、重复与异步语义。
3. **上下文安全审查**：消息角色、provenance、裁剪、证据账本和 CHAT 兼容。
4. **评测审查**：确认失败项没有被跳过或伪装为通过。
5. **交叉冲突审查**：数据库 migration、共享测试、构造器兼容和 Spring Bean 装配。
6. **独立回归**：分别运行组件测试、Agent 控制器集成测试和涉及模块的联合测试。

只有同时满足以下条件，第二阶段才可标记为 `ACCEPTED`：

- 四个任务均已完成复审，没有未解决的 P0/P1 问题。
- MVP-0 的 73 项基线测试继续通过。
- 新增安全测试中不存在被错误标记为 PASS 的未实现能力。
- 普通对话、论文和文献组件回归没有契约性退化。
- 所有跨模块接口已经由主对话统一决定，不存在 worker 私有语义。

### 4.4 第三阶段：Runtime Coordinator

状态：第三阶段 `IN_PROGRESS`；`MVP-2A：Coordinator 契约与 CHAT 骨架` 已 `ACCEPTED`，下一切片为统一 Plan adapter。

任务对话：`Runtime Coordinator 契约与骨架`，ID `019f4cb3-962b-7840-a7c4-2d1140463d35`。本切片只允许一个编码任务修改共享 Runtime 入口，当前主对话负责复审。

启动条件：第二阶段核心接口完成复审并冻结。

#### 4.4.1 本切片目标

- 在现有 `AgentRuntimeService` 之上新增唯一的 Coordinator 门面，普通 CHAT 由 `AgentService` 适配后委托给该门面，不改变外部 Controller 请求/响应。
- 将“解析完成的请求”和“执行策略”分离：进入执行器前必须已有非空 `ResolvedToolPolicy`、模型端点、预算、上下文和可信 capability；不能用占位 strategy 绕过选择。
- 本切片的生产路由只接管 CHAT 的 `DIRECT / SINGLE_STEP_REACT`。现有 Plan API 暂作为 legacy adapter 保留，但 Planner 失败语义必须先收口，供下一切片接入 `PLAN_EXECUTE`。
- 统一同步运行结果中的 selected strategy、停止原因、成功/失败 outcome 和降级标记；不新增数据库 migration，不在本切片重构长任务持久化。
- 保持普通对话、论文润色和文献检索行为兼容。

#### 4.4.2 冻结策略语义

| 输入场景 | 决策 |
| --- | --- |
| CHAT 且已解析工具集合为空 | `DIRECT` |
| CHAT 且已解析工具集合非空 | `SINGLE_STEP_REACT` |
| 受信 Plan API/内部适配器明确请求计划 | `PLAN_EXECUTE`，由下一切片接入统一执行 |
| 用户仅在自然语言中出现“计划、分析”等词 | 不得因此机械进入 Plan |
| 精确 legacy 命令 `/plan reflect` | 保持现有兼容入口，但必须标记为显式请求，不能由模糊关键词触发 |
| capability、工具策略或预算未解析 | fail-closed，不进入模型或执行器 |

策略选择不允许模型提升权限。执行范式与文件写入权限相互独立；本阶段仍没有 Project 写权限。

#### 4.4.3 Planner 失败契约

- `PlanningAgentPlanner` 的模型调用异常、空响应、非法 JSON、缺失/空 steps 必须返回显式失败结果，至少区分 `MODEL_CALL_FAILED / EMPTY_RESPONSE / INVALID_PLAN / NO_STEPS`。
- Planner 失败时不得生成 `PlanSpec`、不得持久化普通可执行步骤、不得显示为计划创建成功。
- 默认策略是显式失败。未来允许降级到 ReAct 时，必须由 Coordinator 的受信策略明确授权，并在结果和审计中记录 `degradedFrom=PLAN_EXECUTE` 与原因；本切片不默认开启降级。
- Recovery planner 可继续返回失败/无恢复计划，但不能伪造恢复步骤。

#### 4.4.4 工具执行门禁

- `ResolvedToolPolicy` 是模型暴露和执行的唯一许可来源；`null` 不得进入 Runtime，`allowedTools=[]` 即 deny-all。
- `LangChain4jToolProvider` 的兼容 allowlist 必须与 runtime policy 完全相等，不能扩大。
- 注解工具桥接不得在内部直接调用 trusted `ToolRegistry.execute(call)` 绕过策略。桥接执行必须携带本次 resolved allowlist，或通过只在调用期有效的受治理执行上下文二次校验。
- 没有执行上下文、工具不在 allowlist、descriptor 为 UNKNOWN、需要确认但未确认时，注解方法必须 fail-closed。
- trusted 无策略执行入口仅限明确的领域内部调用，不得作为模型工具桥接入口。

#### 4.4.5 文件所有权

本任务允许修改：

- 新增独立 Coordinator 请求、决策、结果、停止原因和服务类。
- `AgentRuntimeService`、`AgentStrategySelector`、`AgentRuntimeRequest/Result` 的最小兼容修改。
- `AgentService` 中构建并调用 Runtime 的窄适配段，不改 Controller API、消息可见性和持久化格式。
- `PlanningAgentPlanner` 以及 `PlanAgentService` 中处理显式 Planner 失败所必需的最小分支；不得借机重写 Plan 调度器。
- `LangChain4jToolProvider`、`AgentLangChain4jTools`、`ToolExecutionContext` 及其直接测试，用于收口注解桥接执行门禁。
- `LangChain4jToolCallingStrategy` 仅允许传播结构化 Runtime stop signal 及补对应测试，不得借机修改工具选择、重复调用或领域路由语义。
- 对应 Coordinator、Planner、策略、工具门禁和 Controller 回归测试。

本任务禁止修改：Project 文件服务、ContextPackage/EvidenceLedger 语义、论文和文献领域流程、前端、数据库 migration、Verifier/Reflection、多 Agent、文件写入和命令执行能力。

#### 4.4.6 验收门禁

- 普通 CHAT 请求实际经过 Coordinator；DIRECT 与 REACT 的选择有确定性测试，deny-all 不会被放宽。
- Planner 四类失败均不会产生可执行 fallback plan；Plan API 不会把失败返回为创建成功。
- 直接调用注解工具且缺少 resolved policy 时被拒绝；合法 Provider/Strategy 路径继续执行已允许工具。
- 同步结果能区分正常成功、显式失败、预算停止和未来降级占位，不把模型流畅回答当作 Planner 成功。
- `AgentControllerIntegrationTest`、`PlanAgentControllerIntegrationTest`、Planner/Runtime/Policy/ToolProvider 定向回归通过。
- 论文与文献工具组件回归通过，根目录 `mvn test` 全绿。

#### 4.4.7 本切片明确不完成

- Plan API 与 CHAT 的完整统一编排、Project 工具注册、ContextPackage 的 Project 接线、Verifier、Reflection、长任务托管、多 Agent 和候选 patch。
- `MVP-2A` 通过只表示 Coordinator 契约与 CHAT 骨架可复用；第三阶段仍需后续 Plan adapter 切片才能整体完成。

硬阻塞项：现有 `PlanningAgentPlanner` 仍会生成伪装为正常步骤的 fallback plan；注解工具桥接内部仍可调用 trusted Registry 执行。两项必须在本切片验收前消除。

#### 4.4.8 接下来仅执行两步

本轮只规划并依次实施以下两步。第一步验收前不启动第二步；第二步之后的 Verifier、Reflection、候选 patch、写入、长任务重构和多 Agent 不在本轮计划内。

##### 第一步：MVP-2B 统一 Plan Adapter

状态：`ACCEPTED`。任务 `统一 Plan Adapter`，ID `019f4d15-73a6-7370-a455-0dbf9b38f629`；两轮复审完成，创建/执行受信边界、canonical planId、暂停兼容和结构化预算停止均已收口。

目标：

- 为 Coordinator 增加受信请求类型或 capability，明确区分普通 CHAT、受信 Plan API 和 legacy `/plan reflect`；模型文本不得把 CHAT 提升为 Plan。
- 让现有 Plan API 的创建与执行从统一 Coordinator/adapter 边界进入 `PLAN_EXECUTE`，同时保持 Controller 请求响应、计划表、步骤表、事件表和异步接口兼容。
- 每个 Plan 步骤继续复用现有、受预算约束的 `SINGLE_STEP_REACT`，步骤工具集合必须与 Coordinator 已解析策略求交，不能自行放宽。
- 将计划终态确定性映射到统一结果：完整完成、部分/降级、失败、暂停/等待和预算停止不能被流畅总结文本覆盖。
- 保持 MVP-2A 的 Planner 显式失败、注解工具二次门禁、chat/audit 可见性和论文/文献领域流程不变。

文件所有权：

- Coordinator 请求、决策、结果、策略选择与 Plan adapter 新增类。
- `PlanAgentController`、`PlanAgentService`、`PlanReflectionRuntimeAdapter` 中接入统一入口所必需的窄适配段。
- `AgentRuntimeService/Result` 与 Plan 相关测试的最小兼容修改。
- 禁止修改 Project 服务、ContextPackage/EvidenceLedger、领域论文/文献流程、前端和 migration；禁止在本步实现 Verifier、Reflection 重构、文件写入和多 Agent。

验收门禁：

- 普通 CHAT 不会因自然语言出现“计划/分析”进入 Plan；受信 Plan API 确定性选择 `PLAN_EXECUTE`；legacy `/plan reflect` 仍兼容且边界明确。
- Planner 失败不持久化成功计划；Plan 失败、降级、暂停和预算停止不会被 Coordinator 标成普通成功。
- Plan 步骤不能扩大 resolved tool policy，`null / []` 契约保持。
- Plan/Agent Controller、Planner、Runtime、Policy、论文和文献定向回归通过，根目录 `mvn test` 全绿。

##### 第二步：MVP-3A Project 只读垂直闭环

状态：`ACCEPTED`。任务 `Project 只读垂直闭环`，ID `019f4d52-ed95-72b1-a67c-f5465dec8320`；Project REACT/PLAN_EXECUTE、服务端 envelope、当前轮证据门、异步/重试恢复和真实垂直测试均已收口，主对话独立定向回归与根聚合通过。

目标：

- 把已验收的 Project provision、manifest、list/search/read 能力注册为 `PROJECT + READ_ONLY` 工具，只接受 `projectId + relativePath`，不向模型暴露真实根路径。
- Coordinator 从受信服务端上下文接收 projectId 和 Project capability；普通 CHAT、RAG 文本和模型输出不能自行绑定或切换 Project。
- 将 Project manifest、按需文件片段、文件 hash/version 和工具观察接入现有 ContextPackage/EvidenceLedger，保持不可信数据降权与总预算约束。
- 以一个真实只读场景贯通 REACT 和受控 PLAN_EXECUTE：跨代码、论文与科研报告读取、检索、比较并输出带文件证据的结论。

文件所有权：

- Project 只读工具 adapter、Project capability 接线、ContextPackage/EvidenceLedger 的 Project 窄集成和对应测试。
- 禁止开放写入、删除、重命名、命令执行、自由绝对路径、候选 patch 应用和多 Agent 并发写入。
- 不在本步重构 Planner、论文润色或文献检索领域流程。

验收门禁：

- 越界、绝对路径、符号链接逃逸、敏感文件、超大文件和非法 glob 继续 fail-closed；Project 归属和 capability 有确定性反例。
- 模型只看到当前授权 Project 的只读工具；CHAT deny-all 和非 Project 请求不能暴露 Project 工具。
- 回答中的文件引用可追溯到 projectId、relativePath 和 hash/version；缺失证据时不得宣称完整审查。
- Project、Context、Coordinator、Controller、论文和文献回归通过，根目录 `mvn test` 全绿。

### 4.5 第四阶段：Project 只读工具与上下文集成

状态：`ACCEPTED（已由 MVP-3A 覆盖并完成）`。

目标：

- 将 Project 清单、读取、搜索和结构检查注册为只读语义工具。
- 工具只接受 `projectId + relativePath`，不向模型暴露真实根路径。
- 将 Project manifest 和按需检索片段加入 ContextPackage。
- 将工具结果和文件版本写入 EvidenceLedger。
- 支持跨代码、论文和报告的只读分析任务。
- 默认串行执行，支持安全点取消。

该阶段不会开放：文件写入、删除、重命名、命令执行、自由多 Agent 写入和全量 Project 向量化。

### 4.6 第五阶段：Verifier、Reflection 与候选 patch

状态：`ACCEPTED`。实施切片为 `MVP-4A 最小 Verifier、Reflection 与候选修改产物`，任务 ID `019f4e5d-3192-72b2-a9e2-ebb39974e906`；统一完成验证、最多一次不扩权 Reflection、当前版本证据校验及候选失效生命周期已完成独立验收。

目标：

- 建立“任务是否真的完成”的 Verifier。
- 最终回答必须引用证据或明确说明证据不足。
- 复杂 Project 任务最多进行一次受预算限制的 Reflection 修复循环。
- 生成候选 patch 作为可审计产物，不直接应用到用户文件。
- 文件版本变化时标记候选 patch 失效，避免基于旧版本修改。

最小验收边界：

- DIRECT、REACT、PLAN_EXECUTE 复用同一完成验证门；不能用流畅回答、表面步骤完成或 Planner 降级替代证据。
- Project 审查必须验证当前授权 projectId、relativePath、hash/version 和本轮 EvidenceLedger；历史、跨 Project 或错误版本证据不得通过。
- Reflection 最多执行一次，且不得扩大工具策略、Project capability、projectId、步骤 allowlist 或预算；第二次验证后必须终止。
- 候选修改产物至少携带 projectId、relativePath、base hash/version、修改摘要、候选 patch/建议、evidence refs 和 `NOT_APPLIED` 状态；不得直接写入文件。
- base version 变化时确定性标记候选产物为 `STALE/INVALIDATED`。
- 优先复用现有 AgentArtifact 与审计结构；未经主对话批准不得新增 migration。

### 4.7 第六阶段：MVP 评测与发布门禁

状态：`CHANGES_REQUIRED`，最终任务 `MVP 发布评测与发布门禁`（任务 ID `019f4e90-f913-7463-bc7e-5ed98b8097d5`）首轮门禁为 `NO_GO`；需补齐脚本遗漏的权威测试，并尝试使用 Windows junction 实际执行两项链接逃逸反例。

目标：

- 固定 Project 只读审查、跨文件证据、工具权限、Prompt Injection、失败恢复等场景。
- 组件测试、集成测试和真实科研样例分层执行。
- 模型型评测固定 provider、model、参数、prompt 和工具版本，并记录重复运行稳定率。
- 关键安全用例虚假完成数必须为 0。
- 输出 MVP 发布报告和仍未实现能力清单。

## 5. 当前进度总览

| 阶段/任务 | 状态 | 当前结论 |
| --- | --- | --- |
| 主设计《通用 Agent Runtime 设计》 | `ACCEPTED` | 作为后续通用 Agent 主方向 |
| MVP-0 契约与基线 | `ACCEPTED` | 73 项独立回归通过，契约已冻结 |
| Project 只读边界 | `ACCEPTED` | 三轮复审通过；16 项独立回归无失败，2 项符号链接用例待 CI 实际执行 |
| 工具治理与动态暴露 | `ACCEPTED` | 三轮复审通过；core 14 项、API 联合 56 项独立回归全部通过 |
| 上下文与证据 | `ACCEPTED` | 交叉聚合补充修正通过；41 项定向回归与全聚合均通过 |
| 评测与安全基线 | `ACCEPTED` | 10 项 PASS 可重复验证；7 项 NOT_IMPLEMENTED 如实保留 |
| 第二阶段交叉集成 | `ACCEPTED` | 结论 ACCEPTED_FOR_COORDINATOR；八模块根聚合全部 SUCCESS |
| Runtime Coordinator | `ACCEPTED` | MVP-2A 与 MVP-2B 均已通过；CHAT、受信 Plan API、legacy reflection、统一 outcome 和 Plan adapter 已收口 |
| Project 只读完整闭环 | `ACCEPTED` | Project REACT/PLAN、envelope、防伪、当前轮证据、异步/retry 与垂直测试均通过 |
| Verifier/Reflection/候选 patch | `ACCEPTED` | 完成统一验证门、单次不扩权修复、当前证据校验与 NOT_APPLIED/STALE 候选产物 |
| MVP 发布评测 | `CHANGES_REQUIRED` | 首轮 110 项无失败但 2 项链接逃逸被跳过，脚本测试矩阵仍需补齐 |

总体进度判断：**MVP-0、基础模块、Runtime Coordinator、Project 只读垂直闭环和 MVP-4A 均已完成；距离最小 MVP 只剩最后一个工程步骤：MVP 发布评测与发布门禁。该步骤验收后交由用户进行本地真实科研场景验收。**

## 6. 已知风险与处理方式

### 6.1 共享工作目录写冲突

四个 worker 使用同一工作目录。当前控制方式：

- 每个任务拥有明确的生产代码和测试目录。
- 需要跨所有权修改时停止并提交接口需求。
- 不执行 reset、checkout 或撤销陌生修改。
- 主对话在复审时检查实际改动是否越界。
- 联合测试只在各 worker 完成局部测试后统一运行。

### 6.2 数据库 migration 冲突

Project 任务可能新增下一版本 migration。其他 worker 本阶段不得新增生产 migration。复审时必须检查 MySQL 与 H2 测试迁移是否同步，且不能修改 V1-V30 历史文件。

### 6.3 Planner fallback 伪装成功

该问题已经登记，但不属于四个基础 worker 的修改范围。必须由 Runtime Coordinator 阶段处理，不能只加注释后视为完成。

### 6.4 不可信上下文权限过高

当前基线中 RAG provenance 已进入实际上下文，但旧构建方式仍可能把 RAG/tool trace 包装成 system 消息。`上下文与证据` 任务正在处理，复审时必须同时检查消息角色和现有 CHAT 回归。

### 6.5 测试通过不等于能力完成

尚未实现的 Project 或 Coordinator 能力可以使用 pending/disabled 契约测试登记，但不能计入通过数，也不能用 mock 成功结果宣称真实闭环完成。

### 6.6 垂直业务回归

论文润色和文献检索继续作为独立领域能力存在。通用 Runtime 本阶段不修改其业务流程，但每次共享 agent/tool 契约变化后必须运行其组件回归，防止兼容性退化。

## 7. 每个任务的复审清单

worker 提交报告后，主对话至少检查：

1. 是否超出文件所有权或修改非目标业务。
2. 是否改变 MVP-0 冻结语义。
3. API、模型和工具是否存在绕过权限的第二入口。
4. 错误是否结构化、可审计且不会被误判为成功。
5. 是否覆盖空值、空集合、越界、重复、取消和异常路径。
6. 测试是否验证真实行为，而不是只验证 mock 调用次数。
7. 是否存在外部网络、真实模型、Redis 或数据库环境依赖。
8. 是否运行受影响组件测试和联合回归。
9. 未实现项是否明确登记，没有藏在 fallback 中。
10. 是否给出用户可执行的本地场景验证步骤。

## 8. 进度更新规则

本文由主审查对话维护，更新时间点如下：

- 创建、暂停或取消 worker 后。
- worker 提交最终报告后，将状态改为 `SUBMITTED`。
- 主对话完成首轮复审后，改为 `CHANGES_REQUIRED` 或继续验收。
- 修正完成并独立回归后，改为 `ACCEPTED`。
- 启动 Runtime Coordinator 或下一阶段任务前，记录输入契约和阻塞项。
- 用户完成真实场景测试后，补充本地验收结果。

任何 worker 不得自行把本文件中的任务状态改为 `ACCEPTED`。阶段验收结论只能由当前主对话在代码复审、测试复跑和用户反馈后更新。

## 9. 进度日志

### 2026-07-10：主设计完成

- 完成《通用 Agent Runtime 设计》。
- 明确 Project 模式和普通对话使用同一个 Runtime。
- 明确 MVP 先完成只读科研 Project 闭环，不开放直接文件写入。

### 2026-07-10：MVP-0 契约与基线通过

- 完成任务状态、工具结果、工具策略和最小 ToolDescriptor 契约。
- 修复 Plan `null / []`、旧别名覆盖、Skill 空 allowlist 和结构化工具失败语义。
- 明确 chat 可见消息与审计历史边界。
- 独立复跑 48 项定向测试和 25 项控制器集成测试，全部通过。
- 登记 Planner fallback 为 Runtime Coordinator 硬阻塞项。

### 2026-07-10：第二阶段四任务启动

- 启动 `Project 只读边界`。
- 启动 `工具治理与动态暴露`。
- 启动 `上下文与证据`。
- 启动 `评测与安全基线`。
- 四个任务当前均为 `IN_PROGRESS`，均未进入主对话复审。

### 2026-07-10：评测与安全基线首轮复审

- 生产代码未被该任务修改，10 项 PASS 与 7 项 `NOT_IMPLEMENTED` 的分类方向基本合理。
- core 新增回归独立复跑为 2 tests passed。
- API 回归因 `AgentLangChain4jTools.readFile` 共享签名变化导致测试编译失败，当前不可验收。
- 基线矩阵部分 PASS 未被文档命令覆盖，`EVAL-AUDIT-01` 的引用测试也未完整证明 chat 隐藏与 `view=all` 审计两个方向。
- 任务状态改为 `CHANGES_REQUIRED`，修正后需重新提交并由主对话独立复跑。

### 2026-07-10：评测与安全基线复审通过

- 修复共享 `readFile` 签名变化造成的测试编译问题。
- 为 10 项 PASS 补齐测试类、方法和完整回归命令。
- 审计用例同时验证默认 chat 视图、`view=all` 和下一轮模型工具历史。
- PATH-01/02 明确标记为 `LEGACY_BASELINE`，不计入未来 Project 发布门禁。
- 独立复跑 core 2 项、API 9 项，全部 0 failures、0 errors。
- 任务状态冻结为 `ACCEPTED`；7 项 `NOT_IMPLEMENTED` 继续作为后续阶段门禁。

### 2026-07-10：Project 只读边界首轮复审

- 独立复跑 `ProjectServiceTest,ProjectMigrationTest`，6 项全部通过。
- 路径相对化、授权所有者、已知敏感模式、SHA-256 和 V31 migration 的基础方向正确。
- 发现直接读取未执行 `maxFileBytes`、manifest/search 无遍历预算、阻断目录未提前跳过等资源风险。
- 当前只有根授权 helper，没有能够持久化 Project 的受信 provision 服务，基础能力无法正常创建 Project。
- `InvalidProjectPathException` 未映射 HTTP 状态；符号链接测试在不支持创建链接时会直接返回并被记为 PASS。
- 任务状态改为 `CHANGES_REQUIRED`，修正后重新提交复审。

### 2026-07-10：工具治理与动态暴露首轮复审

- core 的 Registry、ToolResult 和安全定向回归独立复跑 10 项通过。
- API 联合回归被 Project worker 的临时编译错误阻断，未跨文件所有权修复。
- `ToolRegistry.listToolsForModel(null)` 仍可暴露 UNKNOWN/内部工具，与 fail-closed 目标冲突。
- 副作用工具的 `ConfirmationPolicy` 尚未执行，但取消类工具已可见；确认门建立前必须拒绝。
- 调用预算错误依赖可见工具数量，单个 polling 工具会只得到一次调用预算。
- JSON 重复签名会删除字符串内部空白，异步状态又把未知状态当作可继续轮询，均需确定性修正。
- 任务状态改为 `CHANGES_REQUIRED`，修正后重新提交复审。

### 2026-07-10：上下文与证据首轮复审

- 上下文、快照和记忆定向回归独立复跑 16 项通过。
- 完整 `AgentControllerIntegrationTest` 运行 22 项，其中 2 项仍断言旧 system/assistant 降权语义，需要按新安全契约更新。
- summary、memory、retention 和 evidence 不受总字符预算约束，超大证据仍可让最终 prompt 超限。
- provenance 元数据使用未转义文本拼接，可能伪造字段边界；应改为稳定结构化 envelope。
- `canClaimCompleted` 只验证 ID 存在却命名为完成验证，存在被 Coordinator 误用并形成虚假完成的风险。
- normalized override 入口可绕过历史 system/tool 降权，需统一 sanitization。
- 任务状态改为 `CHANGES_REQUIRED`，修正后重新提交复审。

### 2026-07-10：Project 只读边界第二轮复审

- 首轮提出的文件大小、遍历预算、内部 provision、HTTP 异常和 skipped 语义已基本修正。
- 发现非法或损坏的 ignore JSON/glob 会被静默忽略，从而在 include 仍有效时扩大访问范围，违反 fail-closed。
- 要求 provision 时验证所有 include/ignore glob；已有脏策略任一解析失败时整体 deny-all。
- 合法但不存在的文件还需与 malformed path 区分为稳定 404/400。
- 独立回归被 Context worker 当前删除方法但尚未同步测试的在途状态阻断，Project worker 不得跨界修复。

### 2026-07-10：工具治理与动态暴露第二轮复审

- Registry 模型暴露、确认默认值、固定调用预算、canonical JSON 和异步状态词表已按首轮意见修正。
- 发现 `search_web` 因确认与空权限被隐藏，`recommend_literature` 因移出 catalog 变为 UNKNOWN，普通 CHAT 失去网页与文献检索核心能力。
- 要求用外部只读查询/资源权限表达研究工具风险，并为 legacy CHAT 保持现有授权兼容；取消和内部三段式工具继续 fail-closed。
- 受控 Registry 执行仍只校验名称集合，可能绕过 descriptor；需要接收已解析许可或复用治理条件。
- `maxDuplicateToolCalls=1` 仍不能允许一次幂等重试，存在 off-by-one。
- 任务保持 `CHANGES_REQUIRED`，API 完整回归等待 Context 在途修改稳定。

### 2026-07-10：Project 只读边界第三轮复审通过

- provision 在持久化前验证全部 include/ignore glob；已有规则任一 JSON、类型或 glob 异常时整个 policy deny-all。
- 合法但不存在或已变化的文件稳定返回 404；绝对路径、`..`、越界和符号链接继续返回 400。
- 独立复跑 `ProjectServiceTest,ProjectMigrationTest,ProjectControllerIntegrationTest`：16 tests，0 failures，0 errors，2 skipped。
- 两项符号链接测试在当前 Windows 权限下明确 skipped；PATH-03 仍须在 Linux 或具备权限的 Windows CI 实际通过。
- 任务状态冻结为 `ACCEPTED`，等待四路完成后的交叉集成审查。

### 2026-07-10：工具治理与动态暴露第三轮复审通过

- 新增 `EXTERNAL_READ`，恢复普通 CHAT 的 `search_web`、`recommend_literature`、`search_knowledge` 研究能力。
- `paper_task_cancel` 在确认门接入前继续拒绝；`literature_search_start/status/result/cancel` 移出 catalog 并按 UNKNOWN fail-closed。
- Registry 受控执行复用 descriptor 静态门禁；Provider 与执行器共享同一份已解析工具名称集合。
- `maxDuplicateToolCalls=1` 现在允许一次失败后的幂等重试，下一次重复调用才阻断。
- 独立复跑 core 14 项、API 联合 56 项，全部 0 failures、0 errors。
- 任务状态冻结为 `ACCEPTED`；Coordinator 后续必须成为 `ResolvedToolPolicy` 的唯一构造来源。

### 2026-07-10：上下文与证据第二轮复审

- 硬字符预算、单一 JSON runtime-data envelope、结构化 provenance、`containsAllReferences` 和 override 再净化已通过代码复审。
- 独立复跑上下文 19 项全部通过；随后 API 联合 56 项中的完整 `AgentControllerIntegrationTest` 同样通过。
- 发现 `UserModelRequest.modelName` 等用户可配置动态值仍被直接拼入 system identity guard；长度截断不能阻止换行和伪指令获得 system 优先级。
- 要求 system guard 保持 Runtime 静态文本，将 provider/model 显示值移入不可信 JSON user envelope，并补恶意模型名反例。
- 任务保持 `CHANGES_REQUIRED`，修正该唯一 P1 后重新提交。

### 2026-07-10：上下文与证据第三轮复审通过

- system identity guard 改为完全静态文本，用户可配置 provider/model 显示值移入 `trust=UNTRUSTED` 的 JSON user envelope。
- 恶意换行和伪指令反例证明攻击文本不会进入 system role，动态值只按 JSON 数据处理。
- retention 无字段可纳入时不再保留空对象或错误 section。
- 独立合并复跑上下文、快照、记忆和完整 CHAT 集成共 42 项，全部 0 failures、0 errors。
- 任务状态冻结为 `ACCEPTED`。

### 2026-07-10：第二阶段四个基础任务全部通过

- `Project 只读边界`：`ACCEPTED`。
- `工具治理与动态暴露`：`ACCEPTED`。
- `上下文与证据`：`ACCEPTED`。
- `评测与安全基线`：`ACCEPTED`。
- 自动监督轮询停止；下一步进入四模块交叉集成审查，不直接启动 Runtime Coordinator。

### 2026-07-10：交叉集成审查启动

- 创建只读任务 `交叉集成审查`，ID `019f4c8e-58de-7f61-9418-3e59b1f6213a`。
- 审查范围包括文件所有权、冻结契约矩阵、Project/工具/Context 组合风险、Spring/JPA/migration 装配和垂直业务回归。
- 测试统一串行执行，避免共享 `target` 目录竞争；禁止调用外部模型、Embedding 和真实网络服务。
- worker 只能新增《第二阶段交叉集成审查报告》，发现问题只报告，不修改生产代码或测试。
- 当前状态改为 `IN_PROGRESS`，Runtime Coordinator 继续保持 `NOT_STARTED`。

### 2026-07-10：交叉集成审查完成，要求修正

- 交叉审查报告已生成：《第二阶段交叉集成审查报告》；只新增报告，未修改生产代码或测试。
- core 5 项、MVP/API 9 项、Project 14 项和跨组件 88 项定向回归通过；Project 另有 2 项符号链接用例明确 skipped。
- 全聚合 `mvn test` 在 `yanban-paper` 停止：`PaperPolishBaselineEvaluationTest` 的 unsafe placeholder fixture 期望 `FAILED_KEEP_ORIGINAL`，实现返回 `PROTECTION_REJECTED`。
- 主对话独立复跑该测试，稳定复现同一失败。服务实现及两项既有单测均把保护耗尽定义为 `PROTECTION_REJECTED`，`FAILED_KEEP_ORIGINAL` 用于编排器取消兜底；应由论文评测基线所有者确认并修正 fixture，同时保留全部安全断言。
- 注解工具桥接仍依赖 Provider/Strategy 外层双重策略门，登记为 P2；Coordinator 接入前需改为显式策略执行或限制直接调用。
- 第二阶段状态改为 `CHANGES_REQUIRED`，Runtime Coordinator 继续保持 `NOT_STARTED`。
- 已启动窄范围修正任务 `修正论文基线契约`，ID `019f4c97-5c37-7a33-8ee8-b2a4d62e3792`；只允许调整 `PaperPolishBaselineEvaluationTest` 的状态 fixture，并要求重跑论文定向测试和全聚合构建。

### 2026-07-10：论文基线修正通过，聚合发现上下文测试漂移

- `PaperPolishBaselineEvaluationTest` 只将负向 fixture 期望状态改为 `PROTECTION_REJECTED`，所有原文、结构、token 和 lint 安全断言保留。
- 主对话独立复跑论文基线、服务和取消测试共 14 项，全部通过；该修正状态为 `ACCEPTED`。
- 全聚合继续到 `yanban-api` 后发现 `AgentExperimentServiceTest` 4 项中 2 项失败：测试仍期待旧句子 `Answer using the following information`。
- 当前实现输出新的 `langchain4j-augmentor` 检索提示及 `source/file/chunk/citation` provenance，方向符合已冻结上下文契约；判定为测试断言漂移，不回退生产文本。
- 修正意见已发回 `上下文与证据` 原任务，只允许修改该测试文件并要求重跑定向与全聚合测试。

### 2026-07-10：第二阶段交叉集成最终通过

- `AgentExperimentServiceTest` 两处旧文本断言已更新为确定性验证 `langchain4j-augmentor`、检索提示、source/file/chunk/citation 和实际片段内容；未修改生产代码。
- 主对话独立复跑 `AgentExperimentServiceTest,AgentContextBuilderTest,AgentControllerIntegrationTest`：41 tests，0 failures，0 errors。
- 主对话独立运行根目录 `mvn test`：parent、core、knowledge、paper、mcp、skills、api、cli 八个 reactor 模块全部 SUCCESS，总耗时约 2 分 14 秒。
- 第二阶段状态冻结为 `ACCEPTED`，最终建议为 `ACCEPTED_FOR_COORDINATOR`。
- Coordinator 启动前仍需把注解工具桥接的显式策略执行列为前置验收项，并处理已登记的 Planner fallback 硬阻塞；这些不是第二阶段未解决的 P0/P1。

### 2026-07-10：Runtime Coordinator MVP-2A 启动

- 在 4.4 节写入 `Coordinator 契约与 CHAT 骨架` 的范围、冻结策略、Planner 失败契约、工具二次门禁、文件所有权和验收标准。
- 创建单一编码任务 `Runtime Coordinator 契约与骨架`，ID `019f4cb3-962b-7840-a7c4-2d1140463d35`。
- 本切片接管普通 CHAT 的 Coordinator 门面，但不宣称完成 Plan API 全面合并；Plan adapter、Project 工具、Verifier 和多 Agent 保留到后续切片。
- 两个本轮硬阻塞：删除伪装成功的 Planner fallback；注解工具桥接不得绕过 `ResolvedToolPolicy` 使用 trusted Registry 执行。
- Runtime Coordinator 状态改为 `IN_PROGRESS`，由当前主对话逐轮复审。

### 2026-07-11：Runtime Coordinator MVP-2A 首轮复审

- worker 提交 Coordinator CHAT 门面、Planner 显式失败、Plan API 失败拒绝和注解工具策略上下文实现；根聚合报告为 293 项通过、0 failures、0 errors、9 skipped。
- 主对话独立复跑 Coordinator、Planner、Runtime、Controller、论文和文献定向回归：99 tests，0 failures，0 errors。
- 文件所有权检查通过，未修改 Project、ContextPackage/EvidenceLedger、论文/文献领域流程、前端或 migration。
- P1：Coordinator 仅按 `success` 归类停止原因，工具调用预算耗尽后生成的受限回答仍会被记为 `COMPLETED / SUCCESS`，不满足“预算停止可区分”的冻结验收契约。
- P1：`AgentLangChain4jTools` 中部分本地直连 `@Tool` 方法没有统一检查调用期 `ResolvedToolPolicy`，缺少执行上下文时仍可直接进入实现；现有测试只覆盖 registry 转发方法。
- P2：Coordinator 捕获所有 `IllegalStateException` 并归类为 `NO_RUNTIME_ADAPTER`，会混淆 adapter 内部异常。
- 任务状态改为 `CHANGES_REQUIRED`，修正意见已发回原任务；监督继续保留，等待重新提交后复审。

### 2026-07-11：Runtime Coordinator MVP-2A 第二轮复审

- worker 新增结构化 `AgentRuntimeStopSignal`，工具调用预算和 maxSteps 预算均由 Runtime loop 显式传播，Coordinator 统一映射为 `BUDGET_STOP`，不依赖模型或错误文本。
- `NoRuntimeAdapterException` 只表示没有匹配 adapter；adapter 内部 `IllegalStateException` 现归类为 `RUNTIME_EXCEPTION`。
- 全部 18 个 `@Tool` 公共入口已在业务动作前调用统一授权 helper；主对话独立复跑相关小集 30 tests，0 failures，0 errors。
- 仍有 P1：统一 helper 只检查 allowlist 和用户上下文，未检查 descriptor 是否存在、是否 UNKNOWN、是否 model-visible、是否需要确认；本地直连注解方法可在伪造 allowlist 后绕过元数据门禁。
- 为避免用字符串推断预算停止，4.4.5 明确增加 `LangChain4jToolCallingStrategy` 的窄范围所有权，仅限结构化 stop signal 传播和测试。
- 任务继续保持 `CHANGES_REQUIRED`；只需补 descriptor/确认策略 fail-closed 及确定性反例后重新提交。

### 2026-07-11：Runtime Coordinator MVP-2A 第三轮复审

- 注解入口已统一验证 descriptor 存在、model-visible、side effect 非 UNKNOWN、confirmation policy 为 NEVER，并继续验证 allowlist 与用户上下文；缺失 descriptor 的 `read_file` 和需要确认的 `search_web` 反例通过代码审查。
- 主对话独立复跑完整定向集：107 tests，0 failures，0 errors，0 skipped。
- 根目录 `mvn test` 运行到 yanban-api 后为 301 tests、1 failure、9 skipped；唯一失败是 `MvpDeterministicSafetyBaselineTest.workspaceToolRejectsAbsoluteAndTraversalPathsBeforeFileAccess`。
- 失败原因是旧测试没有设置调用期策略和 `read_file` descriptor，新门禁先于路径校验拒绝，导致原有绝对路径/目录穿越断言未被实际执行。
- 禁止把断言改成策略拒绝或调整生产门禁顺序；已授权原 worker 仅在该测试中建立受治理 `read_file` 上下文，保留 `absolute paths are not allowed` 与 `path escapes workspace` 两条原安全断言，并清理 ThreadLocal。
- 状态保持 `CHANGES_REQUIRED`，待该单测、107 项定向集和根聚合全部通过后最终验收。

### 2026-07-11：Runtime Coordinator MVP-2A 最终验收

- 路径安全基线测试只补充受治理 `read_file` descriptor、调用期 allowlist 和 `finally` 清理；`absolute paths are not allowed` 与 `path escapes workspace` 原断言均保留。
- 主对话独立复跑 `MvpDeterministicSafetyBaselineTest`：4 tests，0 failures，0 errors。
- 主对话此前独立复跑完整 MVP-2A 定向集：107 tests，0 failures，0 errors，0 skipped。
- 主对话独立运行根目录 `mvn test`：parent、core、knowledge、paper、mcp、skills、api、cli 八个 reactor 模块全部 SUCCESS，BUILD SUCCESS，总耗时约 2 分 20 秒。
- MVP-2A 最终结论为 `ACCEPTED`：普通 CHAT 已经经过 Coordinator；策略选择、Planner 显式失败、Plan API 失败拒绝、结构化预算停止、无 adapter 分类和注解工具二次门禁均满足冻结契约。
- 该结论不代表第三阶段完成。Runtime Coordinator 总状态恢复为 `IN_PROGRESS`，下一步规划并实施 MVP-2B 统一 Plan adapter；Verifier、Reflection 重构、长任务托管和多 Agent 仍不在 MVP-2A 范围内。

### 2026-07-11：后续两步规划与 MVP-2B 启动

- 后续范围只保留两步：第一步 `MVP-2B 统一 Plan Adapter`，第二步 `MVP-3A Project 只读垂直闭环`；第二步必须等待第一步验收，不并行开发。
- 明确本轮不纳入 Verifier、Reflection 重构、候选 patch、文件写入、命令执行、长任务重构和多 Agent。
- 创建单一编码任务 `统一 Plan Adapter`，ID `019f4d15-73a6-7370-a455-0dbf9b38f629`；共享工作区实施，当前主对话继续负责监督、复审和独立回归。
- `MVP-2B` 状态改为 `IN_PROGRESS`；`MVP-3A` 仅登记为 `NOT_STARTED（已规划）`。

### 2026-07-11：MVP-2B 首轮复审

- worker 已提交受信 capability、`PLAN_EXECUTE` adapter、Plan 终态映射和 legacy reflection 失败语义修正；文件范围未触及 Project、Context/Evidence、论文/文献领域流程、前端或 migration。
- 主对话独立运行 Coordinator、Plan、Controller、Policy、论文和文献定向集；命令在 120 秒外层超时前完成了 13 份 Surefire 报告，共 88 tests，0 failures，0 errors，但未取得 Maven 最终 exit code，因此不作为最终聚合验收证据。
- P1：`PlanAgentService.createPlan` 仍直接调用 Planner 并持久化，只有执行入口进入 Coordinator，未满足“Plan API 创建与执行均经过统一受信 Coordinator/adapter 边界”的冻结目标。
- P1：`planId` 同时存在于 `AgentCoordinationRequest` 和 `AgentRuntimeRequest`；Coordinator 校验前者而 Adapter 消费后者，缺少单一可信来源及冲突拒绝测试。
- P1：`PAUSED` 目前只在分类函数测试中成立；真实执行路径先抛 409，再被 Coordinator 归为 `RUNTIME_EXCEPTION` 并由 service 改写为 500，既未形成 `PAUSED` outcome，也破坏原接口兼容。
- P2：预算停止仍依赖持久化错误文本前缀识别，尚未完全结构化；本切片应尽量收口，无法消除时必须明确登记技术债。
- 首轮结论为 `CHANGES_REQUIRED`，修正意见已发回原 worker；`MVP-3A Project 只读垂直闭环` 继续保持 `NOT_STARTED`，不得提前启动。

### 2026-07-11：MVP-2B 第二轮复审通过与 MVP-3A 启动

- Plan 创建和执行均由受信 `TRUSTED_PLAN_API` capability 进入 `Coordinator -> PLAN_EXECUTE -> PlanRuntimeAdapter`；普通 CHAT 和 legacy `/plan reflect` 边界未放宽。
- `AgentCoordinationRequest.planId` 成为 canonical 身份；缺失、创建时意外提供和 runtime/外层冲突均 fail-closed，只有 Coordinator 校验后才绑定给 Adapter。
- 暂停计划继续返回原有 HTTP 409，并记录 `plan_execution_paused` 审计事件；不再被改写为 `RUNTIME_EXCEPTION / 500`。
- Plan 预算停止通过结构化 `AgentRuntimeStopSignal` 传播，不再解析错误文本；步骤仍使用受预算约束的 `SINGLE_STEP_REACT`，策略求交与 `null / []` 契约保持。
- 文件所有权复审通过，未修改 Project、Context/Evidence、论文/文献领域流程、前端或 migration。
- 主对话独立运行定向集合：95 tests，0 failures，0 errors，`BUILD SUCCESS`。
- 主对话独立运行根目录 `mvn test`：parent、core、knowledge、paper、mcp、skills、api、cli 八个 reactor 模块全部 SUCCESS，Exit code 0，`BUILD SUCCESS`，总耗时约 2 分 32 秒。
- MVP-2B 最终结论为 `ACCEPTED`，Runtime Coordinator 阶段完成；原两分钟监督已删除。
- 按已批准的两步计划启动 `MVP-3A Project 只读垂直闭环`，任务 ID `019f4d52-ed95-72b1-a67c-f5465dec8320`；新增两分钟监督 `project-mvp-3a`，当前主对话继续负责复审与独立回归。

### 2026-07-11：MVP-3A 首轮复审

- worker 如实报告当前只完成 Project REACT 基础接线，未把半闭环伪装为完成；认证 Project 消息入口、`PROJECT_READ` capability、manifest/read/search 三个受治理只读工具、模型 projectId 二次匹配和 UNTRUSTED manifest 上下文方向可保留。
- 主对话独立复跑 Project、策略、Coordinator、Context、Agent/Plan Controller 和新执行器定向集：97 tests，0 failures，0 errors，2 skipped，`BUILD SUCCESS`；两项 skipped 为当前 Windows 无符号链接权限，仍需 CI 实际通过。
- P1：Project capability 未持久化到 `AgentPlan`，受控 `PLAN_EXECUTE`、异步执行和重试恢复均无法安全恢复 projectId，未满足 REACT + PLAN 双链路验收门。
- 主对话批准无 migration 方案：将现有 `raw_plan_json` 改为服务端生成的版本化 envelope，保留原始 Planner JSON，并单独存储 server-attested projectId/capability；旧 Plan 和普通 Plan 默认无 Project context，模型 JSON 不得成为可信身份来源。
- P1：三个 Project 执行器仍返回空 `evidenceRefs`，运行时只保留字符串 tool trace；工具观察没有合并回本次 `EvidenceLedger`，也没有“缺少文件证据不得普通 SUCCESS/COMPLETED”的确定性门。
- P1：尚无真实 MVC + Coordinator + ToolProvider 的 Project REACT 测试，也无 Project Plan 创建、执行、重试、策略求交和证据完成门测试；直接执行器单测不能替代垂直闭环。
- P2：`AgentService.sendProjectMessage` 可接收调用方提供的任意 Project evidence，受信入口仍可缩小或在服务内部重新构造/验证，需增加伪造 context/evidence 反例。
- 首轮结论为 `CHANGES_REQUIRED`，修正意见已发回原 worker；根目录全聚合等待闭环修正后由主对话独立执行。

### 2026-07-11：MVP-3A 第二轮复审

- worker 已补充版本化 Project Plan envelope、`TRUSTED_PROJECT_PLAN_READ` 组合 capability、每步骤 Project 工具策略求交、Project 工具 `evidenceRefs`、步骤证据事件和无证据失败门；这些实现方向可保留。
- 主对话独立复跑 Project、Coordinator/策略、Context、Agent/Plan Controller、论文与文献工具兼容集：145 tests，0 failures，0 errors，2 skipped，`BUILD SUCCESS`；两项 skipped 仍是 Windows 符号链接权限限制。
- P1：普通 Plan 仍直接保存模型生成的 `plannerRawJson`。Planner 接受额外顶层字段，模型可在合法 `summary/steps` 外伪造 `project_plan_envelope_v1` 与 `serverAttestedProjectContext`，随后被恢复为可信 Project context。所有新 Plan 必须使用服务端 wrapper，普通 Plan 明确无 attested context；损坏或非法 Project wrapper 必须 fail-closed，不能静默降级为普通 Plan。
- P1：Project REACT 从 `AgentRuntimeResult.messages` 全量扫描证据，而该集合包含初始历史消息；上一轮的 Project tool-result 可能让当前轮未读取文件也通过完成门。证据必须限制为当前 runtime 新增的真实 tool-result，并增加历史证据不可复用的反例；`INSUFFICIENT_EVIDENCE` 也应作为可见非成功结果返回明确限制。
- P1：`executePlanAsync` 排队/置为 RUNNING 前和 `retryPlan` 重置状态前没有重新认证 Project context；异步、重试边界需先 fail-closed revalidate。真实 MVC、Coordinator + ToolProvider REACT，以及 Project Plan 创建/同步/异步/重试/策略求交/无证据终态测试仍未补齐。
- P2：`AgentService.sendProjectMessage` 仍是 public，且只比对 `context.userId`，未在该入口重新认证 projectId；需缩小可见性或在服务内重新验证，并补伪造 context 反例。
- 第二轮结论继续为 `CHANGES_REQUIRED`；修正意见已发回原 worker。根目录全聚合继续等待上述安全反例和垂直测试收口后执行。

### 2026-07-11：MVP-3A 第三轮窄修正检查

- worker 已将所有新 Plan 统一包入服务端 envelope，普通 Plan 的 attested context 明确为 `null`；非法 wrapper fail-closed。当前轮 evidence 按 runtime 新增消息截断，`INSUFFICIENT_EVIDENCE` 可见返回，异步/重试在状态变更前重新认证，`sendProjectMessage` 已缩为包可见。
- 上述生产代码修正经静态核对方向正确，可继续保留；worker 定向自测为 52 tests，0 failures，0 errors。
- worker 在真实 Project MVC、Coordinator + ToolProvider REACT、Project Plan 同步/异步/重试垂直测试、145 项定向复跑和根聚合完成前再次停止，因此验收状态仍为 `CHANGES_REQUIRED`。
- 主对话已要求原 worker 继续补齐完整垂直矩阵，特别证明异步 worker 实际恢复 Project context、历史证据不能复用、普通 Plan 伪造字段不能升级；完成后再提交最终复审。

### 2026-07-11：MVP-3A 最终复审通过

- 生产契约收口：可信 `projectId/capability` 仅来自认证 Project API/facade 或服务端 Plan envelope；普通 CHAT、普通 Plan、Planner JSON、历史消息和模型工具参数不能绑定、切换或提升 Project。
- 工具与策略收口：`PROJECT + READ_ONLY` registry policy、skill allowlist 和 persisted step allowlist执行 fail-closed 求交；工具执行器再次校验 server-bound projectId，Project 内容保持 `UNTRUSTED`。
- 证据与完成门收口：Project 工具返回 path/hash/version/toolCallId/evidence refs；REACT 只接收当前 runtime 新增 tool-result，Plan 只接收当前 step 观察；历史证据或只有 manifest 时返回 `INSUFFICIENT_EVIDENCE`，不得普通成功。
- Plan 生命周期收口：所有新 Plan 使用 `project_plan_envelope_v1` 服务端 wrapper；普通 Plan context 明确为空，损坏 wrapper fail-closed。同步、异步 worker、FAILED retry 与 CANCELLED 拒绝路径均通过确定性测试，状态变更前、worker 恢复和 step 前都会重新认证。
- 主对话独立定向回归：158 tests，0 failures，0 errors，2 skipped，`BUILD SUCCESS`。两项 skipped 为 Windows 符号链接权限用例，继续保留为具备 symlink 权限 CI 的发布门。
- 主对话独立根目录 `mvn test`：八模块 Reactor 全部 `SUCCESS`，退出码 0；汇总 528 tests，0 failures，0 errors，10 skipped。Surefire fork JVM 退出超时与 Mockito 动态 agent warning 登记为后续测试基础设施问题，不影响本次结果。
- 范围复核：未新增 migration，未修改论文/文献生产实现和前端，未引入写工具、命令工具、候选 patch 或多 Agent 并发写入。
- MVP-3A 最终状态冻结为 `ACCEPTED`；该结论仅表示 Project 只读垂直闭环完成，不表示整个通用 Agent MVP 已完成。

### 2026-07-11：剩余两步冻结与 MVP-4A 启动

- 修正文档过期状态：原第四阶段“Project 只读工具与上下文集成”已被 MVP-3A 完整覆盖，状态改为 `ACCEPTED`；不再作为剩余工程步骤重复实施。
- 最小 MVP 剩余工作冻结为两个顺序步骤：第一步 `MVP-4A 最小 Verifier、Reflection 与候选修改产物`，第二步 `MVP 发布评测与发布门禁`；第一步验收前不启动第二步。
- MVP-4A 只实现统一完成验证门、最多一次不扩权 Reflection、携带 base version/evidence 且 `NOT_APPLIED` 的候选修改产物，以及版本变化后的失效判定；不开放写文件、命令执行或 apply endpoint。
- 已创建开发任务 `MVP-4A Verifier 与候选修改产物`，任务 ID `019f4e5d-3192-72b2-a9e2-ebb39974e906`，状态为 `IN_PROGRESS`；当前主对话继续负责两分钟监督、代码复审和独立回归。
- 两个工程步骤全部通过后停止继续扩展，由用户执行本地代码、论文和科研报告 Project 的真实场景验收。

### 2026-07-11：MVP-4A 首轮复审

- worker 已建立 CompletionStatus/Verification、CompletionVerifier、CompletionReflection 与 CandidateChangeSet 契约，并将统一验证门接入 AgentRuntimeService；候选值可随 debug payload 返回，未开放 apply endpoint、未新增 migration。
- 主对话独立复跑 Verifier、Coordinator、Runtime、Project Plan、Plan/Agent Controller 定向集：68 tests，0 failures，0 errors，`BUILD SUCCESS`；现有测试通过但未覆盖真实闭环缺口。
- P1：所谓 Reflection 仅对同一个 raw result 重复验证，没有再次执行任何受限 repair turn；必须实际执行最多一次、使用剩余预算且不改变 Project/工具权限的修复，再进行第二次验证并终止。
- P1：真实 PlanRuntimeAdapter 只返回 Plan 状态文本，没有把当前执行步骤的受信 Project EvidenceLedger 带回外层统一验证门；Project Plan 即使内部完成也可能被外层误判证据不足。
- P1：Verifier 无条件合并 raw.evidenceLedger，并只用 EvidenceRef ID 前缀判断 Project 归属；历史 ledger、伪造前缀或当前路径的旧 hash 尚未通过 ProjectService 当前版本校验。
- P1：CandidateChangeSet 只存在于当前响应，`revalidate(EvidenceLedger)` 没有生产调用者；文件变化后无法在再次获取候选时确定性标记 `STALE/INVALIDATED`。应复用现有 AgentArtifact 或等价受信候选服务，不新增 migration、不开放 apply。
- P2：当前所有 VERIFIED Project 回答都会把 assistantContent 当作候选修改；应仅在明确修改/建议/patch 意图或结构化 repair output 存在时生成。
- 首轮状态为 `CHANGES_REQUIRED`，修正意见已发回原任务；第二个工程步骤继续保持 `NOT_STARTED`。

### 2026-07-11：MVP-4A 第二轮复审

- worker 已补齐真实单次 repair 执行、当前 Project manifest hash 校验、Plan 步骤证据回流代码、候选 `AgentArtifact` 持久化，以及再次读取时的 `STALE` 判定。
- 主对话独立运行 Runtime、Plan、Project、Controller、论文和文献定向回归，共 88 tests，0 failures，0 errors，0 skipped，`BUILD SUCCESS`。
- 第二轮仍发现两个 P1：Project 的 `allowedTools=[]` / `maxToolCalls=0` 会被 `CompletionReflection.mayAttempt()` 误判为可修复并再次执行；现有测试尚未串起真实 `PlanAgentService -> step_project_evidence -> PlanRuntimeAdapter -> AgentRuntimeService/CompletionVerifier` 外层证据闭环。
- 另要求补齐 repair request 不扩权的逐字段断言、显式中英文修改意图生成候选的正例，以及候选 Artifact 跨用户读取拒绝反例。
- 第二轮结论继续为 `CHANGES_REQUIRED`，修正意见已发回原任务；MVP 发布评测与发布门禁继续保持 `NOT_STARTED`。

### 2026-07-11：MVP-4A 第三轮复审

- Project deny-all、零工具预算和缺少 Project read/search 工具时不再触发 repair；repair request 的 Project 身份、模型、Skill 与 allowlist 保持不变，预算只减不增。
- 候选修改正例、纯读取不生成候选、跨用户 Artifact 拒绝和 `STALE` 重验证均已补齐。
- 主对话独立复跑 `CompletionVerifierTest`、`CandidateChangeArtifactServiceTest`、`PlanRuntimeAdapterTest`、`ProjectPlanVerticalTest`，共 24 tests，0 failures，0 errors，0 skipped，`BUILD SUCCESS`。
- 唯一剩余 P1 是 Plan 外层测试仍 mock `PlanAgentService.executePlanResultWithinAdapter()` 并直接返回构造证据，未覆盖真实 `step_project_evidence` 持久化、恢复、Adapter 传播与外层 Verifier；missing/old hash 和 Plan 创建 `PARTIAL` 也未进入同一真实链路。
- 第三轮结论继续为 `CHANGES_REQUIRED`，仅要求补真实 Plan 服务垂直测试；生产代码无失败则不再扩大修改范围。

### 2026-07-11：MVP-4A 最终验收

- 新增真实 `PlanCompletionEvidenceVerticalTest`：真实 `PlanAgentService` 执行步骤并写入 `step_project_evidence`，再由真实事件读取恢复 typed ledger，经 `PlanRuntimeAdapter -> AgentRuntimeService -> CompletionVerifier` 完成外层验证。
- 当前 hash 得到 `VERIFIED`；无事件和旧 hash 得到 `INSUFFICIENT_EVIDENCE`。测试未 mock `executePlanResultWithinAdapter()` 的最终 EvidenceLedger。
- 主对话独立运行 MVP-4A 完整定向集，共 95 tests，0 failures，0 errors，0 skipped，`BUILD SUCCESS`。
- 主对话独立运行根目录 `mvn test`，8 个模块全部 `SUCCESS`，总耗时 02:54，`BUILD SUCCESS`。
- MVP-4A 最终结论为 `ACCEPTED`；当前监督结束，开始最后一个工程步骤“MVP 发布评测与发布门禁”。

### 2026-07-11：MVP 发布评测与发布门禁启动

- 已创建最后一个工程任务 `MVP 发布评测与发布门禁`，任务 ID `019f4e90-f913-7463-bc7e-5ed98b8097d5`。
- 任务只允许修改确定性评测、安全测试、测试 profile/门禁脚本和发布报告，不得修改生产 Runtime、Project、论文/文献实现、前端或 migration。
- 发布结论必须区分 `GO / NO_GO`、自动确定性发布门与用户本地真实科研验收；自动门通过只能标记 `READY_FOR_LOCAL_ACCEPTANCE`。
- 已建立两分钟监督，主对话将在 worker 完成后独立复跑发布门、定向集和根聚合。

### 2026-07-11：MVP 发布门禁首轮复审

- worker 全量离线聚合为 544 tests、0 failures、0 errors、10 skips；发布门禁为 110 tests、0 failures、0 errors、2 skips，并按设计返回退出码 3。
- 主对话独立运行同一发布门禁，复现 110 tests、0 failures、0 errors、2 个 Windows 符号链接用例 skip，`GATE_EXIT=3`；首轮结论为诚实的 `NO_GO`。
- 复审发现脚本未实际包含报告引用的 `AgentToolPolicyEngineTest`、`ProjectControllerIntegrationTest`、`ProjectCurrentTurnEvidenceTest`、`AgentRuntimeServiceTest`，论文/文献兼容也需加入实际 Tool Executor 回归。
- 两项链接逃逸反例需在测试侧尝试 Windows junction/reparse-point fallback；只有实际执行且通过后才能清除 P0。若 junction 仍不可创建，继续保持 `NO_GO`。
- 首轮状态改为 `CHANGES_REQUIRED`；不得进入 `READY_FOR_LOCAL_ACCEPTANCE`。

### 2026-07-11：Project junction root P0

- 收尾评测使用 Windows directory junction fallback 后，两项链接反例均实际执行；项目内 junction 指向项目外被正确拒绝，但 internal provisioning root 本身为 junction 时生产实现错误接受。
- 扩展发布门为 145 tests、1 failure、0 errors、0 skips，退出码 1；主对话独立复跑 `ProjectServiceTest`，复现 11 tests、1 failure、0 errors、0 skips。
- 根因位于 `ProjectPathGuard.containsSymbolicLink()` 仅识别 `Files.isSymbolicLink`，没有覆盖 Windows junction/reparse point；发布状态继续为 `NO_GO / CHANGES_REQUIRED`。
- 已创建生产修复任务 `修复 Project junction root P0`，任务 ID `019f4eb6-94f9-7500-8c3a-7692f721adb6`；只允许最小 Project 路径防护修改及对应测试。

### 2026-07-11：MVP 发布门禁收尾

- 发布门脚本已纳入遗漏的 `AgentToolPolicyEngineTest`、`ProjectControllerIntegrationTest`、`ProjectCurrentTurnEvidenceTest`、`AgentRuntimeServiceTest`、`PaperTaskToolExecutorTest` 与 `LiteratureSearchTaskToolExecutorTest`；报告中这些 PASS 均来自发布门实际运行，不再与仅根聚合证据混写。
- `ProjectServiceTest` 的链接 fixture 先使用 `Files.createSymbolicLink`；当前 Windows 缺少 `SeCreateSymbolicLinkPrivilege` 时，在 JUnit 临时目录内创建、验证并清理 directory junction/reparse point。项目内 junction 指向项目外目录反例实际通过；internal provisioning root 自身为 junction 的反例实际执行但失败。
- 离线发布门（工作区 `.mvp-release-tmp`，`mvn -o`）结果：145 tests、1 failure、0 errors、0 skipped、exit 1。P0 是 `ProjectServiceTest#internalProvisioningRejectsSymbolicLinkRoot`：现有生产实现接受 junction/reparse-point root；按范围未修改生产代码。
- 七个 historical disabled sentinel 仅是占位映射，不是当前未实现项；当前真正 `NOT_IMPLEMENTED` 是 `EVAL-AUDIT-02`，RAG 最终文本强制引文校验为后续范围。
- 根聚合 544 tests、0 failures、0 errors、10 skips 是前序独立确认的仅根聚合证据；因发布门非零，本次没有重跑根聚合。最终状态为 `CHANGES_REQUIRED` / `NO_GO`，不得标记 `GO` 或 `READY_FOR_LOCAL_ACCEPTANCE`，更不代表用户真实科研验收已完成。

### 2026-07-11：Project junction root P0 修复与发布门复验

- 根因：`ProjectPathGuard.containsSymbolicLink()` 仅调用 `Files.isSymbolicLink()`；Windows directory junction 是 reparse point，在 `NOFOLLOW_LINKS` 下表现为 `BasicFileAttributes.isOther()==true`，故会绕过检查，随后 `toRealPath()` 将其解析为仍在 configured root 内的目录而被接受。
- 最小生产修复仅修改 `ProjectPathGuard` 与 `LocalServerProjectRootProvider`：逐段读取 `BasicFileAttributes`（`NOFOLLOW_LINKS`），任一 symbolic link 或 `isOther()` alias 均 fail-closed；不存在或属性读取失败沿原有 IOException 路径拒绝。保护同时覆盖 configured root、provisioning candidate、已持久化 canonical root 和 Project 文件相对路径链。
- 回归：保留真实 symlink/junction fallback。项目内 junction 指向项目外、internal provisioning root 自身为 junction 两项反例均实际通过且 0 skip；新增 configured root alias、persisted root alias 拒绝，以及普通嵌套目录授权正例。`ProjectServiceTest` 为 12 tests、0 failures、0 errors、0 skipped。
- 离线发布门 `scripts/Invoke-MvpReleaseGate.ps1`：修复前 145 tests、1 failure、0 errors、0 skipped、exit 1；修复后 **146 tests、0 failures、0 errors、0 skipped、exit 0**。Project/Runtime 定向集为 63/0/0/0。
- 根目录 `mvn -o test` 已实际重跑：8 个 reactor 模块 `BUILD SUCCESS`，总计 **545 tests、0 failures、0 errors、8 skipped**（core 41/0/0/0，knowledge 34/0/0/1，paper 121/0/0/0，mcp 3/0/0/0，skills 1/0/0/0，api 345/0/0/7，cli 0/0/0/0）。根聚合 skip 不属于离线发布门。
- P0 和 `CHANGES_REQUIRED` 已清除；自动确定性发布状态为 `READY_FOR_LOCAL_ACCEPTANCE`。这只允许进入用户本地验收阶段，**不代表用户真实科研验收、论文验收或生产上线已完成**。

### 2026-07-11：主对话最终独立复审

- 静态复审确认生产修改仅限 `ProjectPathGuard` 与 `LocalServerProjectRootProvider`；使用 Java NIO `NOFOLLOW_LINKS` 逐段拒绝 symbolic link、Windows junction/reparse alias，并对属性读取失败 fail-closed，没有引入外部命令、Project 写入或跨模块扩权。
- 主对话独立运行 `ProjectServiceTest`：12 tests、0 failures、0 errors、0 skipped；普通嵌套目录正例及真实 junction/reparse 反例均实际执行。
- 主对话独立运行 `scripts/Invoke-MvpReleaseGate.ps1`：146 tests、0 failures、0 errors、0 skipped、exit 0。
- 主对话独立运行根目录 `mvn -o test`：8 个 reactor 模块全部 `SUCCESS`，`BUILD SUCCESS`（耗时 03:47）；结果与 worker 报告一致。
- 最终工程复审结论：`ACCEPTED / READY_FOR_LOCAL_ACCEPTANCE`。最小 MVP 的工程实现与自动发布门已完成，下一步由用户执行本地真实科研场景验收；该结论不代表用户验收或生产上线已经完成。

### 2026-07-11：Project Workspace 前端闭环启动

状态：`IN_PROGRESS`。参考视觉稿：`C:/Users/zhaoyi/AppData/Local/Temp/codex-clipboard-050ab908-9b2a-4aec-933a-816493e91d02.png`。

目标：将现有 `ProjectPreviewPage.vue` 静态预览页替换为可用于本地真实科研验收的 Project 只读工作区，同时保持现有 `AppLayout`、Naive UI、颜色、字体、导航和交互风格。参考图只定义视觉目标，真实后端契约与冻结的 Runtime/Project 安全语义优先；禁止用生产静态假数据伪装功能完成。

实施顺序：

1. 先提交页面字段与 API 映射，核对 Project 列表、manifest、read、search、Project Agent、Plan、Evidence、Artifact/Candidate 的真实数据来源和缺口。
2. 仅补齐必要的安全创建入口：认证用户来自 principal，客户端只能提交配置根目录下的相对路径，访问模式固定 `READ_ONLY`，继续复用越界、symlink、junction/reparse 防护。若 Evidence/Candidate 需要查询投影，只允许增加窄的只读接口。
3. 实现三栏工作区：左侧 Project/文件树/搜索/只读预览，中间 Chat/Plan/步骤与完成状态，右侧 Evidence/hash/version 与 `NOT_APPLIED`/`STALE` Candidate diff。不得增加 Project 写入、删除、移动、重命名或 Apply endpoint。
4. 启动真实后端和前端，使用包含 `.tex`、`.bib`、`.md` 与代码文件的真实测试 Project；覆盖加载、文件预览、搜索、Agent 执行、Plan、Evidence、Candidate、STALE、空状态与失败状态。
5. 必须提交运行截图并迭代视觉：至少检查 `1920x1080`、`1440x900`、`1280x800`，确认无文字截断、布局重叠、异常留白、卡片嵌套或与现有系统明显不一致。不得只以 `npm run build` 作为完成证据。

主对话复审门：

- 代码：复用现有设计系统，不引入生产 mock，不跨范围修改论文/文献 Runtime。
- 契约：Project 身份来自受信路由；Evidence、Verified、Candidate 状态必须来自真实后端结果，不得由前端推测。
- 交互：Project 创建/选择、manifest、read、search、Chat、Plan、Evidence 与 Candidate 主路径可实际操作；失败必须显式展示。
- 视觉：主对话根据真实截图逐项审查参考图一致性、现有系统一致性、三种桌面宽度、长路径/长回答和交互状态；发现问题按 P0/P1/P2 发回原任务继续修正。

### 2026-07-11：Project Workspace 前端闭环执行记录

- Project 页面改为真实只读三栏工作区：认证 Project 创建、manifest 目录树、read/search、Project Chat、Plan、Evidence 与 Candidate 均通过 `/api/v1` 请求；没有 Project 写入或 Apply 操作。
- 证据字段使用 `TRUSTED`，不是未经 Completion verifier 支持的 `VERIFIED`；`current` 由服务端 manifest hash 重校验。Chat 响应只在受信 Project route 返回 typed trusted evidence。
- 前端 session 对明确 404 仅单次清理并恢复，401/403 不恢复；Plan 轮询最多 12 次并在终态刷新 Evidence/Candidate，切换 Project/卸载会取消旧轮询。
- 定向 Maven reactor：30 tests、0 failures、0 errors、0 skipped。前端 `vue-tsc -b && vite build` exit 0（2975 modules）。
- 本地验收 URL：API `http://127.0.0.1:8080`，Vite `http://127.0.0.1:5173`。截图：`data/project-workspace-screenshots/project-workspace-{1920x1080,1440x900,1280x800}.png`。临时 fixture 仅用于本地验收，交付前清理。
- 复审更正：此前三张截图错误地捕获了非浏览器前台窗口，已删除并撤销其结论。后续截图必须先断言 tab URL 为 `/projects`，且 DOM 同时含 `PROJECT WORKSPACE`、真实 Project 名和 `Files`；截图后以图像查看器复核页面内容。

状态：`ACCEPTED_FOR_LOCAL_UI_ACCEPTANCE`。主对话已完成静态边界审查、独立构建与测试、真实运行入口检查和全部验收截图复审；该结论仅表示可以进入用户本地 UI/科研场景验收，不代表用户验收或生产上线完成。

最终收口记录：
- 文件所有权审查：修改集中在 `frontend/src/views/ProjectPreviewPage.vue`、`frontend/src/api/project.ts`、`frontend/src/api/artifact.ts`、`frontend/src/api/agent.ts`、`yanban-api` 的 Project 创建、Project Agent/Evidence/Candidate 窄投影及对应测试、以及本文档。未新增 Project write/apply/delete/move/rename/overwrite endpoint，公开 `POST /api/v1/projects` 请求体不包含 `userId`，所有权来自 authenticated principal，access mode 固定 `READ_ONLY`。
- API 映射：Project 创建 -> `POST /api/v1/projects`；列表 -> `GET /api/v1/projects`；manifest -> `GET /api/v1/projects/{projectId}/manifest`；read -> `GET /api/v1/projects/{projectId}/files/read`；search -> `GET /api/v1/projects/{projectId}/search`；Project Chat -> `POST /api/v1/projects/{projectId}/agent/sessions/{sessionId}/messages` 并返回 typed `projectEvidence`；Project Plan -> `POST /api/v1/projects/{projectId}/agent/sessions/{sessionId}/plans`；Plan Evidence -> `GET /api/v1/projects/{projectId}/agent/plans/{planId}/evidence`；Candidate -> 既有 Artifact API 的 `CANDIDATE_CHANGESET` 只读详情与 revalidate。Evidence/Candidate 的 `trusted/current/status/applicationStatus/hash/version` 均来自后端，前端不解析 assistant 文本推测。
- 测试：`frontend npm run build` exit 0（`vue-tsc -b && vite build`，2975 modules，Vite 大 chunk warning 仍为 warning）；定向 Maven reactor `ProjectControllerIntegrationTest,ProjectServiceTest,ProjectMigrationTest,ProjectCurrentTurnEvidenceTest,ProjectPlanVerticalTest,PlanCompletionEvidenceVerticalTest,ProjectPlanEnvelopeTest,ProjectReactVerticalTest,ProjectReadToolExecutorTest,AgentControllerIntegrationTest,CandidateChangeArtifactServiceTest,CompletionVerifierTest`：72 tests，0 failures，0 errors，0 skipped；`scripts/Invoke-MvpReleaseGate.ps1` exit 0（脚本聚合 `MVP_RELEASE_GATE_RESULT tests=148 failures=0 errors=0 skipped=0`，Maven surefire summary 行为 146/0/0/0）；根目录 `mvn -o test` exit 0，Surefire XML 汇总 547 tests，0 failures，0 errors，8 skipped。
- 真实运行边界：API 已重新打包并启动为当前 jar，`http://127.0.0.1:8080`；Vite 为 `http://127.0.0.1:5173`。浏览器验收使用本机 Chrome + Playwright、`deviceScaleFactor=1`、真实注册/认证 token 初始化、真实 HTTP/API/DB；Plan/Evidence 与长 Chat 页面中的非空数据明确为验收脚本向真实 DB 预置的 acceptance data，不伪装 worker/model 执行，不进入生产前端 mock。
- 截图保留：`data/project-workspace-screenshots/project-workspace-light-1280x800.png`、`project-workspace-light-1440x900.png`、`project-workspace-light-1920x1080.png`、`candidate-not-applied-1440x900.png`、`candidate-stale-1440x900.png`、`session-404-recovery-1440x900.png`、`plan-evidence-1440x900.png`、`chat-long-evidence-1280x800.png`。每张已读取 PNG 元数据与 SHA-256；错误深色/旧 proof 图已删除。
- 真实交互证据：Candidate 通过真实 Artifact API 创建后页面显示 `NOT_APPLIED`，修改 fixture 源文件后真实 revalidate 返回并显示 `STALE`；不存在旧 sessionId 的恢复中旧 messages 404 恰好 1 次、创建 session 恰好 1 次；Plan/Evidence 页面显示 `COMPLETED` step、`main.tex`、`CURRENT` 与 `TRUSTED`；长 Chat 页面由真实 `GET messages` 加载预置长回答并验证 1280×800 三栏不横向溢出。
- 清理：所有 `pw_accept_` 临时用户及其关联 projects/sessions/messages/plans/steps/events/artifacts/invite codes 已按前缀清理，数据库 `pw_accept_` user count=0、invite count=0；此前泄露旧用户名 `project_workspace_ui_0711` count=0。`data/project-workspace-fixture` 与旧 proof/错误截图已删除，只保留最终验收截图。
- 残余风险：Git 根目录识别为父级 `C:/java_file/private_helper_Agent`，当前工作目录在父级状态中显示为未跟踪目录，无法用普通 `git diff` 可靠表达本任务文件级 diff；最终复审需按文件清单和工作区实际文件独立检查。Plan/Evidence 非空浏览器态采用 DB 预置验收数据证明真实 HTTP/DB 投影与布局，不证明异步 worker 在无外部模型条件下真实产出该 Plan。

主对话最终复审：
- 静态边界通过：公开 Project 创建请求不含 `userId`，所有权取自 authenticated principal；Project 创建固定 `READ_ONLY`；未增加 Project write/apply/delete/move/rename/overwrite 能力。Project Chat/Plan 均由受信路由绑定 `projectId`，Evidence 的 `trusted/current/hash/version` 与 Candidate 的 `NOT_APPLIED/STALE` 均来自后端投影。
- 独立前端构建：`npm.cmd run build` 通过，2975 modules transformed；仅保留既有大 chunk warning。
- 独立关键回归：`ProjectControllerIntegrationTest,ProjectServiceTest,ProjectCurrentTurnEvidenceTest,ProjectPlanVerticalTest,CandidateChangeArtifactServiceTest` 共 30 tests，0 failures，0 errors，0 skipped。
- 独立发布门：`scripts/Invoke-MvpReleaseGate.ps1` 通过，`MVP_RELEASE_GATE_RESULT tests=148 failures=0 errors=0 skipped=0`。
- 真实运行检查：API health 与 Vite 均返回 HTTP 200；未认证访问 `/projects` 正确跳转 `/login?redirect=/projects`。主对话复核了三种桌面尺寸以及 `NOT_APPLIED`、`STALE`、session 404 恢复、Plan/Evidence、长 Chat/Evidence 截图，未发现阻塞性布局或状态来源问题。
- 最终结论：`ACCEPTED_FOR_LOCAL_UI_ACCEPTANCE`。下一步由用户使用自己的 Project 目录和真实模型配置执行本地科研场景验收。

### 2026-07-11：Project 绝对路径创建（受控本地模式）

- 创建契约由 `rootRelativePath` 调整为 `projectFolder`；公开 `POST /api/v1/projects` 仍只接收 `name`、`projectFolder`、include/ignore rules，不接收 `userId` 或 `accessMode`。所有者继续仅来自 authenticated principal，实体构造继续固定 `READ_ONLY`。前端创建表单改为 “Project folder / 项目文件夹”，可直接粘贴如 `C:\科研项目\FDA-MIMO` 的 Windows 绝对路径。
- 新增 `yanban.project.allow-local-absolute-project-folders`，默认 `false`（fail-closed）。仅受控本地部署可显式设为 `true`；`application-dev.yml` 支持 IDEA Run Configuration 环境变量 `YANBAN_PROJECT_ALLOW_LOCAL_ABSOLUTE_PROJECT_FOLDERS=true`。远程、共享或不受信任的服务器必须保持默认关闭，因为开启后登录用户可绑定该进程可读的任意本地目录。
- 创建/绑定时将绝对目录规范化，并以 `NOFOLLOW_LINKS` 逐段拒绝 symbolic link、Windows junction/reparse point 和其他 alias，同时拒绝 Windows UNC/设备路径；随后要求 `toRealPath()` 与规范化路径一致、路径存在、为目录且可读（实际打开目录验证）。异常不回显用户路径。绝对输入仅用于绑定，新增 Project 的 `root_path` 不保存该输入；canonical root 仅由内部 root provider 使用。Project summary、manifest、read/search、Agent 工具、Evidence、Candidate 和文件树继续只投影 Project 内相对路径。
- 没有增加 Project 写入、Apply、删除、移动、重命名或命令执行能力。开关关闭时，既有 Project 仍需位于 `yanban.project.local-server-root` 下；新绝对目录绑定被拒绝。
- 回归：`mvn -o -pl yanban-api -am -Dtest=ProjectServiceTest,ProjectControllerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` 通过（21 tests，0 failures，0 errors，0 skipped）；Project Runtime/Evidence/Candidate 子集通过（29/0/0/0）；`frontend/npm.cmd run build` 通过（2975 modules，只有既有 large-chunk warning）。完整 `Invoke-MvpReleaseGate.ps1` 的 Maven 本体完成为 147/0/0/0、reactor `BUILD SUCCESS`，但外层脚本汇总在执行环境的 60 秒命令上限后被中断，未产生其自身聚合结果行。
- 残余风险：该开关是部署边界，不是多租户沙箱；任何远程部署或非受控主机均不得开启。绝对路径仅保留为内部 canonical binding，管理员仍应限制运行进程的文件系统读取权限。

### 2026-07-12：Project 删除绑定、聊天流式一致性与 Evidence repair 修复

- 删除契约新增 `DELETE /api/v1/projects/{projectId}`，成功返回 204。`userId` 仍只来自 authenticated principal；不存在和非本人 Project 统一 404。删除实现仅执行 owner-scoped Project 数据库 binding 删除，不解析 Project root、不调用任何文件删除，因此目录已经缺失或不可读时仍可移除绑定，本机目录和文件保持不变。前端确认文案明确“只删除绑定”；成功后清除该 Project 的本地 session/collapse 映射和迟到请求状态，并选择下一个 Project。服务端 Session/Plan/Evidence/Candidate 因缺少可靠 Project 外键不做级联删除；已删除 Project 的 Candidate 重验返回 `INVALIDATED / NOT_APPLIED`。
- Project Chat 新增受认证、路由绑定的 `/api/v1/ws/projects/{projectId}/chat`。握手顺序为 JWT principal 校验后再做 owner/read-only/root 校验，模型请求体不能提供或覆盖 Project 身份。事件复用 `ack/process/chunk/debug/done/error`，`done` 附带唯一 canonical `assistantContent` 和只含安全相对路径的 typed `projectEvidence`；任何异常投影均抑制服务器绝对路径。HTTP Project Chat 保留为使用同一 `clientRequestId` 的幂等回退。
- Project 前端聊天改为 Workspace 同类交互：先显示 optimistic user、可折叠过程卡片和唯一 assistant placeholder；process 事件实时追加，canonical chunks 始终更新同一个气泡，done 后以服务端 `view=all` 权威消息重载。增加 `New conversation`，用于绕开修复前已经污染的旧 session；不按回答文本猜测性去重，也不删除旧审计历史。所谓过程仅为安全的工具/运行摘要，不公开模型隐藏 chain-of-thought。
- 流式安全边界：Project runtime 的首轮和 bounded repair attempt token 均不直接投影。过程事件实时发送；Completion verifier 选出唯一成功回答后，再按 64 Unicode code points 分块发送 canonical answer，最后由 done 的完整内容权威替换。这避免首轮未验证回答和 repair 回答串接，同时保留可见的增量输出。
- Runtime 根因修复：repair 不再把两份完整 transcript 直接相加，只保留 repair transcript；首轮观察仅通过已重验的 trusted typed ledger 合并。Project 初始提示和 repair server instruction 明确 `project_manifest` 只是 inventory，涉及 Project 结论必须继续调用 `project_read_file` 或 `project_search`。完全相同的 Evidence ID/value 可确定性去重，冲突 ID 继续 fail-closed。`INSUFFICIENT_EVIDENCE` 移除当前轮未验证 plain assistant，只保留一个 limitation；持久化边界排除旧历史、内部 system/repair prompt 和中间回答，只保存当前工具审计与一个最终 assistant。
- 安全范围没有变化：没有新增 Project 文件写入、Apply、移动、重命名、递归删除或命令执行能力；Agent、Evidence、Candidate、WebSocket 与普通 API 继续只投影 Project 内相对路径。未新增 migration。
- 独立整合回归：Project path/controller/delete、Runtime/Reflection/Evidence、Project React/Plan、Candidate、普通与 Project WebSocket 共 19 类 **123 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS**。`scripts/Invoke-MvpReleaseGate.ps1` exit 0，聚合结果 **160 tests，0 failures，0 errors，0 skipped**；Surefire fork JVM 退出等待与 Mockito 动态 agent 仍为既有测试基础设施 warning。前端 `npm.cmd run build` exit 0，`vue-tsc -b && vite build`，2975 modules transformed；仅保留既有 large-chunk warning。测试过程没有启动或占用 8080/5173 服务。
- 残余风险：真实模型仍可能拒绝 read/search；此时系统会返回单个 `INSUFFICIENT_EVIDENCE`，不会降低证据门。修复前已经持久化的重复消息不会自动删除，用户应在 Project 页点击 `New conversation`；旧 Session 仍作为 Workspace 历史保留。Project WebSocket 握手和 evidence current 投影会使用现有 manifest 校验，大型但仍在 traversal limit 内的 Project 可能有额外首包延迟。

### 2026-07-12：Project 回答 Markdown 与字体层级修复

- 对截图对应的真实会话做只读核对：该请求只有一个 completed turn 和一条 chat-visible final assistant；其他 assistant/tool 行均是隐藏的工具过程。Project streaming 仍只发送 verifier 选出的单一 canonical answer，64-code-point chunks 精确重组并在 done 时由完整 `assistantContent` 覆盖。本次不修改回答聚合、repair、streaming 或 Evidence 逻辑。
- 根因是模型返回 malformed Markdown：短标题之外，又把完整正文写成 `##长段落`，并把列表写成 `-内容`。原 `MarkdownMessage` 会无条件修复无空格 heading，而 Project 正文 12px、全局 h2 18px/粗体/下边框，造成视觉上类似多份答案拼接。
- 新增共享 `markdownNormalization.mjs`：代码围栏、inline code、四空格/Tab 缩进代码保持原样；无空格短标题补空格；句子长度/正文标点命中的伪标题降级为普通段落；Project variant 还会防御已经带空格的长伪标题，避免影响 Workspace/Artifact 中合法的长标题；仅保守修正中文或 Markdown inline 起始的 `-内容`，负数、命令参数和分隔线不变。`MarkdownMessage` 继续在 Markdown-It 后经 DOMPurify 输出。
- Project assistant typography 单独调整为正文 14px，h1/h2/h3 为 18/16/15px，收紧标题间距并移除 h2 强分隔线；Workspace 与其他页面样式不变。
- Project system prompt 新增格式约束：只返回一份连贯 final answer；标题必须为短独立短语、单独一行且 `#` 后留空格；正文另起行；禁止整句/整段充当标题；无序列表使用 `- `。
- 回归：真实 assistant 形态与 code/list/heading 边界 Node fixture **6 tests，6 passed**，并直接断言最终 HTML 只有一个 h2 和三个 li；`npm.cmd run build` 通过（2976 modules，仅既有 large-chunk warning）；`LangChain4jToolCallingStrategyTest,CompletionVerifierTest,ProjectAgentRuntimeStreamingTest,ProjectChatWebSocketHandlerTest` 共 **38 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS**。未启动 8080/5173。
