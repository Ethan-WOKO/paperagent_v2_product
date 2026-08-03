# MVP 后续优化与云沙箱选型

> 文档分类：**已被替代**。E2B 已完成接入和生产部署，旧 Worker/MVP 排期不再有效。未来多 Provider、GPU 或科学计算环境只在出现真实需求后重新评估，不能从本文直接启动。

> 状态：`CURRENT_DECISION / E2B_DEPLOYED / STAGE_8_COMPLETED`
>
> 整理日期：2026-07-21
>
> 适用范围：Worker 15 后续优化、统一入口与 LLM Router、长期记忆、界面整理、普通问答与 Evidence 关系、工具自修复、云沙箱 Provider 选型。

## 一、当前判断

第一版的重点应从“继续增加治理层”转为“让基本用户流程稳定可用”。保留会直接阻止误执行、越权写入或失控资源消耗的最小边界，但不再为尚未出现的复杂攻击场景增加 HMAC、历史 provenance 链、复杂攻击矩阵或多层重复校验。

E2B 已在现有 Provider 抽象下作为新增实现进入 GitHub `main` 并完成云服务器部署，原有 `docker-sbx` 保留。当前不继续扩充 Provider，也不把业务层绑定到 E2B；后续先收口统一入口、路由、记忆和用户界面，再根据真实运行数据决定 Provider 扩展。

## 二、普通问答与 Evidence 闸门

### 已识别问题

1. Project 内普通消息容易被统一解释为 Project 读取任务，导致本来可以直接回答的问题也进入 ReAct、工具调用和 Evidence 校验。
2. Evidence 不足时，系统可能用“证据不足”替换原本有用的回答，使普通问答表现为失败。
3. 后端承担了过多回答级校验，增加延迟、失败点和生产排障难度。

### 后续优化原则

按影响分层，而不是让所有请求通过同一个硬闸门：

| 请求类型 | 建议行为 |
| --- | --- |
| 普通知识问答、产品说明、项目操作咨询 | 允许直接回答；Evidence 可选，不因缺少项目文件证据而阻断。 |
| 用户明确要求分析当前 Project 文件 | 使用 Project 工具；回答标注依据和局限，但证据不足时优先给出部分结果。 |
| Candidate 生成、沙箱验证结论 | 必须绑定当前 ProjectVersion/Candidate 和真实执行事实。 |
| Apply、回滚、导出等会改变或产生正式项目状态的操作 | 保留显式确认、版本绑定和幂等约束。 |

Evidence 的目标应是帮助用户理解“结论来自哪里”，而不是成为所有聊天的通行证。硬闸门只用于会产生外部影响或声称确定性执行成功的流程。

## 三、工具调用自修复

### 当前已有能力

1. 同一轮 ReAct 中，工具失败结果会作为 Tool Message 返回给 LLM，LLM 可以修改参数后再次调用。
2. Plan 步骤第二次尝试会看到 `Previous attempt error` 和前一轮可复用结果，不是完全盲重试。
3. 完全相同且没有新进展的重复调用会被阻止，修改参数后的新调用仍可执行。

### 当前缺口

Completion Repair 的新一轮请求通常只有通用的“补充证据”提示，没有稳定携带具体失败工具、原参数、错误码和缺失字段。JSON 格式错误或参数缺失有时还会被归类为 `INTERNAL_ERROR / retryable=false`，不利于模型判断如何修复。

### 最小改造方案

后续只增加一个有界、脱敏的 `RepairContext`，不建设复杂自愈框架：

```json
{
  "failedTool": "project_read_file",
  "arguments": {"path": "src/Main.java"},
  "errorCode": "VALIDATION_ERROR",
  "errorMessage": "relativePath is required",
  "retryable": true,
  "remainingAttempts": 1
}
```

规则：

- 参数缺失、JSON 格式和可调整范围错误：允许 LLM 修改参数后重试一次。
- 新参数与上一次不同：允许执行；完全相同的失败调用：停止重复。
- 权限不足、功能关闭、用户取消和确定性资源边界：不交给模型反复尝试。
- 不向模型暴露堆栈、密钥、绝对宿主机路径或内部连接信息。
- 修复结果不能覆盖后端记录的 `status`、`exitCode`、`timedOut` 和 `provider` 执行事实。

## 四、必须保留的最小安全边界

以下内容不是过度防卫，第一版仍应保留：

1. 沙箱默认关闭；用户明确确认后才能执行。
2. Candidate 默认 `NOT_APPLIED`；验证不等于应用。
3. Apply 必须显式接受，并绑定当前 ProjectVersion 和 Candidate。
4. 命令、工作目录和相对路径限制；默认禁网。
5. CPU、内存、运行时间、输出大小和并发限制。
6. 不把宿主机敏感环境变量注入用户代码。
7. 取消、超时和异常后执行 cleanup，保留真实 stdout/stderr 与执行事实。
8. 只读总结 Agent 无工具、无写权限，不能触发 Apply、Evidence 写入或后续自动执行。

以下内容推迟到真实规模和威胁模型需要时再做：跨服务 HMAC、复杂历史 provenance、全面恶意提示攻击矩阵、多租户企业审计和自建大规模调度集群。

## 五、云沙箱候选方案

价格是 2026-07-21 的官方页面快照，实施前必须重新核价。以下成本均不包含模型费用、网络流量、日志和项目自身云服务器费用。

### 1. E2B：当前首选

- Hobby 基础费用为 `$0/月 + 用量`，新用户一次性 `$100` 用量额度。
- Hobby 最长单次 1 小时、最多 20 个并发沙箱；对于低用户量 MVP 足够。
- 按秒计费；1 vCPU 为 `$0.000014/秒`，内存为 `$0.0000045/GiB/秒`。
- 支持暂停/自动暂停，暂停、终止或超时后停止计算费用，并可设置预算上限。

适合原因：产品定位就是 Agent 代码执行沙箱；接入语义与现有 Worker 14/15 Provider 边界最接近，当前子任务也已经选择“保留 docker-sbx，新增 E2B”。短期不需要购买 `$150/月` 的 Pro，除非需要超过 1 小时、超过 20 并发或更高资源上限。

官方资料：[E2B Pricing](https://e2b.dev/pricing)、[E2B Billing & Limits](https://e2b.dev/docs/billing)

### 2. Daytona：主要备选

- 按量付费，官方页面当前提供 `$200` 免费计算额度。
- vCPU 与内存单价分别为 `$0.000014/秒` 和 `$0.0000045/GiB/秒`，另计存储。
- Started 状态计 CPU、内存和磁盘；Stopped/Paused 仍计磁盘，Archived/Deleted 不计费。

适合原因：文件、Git 和 Execute API 完整，容器/VM 选项丰富。缺点是当前项目已经在接 E2B，立即再接 Daytona 会增加测试矩阵和维护面。建议只保留为 E2B 因地区、稳定性或 SDK 限制不可用时的第二选择。

官方资料：[Daytona Pricing](https://www.daytona.io/pricing)、[Daytona Billing](https://www.daytona.io/docs/billing)

### 3. Modal：科研计算和 GPU 的后续选择

- Starter 为 `$0/月`，当前包含每月 `$30` 计算额度。
- Modal Sandbox 按秒计费，CPU 为 `$0.00003942/物理核/秒`，内存为 `$0.00000667/GiB/秒`。
- 支持自定义镜像、CPU/内存上限、按需扩缩和 GPU。

适合原因：以后运行科研 Python、批处理或 GPU 任务时很有吸引力。当前通用代码沙箱的单位成本和接入迁移成本通常高于 E2B，且现有后端是 Java，因此不作为第一接入目标。

官方资料：[Modal Pricing](https://modal.com/pricing)、[Modal Sandbox Resources and Pricing](https://modal.com/docs/guide/sandbox-resources)

### 4. Cloudflare Sandbox：未来成本优化候选

- 基于 Workers Paid，基础费用 `$5/月`，含一定 CPU、内存和磁盘用量。
- 超额 CPU `$0.000020/vCPU 秒`、内存 `$0.0000025/GiB 秒`、磁盘 `$0.00000007/GB 秒`，可缩到零。
- 还会涉及 Workers、Durable Objects、日志和网络费用。

适合原因：低负载下价格有竞争力，全球部署和自动休眠也很好。缺点是需要引入 Workers/Durable Objects/TypeScript 运行体系，与当前 Java 服务和 Provider 接口的距离更远；产品也较新。建议在 E2B 稳定后做独立 PoC，不在当前 Worker 中并行接入。

官方资料：[Cloudflare Sandbox Pricing](https://developers.cloudflare.com/sandbox/platform/pricing/)、[Cloudflare Containers Pricing](https://developers.cloudflare.com/containers/pricing/)

## 六、当前推荐路线

1. 冻结当前 Provider 抽象：E2B 与 `docker-sbx` 并存，业务层不感知具体 Provider。
2. 保持硬预算、短超时、低并发和自动 cleanup，观察真实用量与失败类型。
3. 普通问答与沙箱不可用解耦：E2B 故障不能影响普通聊天、Project 浏览和 Candidate 审查。
4. 先统一 Project 输入入口、LLM Router 和长期记忆，再整理页面与执行过程展示。
5. 随后处理 Evidence 分层、工具自修复和状态语义，避免继续用治理补丁掩盖基础流程问题。
6. 用户量扩大后再根据真实数据选择 E2B Pro、Daytona、Cloudflare 或自建集群，不提前建设多 Provider 调度平台。

## 七、后续任务建议顺序

| 优先级 | 任务 | 完成标准 |
| --- | --- | --- |
| 已完成 | 统一入口、LLM Router 与记忆贯通 | 一个输入框；Project LLM Router 提出 DIRECT/PLAN；Runtime 校验可用性；全局偏好贯通；只有一个最终回答。普通 Chat 保留 ReAct。 |
| 已完成 | 整理 Project 页面和 Plan 展示 | 三区域高度比例保持不变；标题稳定；Plan 默认折叠；检查器仅保留一组标签。 |
| P1 | 放宽普通问答的 Evidence 硬闸门 | 普通问题可直接回答；只有 Project 事实分析和外部影响操作进入对应校验。 |
| P1 | 补全跨轮工具自修复上下文 | 第二次尝试看到结构化、脱敏的具体错误；参数变化后可恢复；相同失败不循环。 |
| P1 | 整理 PARTIAL、失败和取消语义 | 前端、后端、Event 和 canonical answer 对同一状态解释一致。 |
| P2 | 统一输出截断与只读总结展示 | 原始输出可查；摘要明确“基于输出、未独立验证”；不能覆盖执行事实。 |
| P2 | 基于真实账单复核 Provider | 记录每次运行时长、资源和费用；达到阈值后再评估 Daytona/Cloudflare/自建。 |

## 八、统一入口与 LLM Router 决策

Project 用户只面对一个输入入口，不再承担 Chat 与 Plan 的策略选择。语义分类由 LLM Router 完成，但“交给 LLM”不等于取消 Runtime：

1. Project Router 根据任务是否需要工具、是否存在多个依赖目标和明确交付物，结构化输出 `DIRECT / PLAN_EXECUTE` 及简短理由；普通非 Project Chat 保留既有 ReAct。
2. 不能只使用主观“难度”分类，也不能只靠“分析”“计划”等关键词触发 Plan。
3. Runtime 校验当前 capability、Project 授权、工具集合、沙箱确认和预算；不可执行的选择必须明确降级或失败，不能由模型扩大权限。
4. 两种 Project 顶层策略共享 ContextPackage、Task Run、事件和 canonical answer，不建设第二套 Plan 会话；步骤内 ReAct 延后到复杂执行范式阶段。
5. “默认中文”“回答格式”等用户确认偏好属于全局偏好，每次请求始终注入；Project 事实和一般经验仍按 scope、版本与相关性检索。

## 九、界面与复杂执行范式决策

界面只做边界明确的整理：

- 保留项目、会话、文件三个区域平均分配页面高度，不修改 flex 比例、最小高度和总体布局算法。
- 只修复标题跳动、展开图标、Chat/Plan 双入口、过程消息刷屏和重复检查器标签。
- Plan 作为同一会话中的可折叠执行卡；默认显示状态、进度、耗时、等待确认和失败原因，详细工具过程进入运行详情。

复杂执行范式推迟到统一入口、界面和可靠性阶段验收后：

- Planner 定义目标、依赖、成功条件和预算。
- 每个步骤内部使用 ReAct 获取真实结果。
- Reflection 只由步骤失败、结果不足、结果冲突或确定性验证不通过触发，不在每一步固定调用。
- Reflection 只能调整未执行步骤，并受最大重规划次数、总预算和无进展终止条件约束。
- 该范式提高复杂任务的适应性，但不能保证所有任务成功；权限、环境和 Provider 阻断必须明确失败，不能用反思循环掩盖。

本记录只定义后续方向，不授权当前主对话修改实现，也不替代正在执行的子任务验收报告。
