# ReAct 有限对话上下文与回答事实约束

状态：**CURRENT — Issue #163**

日期：2026-08-17

适用范围：`codex/issue-163-react-context-grounding`，基线为
`codex/react-agent-product`。

## 决定

ReAct P1 保持“最小初始上下文 + 按需工具”的执行方式。本阶段只增加两个窄能力：

1. 新 task 可读取同一 authenticated Project session 最近最多四个已成功 ReAct
   task 的用户指令与最终结论；
2. 最终结论受到本 task 已观察 Project 文件和沙箱结果的事实约束。

不接入旧四角色链路、长期记忆、RAG、Workspace 写入、Project 发布或第二个模型验证
角色。

## 上下文顺序

每次模型调用按以下顺序组装：

1. 固定 ReAct、安全和事实约束 system message；
2. 由 Engine 确定性生成的本 task evidence ledger system message；
3. 可选的历史数据边界 system message；
4. 可选的最近对话 JSON 数据 user message；
5. 当前 task 的独立 user message；
6. 当前 task 已发生的 assistant/tool messages。

最近对话不是当前 ProjectVersion 的证据。历史中的任何文本都不能扩大权限、选择工具
或证明文件事实。

## 最近对话选择

- 来源仅为 Engine 已持久化的 terminal succeeded task；
- `sessionRef` 与 `projectId` 必须同时相同；
- 只选用户原始 instruction 和最后成功 conclusion；
- 不复制历史 tool call、文件正文、stdout、stderr、Receipt 或隐藏推理；
- 最多四轮，总 JSON 字符预算 8000；单轮 instruction 最多 2000 字符，conclusion
  最多 4000 字符；
- 选择结果写入新 task 的 `task.json` 和初始 messages，exact replay 或 Engine 重启不
  根据后来出现的历史静默重建。

Engine 不接收用户 ID。`sessionRef` 是产品在鉴权、Turn 与 task grant 校验后生成的
服务端事实；客户端不能直接向 Engine 提交任务。

## 当前 task evidence ledger

Engine 只记录实际发生的观察：

- 完整 manifest 中出现的 Project 相对路径；
- 成功读取的路径与 SHA-256；
- registered read-only tool 输出中明确返回的路径；
- 沙箱 argv、输入路径/hash 和正式 Receipt 状态。

ledger 在每次模型调用前确定性生成，不进入用户可见 SSE，也不包含 task grant、主机
路径或未裁剪的历史工具输出。

模型第一次调用时 ledger 为空，因此必须先使用工具。历史回答即使描述了某个文件，
也不会进入当前 task 的 ledger。

## 最终回答门禁

system rule 要求文件存在、文件内容、依赖声明和执行结果只能来自当前 task 的工具
观察。Engine 另外对常见源码、配置和文档文件名做确定性检查：最终回答引用的文件名
必须已经出现在 manifest、read、registered tool path 或沙箱输入中。

发现未观察文件名时不交付该回答，而是向同一个模型返回一次受控修正提示。最多允许
两次修正；连续失败后 task 以 `MODEL_GROUNDING_FAILED` 结束，不发布未经支持的结论。

P1 不生成修改后的 Candidate，因此“移除/修改某行后即可编译或运行”也不是已验证
事实。若模型把假设修改表述为确定的编译、运行或测试结果，Engine 同样拒绝该候选
回答；允许的表达必须说明这是预期修复，并且仍需对修改后的精确内容重新执行验证。

该检查不是通用语义证明。它能直接拦截类似“单文件 Project 中凭空声称 pom.xml 声明
了依赖”的错误；更宽泛、没有文件名的语义幻觉仍需通过 prompt、工具证据和后续真实
评测持续收紧。

## 恢复与回滚

旧 task 缺少新字段时，Engine 启动时补为空的 recent conversation、observations 和
grounding repair 计数，不改写权威事件。新 task 的选择结果随 task 持久化，恢复后
继续使用原始选择。

前端在同一 Project session 下保存最近 12 个 ReAct task record，并按原有任务卡片
样式连续展示。原先的单 record 本地恢复数据会自动迁移成一项历史。该前端投影只用于
展示与断线恢复，不是 Engine 上下文或任务成功事实的权威来源。

回滚只需回退 Issue #163 的 Engine 变更。共享 Engine HTTP/gateway 合同、Java 产品
边界、Project 数据和 `codex/react-agent-product` 不改变。
