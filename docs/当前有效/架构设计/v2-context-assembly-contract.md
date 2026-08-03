# V2 分层上下文、独立压缩与恢复合同

状态：**设计冻结，待用户审核**

Issue：`#133`

日期：2026-08-03

基础提交：`fe005f3d4b7ae7cf50335acedd1f5e580301f503`

## 1. 目标

V2 从一次请求开始就按固定区域管理上下文。每个区域占模型上下文窗口的固定
百分比，不互相借用。一个区域超限时只整理该区域，其他区域保持不变。

Context Revision 不是主体功能。它只记录每次 Planner、Step、Reflection、
Replan 或 Final Synthesis 实际使用了哪些上下文，保证刷新、重试和服务重启后
可以恢复同一份输入。

## 2. 第一版非目标

- 不做区域间预算借用或动态调度；
- 不处理故意填满某个区域等极端攻击场景；
- 不实现递归摘要、无限历史恢复或自动扩大模型窗口；
- 不修改 V2 核心的 TaskFrame、Plan、Step、Receipt、Workspace、Candidate 或
  ProjectVersion 权威；
- 不同时实现 Project memory、Skill、MCP 或子 Agent。

第一版仍保留最基本的失败闭合：身份或事实引用不匹配、结构损坏、权威区超限、
压缩失败时停止本次模型调用，不伪造成功事实，也不扩大权限。

## 3. 模型窗口来源

模型窗口来自版本化的产品配置，值必须有公开 Provider 文档来源，不由模型输出、
用户消息或 Prompt 决定。

DeepSeek 官方当前将 `deepseek-v4-flash` 和 `deepseek-v4-pro` 的上下文长度列为
1M token，最大输出列为 384K token。输入和输出合计受上下文窗口限制：

- <https://api-docs.deepseek.com/zh-cn/quick_start/pricing>
- <https://api-docs.deepseek.com/api/create-chat-completion>

第一版为每个已支持的 provider/model 保存：

- `contextWindowTokens`；
- `maxOutputTokens`；
- `tokenCounterVersion`；
- 固定分区 profile 版本。

未知模型没有可信窗口配置时不得假装使用 1M；应使用明确的保守 profile 或拒绝
进入分层执行，并返回安全错误。

## 4. 固定分区

以 1M token 模型为例，第一版默认 profile 如下：

| 区域 | 比例 | 1M 对应上限 | 内容 |
| --- | ---: | ---: | --- |
| `CORE_AUTHORITY` | 10% | 100K | system/safety、权限、当前请求、TaskFrame、当前 Plan/Step、工具 schema |
| `RECENT_CONVERSATION` | 20% | 200K | 最近的完整 canonical user/assistant turns |
| `CONVERSATION_SUMMARY` | 10% | 100K | 已被覆盖的较早对话摘要及 coverage |
| `TOOL_RESULTS` | 20% | 200K | 当前和历史相关工具结果的受控投影 |
| `STEP_STATE` | 15% | 150K | Step 状态、accepted Step Result、Receipt/Candidate/Workspace 引用 |
| `LONG_TERM_MEMORY` | 10% | 100K | 本轮检索命中的 governed memory |
| `RAG_EVIDENCE` | 5% | 50K | 本轮检索命中的 RAG/evidence 投影 |
| `OUTPUT_RESERVE` | 5% | 50K | 当前模型调用允许使用的输出空间 |
| `SAFETY_MARGIN` | 5% | 50K | tokenizer 偏差、固定协议和 Provider 额外开销 |

总计 100%。第一版各区域不借用：一个区域未使用的 token 保持空闲，不能转给
另一个区域。

这些比例是首版工程基线，不是永久产品事实。后续只能根据真实运行数据和评测在
新的 profile 版本中调整，已经开始的 turn 继续使用其冻结 profile。

## 5. 各区域超限后的处理

每个区域在组装时计算 token。未超过自身上限时不处理；超过时只处理该区域，
目标是降到该区域上限的 70%，避免下一次调用立即再次压缩。

### 5.1 `CORE_AUTHORITY`

不得语义压缩。允许通过稳定引用替代重复展示文本，但当前请求、权限、TaskFrame、
当前 Plan/Step 和完成条件必须完整。仍超过 10% 时，本次模型调用失败，不占用
其他区域。

### 5.2 `RECENT_CONVERSATION`

保留最新完整 turn，从最旧的完整 turn 开始移出。被移出的 turn 进入
`CONVERSATION_SUMMARY` 的待摘要集合。user/assistant turn 作为一个对象处理，
不能截成半轮对话。

### 5.3 `CONVERSATION_SUMMARY`

摘要记录必须包含覆盖到的 message ID、来源范围和摘要版本。超过上限时只合并
最早的摘要段，保留较新的摘要段和 coverage 单调性。摘要是辅助信息，不能声称
工具成功、文件已修改或 Candidate 已应用。

### 5.4 `TOOL_RESULTS`

先去掉重复展示文本，再将较旧且与当前 Step 无直接关系的输出转换为结构化投影。
始终保留 ToolCall/Receipt ID、工具种类、状态、关键返回值、错误码和截断标记。
原始 Receipt 仍是权威来源。

### 5.5 `STEP_STATE`

当前 Step、目标、完成条件和状态不压缩。较早已完成 Step 可以缩短说明，但必须
保留 Step ID、终态、accepted Step Result 和 Receipt/Candidate/Workspace 引用。
若权威引用投影仍超过 15%，停止本次调用。

### 5.6 `LONG_TERM_MEMORY`

不修改 memory 表中的原始记录。根据当前请求的治理条件和相关性排序，减少本轮
注入的记录，直到不超过 10%。保存实际选中的 memory ID、版本和投影 digest。

### 5.7 `RAG_EVIDENCE`

不重新摘要知识库正文。按相关性、来源治理和稳定排序减少本轮片段，直到不超过
5%。保存 evidence ref、来源版本和投影 digest。

### 5.8 输出与安全余量

`OUTPUT_RESERVE` 和 `SAFETY_MARGIN` 提前扣除，不参与压缩，也不能被其他区域
使用。每个调用阶段仍可在该固定上限内设置更小的实际输出上限。

## 6. 不同执行阶段使用哪些区域

所有阶段使用同一份 profile，但只装配该阶段需要的区域内容：

| 阶段 | 主要区域 |
| --- | --- |
| Planner | CORE、最近对话、对话摘要、长期记忆、RAG；只读取相关历史任务最终状态 |
| Step model | CORE、当前 Step、相关 accepted results、相关工具结果、少量最近对话和记忆 |
| Reflection | CORE、当前 Step 完成条件、当前 Step Result、相关 Receipt、当前失败诊断 |
| Replan | CORE、已完成不可改写事实、当前失败原因和未完成目标 |
| Final Synthesis | CORE 中的目标/交付物、accepted Step Results、最终 Artifact/Candidate 引用 |

没有被某阶段使用的区域保持空，不自动把额度转给其他区域。

## 7. 同一任务恢复与下一轮对话

### 7.1 同一持久化 turn 的恢复

刷新、重复 POST、服务重启或相同步骤重试属于同一 turn。必须读取原来的 Context
Revision、固定 profile、summary coverage、memory/RAG 选择和受控投影，不能用
数据库此刻的最新值静默重建。

权威状态推进后，例如产生新 Receipt、accepted Step Result 或 replan，下一次
模型调用追加子 revision。旧 revision 不覆盖。

### 7.2 用户发起下一轮新对话

新 turn 重新组装上下文：

1. 在 20% 区域内读取最近完整 user/assistant turns；
2. 更早对话只读取最新有效 summary 及其 coverage；
3. 根据新请求重新检索当前有效长期记忆；
4. 根据新请求重新检索 RAG；
5. 只带入相关历史任务的最终状态、accepted Step Result、未解决问题和待确认
   Candidate；
6. 不带入全部 Reflection、工具原始输出、中间轮询状态和已经无关的失败诊断；
7. Project 请求冻结新 turn 开始时的当前 ProjectVersion。

memory 被用户删除、纠正或拒绝后影响新的 turn，但不改变已经运行中的旧 turn。

## 8. 压缩发生时的状态

压缩是模型调用前的上下文子阶段，不是新的 Plan 或 Step 状态。Plan/Step 权威状态
保持原样，例如当前 Step 继续是 `ACTIVE`。

Context Revision 使用独立状态：

```text
ASSEMBLING
  -> READY

ASSEMBLING
  -> COMPACTION_REQUIRED
  -> COMPACTING
  -> READY

COMPACTING
  -> COMPACTION_FAILED
```

压缩期间：

- 不执行新工具；
- 不完成 Step；
- 不创建成功 Step Result；
- 不修改原 ProjectVersion；
- 已有 Plan、Receipt、Candidate 和 Workspace 事实保持不变。

压缩失败时保留最后成功的 Context Revision，本次模型调用停止。后续重试使用稳定
stage key，不重复执行已经完成的工具。

## 9. 用户可见状态

任务仍显示“执行中”。页面在当前 Step 的折叠执行信息内显示独立 phase：

- 正在组装上下文；
- 正在整理历史对话；
- 正在整理工具执行结果；
- 上下文整理完成；
- 上下文整理失败。

压缩状态不是 assistant 消息，不进入对话历史，也不能被当作 Step Result。为了
避免闪烁，第一版可以只展示持续超过 1 秒的压缩 phase；无论是否展示，状态都要
持久化以支持刷新恢复。

## 10. 存储设计

不为每个区域新建一张业务表。现有事实继续留在原表：

- 对话：`agent_messages`；
- 摘要：`agent_session_summaries`；
- 长期记忆：现有 memory 表；
- Plan/Step：V2 Plan 生命周期表；
- 工具结果：Receipt/EffectOutcome；
- ProjectVersion、Workspace、Candidate：各自现有权威表。

后续实现原地演进 `agent_context_snapshots` 为 revision header，并新增一个 section
子表，而不是建立第二套平行 context ledger。

### 10.1 Revision header

至少记录：turn、revision number、parent revision、stage、stable stage key、状态、
provider/model profile、context window、总 token、输出预留、profile 版本、父 digest、
当前 digest 和创建时间。

### 10.2 Section row

每个 revision 每个区域一行，至少记录：section type、固定比例、token limit、压缩前
token、压缩后 token、状态、来源引用、summary coverage 或选择结果、受控投影、
projection digest 和压缩原因。

禁止保存 API key、`.env`、host path、用户文件全文、Provider 原始响应、模型
reasoning 或未裁剪工具输出。

## 11. `V2ContextRevision` 合同

后续 Java 类型名可以调整，但必须表达：

- owner-qualified turn；
- 单调 revision number 和直接 parent；
- `PLANNER`、`STEP_DECISION`、`REFLECTION`、`REPLAN` 或
  `FINAL_SYNTHESIS` stage；
- 绑定 Plan/revision/Step/ToolCall 或 terminal cut 的 stable stage key；
- 冻结的 model profile 和固定分区 profile；
- 有序 section manifest；
- 来源 ID、版本、coverage、选择/压缩结果；
- parent digest 和 canonical lowercase SHA-256 digest。

同一 stage key、相同内容永久 replay；同一 stage key 的不同内容冲突。占用但
digest、父链、owner、Plan 或来源绑定不一致时失败闭合，不覆盖或自动修复。

## 12. 实施顺序

设计审核后，实施使用一个长期集成分支和一个 Draft PR；多个子 Issue 作为阶段
检查点，不单独创建分支和 PR。这是用户为 V2 分层上下文 V1 明确批准的阶段性
例外，不适用于其他功能。

建议顺序：

1. **模型 profile 与 token 统计**：建立 1M 等窗口配置、固定比例计算和影子统计，
   暂不改变运行行为；
2. **Context Revision 存储**：演进 snapshot header、增加 section 子表、实现
   exact replay 和 digest；
3. **对话层**：最近完整对话、summary coverage 和独立对话压缩；
4. **新 turn 组装**：重新选择最新摘要、当前 memory、RAG 和相关历史终态；
5. **执行层**：接入 Step、tool result、Reflection 和 Final Synthesis 的独立区域；
6. **恢复与压缩状态**：同 turn 恢复、压缩失败和稳定重试；
7. **前端 phase**：显示持续压缩状态；
8. **整体验收**：长对话、长工具输出、重启、Candidate 等待确认和失败路径。

每个子 Issue 使用独立提交并记录测试命令和 checkpoint。整个分支稳定后一次合入
`main`。

## 13. 最小测试矩阵

- 1M profile 的各区域 token 上限计算准确，总和为 100%；
- 一个区域超限只改变该区域，其他 section digest 不变；
- 不发生预算借用；
- 最近对话按完整 turn 移入摘要，不产生半条消息；
- summary coverage 单调且不会重复包含同一消息；
- memory/RAG 只减少本轮选择，不修改原记录；
- tool/Step 压缩保留 Receipt 和 accepted Step Result 引用；
- 当前请求、TaskFrame、当前 Plan/Step 不被语义压缩；
- 同 turn 重启恢复得到相同 revision digest；
- 新 turn 使用最新有效 memory/summary，但不继承全部旧执行日志；
- 压缩时 Step 保持 ACTIVE，工具不重复执行；
- COMPACTING/READY/FAILED phase 刷新后可恢复；
- 压缩失败不创建成功 Step Result、Candidate 或新 ProjectVersion。

## 14. 优点、缺点和剩余风险

### 优点

- 规则简单：固定比例、互不借用、哪个区域超限就只处理哪个区域；
- 实现和测试比动态预算分配容易；
- 对话、记忆、RAG、工具结果和 Step 状态各自使用适合的处理方式；
- 同 turn 可稳定恢复，新 turn 又能使用最新有效记忆；
- 复用现有事实表，只增加 revision/section 管理，不重做 Agent 核心。

### 缺点

- 某个区域空闲时额度会浪费，另一个区域仍可能因为固定上限失败；
- 默认比例未经过真实任务长期评测，可能需要后续 profile 版本调整；
- 1M 窗口仍有显著 token 成本和延迟，不能因为窗口大就默认装满；
- summary 和工具投影的质量会影响后续回答；
- snapshot 兼容迁移、token 统计、恢复和前端 phase 仍是中等规模改动。

### 第一版接受的剩余风险

- 暂不处理恶意填满单一区域和复杂动态抢占；
- tokenizer 与 Provider 服务端计数可能有小幅偏差，由固定 5% 安全余量承担；
- 已开始 turn 默认看不到运行中后来修改的 memory；
- 固定比例可能不是所有 Planner/Step/Reflection 场景的最优利用方式。

这些问题只有出现真实失败样本后才进入后续优化，第一版不提前实现动态预算借用。

## 15. 第一版实施结果（2026-08-03）

第一版已在 `codex/issue-135-layered-context` 集成分支实现，等待 Draft PR
审查，尚未合入 `main`：

- 九个固定区域继续使用 10/20/10/20/15/10/5/5/5 百分比，不借用额度；
- Context Revision 使用一个 header 表和一个 section 子表，没有按区域拆表；
- 新 turn 重新选择近期完整对话、摘要 coverage、当前 memory/RAG 和相关历史终态；
- Planner、Step、Reflection 主调用/审计/格式修复、Final Synthesis 的真实
  Provider 调用前都必须得到 READY revision；
- 同一稳定 stage key 重放同一安全请求；父 digest、九层唯一性和未替换层继承
  不一致时失败闭合；
- 工具结果超限时只压缩 TOOL_RESULTS，并把压缩后的同一内容交给模型；
- 压缩与上下文失败不完成 Step、不重复工具、不发布 Candidate、不应用
  ProjectVersion；
- Final Synthesis 成功后才允许发布 Candidate；发布失败会丢弃未持久化答复；
- owner-qualified turn API 和项目对话 UI 在当前 RUNNING 任务级显示持久化
  ASSEMBLING、COMPACTION_REQUIRED、COMPACTING、READY 或 FAILED；启动 POST
  未返回时前端也并行查询，刷新后可恢复。第一版不把 phase 绑定到某个步骤行，
  避免协调循环内 timeline 尚未逐轮持久化时把后续步骤状态贴到旧步骤。

验证结果：直接相关的 21 个后端测试类共 134 项通过；前端状态映射/展示 19 项
通过；前端生产构建通过；V2 Context H2 迁移、并发与 replay 测试通过。完整
reactor 仍会被仓库既有的 RAG fixture 缺失阻断；`yanban-api` 全量测试仍包含旧
Project 路由、Kafka、本地 demo 数据和独立旧 Step vertical 的环境/基线失败，均
不在本能力改动路径内，必须在 PR 证据中原样列出，不能写成全量通过。

真实 1M profile 下，近期对话进入 Planner 前仍受 12 条/12,000 字符上限约束，
普通用户任务很难稳定触发 200K 的 RECENT_CONVERSATION 压缩；工具原始 stdout
也不会直接进入 TOOL_RESULTS。第一版不增加生产测试后门或动态缩小窗口。用户
视角验收应验证正常 V2 项目任务、状态组件和刷新恢复；真实压缩阈值由自动化
超限测试覆盖，并把这一限制列为剩余风险。

用户视角验收使用本地管理员登录和仅含合成 README 的临时项目：登录、导入和
任务提交成功；任务执行时显示“上下文已就绪”；刷新后同一任务继续可见，并把
真实 Reflection 失败明确显示为 `REFLECTION_FAILED`，没有伪造成功。该环境未能
完成正常模型终态，也没有在真实 1M profile 下触发压缩阈值。验收后删除合成
Project 时既有删除接口返回 HTTP 500，因此合成 Project 仍留在本地测试数据库；
临时源文件、测试进程和容器已清理，此删除故障不属于本能力改动路径。
