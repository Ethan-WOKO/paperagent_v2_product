# V2 Context Assembly、预算与恢复合同

状态：**设计冻结，待实现**  
Issue：`#133`  
日期：2026-08-03  
基础提交：`fe005f3d4b7ae7cf50335acedd1f5e580301f503`

## 1. 目标

本合同规定 V2 一次持久化 turn 在 Planner、Step model、Reflection、
Replan 和 Final Synthesis 阶段如何选择、预算、记录和恢复上下文。

核心目标只有两个：

1. 同一阶段发生刷新、重试或服务重启时，模型重新看到同一份上下文；
2. 摘要、记忆、RAG 和模型输出不能覆盖 TaskFrame、Plan、Step Result、
   Receipt、Workspace、Candidate 或 ProjectVersion 等权威事实。

## 2. 非目标

本 Issue 不修改 Java、数据库、API、前端或 Prompt，也不实现 tokenizer、
自动摘要、语义压缩、Project memory、Skill、MCP 或子 Agent。

本合同不改变工具权限、Candidate apply gate、Workspace 隔离或
ProjectVersion 的不可变边界。

## 3. 当前代码事实

| 阶段 | 当前输入 | 当前预算/裁剪 | 当前恢复行为 | 主要问题 |
| --- | --- | --- | --- | --- |
| Planner | 当前请求、最近消息、session summary、用户 memory、RAG、Skill prompt、Project 状态 | 最近 12 条、总计约 12,000 字符 | resume 重新读取当前 summary、memory 和 RAG | 同一 turn 的辅助上下文可能漂移 |
| Step model | TaskFrame、当前 Plan/Step、工具目录、conversation context、已有执行事实 | conversation 每项直接截到 2,000 字符；工具和事实各有局部上限 | 从持久化 Plan 恢复，但 conversation 由 intake 重建 | 可能截坏结构，也没有统一总预算 |
| Reflection | TaskFrame、Plan、conversation、accepted results、Receipts、当前 Step result、未完成 Steps | 多处局部 2,000/12,000/20,000 字符上限 | 从当前持久化事实重新聚合 | 没有记录本次判断到底使用了哪些投影 |
| Replan | Reflection 的 REPLAN 结果与当前 ACTIVE authority | replacement 字段有解析上限 | 依赖持久化 replan marker | 上下文来源仍沿用未冻结的 Reflection 输入 |
| Final Synthesis | TaskFrame、accepted Step Results、Candidate/输出路径 | 总事实约 24,000 字符；单结果约 4,000 字符 | 重新读取 accepted results | 有确定性事实来源，但没有统一 revision 和预算说明 |

现有 `AgentContextBuilder` 已具备消息规范化、section、dropped item 和安全
debug projection，应该复用。现有 `agent_context_snapshots` 对 `turn_id` 唯一，
只能表达一条快照，不能表达一个 turn 内多个模型阶段的追加 revision。

## 4. 最小改动决策

1. 不修改 `agent-v2` 核心事实模型。Context Assembly 先作为 product-side
   adapter 接入 `yanban-api`，只引用稳定 V2 标识。
2. 不新建第二套 summary、memory、Receipt、Workspace、Candidate 或
   ProjectVersion 表。
3. 后续实现优先演进现有 `agent_context_snapshots`，使其能够追加保存多个
   revision；不另建平行的 context ledger 表。
4. 继续复用 `AgentContextBuilder` 的规范化和安全投影能力，但把字符窗口升级为
   统一预算器的一个输入，而不是最终事实源。
5. 初次实现只解决“冻结、恢复、审计和确定性裁剪”，不同时实现模型生成摘要。

## 5. 上下文层级

### 5.1 必须保留的运行合同

- 已认证 user/session/turn；
- permission tier 与允许的 capability/tool 集合；
- TaskFrame；
- 当前 Plan revision、active Step 和 completion criteria；
- 当前用户请求。

如果这些内容无法放入预算，必须停止模型调用并返回安全的
`CONTEXT_AUTHORITY_OVER_BUDGET` 类失败；不得裁掉其中一部分继续执行。

### 5.2 不可改写的事实引用

- accepted Step Result；
- 当前判断必须使用的 Receipt；
- 冻结 ProjectVersion；
- Workspace、Candidate 和 Artifact 引用；
- 已持久化 replan/completion 状态。

默认保存稳定标识、版本和 digest，不复制完整文件或原始工具输出。只有当前模型
调用确实需要的受控投影才进入 assembled content。

### 5.3 可预算的辅助上下文

- frozen session summary projection；
- governed memory hits；
- RAG/evidence snippets；
- recent canonical conversation turns；
- 非权威工具观察和失败诊断。

辅助上下文可以按确定性顺序省略或压缩，但必须记录原因。它不能改变身份、权限、
事实状态或完成判断。

## 6. `V2ContextRevision` 逻辑合同

后续实现可以使用不同 Java 类型名，但必须表达以下字段和语义：

| 字段 | 语义 |
| --- | --- |
| `turnId` | owner-qualified 的持久化 turn |
| `revisionNumber` | 从 1 开始、同 turn 单调递增 |
| `parentRevisionNumber` | 首个 revision 为空，其余必须指向直接父 revision |
| `stage` | `PLANNER`、`STEP_DECISION`、`REFLECTION`、`REPLAN`、`FINAL_SYNTHESIS` |
| `stageKey` | 阶段稳定键；至少绑定 Plan/revision/Step/ToolCall 或 terminal cut 中适用的身份 |
| `reason` | `INITIAL`、`AUTHORITY_ADVANCED` 或受控的 `EXPLICIT_AUXILIARY_REFRESH` |
| `modelEndpointRef` | provider/model 的非秘密快照；不得包含 key 或完整 URL 凭据 |
| `budget` | 模型窗口、系统指令、工具 schema、输入、输出和 repair reserve 的预算 |
| `sections` | 有序 section manifest 与受控投影 |
| `sourceRefs` | summary coverage、message、memory、RAG evidence、Plan、Result、Receipt 等来源引用 |
| `parentDigest` | 父 revision 的 canonical digest；首个 revision 为空 |
| `digest` | 当前 canonical document 的小写 SHA-256 |
| `createdAt` | 持久化时间，只用于审计，不参与内容选择 |

### 6.1 Section 合同

每个 section 至少记录：

- `type`、稳定排序位置和 authority/auxiliary 分类；
- 来源标识、来源版本或覆盖上限；
- `INCLUDED`、`DROPPED` 或 `TRUNCATED`；
- 原始项目数、纳入项目数、预算和实际 token/字符估算；
- 省略或截断原因；
- 受控投影 digest；
- 仅在 exact replay 必需且允许持久化时保存的 bounded projection。

禁止保存 API key、`.env`、host path、用户文件全文、Provider 原始响应、模型
reasoning 或未裁剪工具输出。

## 7. Revision 创建和恢复规则

1. Planner 首次调用前创建 revision 1，冻结该 turn 使用的 summary coverage、
   memory 命中、RAG 投影、最近对话、Skill revision 和 model endpoint。
2. 同一 `stageKey` 的 exact replay 必须返回原 revision，不重新读取当前 summary、
   memory、RAG、Skill 或设置。
3. Plan/Step/Result/Receipt 等权威状态推进后，下一模型阶段追加子 revision；不得
   覆盖旧 revision。
4. 辅助数据变化默认只影响未来 turn。若产品以后允许运行中刷新，必须使用
   `EXPLICIT_AUXILIARY_REFRESH` 追加 revision，并记录用户或服务器授权原因。
5. 找到占用但 digest、父链、stageKey 或来源绑定不一致的 revision 时失败闭合；
   不自动修复、覆盖或重新组装。
6. Final Synthesis 必须引用 terminal cut 与全部 accepted Step Result 的稳定集合；
   重放时不得因为新的 summary 或 memory 而改变答案输入。

## 8. 预算合同

预算按实际 provider/model profile 解析，至少分为：

```text
model input window
  - system/safety reserve
  - tool schema reserve
  - required authority facts
  - required Receipt/result projections
  - auxiliary context allowance
  - output reserve
  - structured-output repair reserve
```

规则：

1. 先预留输出和 repair，再组装输入。
2. 权威层按固定顺序完整纳入；不足时失败，不降级为辅助信息。
3. 辅助层使用稳定优先级、稳定排序和稳定 tie-breaker。
4. JSON、tool call/result、消息 turn 和 Receipt projection 只能在对象边界裁剪，
   不允许任意 `substring` 产生半个结构。
5. estimator 与 provider 实际计数存在偏差时使用安全余量；超限失败不能自动扩大
   模型、网络或工具权限。
6. 相同输入、相同 profile 和相同来源 revision 必须产生相同 section 顺序和 digest。

第一版不要求跨 provider 完全相同的 token 数，但要求同一 provider/model profile
内确定性，并记录估算器版本。

## 9. 现有能力复用结论

| 能力 | 结论 | 后续最小变化 |
| --- | --- | --- |
| `AgentContextBuilder` | `REUSE_WITH_ADAPTER` | 保留规范化与安全 debug projection；由 revision assembler 提供冻结来源和预算 |
| `agent_context_snapshots` | `EVOLVE_IN_PLACE` | 后续迁移为同 turn 多 revision；保留现表和查询能力，旧行按 legacy revision 读取 |
| `AgentSessionSummaryService` | `REUSE_AFTER_MONOTONIC_GUARD` | summary 仍是一 session 一行；后续增加 coverage/CAS 防倒退，不作为执行事实 |
| long-term memory | `REUSE_GOVERNED_PROJECTION` | 冻结本 turn 实际命中的受控投影和来源版本；删除/纠正影响未来 turn |
| RAG/evidence | `REUSE_BY_REFERENCE` | 优先保存稳定 evidence ref 与 bounded projection，不复制知识库或用户文件 |
| TaskFrame/Plan/Step Result/Receipt | `REFERENCE_ONLY` | Context Revision 只引用，不复制或改写权威事实 |

## 10. 对抗性原理与失败行为

设计默认所有非权威输入和所有恢复边界都可能被攻击或发生竞争：

| 场景 | 必须行为 |
| --- | --- |
| summary/memory 在任务运行中被修改或删除 | 当前 stage exact replay 仍使用冻结 revision；变化只影响未来 turn 或显式新 revision |
| memory、RAG、文件或工具输出包含“忽略规则”等指令 | 标记为 untrusted data；不能改变 system policy、权限、工具集合或事实状态 |
| 超长字符串把关键字段挤出窗口 | 权威层保留；辅助层按确定性顺序丢弃；权威层本身超限则失败 |
| JSON 或 tool call/result 在边界处被截断 | 整项省略或按字段重建合法投影，绝不保存半个结构 |
| 恢复时 provider/model 设置已变化 | 使用 revision 中冻结的非秘密 endpoint profile；无法安全恢复时停止，不静默换模型 |
| forged source ID、跨用户 memory 或跨 Plan Receipt | owner、turn、Plan、Step、ProjectVersion 和 digest 任一不符即拒绝 |
| 两个请求并发创建同一 stage revision | 数据库唯一约束决定赢家；完全相同者 replay，不同内容者 conflict |
| revision 已占用但正文、digest 或父链损坏 | 返回 sanitized partial-state failure，不读取正文到日志，不自动覆盖 |
| snapshot 写入成功但模型调用失败 | revision 保留供重试；不得制造 Step Result 或成功事实 |
| 模型调用成功但后续事实未提交 | 不能根据未持久化输出推进；恢复从最后权威 cut 和已存 revision 重试 |
| estimator 低估导致 provider 拒绝 | 记录安全错误并停止；不得随机裁剪后无痕重试 |

## 11. 后续实现拆分

### Issue B1：revision 持久化与初始冻结

- 演进现有 snapshot 表和服务，支持 append-only revision、stageKey、父 digest 和
  exact replay；
- intake 首次组装时保存 revision 1；resume 读取 revision 1；
- 不改变上下文选择算法，不接自动摘要。

### Issue B2：统一预算与各阶段接线

- 增加 provider/model-aware budget profile；
- 按对象边界替换 conversation/JSON 的任意字符串截断；
- Step、Reflection、Replan 和 Final Synthesis 使用显式子 revision；
- 日志只记录 stage、计数、预算、digest 和安全错误码。

### Issue C：滚动摘要与语义压缩

- 在 revision/replay 稳定后接入 V2 summary coverage；
- 增加 CAS/单调推进和失败语义；
- 自动提取记忆、Skill、MCP 和子 Agent 继续保持独立 Issue。

## 12. 后续测试矩阵

### 合同测试

- canonical 排序和 digest 稳定；
- authority section 不能被 dropped/truncated；
- structured item 只按对象边界裁剪；
- 相同来源/profile 生成相同 revision；
- 不同 stageKey 不能复用同一 revision。

### 仓储与并发测试

- exact replay、changed replay conflict；
- revisionNumber 和 parentDigest 单调；
- 同 stage 并发只有一个赢家；
- torn/corrupt/cross-owner/cross-Plan 数据失败闭合；
- 旧 snapshot 行可安全读取但不能冒充新的 canonical revision。

### 行为和恢复测试

- intake 后修改 summary、memory、Skill 或 model settings，resume 仍使用冻结输入；
- accepted Step Result/Receipt 推进后只追加新 revision；
- 重启前后 revision digest 与模型输入一致；
- prompt injection 不扩大权限；
- 超预算、缺失权威来源和 estimator/provider 不一致均有稳定失败结果；
- DIRECT、Plan 成功、等待确认和失败都不会制造或覆盖权威事实。

## 13. 优点、缺点与剩余风险

### 优点

- 改动集中在 product adapter 和现有 snapshot 能力，避免侵入 V2 核心。
- 同一任务可重放、可解释，解决 summary/memory 静默漂移。
- 权威事实和辅助上下文分层，降低提示注入和错误摘要污染完成判断的风险。
- 先冻结合同，再分别实现预算、摘要、Skill 和子 Agent，减少重复数据模型。
- append-only revision 与现有 Plan/Step/Receipt 的事实风格一致。

### 缺点

- 同一 turn 会保存多条 revision，数据库容量和查询复杂度会上升。
- 为 exact replay 保存 bounded conversation/summary/memory 投影，会增加隐私和保留期治理责任。
- provider tokenizer 和工具 schema 成本不同，预算实现与测试成本高于简单字符截断。
- 现有 snapshot API 和 `turn_id` 唯一约束需要兼容迁移，不能只改 service。
- 设计优先保证确定性；运行中 memory 更新默认不会立即影响已经开始的任务。

### 剩余风险

- tokenizer 版本或 provider 服务端计数变化仍可能造成少量估算偏差；需要安全余量和显式失败。
- 数据库只保证已持久化 revision；模型调用与事实提交之间仍需要现有幂等身份和恢复协议配合。
- 若未来需要按法规删除已冻结的辅助文本，exact replay 与删除权之间需要单独的数据保留设计。
- 大型 Project 仍可能因权威事实本身超预算而失败；本合同不引入第二套索引或无限上下文。

## 14. 被拒绝的替代方案

1. **每次 resume 重新组装当前上下文**：改动最小但不能保证行为一致，拒绝。
2. **只保存最终完整 prompt**：重放简单，但会复制策略文本、用户数据和工具正文，
   难以审计来源，拒绝。
3. **新建独立 context ledger 表并保留旧 snapshot 不动**：会形成两个事实源，拒绝。
4. **立刻换成框架 ChatMemory/tokenizer**：无法表达 V2 权威引用和恢复协议，拒绝。
5. **先实现自动摘要或子 Agent**：会在没有稳定上下文合同前制造更多漂移来源，拒绝。
