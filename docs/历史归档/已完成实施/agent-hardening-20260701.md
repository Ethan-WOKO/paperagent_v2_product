# Agent Hardening Execution Log - 2026-07-01

## Summary

This document tracks the ordered execution of the Agent hardening plan. It records start/end times, changes made, validation commands, results, risks, and next actions. Sensitive values such as API keys, passwords, and private document content are intentionally omitted.

Overall status: `COMPLETED_WITH_MONITORED_RISKS`.

The main stability gate now passes after the GLM account was recharged: DeepSeek + GLM mixed fixture evaluation completed with `40 PASS / 0 FAIL / 0 SKIP`. A second hardening pass completed the previously baseline-only items: RAG rerank, 60-case standard-answer QA set, citation precision scoring, observability dashboard API, alert rules API, outbound model trace headers, and MDC propagation for parallel Plan step workers. The latest DeepSeek + GLM RAG/observability gate completed with `44 PASS / 0 FAIL / 0 SKIP`.

## Task Checklist

| # | Task | Status | Result |
|---|---|---|---|
| 1 | Re-run GLM full local evaluation | COMPLETED | Initial GLM-only run exposed sync Plan timeout. After async fixes and GLM recharge, DeepSeek+GLM full run passed: 40 PASS / 0 FAIL / 0 SKIP. |
| 2 | Add async Plan execution and retry API | COMPLETED | Added `execute-async` and `retry`; frontend Plan mode now polls plan/events. Async return verified at 27 ms for GLM and 28 ms for DeepSeek in the final combined run. |
| 3 | Add Plan execution guardrails | COMPLETED | Added tool-call budget, duplicate tool-call blocking, plan deadline budget, failure/degraded events, no-tool final synthesis handling, and persisted empty-tool-list semantics. |
| 4 | Improve RAG quality and QA evaluation | COMPLETED | Added candidate expansion, query variant extraction, lookup-token-aware deterministic rerank, rerank score/reason metadata, 60-case standard-answer QA set, citation precision scoring, and rerank coverage scoring. |
| 5 | Add observability and final regression gate | COMPLETED | Added traceId filter/log pattern, outbound `X-Trace-Id` model headers, MDC propagation for parallel Plan step workers, dashboard API, alert rules API, slow request/provider summaries, concurrency eval, and ops eval. |

## Environment

- Backend: `http://localhost:8080`.
- Health check at 2026-07-01 12:46 +08:00 after latest jar restart: `UP`.
- Docker services: checked by ops eval through Docker Compose, MySQL dump, Elasticsearch health, MinIO readiness, and Kafka topic listing.
- Workspace note: existing unrelated changes were present before this execution; this work did not revert them.

## Changes Implemented

### Async Plan Execution

- Added `POST /api/v1/agent/plans/{planId}/execute-async`.
- Added `POST /api/v1/agent/plans/{planId}/retry`.
- Kept the existing synchronous `/execute` endpoint for compatibility.
- Added backend async execution through a bounded executor and persisted Plan state.
- Added frontend Plan Mode polling after async execution starts.
- Added `plan_queued` / retry queued event flow.

### Plan Guardrails

- Added per-step tool-call budget.
- Added duplicate tool-call detection.
- Added global Plan deadline budget.
- Added guardrail events:
  - `plan_queued`
  - `plan_budget_exceeded`
  - `step_tool_budget_exceeded`
  - `step_duplicate_tool_call_blocked`
  - `plan_final_verification_failed`
- Fixed an important runtime bug: persisted `allowedToolsJson=[]` now means "no tools allowed" instead of falling back to all runtime tools. This prevented final synthesis steps from unexpectedly calling search tools.
- Added degraded/failure handling so budget failures become Plan state/events instead of raw HTTP 500s.

### RAG Quality

- Changed default RAG retrieval topK from fixed 3 to configurable default 5.
- Added optional result metadata: `citationId`, `scoreBand`, `source`, `rerankScore`, `rerankReason`.
- Added candidate expansion before final topK selection.
- Added query variant extraction so natural-language searches like "find the exact answer for lookup_key" still recall the lookup token.
- Added deterministic rerank using phrase match, lookup-token match, term coverage, filename match, chunk position, and original vector/database score.
- Added rerank metadata into harness RAG context and search tool output.
- Added mixed fixture coverage for real PDF, DOCX, and Markdown files.
- Expanded fixed-fact assertions to a 60-case standard-answer QA set across 15 facts and 4 query phrasings per fact.
- Added report-level `answerAccuracy`, `citationPrecision`, and `rerankCoverage`.

### Observability

- Added request trace ID filter and response `X-Trace-Id`.
- Added console log pattern containing `traceId`.
- Added outbound model provider `X-Trace-Id` headers for DeepSeek and GLM calls.
- Added MDC propagation for parallel Plan step worker threads.
- Added model-call logs with provider/model/duration/finishReason/token usage when available.
- Added tool-call logs with tool name, success flag, duration, and traceId.
- Added `GET /api/v1/observability/dashboard`.
- Added `GET /api/v1/observability/alerts`.
- Added alert rules for Plan failure rate, oldest RUNNING plan age, terminal Plan p95 duration, and guardrail/verification event count.
- Added evaluation report sections:
  - provider stats
  - slow requests top 10
  - RAG QA metrics
  - sanitized ops secret check

## Validation Results

### Code-Level Regression

Initial command:

```powershell
mvn -pl yanban-api -am "-Dtest=PlanAgentControllerIntegrationTest,AgentControllerIntegrationTest,PlanAgentServiceTest,PlanningAgentPlannerTest,PlanStepVerifierTest,HarnessEngineTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Initial result:

- `BUILD SUCCESS`
- 26 tests passed, 0 failures, 0 errors, 0 skipped.

Second hardening command:

```powershell
mvn -pl yanban-api -am "-Dtest=PlanAgentControllerIntegrationTest,KnowledgeControllerIntegrationTest,PlanAgentServiceTest,PlanningAgentPlannerTest,PlanStepVerifierTest,HarnessEngineTest,HybridKnowledgeSearchServiceTest,KnowledgeRerankerTest,SearchKnowledgeToolExecutorTest,DeepSeekModelProviderTest,GlmModelProviderTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Second hardening result:

- `BUILD SUCCESS`
- 42 targeted tests passed, 0 failures, 0 errors, 0 skipped.
- Covered rerank ordering, fallback search candidate expansion, search API rerank metadata, outbound provider trace headers, Plan observability API, Plan async events, and existing Plan guardrails.

Frontend build:

```powershell
npm run build
```

Result:

- Passed.

### Initial GLM-Only Gate

Command:

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-local-eval.ps1 -Providers glm -FixtureMode mixed -RunPlanExecution
```

Initial report:

- JSON: `docs/evaluation/runs/run-20260701103155-glm.json`
- Markdown: `docs/evaluation/runs/run-20260701103155-glm.md`
- Result: `20 PASS / 1 FAIL / 0 SKIP`.
- Finding: synchronous Plan execution hit the 300s eval timeout.

After async and guardrail fixes:

- JSON: `docs/evaluation/runs/run-20260701111352-glm.json`
- Markdown: `docs/evaluation/runs/run-20260701111352-glm.md`
- Result: `21 PASS / 0 FAIL / 0 SKIP`.
- GLM Plan execution: `COMPLETED`, async return around 72 ms, background duration about 186 s.

### DeepSeek + GLM Combined Gate

Command:

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-local-eval.ps1 -Providers deepseek,glm -FixtureMode mixed -RunPlanExecution
```

Intermediate failed report:

- JSON: `docs/evaluation/runs/run-20260701112534-deepseek-glm.json`
- Markdown: `docs/evaluation/runs/run-20260701112534-deepseek-glm.md`
- Result: `39 PASS / 1 FAIL / 0 SKIP`.
- Finding: GLM Plan ended `FAILED`; user confirmed GLM account balance was depleted during the run.

Final report after GLM recharge:

- JSON: `docs/evaluation/runs/run-20260701113231-deepseek-glm.json`
- Markdown: `docs/evaluation/runs/run-20260701113231-deepseek-glm.md`
- Result: `40 PASS / 0 FAIL / 0 SKIP`.
- DeepSeek Plan: `COMPLETED`, async return 28 ms, background duration 69,100 ms.
- GLM Plan: `COMPLETED`, async return 27 ms, background duration 159,281 ms.
- GLM tool loop: passed, duration 91,690 ms.

### Concurrency Gate

Command:

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-concurrency-eval.ps1 -Concurrency 5 -Requests 10
```

Report:

- JSON: `docs/evaluation/runs/concurrency-20260701113955.json`
- Markdown: `docs/evaluation/runs/concurrency-20260701113955.md`
- Result: `5 PASS / 0 FAIL`.
- Main assertion: completed 10 requests, failed 0, p95 3,037 ms.

### Ops Gate

Command:

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-ops-eval.ps1
```

Report:

- JSON: `docs/evaluation/runs/ops-20260701114006.json`
- Markdown: `docs/evaluation/runs/ops-20260701114006.md`
- Result: `8 PASS / 0 FAIL`.
- Covered:
  - backend actuator health
  - Docker command availability
  - Docker Compose service visibility
  - non-destructive MySQL schema dump
  - Elasticsearch cluster health
  - MinIO readiness
  - Kafka topic listing
  - report secret leakage check

### RAG QA, Rerank, Dashboard, And Alerts Gate

Command:

```powershell
powershell -ExecutionPolicy Bypass -File docs/evaluation/run-local-eval.ps1 -Providers deepseek,glm -FixtureMode mixed
```

Report:

- JSON: `docs/evaluation/runs/run-20260701124649-deepseek-glm.json`
- Markdown: `docs/evaluation/runs/run-20260701124649-deepseek-glm.md`
- Result: `44 PASS / 0 FAIL / 0 SKIP`.
- RAG QA set: 60 cases per provider, built from 15 fixed facts and 4 query phrasings per fact.
- DeepSeek `answerAccuracy`: `1`, `citationPrecision`: `1`, `rerankCoverage`: `1`.
- GLM `answerAccuracy`: `1`, `citationPrecision`: `1`, `rerankCoverage`: `1`.
- Observability dashboard endpoint: `PASS` for both providers.
- Observability alert rules endpoint: `PASS` for both providers; returned `CRITICAL` because the real local 1440-minute window contains historical failed/slow plans. This is expected alert behavior, not an endpoint failure.
- Package result after stopping the old locked jar process: `BUILD SUCCESS`.
- Backend after latest restart: PID `48328`, actuator health `UP`.

Intermediate finding:

- First 60-case run exposed a real retrieval gap: direct lookup-key queries worked, but natural-language wrappers such as "find the exact answer for lookup_key" caused poor recall (`answerAccuracy=0.25`).
- Query variant extraction fixed recall, then the next run exposed citation ordering weakness (`answerAccuracy=1`, `citationPrecision=0.55`).
- Lookup-token-aware rerank fixed first-citation precision, producing the final `44 PASS / 0 FAIL / 0 SKIP` run.

## Residual Risks

- GLM tool-search path is still slow. Final combined run passed, but `P1-TOOLS-01` took 91,690 ms. This should be moved to an async task UX or given stricter search/synthesis budgets for normal chat.
- GLM Plan execution is stable but slow. Final combined run completed, but background execution took 159,281 ms. The async API prevents request timeout, but the frontend must clearly show progress/events and cancellation.
- DeepSeek Plan also remains non-trivial at 69,100 ms in the final combined run. Async execution is required for both providers, not just GLM.
- Dashboard and alert APIs are implemented, but production operations still need an external dashboard/alert sink such as Grafana, Prometheus, CloudWatch, or another monitoring system.
- RAG quality is now scored with a 60-case local fixture set, but customer launch should add domain-specific real-world QA cases and human review thresholds.
- The deterministic reranker is stable and explainable, but it is not a neural cross-encoder reranker. If customers need high-recall semantic search quality, add a model-based reranker behind the same service interface.

## Next Actions

1. Treat the current state as an internal prelaunch baseline, not a final customer SLA.
2. Prioritize async UX for normal tool-search chat paths, not only Plan execution.
3. Wire `/api/v1/observability/dashboard` and `/api/v1/observability/alerts` into the frontend admin page or an external monitoring stack.
4. Expand the 60-case local RAG QA suite with customer-domain documents and expected citations.
5. Consider a neural reranker if deterministic rerank quality is not enough for customer corpora.
