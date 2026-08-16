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
- [x] 网关客户端接口 + StubGateway + 真实 HTTP 客户端（`gateway-http.ts`，接 #151 网关）
- [x] conformance 自测：36 项全过（覆盖契约 9 个运行时场景中的引擎侧部分）
- [x] DSH ReactLoopAgent 接入（`ENGINE_RUNNER=dsh`）：真实模型 + 产品工具（project_list/read、sandbox_execute，
      模型可见名与合同稳定名分离映射）
- [x] 硬预算：每请求 maxOutputTokens=4096、每任务最多 20 次模型调用（超限 MODEL_BUDGET_EXCEEDED，持久计数）、
      并行工具=1、无 subagent
- [x] 固定沙箱轮询 1/2/4/5/5…s、`timeoutMillis+30s` 截止（SANDBOX_STATUS_DEADLINE_EXCEEDED）
- [x] 真实模型 smoke（T1）：v4-pro 端到端通过（列文件→读文件→javac→补依赖编译成功→交付结论，35s，13 事件）

## 目录

- `engine/src/`：引擎实现
  - `index.ts` 启动入口；`server.ts` 控制面 HTTP；`task.ts` 任务运行时与事件；`store.ts` 持久化；
  - `validate.ts`/`schemas.ts` 共享契约 schema 消费；`canonical.ts` 契约摘要；`gateway.ts` 网关接口 + Stub；
  - `gateway-http.ts` 真实网关客户端；`runner.ts` StubRunner；`dsh/` DSH 装配（runtime/tools/runner）
- `engine/cordis.yml`：最小 DSH 组合（llm + llm-deepseek + session + system-prompt + tools + agent + agent-loop）
- `engine/test/conformance.mjs`：契约 conformance 自测（36 项断言，stub 控制面）
- `engine/test/dsh-smoke.mjs`：真实模型 smoke（T1 场景，消费真实 DeepSeek API）
- `spike/`：模型编排可行性 Spike（零依赖，已验证；仅作证据，不是产品代码）

## 运行

```powershell
cd engine
npm install --offline --ignore-scripts      # 依赖（首次）
$env:ENGINE_SERVICE_TOKEN='dev-token'        # 控制面凭证
$env:ENGINE_RUNNER='dsh'                     # dsh=真实模型循环；stub=conformance 控制面
node src/index.ts                            # 默认 127.0.0.1:8092
```

环境变量：`ENGINE_PORT`、`ENGINE_SERVICE_TOKEN`、`ENGINE_DATA_DIR`、`ENGINE_RUNNER=stub|dsh`、
`ENGINE_GATEWAY_BASE_URL`（真实网关；缺省用 StubGateway）、`DEEPSEEK_API_KEY`（dsh runner 需要）、
`STUB_STEP_DELAY_MS`、`STUB_QUESTION`、`STUB_FAIL`、`STUB_USE_GATEWAY`、`STUB_MESSAGE`、`STUB_CONCLUSION`、`STUB_ANSWER_DELAY_MS`。

## 验证

```powershell
cd engine
node test/conformance.mjs   # 控制面 conformance（全过）
npx tsc -p tsconfig.json    # 类型检查（erasable syntax only）
```

## 设计说明

- 契约文档：`../agent-engine-contract/`（openapi.yaml + schemas + conformance fixtures）。
- 引擎只持有：有界 ReAct 模型循环、模型可见工具描述、任务运行状态与可重放事件、取消/恢复/提问/交付编排。
- 产品权威（Workspace、broker 凭证、Receipt、发布回滚）全部留在 Java 侧；引擎经 task grant 调网关。
- 评审意见 M1（不同答案 409）与 M4（delivery 先于 succeeded 的后置检查）已在实现中执行；
  M2（轮询节奏）与 M3（评测预算）待验收时与 #153 对齐统一。
