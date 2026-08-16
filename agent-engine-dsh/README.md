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
- [x] 网关客户端接口 + StubGateway（#151 网关就绪后替换为 HTTP 客户端）
- [x] conformance 自测：28 项全过（覆盖契约 9 个运行时场景中的引擎侧部分）
- [ ] DSH 模型循环接入（stub runner 先行，下一步替换）
- [ ] 真实网关 HTTP 客户端（等 #151）

## 目录

- `engine/src/`：引擎实现
  - `index.ts` 启动入口；`server.ts` 控制面 HTTP；`task.ts` 任务运行时与事件；`store.ts` 持久化；
  - `validate.ts` 输入校验；`canonical.ts` 契约摘要；`gateway.ts` 网关接口 + Stub；`runner.ts` StubRunner
- `engine/test/conformance.mjs`：契约 conformance 自测（spawn 引擎实例，28 项断言）
- `spike/`：模型编排可行性 Spike（零依赖，已验证；仅作证据，不是产品代码）

## 运行

```powershell
cd engine
npm install --offline --ignore-scripts      # 依赖（首次）
$env:ENGINE_SERVICE_TOKEN='dev-token'        # 控制面凭证
node src/index.ts                            # 默认 127.0.0.1:8092
```

环境变量：`ENGINE_PORT`、`ENGINE_SERVICE_TOKEN`、`ENGINE_DATA_DIR`、
`ENGINE_RUNNER=stub`（当前唯一）、`STUB_STEP_DELAY_MS`、`STUB_QUESTION`、`STUB_FAIL`、
`STUB_USE_GATEWAY`、`STUB_MESSAGE`、`STUB_CONCLUSION`。

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
