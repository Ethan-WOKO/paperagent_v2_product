# 验证矩阵

使用本矩阵为每个 issue 选择检查项。风险更高的 PR 可以增加更强的验证。

| 变更类型 | 必须验证 | 可选或按风险增加 |
| --- | --- | --- |
| 仅文档 | `git diff --check` | 链接预览或人工阅读 |
| GitHub 模板 | `git diff --check` | 必要时创建测试 issue/PR |
| Maven pom/config | `mvn -q -DskipTests validate` | `mvn test` |
| 后端 service | focused module tests | 完整 `mvn test` |
| API/controller | controller 或 integration tests | 手动 API smoke test |
| Flyway migration | API 模块测试和必要的 H2 兼容验证 | 真实 MySQL migration 检查 |
| 前端 UI | `$env:CI='true'; pnpm build` | 浏览器/手动工作流检查 |
| 前端状态机 | build + focused logic 或手动状态验证 | 截图或短录屏 |
| Agent runtime | focused service tests + 手动/eval cases | 端到端聊天运行 |
| V2 核心导入/构建组合 | 冻结提交来源比对 + `agent-v2` 完整测试 + 根 reactor 的 `agent-v2` 构建 + V2→V1 静态依赖检查 | 仅在直接影响产品模块时增加对应模块测试 |
| RAG 检索 | 检索 eval cases | 真实 ES/embedding 环境测试 |
| 文献推荐 | 真实性和去重 eval cases | 外部来源 smoke test |
| 论文润色 | focused paper tests + artifact 检查 | 端到端论文任务运行 |
| Kafka 任务分发 | producer/consumer focused tests | 本地 Docker Kafka smoke test |
| SSE/event stream | 事件 sequence 和终态测试 | 重连/浏览器手动检查 |
| 取消能力 | cancel 状态和 partial artifact 检查 | 长任务手动取消场景 |

## 默认命令

后端 validate：

```powershell
mvn -q -DskipTests validate
```

后端完整测试：

```powershell
mvn test
```

前端 build：

```powershell
cd frontend
$env:CI='true'
pnpm build
```

空白检查：

```powershell
git diff --check
```

## 合并规则

PR 只有满足以下任一条件才可以合并：

1. 必须验证项已通过。
2. 跳过的检查已有明确理由，风险可接受，并在需要时创建了后续 issue。

## 阶段纪律

开始实现前必须确认：

1. issue 属于 roadmap 的哪个阶段。
2. issue 是设计、spike、实现、测试还是文档任务。
3. 哪些非目标用于防止范围膨胀。

## V2 集成纪律

1. V2 核心的导入 PR 不运行无关的论文、知识库或前端全量测试。
2. product adapter PR 必须测试其直接连接的 V1 入口和 V2 契约。
3. API 切换 PR 才增加对应端到端流程；里程碑验收再运行完整相关门禁。
4. 每次检查 `agent-v2/` 不含 `com.yanban` 依赖、生成物、密钥或本地数据。

## V2 Project Candidate focused gate

- Run only the Candidate composition, delivery, service, migration,
  controller, exact Step-selection/dispatch, and directly affected Project
  effect tests.
- Compile `yanban-api` with dependencies; run the single Candidate frontend
  Vitest file and the frontend production build.
- Verify the diff contains only frozen MODIFY paths, original Project bytes
  remain unchanged until the exact final successful sandbox input is bound,
  durable failure creates no current revision, automatic application creates
  one immutable revision, rollback remains available, and the UI does not ask
  for a redundant second validation or confirmation.
- Do not run unrelated full product suites without a concrete dependency or
  failure that justifies expanding scope.

## V2 product availability focused gate

- Run the availability property/document tests and focused Agent/Project
  controller tests for all existing V2 start/read/cancel methods.
- Prove disabled requests fail before service delegation and enabled requests
  delegate exactly once. Directly adjacent ordinary message, Project message,
  and Candidate apply methods must remain callable while V2 is disabled.
- Run only `tests/v2ProductAvailability.test.ts` plus the frontend production
  build unless a directly affected import or state seam justifies another
  focused test.
- Compile `yanban-api` with dependencies. Audit owned paths and prove no
  `agent-v2/**`, schema, legacy Agent implementation, secret, user/local data,
  or generated artifact changed.
- Full RAG, paper-quality, retrieval, deployment, and product suites are not
  part of this gate without a concrete directly affected failure.

## V2 four-role adaptive chain focused gate

- Verify planner and replan outputs contain goal-based Steps with no capability
  field, publication Step, confirmation Step, or redundant validation Step.
- Verify the current-Step model can send complete `replacements` and the
  Project write effect performs no nested model call. Reject missing, extra,
  mismatched, duplicate, oversized, invalid UTF-8, unchanged, or out-of-scope
  replacements before durable publication.
- Verify a later correction may append another authority row in the same Plan
  or Step, exact replay converges, and the latest valid preparation is selected.
- Verify reflection format repair receives exact missing/unexpected fields and
  the previous invalid output. Verify semantic reflection reuses accepted read,
  write, and exact sandbox facts without inventing a second audit or user gate.
- Verify final synthesis receives complete final file text and the matching
  successful sandbox command/output, and literature/project compatibility
  narration performs no extra model call.
- Preserve E2B dependency preparation, Workspace isolation, sandbox input
  fingerprinting, automatic immutable revision creation, deletion behavior,
  and rollback tests.
