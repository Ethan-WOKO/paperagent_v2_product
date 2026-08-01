# Project Document Map - 2026-07-08

> Purpose: reduce confusion from overlapping roadmap, design, evaluation, and memory-bank documents.
>
> Rule of thumb: use explicit document dates first. If a document has no internal date, use its filename or last modified date as weaker evidence.

## 1. Current Reading Order

Read these first when deciding what to do next:

| Priority | Document | Date | Role | Current takeaway |
| ---: | --- | --- | --- | --- |
| 1 | `docs/design/v2-agent-core-integration.md` | 2026-07-27 | Current Agent architecture direction | Keep this repository as the product shell and integrate the isolated V2 core through one-way adapters. |
| 2 | Active GitHub Issue | Current | Frozen implementation contract | Defines base/source commits, owned paths, acceptance tests, and stop conditions for one capability. |
| 3 | `docs/design/paper-agent-next-steps-roadmap.md` | 2026-07-07 | Product-capability direction | Continue to preserve and improve literature, paper, and UI capabilities alongside the V2 Agent migration. |
| 4 | `docs/process/local-merge-gate.md` and `docs/process/verification-matrix.md` | 2026-07-04 / current | Merge discipline | Select focused checks for the current change; expand only for a directly affected boundary. |
| 5 | `docs/evaluation/reports/literature-recommendation-eval-20260708.md` | 2026-07-08 | Product evaluation baseline | Literature recommendation baseline is 4.445 / 5 across 10 golden cases. |

## 2. Current Direction

As of 2026-07-27, development has two coordinated tracks:

1. preserve the existing product shell and its literature, paper, knowledge,
   authentication, UI, and deployment capabilities;
2. integrate the frozen V2 Agent core under `agent-v2/`, then add product-side
   adapters one capability at a time.

The current product tool expansion groups four to five closely related tools
per Issue only when authority, side effects, runtime target, and verification
are identical. Issue #118 applies that rule to five read-only paper-quality
audits; their outputs are structured facts for the V2 Agent and do not replace
semantic review or authorize Project mutation.

`docs/design/v2-agent-core-integration.md` is authoritative for Agent runtime
architecture. The dated product roadmap remains authoritative for quality work
outside the Agent core. Older runtime documents are historical where they
conflict with the V2 integration decision.

## 3. Historical Roadmaps And Audits

These documents explain how the project got here.

| Document | Date | Category | Summary | Current status |
| --- | --- | --- | --- | --- |
| `memory-bank/agent-product-reconstruction-roadmap.md` | Updated 2026-07-05 | Historical master roadmap | Defines product model, runtime boundary, RAG, memory, task lifecycle, event stream, literature reuse, and Project direction. | Keep as architecture background. Do not treat every old phase as an open task. |
| `memory-bank/roadmap-completion-audit-20260705.md` | 2026-07-05 | Completion audit | Audits the roadmap against closed issues and repo artifacts. Says no obvious unimplemented roadmap issue queue remains. | Use as proof that the old roadmap is mostly complete. |
| `memory-bank/issues/issue-86-local-draft.md` | 2026-07-05 | Local issue draft | Draft for standalone literature search reusing existing `LiteratureCard` assets. | Mostly superseded by current literature recommendation work, but still useful for acceptance criteria around local card reuse. |

## 4. Current Product Roadmap

| Document | Date | Category | Summary | Next action implied |
| --- | --- | --- | --- | --- |
| `docs/design/paper-agent-next-steps-roadmap.md` | 2026-07-07 | Current roadmap | Re-centers work on paper-agent quality: literature recommendation, paper polish quality closure, chart understanding, UI, evaluation, general Agent, Project. | Finish literature recommendation independence and reranking evaluation first. |

Key ordered sequence from that document:

1. literature recommendation independence and reranking evaluation,
2. paper polish diff, failure reasons, LaTeX compile validation,
3. paper polish UI rebuild,
4. chart understanding,
5. general Agent and Project expansion.

## 5. Design Documents

These describe target behavior and boundaries.

| Document | Date / modified | Topic | Summary |
| --- | --- | --- | --- |
| `docs/design/v2-agent-core-integration.md` | 2026-07-27 | Current Agent integration boundary | Defines the product-shell decision, isolated V2 reactor, dependency direction, and migration sequence. |
| `docs/design/agent-runtime-task-lifecycle.md` | 2026-07-04 | Historical runtime boundary | Retained for V1 lifecycle context; superseded where it conflicts with the V2 core contracts. |
| `docs/design/agent-event-cancel-protocol.md` | 2026-07-04 | Event/cancel protocol | Defines unified task events, SSE, Kafka messages, cancel API, worker safe points, and frontend state mapping. |
| `docs/design/kb-version-governance-rag-filtering.md` | 2026-07-04 | Knowledge governance | Defines version status, source type, filtering, ranking, ES sync, LangChain4j metadata requirements. |
| `docs/design/chatmemory-context-management.md` | 2026-07-04 | Context and memory | Defines UI history, short-term memory, summaries, long-term memory, context snapshots, user confirmation points. |
| `docs/design/long-term-memory-crud.md` | 2026-07-04 | Long-term memory CRUD | Defines memory records, user APIs, UI placement, and later work. |
| `docs/design/long-term-memory-context-injection.md` | 2026-07-04 | Memory injection | Defines runtime retrieval and injection rules for long-term memory. |
| `docs/design/context-debug-snapshots.md` | 2026-07-04 | Debug snapshots | Defines how to inspect constructed context for debugging. |
| `docs/design/langchain4j-ab-evaluation-console.md` | 2026-07-05 | A/B evaluation console | Defines experiment dimensions for runtime, RAG, memory, and tool calling. |
| `docs/design/paper-task-cancel-semantics.md` | 2026-07-04 | Paper task cancel | Defines paper task cancellation states and worker rules. |

## 6. Evaluation Documents And Reports

Use these to understand what has actually been tested.

| Document | Date | Category | Summary | Current verdict |
| --- | --- | --- | --- | --- |
| `docs/evaluation/reports/literature-recommendation-eval-20260708.md` | 2026-07-08 | Literature recommendation eval | New baseline for independent literature recommendation: 10 cases, average 4.445 / 5. | Current active baseline. |
| `docs/evaluation/reports/langchain4j-ab-evaluation-console-20260705.md` | 2026-07-05 | LangChain4j A/B conclusion | Keep Harness as default; use LangChain4j as experiment layer. | Do not replace Harness wholesale. |
| `docs/evaluation/reports/kb-real-es-hybrid-rag-e2e-20260704.md` | 2026-07-04 | Real ES hybrid RAG e2e | Real ES e2e passes 10/10, Recall@5 = 1.0, forbidden hits = 0. | Baseline RAG governance is working. |
| `docs/evaluation/reports/kb-version-governance-rag-eval-20260704.md` | 2026-07-04 | KB version governance eval | Validates ACTIVE/SUPERSEDED/DELETED behavior in baseline eval. | Version filtering has test evidence. |
| `docs/evaluation/reports/langchain4j-rag-spike-20260704.md` | 2026-07-04 | RAG spike | LangChain4j adapter is feasible, but earlier report said more real eval was needed. | Superseded by later A/B conclusion for migration strategy. |
| `docs/evaluation/real-run-assessment-2026-06-30.md` | 2026-06-30 | Real-run assessment | Earlier real backend/middleware/model chain assessment. | Historical evidence for base operability. |
| `docs/evaluation/current-assessment-2026-06-30.md` | 2026-06-30 | Current assessment | Earlier evaluation snapshot. | Historical. |
| `docs/evaluation/v1-eval-suite.md` | 2026-07-02 | Eval suite | Defines local eval suite structure. | Reference for building future evals. |
| `docs/evaluation/agent-rag-eval-requirements.md` | 2026-07-04 | Eval requirements | Defines Agent/RAG evaluation expectations. | Use for coverage planning. |

## 7. Evaluation Scripts And Fixtures

| Path | Date / modified | Purpose |
| --- | --- | --- |
| `docs/evaluation/run-literature-recommendation-eval.ps1` | 2026-07-07 | Runs `LiteratureRecommendationEvaluationTest` and checks generated report files. |
| `docs/evaluation/run-local-eval.ps1` | 2026-07-02 | Local eval runner. |
| `docs/evaluation/run-ops-eval.ps1` | 2026-07-01 | Ops eval runner. |
| `docs/evaluation/run-concurrency-eval.ps1` | 2026-07-01 | Concurrency eval runner. |
| `docs/evaluation/fixtures/rag-spike/` | 2026-07-04 | RAG spike fixture cases and documents. |
| `docs/evaluation/fixtures/agent-mode-regression/cases.json` | 2026-07-06 | Agent mode regression cases. |
| `docs/evaluation/runs/` | 2026-06-30 to 2026-07-02 | Historical generated runs and artifacts. Usually read only when debugging prior evals. |

## 8. Operations And Setup

| Document | Date / modified | Summary |
| --- | --- | --- |
| `docs/SETUP.md` | 2026-07-06 | Local development setup, middleware startup, backend/frontend startup, MCP/OCR notes, test commands. |
| `docs/docker-compose.yml` | 2026-07-05 | Local middleware compose file. |
| `docs/operations/kb-elasticsearch-index.md` | 2026-07-04 | ES index mapping and rebuild strategy. |
| `docs/DEPLOYMENT.md` | 2026-06-17 | Older deployment note. |
| `docs/API-smoke.md` | 2026-06-17 | Older API smoke note. |
| `docs/OCR.md` | 2026-06-17 | OCR setup notes. |
| `docs/SKILLS.md` | 2026-06-17 | Skills documentation. |
| `docs/WEBSOCKET.md` | 2026-06-17 | WebSocket note. |

## 9. Process Documents

| Document | Date / modified | Summary |
| --- | --- | --- |
| `docs/process/local-merge-gate.md` | 2026-07-04 | Defines PR/test/risk reporting expectations. |
| `docs/process/verification-matrix.md` | 2026-07-04 | Maps change types to required verification. |

## 10. Samples

These are sample content, not roadmap direction.

| Path | Purpose |
| --- | --- |
| `docs/kb-samples/` | Knowledge-base sample documents. |
| `docs/paper-quality-samples/` | Paper-quality sample notes. |

## 11. Practical Notes For The Next Contributor

Current repository context as of 2026-07-27:

1. Start from the active Issue's exact base commit in an independent worktree.
2. Treat legacy Agent code as `UNASSESSED`; do not copy it into V2 without an
   independent review. Group four to five closely related tools only when they
   share one authority, side-effect, runtime, and verification boundary.
3. Keep V2-to-product dependency checks in every Agent integration PR.
4. Preserve the existing product features and run focused tests for only the
   directly affected boundary.

## 12. Short Answer

If you are confused, start here:

1. Read `docs/design/v2-agent-core-integration.md`.
2. Read the active GitHub Issue.
3. Use `docs/process/verification-matrix.md` to choose focused checks.
4. Use `docs/design/paper-agent-next-steps-roadmap.md` for non-Agent product
   priorities.
