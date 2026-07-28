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
