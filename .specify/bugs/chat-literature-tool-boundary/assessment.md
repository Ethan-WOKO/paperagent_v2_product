# Bug Assessment: Literature tool exposure and polling boundaries

- **Slug**: chat-literature-tool-boundary
- **Created**: 2026-09-03
- **Source**: pasted conversation; GitHub Issue #215
- **Verdict**: valid
- **Severity**: medium

## Report (verbatim or summarized)

Ordinary chat should use `recommend_literature`, `search_web`, and
`search_knowledge`, without exposing asynchronous task-control tools. Project
ReactPlan and governed V2 execution must retain the
`literature_search_start/status/result/cancel` workflow. Repeated unchanged
`literature_search_status` polling must be bounded so a simple request cannot
produce a long sequence of redundant execution records.

## Symptom

Two existing Java tests fail because `LangChain4jToolProvider` exposes
`literature_search_start` and `literature_search_status` whenever a compatibility
runtime allow-list contains them, even though `AgentToolPolicyEngine` classifies
the entire asynchronous literature bundle as hidden from ordinary chat. In the
Project ReactPlan path, the asynchronous status tool is intentionally available,
but the Engine currently has no state-aware or per-task polling bound beyond the
global 20-model-call ceiling.

Expected behavior is profile-aware: ordinary chat must have a defense-in-depth
deny boundary, while Project/V2 keeps the governed asynchronous capability and
stops polling after an unchanged observation or a small hard attempt budget.

## Reproduction

1. Run `LangChain4jToolCallingStrategyTest` on current `main`.
2. Observe failures in
   `descriptorVisibilityPreventsPolicyOrOldAliasFromReexposingInternalTool` and
   `providerExposesOnlyTheNormalChatResearchPolicy`.
3. In an Engine task whose registered catalog contains
   `literature_search_status`, have the model repeatedly request the same
   `taskId` while the returned `status`, `currentStage`, counters, and terminal
   flag do not change.
4. Observe that each request reaches the product gateway and produces another
   requested/succeeded execution pair until the global model-call budget or the
   model itself stops.

## Suspected Code Paths

- `yanban-api/src/main/java/com/yanban/api/agent/AgentToolPolicyEngine.java:18`
  — correctly declares the asynchronous literature bundle hidden from ordinary
  chat policy resolution.
- `yanban-api/src/main/java/com/yanban/api/agent/LangChain4jToolProvider.java:99`
  — validates the supplied allow-list and descriptor metadata but does not apply
  the ordinary-chat orchestration deny boundary itself.
- `yanban-api/src/main/java/com/yanban/api/agent/reactplan/gateway/AgentEngineRegisteredToolGateway.java:41`
  — intentionally exposes the asynchronous literature bundle to Project
  ReactPlan through a separate governed gateway.
- `yanban-api/src/main/java/com/yanban/api/agent/v2/effect/AuthenticatedLiteratureSearchEffectExecutionComposer.java:51`
  — intentionally maps governed V2 `literature.search` to the product
  `literature_search_start` effect.
- `agent-engine-reactplan/src/engine.ts:918` — invokes every repeated registered
  status call and retains no durable observation fingerprint or polling budget.
- `agent-engine-reactplan/src/types.ts:194` — persisted task state has no field
  for registered-tool polling observations or task-local tool suppression.

## Root Cause Hypothesis

Confidence: high. Model exposure and execution are correctly separated for the
Project gateway, but the legacy LangChain4j provider assumes every supplied
runtime allow-list has already passed the policy engine. Compatibility callers
and tests can therefore widen ordinary-chat exposure. Separately, the ReactPlan
Engine treats every registered invocation uniformly and does not interpret the
stable task-state fields returned by asynchronous status tools, so unchanged
polls remain legitimate fresh calls until the broad model budget is exhausted.

## Proposed Remediation

**Preferred**:

1. Add a defense-in-depth deny set to `LangChain4jToolProvider` for the four
   asynchronous literature orchestration tools. This provider is not the
   Project ReactPlan registered-tool gateway and is not the V2 effect composer,
   so the change fixes ordinary-chat exposure without removing Project/V2
   functionality.
2. Add durable, task-local polling state to the ReactPlan Engine for
   `literature_search_status`. Fingerprint meaningful state fields rather than
   timestamps, allow a state transition, and suppress the tool for the remainder
   of the current Engine task after two consecutive identical observations or a
   small absolute poll ceiling. Tell the model to return the current progress
   instead of polling again. A later user turn receives a fresh task-local
   budget.
3. Keep `literature_search_start`, `result`, and `cancel` unchanged. Do not alter
   result schemas, authorization, persistence, or synchronous literature tools.

**Alternatives**:

- Remove the asynchronous bundle from all descriptors. Rejected because it
  breaks Project ReactPlan and governed V2 literature execution.
- Rely only on the global 20-model-call ceiling. Rejected because it permits many
  redundant calls and execution records before termination.
- Hide the bundle only in `AgentToolPolicyEngine`. This is current behavior and
  does not protect compatibility callers that construct an already-resolved
  allow-list.

**Files likely to change**:

- `yanban-api/src/main/java/com/yanban/api/agent/LangChain4jToolProvider.java`
- `yanban-api/src/test/java/com/yanban/api/agent/LangChain4jToolCallingStrategyTest.java`
- `agent-engine-reactplan/src/types.ts`
- `agent-engine-reactplan/src/engine.ts`
- `agent-engine-reactplan/test/engine.test.ts`

**Tests to add or update**:

- Existing ordinary-chat visibility tests must pass while normal research tools
  remain exposed.
- Project registered-tool catalog test must continue exposing the complete
  literature task bundle.
- Engine test must prove two identical status observations stop additional
  gateway calls and still allow a concise final answer.
- Engine test must prove a meaningful state transition is not mistaken for an
  unchanged poll.
- Existing V2 literature effect tests must continue passing.

## Risks & Considerations

- Poll-state fields must be persisted so an Engine restart cannot reset the
  limit and resume a busy loop.
- The fingerprint must ignore timestamp-only churn but include status, stage,
  terminal flag, partial-result availability, and progress counters.
- Suppression is scoped to one Engine task. It must not permanently prevent a
  later user turn from checking the same asynchronous task.
- A malformed provider-specific tool call after suppression must receive bounded
  feedback rather than reopen gateway execution.
- No database migration should be required because Engine task state is stored
  as an extensible persisted JSON object.

## Open Questions

- None. The user explicitly selected profile-aware exposure with bounded,
  state-aware Project polling.
