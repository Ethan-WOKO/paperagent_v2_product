# V2 执行链路替换：基于 DeepSeek Harness（DSH）的 ReAct 引擎设计与评估（草案）

> 日期：2026-08-16
> 分类：架构设计草案（评审中，未实施）
> 适用范围：替换现有 V2 四角色 plan-and-execute 链路的"任务执行"部分
> 非目标：不替换产品 UI、会话存储、认证、文献/知识库服务本体、沙箱 broker；不删除旧链路（保留只读回退）

## 1. 背景与问题

现有 V2 链路（`agent-v2/` + `yanban-api` 适配层）采用四角色 plan-and-execute：
Planner → Executor → Reflector → Answer，每个角色的输出都是严格校验的 JSON 契约。
实际运行中（2026-08-14 至 08-16 的多次真实任务）反复出现同一类失败：

1. 模型无法稳定产出符合契约的 JSON（缺字段、引用绑定错误），每次失败重试消耗 10K–30K token；
2. 对"检查 Sort.java 是否能编译"这类任务，模型在多次运行中始终不声明第三方依赖（logback），
   导致编译失败 → STEP_BLOCKED → 任务失败（错误码 STEP_BLOCKED）；
3. 换更强模型（deepseek-v4-pro）后问题依旧；在规则、schema、修复话术上多轮修补后仍无效。

### Spike 证据（2026-08-16，真实沙箱 + v4-pro）

用最小 ReAct 循环（原生工具调用，无 JSON 契约）跑同一任务：

- 第 1 轮：列出文件、读取 Sort.java、发现 logback import；
- 第 2 轮：第一次编译即声明 `--dependency=ch.qos.logback:logback-core:1.2.13`，
  真实 E2B 沙箱下载依赖、编译运行成功（exit 0，输出 `[1, 2, 3]`）；
- 第 3 轮：交付结论。

共 3 次模型调用、约 1.7K token。结论：失败根因在链路协议（模型被迫背机器可验证的
JSON 契约），不在模型能力。ReAct + 原生工具调用是正确方向。

### 顺带结论

- 模型 API：chat/completions + 原生 tool_calls 完全够用（Spike 全程使用）；
  现有链路中 `Thinking.disabled()` + `response_format=json_object` 对 v4-pro 等推理
  模型是抑制项，Spike 未做这两项设置即成功。
- 沙箱依赖下载功能本身可用（Spike 走通）；此前从未被模型正确触发。

## 2. 最终形态（目标图景）

```
用户 → 产品前端 → Java 后端（会话/权限/项目/发布回滚，保留）
                        │ HTTP 提交任务 / 回调
                        ▼
              Agent Engine（新，Node/TS，基于 DSH）
              ├─ ReactLoopAgent（DSH 自带循环，不改核心）
              ├─ 产品工具插件（ToolDefinition）：
              │    project_list / project_read / project_write(仅工作区) /
              │    sandbox_execute(现有 broker) / literature_search / kb_search /
              │    publish(强制绑定成功回执) / ask_user
              ├─ 保证插件（DSH 事件点）：
              │    tools/pre-execute + guard：权限、只读约束、工作区隔离
              │    tools/post-execute：回执生成、结果规范化
              │    session/event 监听：把事件流推给 Java 后端 → 前端
              └─ 会话/上下文：DSH session + compaction
```

核心原则：**强保证从"协议层"下放到"工具层"**。模型只需会用工具；
工作区隔离、回执绑定、自动发布、回滚由工具和 guard 强制执行。

## 3. DSH 复用评估（v0.1.0-rc.6，MIT 许可）

| DSH 模块 | 结论 | 说明 |
|---|---|---|
| `dsh-agent-loop` | 复用，不改 | 全库唯一具体循环（ReactLoopAgent）；新行为一律走插件/事件 |
| `dsh-tools`（含 `defineTool`） | 复用 | 工具注册、类型化参数 schema、pre/execute/post 管线、guard |
| `dsh-llm` + `dsh-llm-deepseek` | 复用 | chat/completions 适配器，原生工具调用 |
| `dsh-llm-retry` | 复用 | `agent/request-error` 上的退避重试 |
| `dsh-session` / `dsh-session-projection` / `dsh-session-persistence` | 改造 | 会话投影适配产品会话；持久化可初期落在本地，最终接产品存储 |
| `dsh-compaction` | 复用 | 上下文压缩（对比现链路无压缩，长任务优势明显） |
| `dsh-sandbox` / `dsh-permission-presets` / `dsh-user-approval` | 改造 | 用产品权限/审批语义替换默认预设；沙箱执行走现有 broker |
| `dsh-subagent` / `dsh-workflow` | 二期 | 一期不使用 |
| Web UI / client / cmdline 模块 | 不用 | 前端继续用现有产品前端 |
| 其余（fs/bash 等本地工具） | 不用或替换 | 产品工具集重新定义 |

复用方式（待确认，默认建议）：**npm 依赖官方包 + 自研产品插件包**。
如后续需要改循环核心再考虑 fork（fork 的维护成本需列入风险）。

## 4. 产品资产映射（哪些保留、落在哪）

| 产品现有资产 | 在新引擎中的位置 |
|---|---|
| 沙箱执行（E2B broker + 命令白名单 + 依赖下载） | `sandbox_execute` 工具，直接调现有 broker HTTP 接口（Spike 已验证协议） |
| 项目文件读/写（对象存储 + 工作区） | `project_list` / `project_read` / `project_write` 工具；写入仅进隔离工作区 |
| 回执（ExecutionReceipt） | `tools/post-execute` 插件为每次执行生成正式回执并落库 |
| 自动发布 + 不可变版本 + 回滚 | `publish` 工具 + guard：无绑定成功回执则拒绝；发布动作复用产品现有发布服务 |
| 权限/审批 | `tools/pre-execute` + guard：按产品权限层判定 allow/deny/ask |
| 文献检索 / 知识库检索 | 对应工具封装现有服务，工具结果走 DSH 的标准结果结构 |
| 会话/多轮/前端推送 | DSH session 事件 → 监听器转发 Java 后端 WebSocket |
| 任务取消 | 复用 DSH 的取消机制（AgentHandle/cancel），对接现有取消协议 |

## 5. 分阶段计划与验收

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| P0 设计评审 | 本文档 + 关键问题确认 | 评审通过 |
| P1 引擎骨架 | Node/TS 服务，DSH 依赖 + 最小工具（list/read/sandbox_execute） | Sort.java 检查任务端到端通过（Spike 场景正式化） |
| P2 工具与保证 | 写文件、回执、发布/回滚、权限 guard、会话事件转发 | 单文件修改任务：沙箱验证→自动发布→可回滚，全自动且可审计 |
| P3 任务面扩展 | 文献检索、知识库问答、多文件修改 | 验收任务集全绿 |
| P4 切换与灰度 | 前端任务入口切新引擎，旧链路只读保留 | 灰度通过，回退演练成功 |

**验收任务集（P1–P3 全绿才算"能用"）**：
1. 检查 Sort.java 是否能编译（含依赖声明）——必须过；
2. 修改一个 Java 文件并跑通测试（工作区→沙箱→发布→回滚）——必须过；
3. 多文件修改 + 依赖变更——必须过；
4. 文献检索类任务——必须过；
5. 知识库问答类任务——加分项（服务可用性依赖外部）。

## 6. 风险与开放问题

1. **上游依赖**：DSH 目前是 rc 版本；若用 npm 依赖，核心行为随上游变化；若 fork，需持续跟进。
2. **上下文策略变化**：现链路每次调用重发完整上下文；DSH 有 compaction，行为与成本特征不同，需评测。
3. **权限模型差异**：DSH 的 approval/permission 是通用模型，需严格映射产品权限层，防止越权。
4. **旧链路退役节奏**：P4 前旧链路只做冻结维护，不新增功能。
5. **团队技能**：新引擎是 TS 技术栈，需确认维护人力。

## 7. 待确认问题（草案默认值，评审时改）

| # | 问题 | 草案默认 |
|---|---|---|
| 1 | 引擎范围 | 只替换任务执行层，会话/权限/前端沿用现有 |
| 2 | DSH 复用方式 | npm 依赖 + 产品插件包 |
| 3 | 一期任务类型 | 项目文件检查/问答 + 代码修改；检索/知识库 P3 接入 |
| 4 | 模型策略 | deepseek 系列，允许思考模式，不禁用 thinking、不强制 JSON 模式 |
| 5 | 验收口径 | 见第 5 节任务集 |
| 6 | 引擎部署 | 独立 Node 服务，Java 通过 HTTP 提交/回调 |
