# 未完成能力提取与必要性评估

更新时间：2026-08-03

## 1. 评估方法

本清单综合检查了：

- `docs/历史归档/0708阶段/**`；
- `docs/**` 和 `memory-bank/**`；
- 当前 V2 intake、context builder、memory、Step Result、Reflection、Candidate、Workspace、tool catalog 和前端状态；
- 当前 main 的提交历史和迁移地图。

结论分为：

- **建议实施**：存在当前代码缺口，能直接改善产品目标，并且有清晰安全边界。
- **条件实施**：方向有价值，但应等待前置合同、真实用例或评测证据。
- **暂不实施**：现阶段收益不足、容易重复实现或会扩大范围。
- **确认不做**：与已冻结产品决定冲突。

## 2. 建议实施

| 优先级 | 能力 | 当前状态 | 为什么有必要 | 实施前提 |
| --- | --- | --- | --- | --- |
| P0 | V2 Context Revision/ledger | Planner、Step、Reflection 和 Final Synthesis 各有局部上下文，但没有贯穿 turn 的可重放 revision | 是摘要、记忆和子 Agent 的共同基础；解决恢复时上下文漂移和调试不可解释 | 先冻结不可裁剪事实、辅助上下文、版本和 digest 合同 |
| P0 | V2 语义压缩和确定性预算 | intake 主要使用最近 12 条/12000 字符；adaptive 上下文仍有单条 2000 字符截断 | 长会话会丢语义或截坏结构，模型成本和行为不可预测 | provider/model-aware token 预算；结构化裁剪；保留 TaskFrame/Step Result/Receipt 引用 |
| P0 | V2 滚动会话摘要 | summary 表和服务已存在，但只有旧 `AgentService` 更新；V2 只读取 | V2 新任务无法把 DIRECT、Plan 成功、等待确认和失败稳定带入后续对话 | 复用现表；CAS/coverage 单调推进；失败不写成成功事实 |
| P0 | V2 context snapshot 接入 | snapshot 表/API 已存在，但 V2 不保存 | 无法解释 planner/恢复阶段到底使用了哪些 summary、memory、RAG 和消息 | 复用现表或增加明确 revision 子模型；默认只存安全元数据 |
| P1 | 长期记忆闭环 | 用户 CRUD、确认、纠正、过期和 USER 检索已存在；Project 检索未接线，无自动提议 | 论文助手需要跨会话研究方向、术语和风格偏好 | 自动提取只生成未确认提议；ProjectVersion 绑定；防止 Project/tool 内容污染全局记忆 |
| P1 | V2 产品 Skill 合同 | `skillId` 已持久化，Skill prompt 已注入 intake Planner；但旧 `allowed_tools` 仍使用 legacy/MCP 工具名，未约束 V2 autonomous tool catalog | 论文审查、代码复核等可复用工作方式需要稳定模板，同时必须避免“提示词说受限、后端实际暴露全部工具”的权限分裂 | 建立在 Context Revision 上；持久化 Skill revision/digest；只允许映射现有 V2 ToolId；后端按用户权限、Skill 和产品 catalog 取交集 |
| P1 | 只读子 Agent 合同 | V2 没有子 Agent；旧 worker prompt 不是独立任务系统 | 可并行处理论文、代码、实验材料，并让主 Agent 只接收结构化摘要和证据引用 | 必须建立在 Context Revision、Step Result、Receipt、lease 和权限收窄之上 |
| P1 | 论文润色质量闭环 | 已有论文任务、质量 baseline 和若干审计工具，但 V2 没有统一的全文修改—编译—diff—失败原因闭环 | 与产品“高质量论文”目标直接一致 | 先定义固定样例、章节结果、保护规则、Candidate 和 LaTeX 验证边界 |
| P1 | LaTeX 编译与渲染验证 | E2B 可执行 Java/Python，现有 LaTeX 工具主要是静态审计 | 只做语法/交叉引用观察不能证明论文可编译和版面可用 | 冻结镜像/依赖、离线策略、日志 Receipt、PDF artifact 和 Candidate apply gate |
| P2 | 图表理解和正文一致性 | float audit 只检查 caption/label/path，不读取图片语义 | 对实验论文质量有明显价值 | 先只读理解和建议；图像作为不可信输入；暂不自动改图 |
| P2 | 文献反馈与 citation-slot 闭环 | 文献搜索、卡片和 baseline 已存在，但认可/拒绝/已使用状态与论文论点绑定仍不完整 | 能减少重复推荐并解释每条文献支持哪个论点 | 先审计现有 LiteratureCard/任务字段，避免新建重复文献表 |
| P2 | 科学计算与实验复现环境 | Python 仅保证解释器/标准库，现有沙箱没有统一科学依赖协议 | 代码—实验—图表—论文联合任务需要可复现依赖 | 锁定环境、依赖 allowlist/cache、资源预算和无网络默认策略 |
| P2 | V2 状态机与恢复评测集 | 当前有 focused tests 和一次真实链路记录，但上下文/记忆/子 Agent 尚无不变量评测 | 防止继续依赖浏览器偶然发现架构缺口 | 先做纯合同、仓储、并发和恢复测试，再做少量真实验收 |

工具侧当前不需要把“继续新增通用工具”作为上述工作的前置条件。现有 17 个 V2 工具已经能完成有边界的“读取论文/报告与代码 → 运行代码 → 根据 Receipt 对照文档结论 → 修改现有 UTF-8 代码或论文文本 → 再验证 → 生成 Candidate”闭环。当前限制是 PDF、DOCX、XLSX 只读，Candidate 一次最多覆盖 4 个已有文本文件且单文件不超过 64 KiB，图片语义、OCR、LaTeX 渲染和大项目上下文仍未完成。

## 3. 条件实施

| 能力 | 价值 | 暂缓原因 | 重新启动条件 |
| --- | --- | --- | --- |
| 跨轮次“当前操作对象” | 能改善“继续修改它”之类指代 | 容易与 session summary、TaskFrame 和 ProjectVersion 重复 | Context Revision 完成后，以结构化 active-object 引用而非自由文本记忆实现 |
| 持久化科研结构化索引 | 大 Project 可减少重复扫描 | 现有 17 个工具已能按需读取，尚无性能数据证明需要第二套索引 | 大型真实 Project 显示重复解析成为主要成本，并能绑定 ProjectVersion |
| 多 Provider/GPU 沙箱 | 可支持重计算和 GPU | E2B 已上线，继续扩 Provider 会增加部署、权限和恢复复杂度 | 出现 E2B 无法满足且可量化的真实科研任务 |
| OCR | 扫描 PDF 和图片论文可能需要 | 当前 PDF 工具明确不做 OCR，安全和成本边界未定义 | 图表/扫描文档用例进入排期，并明确语言、页数、隐私和预算 |
| MCP/GitHub/外部工具 | 可扩展检索和代码协作 | 现有 MCP 只注册到 legacy `ToolRegistry`，没有进入 V2 EffectIntent/Receipt/恢复链；还会引入联网、账号、动态 schema、权限和提示注入风险 | Context 与 V2 Skill 合同稳定后，为一个明确的只读工作流冻结权限、manifest snapshot、Receipt、输出预算和确认流程；不建设任意 MCP Server 市场 |
| V2 流式事件/WebSocket | 可改善长任务反馈 | 当前持久化轮询和刷新恢复已经可用，旧 WebSocket 只服务 `/chat` | 真实延迟和并发数据显示轮询成为瓶颈 |
| 统一旧 `/chat` 与 Project V2 运行时 | 可减少重复维护 | 用户明确要求保留当前 `/chat`，迁移风险大 | V2 功能覆盖、体验和迁移方案单独获批后再评估 |
| 自动语义路由规则 | 可减少极少数模型误路由 | 用户已决定暂不做；硬编码容易误伤新工具 | 有稳定错误样本和评测集后，仅增加最小确定性审计 |

## 4. 暂不实施

| 想法 | 原因 |
| --- | --- |
| 立刻加入递归/自由多 Agent | Context、权限、租约、成本和结果接受合同都未完成 |
| 自动合并多个写入子 Agent 的 Candidate | 冲突、来源、验证和用户确认语义尚未冻结 |
| 立即切换到 LangChain4j ChatMemory/RAG 默认裁剪 | MySQL/ES/MinIO 和 V2 权威事实不能交给框架内存对象；现有 A/B 结论也不支持整体替换 |
| 继续扩充沙箱 Provider | 当前 E2B 已满足基础真实链路，缺少投入依据 |
| 为每个新工具建立独立 Issue | 用户要求相关工具按共同边界成组，避免流程碎片化 |
| 重做当前前端布局 | 新布局已合入 main，当前没有具体缺陷证据 |
| 无差别整仓测试 | 应按 verification matrix 选择直接受影响的 focused tests |

## 5. 确认不做

1. 恢复 Project 页 V1 对话和 V1/V2 切换。
2. 删除或迁移旧历史消息作为 V2 上线前置条件。
3. 用硬编码日期区分历史 V1/V2 消息。
4. 复制旧 Planner、旧 Agent Loop、旧 CompletionVerifier 或固定 Candidate 工具链到 V2。
5. 允许模型自行扩大工具、网络、Project 或 Workspace 权限。
6. 用户确认前修改原 ProjectVersion，或让 Agent 自动 apply Candidate。
7. 让摘要、记忆、RAG 或子 Agent 输出覆盖已接受 Step Result 和 Receipt。
8. 建立第二套重复的 ProjectVersion、Workspace、Candidate、Receipt、summary 或 memory 事实源。
9. 让 Project V2 通过 filesystem MCP 读取服务器或用户本地任意路径。用户本地文件必须先上传，Project 内容只能通过冻结 ProjectVersion 和隔离 Workspace 工具访问。

## 6. 推荐顺序

```text
Context Assembly 审计
  -> Context Revision + V2 snapshot
  -> token 预算与语义压缩
  -> V2 rolling summary
  -> 长期记忆闭环
  -> V2 产品 Skill 合同
  -> 一个明确的只读 MCP 试点（仅在有真实用例时）
  -> 只读子 Agent 合同
  -> 子 Agent 调度/恢复/前端
  -> 论文质量闭环与图表/实验能力
```

先完成共同上下文和事实合同，可以避免记忆、压缩、Skill、MCP 和子 Agent 各自创建一套不兼容的数据模型。MCP 不是子 Agent 的硬前置；没有明确外部工作流时可以跳过 MCP 试点，直接进入只读子 Agent 合同。

## 7. 首个建议 Issue

名称建议：`design(v2): 冻结 Context Assembly、预算与恢复不变量`

只交付：

1. Planner、Step、Reflection、Replan、Final Synthesis 的输入数据流；
2. 每一阶段的来源、上限、裁剪和恢复表；
3. Context Revision/ledger 合同；
4. 现有 summary、memory、snapshot 的复用结论；
5. focused contract tests 计划；
6. 明确不实现自动摘要、子 Agent 和新 UI。

该设计经用户审核后，再拆实现 Issue。
