# P1 验收合同

日期：2026-08-16

分类：Agent Engine P1 验收

状态：Issue #150 冻结候选

## 1. 共同环境

DSH 与 Codex Engine 必须使用：

- 相同的 Java 产品工具网关提交；
- 相同冻结 ProjectVersion 和 Workspace；
- 相同 Sandbox broker、policy 和资源上限；
- 相同 provider、model、系统工具描述、最大输出 token 和轮次上限；
- 全新任务 ID；恢复场景除外；
- 不含本地 fixture、宿主路径或人工补写依赖提示的正式任务输入。

开发 smoke 每项运行 3 次；候选胜负评测每项运行至少 10 次并报告所有样本，不只报告
中位数。评测顺序随机交错，避免先后顺序和外部服务状态偏置。

固定执行预算为：每次模型请求最多 4096 output token、每任务最多 20 次模型请求、
并行工具调用数 1、禁用 subagent。Sandbox 状态轮询固定为 1、2、4、5、5……秒且不加
jitter，总截止时间为提交接受时刻加 `timeoutMillis + 30000 ms`。空闲 SSE heartbeat
固定为 15000 ms。

## 2. P1 必过任务

### T1：带第三方依赖的 Java 只读编译验证

固定用户输入：

```text
检查 Sort.java，看看其是否能编译正常，不要修改文件。
```

Project 中的 Java 文件引用非 JDK 依赖。Agent 必须先读取文件/import，再通过产品网关
提交允许的 `yanban-runner java <src> --dependency=...` 命令。通过标准：

- 正确给出是否编译成功、执行方式和退出码；
- 正式 Receipt 与冻结 Workspace 输入 hash 对应；
- Workspace diff 为空，ProjectVersion 和 revision 数不变；
- 不把缺失第三方依赖误报为产品系统失败。

### T2：确定性的代码编译失败

使用固定语法错误 fixture。通过标准：终态为可交付失败结论，而非 running 或系统异常；
结论引用有界诊断，Project 不变。

### T3：Sandbox 系统失败/超时

注入 broker 不可用、超时或系统拒绝。通过标准：与代码编译失败使用不同错误分类；不
伪造 exitCode；任务进入 failed 并提供可理解说明。

### T4：幂等与恢复

覆盖：

- 同 taskId、同 digest 重放；
- 同 taskId、不同 digest 返回 409；
- 沙箱提交后、Receipt 已持久化时重启 Engine；
- 重启后继续交付且不重复沙箱副作用；
- SSE 使用 Last-Event-ID 无缺失、无重复地继续。
- 同 questionId/answerDigest 精确重放只消费一次；不同 answerDigest 返回 409 且保留
  第一个答案。

### T5：取消

在模型调用、沙箱 RUNNING 和交付前分别取消。取消必须幂等，最终只有一个 cancelled
终态；终态后取消不得改写结果。

### T6：安全负例

必须拒绝：过期/错 task grant、路径穿越、绝对路径、越权工具、未授权 argv、超限文件、
同幂等键不同摘要。事件和错误中不得出现文件正文、token、宿主路径或未裁剪输出。

## 3. P1 明确非目标

- Workspace 写入；
- Candidate 或 ProjectVersion 创建；
- 自动发布和回滚；
- 文献检索、知识库和网络工具；
- 前端切换或旧链路退役。

这些能力不得为了提高 P1 得分而偷偷加入。

## 4. 指标

成功率优先。每次运行记录：

1. 是否满足任务全部强断言；
2. 模型调用次数、输入/输出/缓存 token 和总 token；
3. 工具调用次数，特别是重复沙箱调用数；
4. 首事件、首工具、Receipt、终态的端到端耗时；
5. 恢复后是否重复副作用；
6. 用户结论的正确性与失败说明质量。

成功率不同时不使用 token 或耗时抵消失败。成功率相同时依次比较重复副作用、总 token、
模型调用次数和端到端耗时。框架后台调用必须纳入统计，缺失指标按不可比较处理。

## 5. 通过与胜负

- “P1 可用”：T1–T6 强断言全部通过，T1–T5 的 10 次候选运行无安全或重复副作用失败。
- “胜出”：双方均可用后，再比较成功率置信区间和资源指标；样本量不足时只报告趋势，
  不宣布胜负。
- 外部模型或 broker 故障必须原样计入并分类，不能删除失败样本；若双方不在同一故障窗，
  该轮标记环境不可比并重跑双方。

## 6. P2 入口条件

只有双方至少一条实现达到 P1 可用，才启动写入/发布 P2。P2 必须新增精确输入绑定、
Workspace diff、Candidate、不变版本发布和回滚测试，不能沿用 P1 成功推断发布安全。

## 7. 1.1 只读产品搜索扩展门禁

本节不改变 T1–T6 的 P1 胜负样本，也不要求重新刷已经完成的 Sandbox 槽位。产品网关
和两条 Engine 线接入 `knowledge.search`、`literature.search` 时分别验证：

1. 同一 Unicode query 的 JavaScript 与 Java canonical digest 与 fixture 完全一致；
2. 精确重放只调用底层检索一次，不同摘要返回 409 并保留首个持久结果；
3. 过期 grant、错 task、缺少对应搜索权限、跨用户和跨 Project 请求均在检索前失败；
4. knowledge 只返回 active 且属于公开/当前用户/冻结 Project 的结果，摘录不超过 4000
   字符，总数不超过 10；
5. literature 结果不超过 10，摘要不超过 2000 字符，引用字段与 URL 经过 allowlist 和
   大小限制；
6. 上游部分失败只产生稳定 warning，整体失败产生脱敏 Problem；事件、日志和错误不含
   摘录、摘要、原始异常、credential 或内部 URL；
7. Engine 重启后从持久 search result 恢复，不重复本地检索或外部文献请求。
8. 成功搜索事件携带合法 searchRef，未知错误码 fail-closed，文献 URL 拒绝 user-info、
   非 HTTP(S)、loopback、link-local、RFC1918、内部域名以及不安全重定向目标。

契约 PR 只运行共享 validator 与 JavaScript/Java 摘要 fixture。Java 网关、DSH 和 Codex
实现必须在各自独立 Issue 中增加行为测试与真实产品 smoke，不能用 schema 通过代替运行
证据。
