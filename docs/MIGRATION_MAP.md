# V2 Capability Migration Map

## Literature search task start

- Status: `REUSE_WITH_ADAPTER`
- Assessed product entry: `LiteratureSearchStartToolExecutor`
- V2 tool identity: `literature.search`
- Product tool identity: `literature_search_start`
- Authority: authenticated Agent turn, recovered ACTIVE Step, persisted
  EffectIntent, and current fenced lease; model arguments provide no identity
  or permission authority.
- Adaptation: permit only query, bounded `topK`, bounded `yearFrom`, and
  `includeBibtex`; derive `clientRequestId` from ToolCallId; inject user and
  optional Project identity from verified product state.
- Atomicity: a unique V2 execution claim, the product literature task, its
  ExecutionReceipt, and EffectOutcome commit in one product database
  transaction. A committed result is replay-only.
- Excluded: legacy Agent planning/verification/loop services, literature
  status/result/cancel, live retrieval, and generic tool execution.

## Persistent Plan loop and active-Step replan

- Status: `V2_COMPOSE_WITH_PRODUCT_ADAPTERS`
- Assessed product entries: the V2 authenticated Step recovery, provider turn,
  governed `literature.search` effect, effect-driven progression, and
  relational Step lifecycle adapters.
- Authority: owner-qualified Agent turn identity plus canonical Plan,
  TaskFrame, activation, checkpoint/event heads, and current fenced lease.
  The optional replan request remains an untrusted proposal.
- Adaptation: a bounded product-side loop composes one stable kernel turn per
  cycle and dispatches only the exact persisted `literature.search` intent.
  V54 stores each active-Step replan as an immutable canonical marker; product
  recovery folds zero or more markers by source event sequence. Effect writers
  revalidate the exact current ACTIVE cut under the Plan lock, so prior
  completed Steps remain valid history; lifecycle writers reserve V54 event
  identities against known stores in that same lock domain. This is not a
  database-enforced cross-Plan global event registry; concurrent cross-Plan
  reuse remains a separately scoped residual risk.
- Excluded: legacy Agent loop/planner/verifier services, arbitrary tools,
  Controller/API/UI cutover, background retry, Workspace/Project mutation,
  and automatic model-generated replan proposals.
- Replan trigger boundary: the stable composer now accepts a genuine
  first-turn `BoundedStepAgentLoopNoEffect` with no persisted intents. An
  exact caller proposal may be applied from that ACTIVE stall. A hard product
  cycle limit after an intent was persisted still returns `REPLAN_REQUIRED`
  without a replan write; the governed effect must not be abandoned.

## Opt-in literature turn and durable final delivery

- Status: `V2_COMPOSE_WITH_PRODUCT_ADAPTERS`.
- Product entry:
  `POST /api/v1/agent/sessions/{sessionId}/v2/literature-turns`.
- Scope: authenticated owner-qualified `WORKSPACE` sessions only. Project
  sessions are rejected and the legacy messages endpoint is unchanged.
- Intake: the server freezes one TaskFrame and one bounded
  `literature.search` Step from the structured request. Client input cannot
  provide Plan, Step, ToolCall, lease, Project, or Receipt authority.
- Delivery authority: only a terminal `PersistedStepRecoverySucceeded` cut is
  eligible. Its checkpoint Receipt order must exactly equal the ordered
  completion facts; every Receipt must be successful and owned by a persisted
  `literature.search` intent for the same Plan.
- Synthesis: Receipt payloads are reduced to bounded projections explicitly
  labelled untrusted, the Provider receives no tools, and empty/tool-calling/
  failed Provider results create no successful synthesis or assistant message.
- Replay: V55 records the request fingerprint and explicit delivery state.
  Same-key retries and same-process concurrency converge on one turn, Plan,
  synthesis, and assistant message; changed payload reuse conflicts.
- Semantics: success states only that the literature search task was durably
  created or queued. It does not claim that paper results were returned.
- Excluded: Project routing, literature status/result/cancel, UI, generic
  planning, other tools, Workspace mutation, ProjectVersion apply, and legacy
  Agent planning/execution/final-synthesis services.

## V2 literature task outcomes and chat UI

- Status: `REUSE_WITH_ADAPTER`.
- Assessed product entries: `LiteratureSearchTask`,
  `LiteratureSearchTaskService`, the existing asynchronous OpenAlex/arXiv
  worker and result materializer, and the existing chat presentation shell.
- Authority: the successful `literature.search` ExecutionReceipt supplies the
  only task ID. The effect-claim transaction locks the owner/turn delivery,
  verifies the owner-qualified non-Project task and its frozen request, and
  writes the task binding before the Receipt and EffectOutcome can commit.
- Outcome access: owner/session/client-request reads and cancellation resolve
  only that durable binding. Reads project bounded allowlisted paper fields
  from the product result and persist at most one code-owned result assistant
  message under the delivery row lock. Corrupt or oversized results fail
  closed without a message.
- UI: the explicit Search Papers form uses the V2 start endpoint, bounded
  polling, scoped cancellation, session/unmount cleanup, safe links, and
  collapsed BibTeX. Ordinary messages and legacy Plan mode are unchanged.
- Excluded: legacy Agent planning/loop/verification, Project routing, new V2
  Steps, retrieval/ranking/worker rewrites, and generic natural-language
  routing.

## Opt-in read-only V2 Project analysis

- Status: `REUSE_WITH_ADAPTER`.
- Assessed product entries: authenticated Project sessions, immutable Project
  manifests, the V2 local Workspace port, and the existing Project chat UI.
- Authority: V57 freezes the exact user/Project/session/request/version/Plan
  binding and one exact effect kind and canonical argument document per Step.
  The Provider receives exactly that Step's one tool descriptor; model output
  cannot change path, query, Project, version, Workspace, or permission facts.
- Effects: `project.read` reads one requested UTF-8 text path up to 64 KiB.
  `project.search` performs literal, sorted, bounded search only inside the
  confirmed frozen Workspace. Traversal, links, binary/oversized/missing
  content, changed versions, and cross-bound intents fail closed.
- Delivery: terminal successful recovery and exact owned Receipts produce one
  no-tools synthesis with matching ProjectVersion and an empty Workspace diff.
  Project provenance is now stored canonically by the existing final-synthesis
  adapter. One delivery row owns at most one assistant message and exact
  request replay.
- UI: ProjectPreviewPage exposes an explicit opt-in form with 1-4 paths,
  optional literal search, scoped reload recovery, and bounded polling.
  Ordinary Project messages and legacy Project Plans remain unchanged.
- Excluded: Project writes, command/Sandbox execution, diff acceptance,
  revision apply, automatic replan/repair, arbitrary tools, and legacy Agent
  planner/service/verifier execution.
