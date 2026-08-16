# Agent Engine (DSH 路线)

> 本文件夹是 DSH 路线的实现：基于 DeepSeek Harness（DSH）的 ReAct 执行引擎。
> 对应 Issue #152。实现边界与验收见仓库根的 `agent-engine-contract/`（Issue #150 冻结）。
> 与 Codex 的轻量 runner（#153）按同一契约与验收竞速。
>
> 作者澄清：本文件夹由运行在 DSH 中的助手（我）创建与维护，与"DSH 框架本身"区分：
> 引擎消费 `@deepseek-ai/dsh` 包作为循环/工具/会话框架，但产品逻辑全在本仓库内实现。

## 状态（P1）

- [x] 控制面：提交（幂等/409 摘要冲突）、查询、SSE（sequence/Last-Event-ID）、取消、回答
- [x] 持久事件与重启恢复（JSONL，`data/<taskId>/events.jsonl` + `meta.json`）
- [x] 任务状态机（queued/running/waiting_user/succeeded/failed/cancelled，单终态，delivery 先于 succeeded）
- [x] 网关客户端接口 + StubGateway + 真实 HTTP 客户端（`gateway-http.ts`，接 #151 网关；每次请求动态读取 grant）
- [x] 网关响应精确绑定（`gateway-http.ts` + `test/gateway-binding.mjs` 18 项失败测试）：
      fileList 绑定 taskId + 冻结 projectVersion；fileRead 重证正文 size/hash；sandbox 提交与轮询响应均绑定
      clientRequestId/requestDigest/executionRef（轮询全程一致，非终态不得携带 receiptRef）；receipt 绑定
      receiptRef/executionRef/终态 status/精确 inputs（顺序+path+sha256），路径 encodeURIComponent；
      错误响应过 Problem schema 后按显式集合 + 受控 `TASK_*`/`WORKSPACE_*`/`SANDBOX_*` 前缀（#151 正式错误码）
      分类，其余 fail-closed 为 GATEWAY_ERROR（原始 message/sourceRef 一律不传播）
- [x] 沙箱摘要 = canonical JSON{argv, inputs, timeoutMillis}（契约 §4 键排序，timeoutMillis 参与）
- [x] conformance 自测：40 项全过（覆盖契约 9 个运行时场景中的引擎侧部分）
- [x] DSH ReactLoopAgent 接入（`ENGINE_RUNNER=dsh`）：真实模型 + 产品工具（project_list/read、sandbox_execute、
      模型可见名与合同稳定名分离映射）；正式入口 fail-closed（必须显式 dsh|stub）
- [x] 硬预算：每请求 maxOutputTokens=4096、每任务最多 20 次模型调用（第 21 次在派发前拒绝
      MODEL_BUDGET_EXCEEDED，持久计数）、并行工具=1、无 subagent
- [x] 固定沙箱轮询 1/2/4/5/5…s、`timeoutMillis+30s` 截止（SANDBOX_STATUS_DEADLINE_EXCEEDED，
      category sandbox_system；活跃进程用 monotonic clock 计时，持久 wall deadline 仅作重启后 fail-closed
      上限；每次状态请求前都先查截止，sleep 取 min(delay, remaining)，绝不越过 deadline 再发请求）
- [x] 持久恢复：稳定 callId + 工具账本；提交 202 后、Receipt 前即持久化 executionRef + 固定截止时间，
      覆盖 submit→receipt 崩溃窗口（同 argvDigest 复用 clientRequestId，绝不重派发）
- [x] 恢复 prompt 从持久事实重建（DSH 会话重启后为空）：冻结 instruction、ProjectVersion、已完成 Receipt
      的 ref/status、已接受答案与对应问题、最近持久 assistant 输出、明确"不得重新提交账本中已有执行"
- [x] 交付集合自动再收养：buildProductTools 从账本已完成且已验证的 Receipt 初始化（去重），
      模型恢复后直接下结论也能通过 completion gate，无需重复工具调用
- [x] 首次运行检测：`resumed` 在首次写 phase 前读取持久值（全新任务绝不被当成恢复续跑）
- [x] waiting_user 重启恢复：pending 问题正文与已接受答案正文持久化；重放后 runner 重挂回答门，
      答案正文注入模型上下文并续跑（不再重复提问）
- [x] ask_user 门：waiting_user → 正式回答 → 循环恢复；无 receipt 的纯提问流以
      RECEIPT_REQUIRED_NOT_SATISFIED 失败（无 stub finalizer）
- [x] 真实模型 smoke（T1）：v4-pro 端到端通过，网关为受控 HTTP mock——**不是** #151/E2B 真实网关 T1 证据

## 目录

- `engine/src/`：引擎实现
  - `index.ts` 启动入口；`server.ts` 控制面 HTTP；`task.ts` 任务运行时与事件；`store.ts` 持久化；
  - `validate.ts`/`schemas.ts` 共享契约 schema 消费；`canonical.ts` 契约摘要；`gateway.ts` 网关接口 + Stub；
  - `gateway-http.ts` 真实网关客户端；`runner.ts` StubRunner；`dsh/` DSH 装配（runtime/tools/runner）
- `engine/cordis.yml`：最小 DSH 组合（llm + llm-deepseek + session + system-prompt + tools + agent + agent-loop）
- `engine/test/conformance.mjs`：契约 conformance 自测（40 项断言，stub 控制面）
- `engine/test/dsh-formal.mjs`：正式路径测试（56 项：恢复账本/崩溃窗口/预算/ask_user 门/waiting_user 重启/
      恢复 prompt 事实重建/ledger receipt 再收养/截止轮询边界/摘要规范，FakeAdapter + HTTP mock 网关，无需真实 API key）
- `engine/test/mock-gateway.mjs`：受控 HTTP 网关测试替身（独立重算 canonical 摘要、holdPolls 崩溃窗口、
      statusLog 轮询计数、计数派发，供 formal/smoke 共用）
- `engine/test/gateway-binding.mjs`：网关绑定失败测试（18 项，进程内直接测 HttpGatewayClient）
- `engine/test/dsh-smoke.mjs`：真实模型 smoke（T1 场景，v4-pro + 受控 HTTP mock 网关；
      只消费环境变量 `DEEPSEEK_API_KEY`，不读任何 .env 文件）
- `spike/`：模型编排可行性 Spike（零依赖，已验证；仅作证据，不是产品代码）

## 运行

```powershell
cd engine
npm install --offline --ignore-scripts      # 依赖（首次）
$env:ENGINE_SERVICE_TOKEN='dev-token'        # 控制面凭证
$env:ENGINE_RUNNER='dsh'                     # 必须显式 dsh|stub（fail-closed，无缺省）
$env:ENGINE_GATEWAY_BASE_URL='http://127.0.0.1:8080'   # dsh 运行必需（真实网关 #151）
node src/index.ts                            # 默认 127.0.0.1:8092
```

环境变量：`ENGINE_PORT`、`ENGINE_SERVICE_TOKEN`、`ENGINE_DATA_DIR`、`ENGINE_RUNNER=stub|dsh`、
`ENGINE_GATEWAY_BASE_URL`（`ENGINE_RUNNER=dsh` 时必填，否则拒绝启动；stub 用 StubGateway）、
`DEEPSEEK_API_KEY`（dsh runner 需要）、`ENGINE_FAKE_LLM=1`（注册 FakeAdapter，测试用）、
`STUB_STEP_DELAY_MS`、`STUB_QUESTION`、`STUB_FAIL`、`STUB_USE_GATEWAY`、`STUB_MESSAGE`、`STUB_CONCLUSION`、`STUB_ANSWER_DELAY_MS`。

## 验证

```powershell
cd engine
node test/conformance.mjs   # 控制面 conformance（40 项，全过）
node test/dsh-formal.mjs    # 正式路径（56 项：恢复/预算/ask_user 门/崩溃窗口/截止轮询/摘要，无需真实 API）
node test/gateway-binding.mjs  # 网关绑定失败测试（18 项）
npx tsc -p tsconfig.json    # 类型检查（erasable syntax only）
# 可选（需环境变量 DEEPSEEK_API_KEY；文件不读任何 .env）：
node test/dsh-smoke.mjs     # 真实模型 T1 smoke（v4-pro + 受控 HTTP mock 网关）
```

> smoke 定位：真实模型 + 受控 mock 网关，仅证明模型循环/工具/事件桥；**不能**作为
> #151 真实网关或 E2B 真实沙箱的 T1 证据。真实 E2E 验收在 #151 网关就绪后按
> `agent-engine-contract/ACCEPTANCE.md` 执行。

## 设计说明

- 契约文档：`../agent-engine-contract/`（openapi.yaml + schemas + conformance fixtures）。
- 引擎只持有：有界 ReAct 模型循环、模型可见工具描述、任务运行状态与可重放事件、取消/恢复/提问/交付编排。
- 产品权威（Workspace、broker 凭证、Receipt、发布回滚）全部留在 Java 侧；引擎经 task grant 调网关。
- 评审意见 M1（不同答案 409）与 M4（delivery 先于 succeeded 的后置检查）已在实现中执行；
  M2（轮询节奏）与 M3（评测预算）待验收时与 #153 对齐统一。
