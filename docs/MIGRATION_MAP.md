# V2 Capability Migration Map

## Natural-language V2 turn intake and initial Plan

- Status: `V2_COMPOSE_WITH_PRODUCT_ADAPTERS`.
- Product entry:
  `POST /api/v1/agent/sessions/{sessionId}/v2/turns`.
- Context reuse: the intake reuses the bounded product conversation context,
  rolling summary, governed user memory, applicable experiment RAG context,
  selected Skill prompt, authenticated Project/version state, and the owner's
  saved model endpoint. Credentials are used only by the product model call
  and are never included in the planner prompt or durable evidence.
- Planning: one no-tools model call returns strict bounded JSON choosing only
  `DIRECT` or `PERSISTENT_PLAN_EXECUTE`. Public underscore aliases map through
  one product table to dotted internal ToolIds; dotted or unknown model
  aliases fail closed.
- Authority: V61 owns idempotency by authenticated owner/session/client
  request plus a canonical request digest. The server creates the canonical
  user message and RUNNING Agent turn. For persistent work, only the existing
  authenticated bootstrap composer supplies TaskFrame, Plan, ProjectVersion,
  and durable identity authority; the model supplies draft content only.
- Delivery: DIRECT creates exactly one canonical assistant message and
  completes the turn. Persistent intake stores an initial NOT_STARTED Plan and
  leaves the turn RUNNING for the later adaptive execution Issue. Exact replay
  returns the same result; changed-payload reuse and malformed, oversized, or
  tool-calling planner responses fail closed.
- Excluded: Step execution, reflection, retry, automatic replan, final
  synthesis, UI changes, Project mutation, Sandbox effects, and legacy Agent
  planner, loop, verifier, or Candidate-chain reuse.

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

## Opt-in V2 Project modification Candidate

- Status: `REUSE_WITH_ADAPTER`.
- Assessed product entries: `CandidateChangeArtifactService`, the immutable
  Candidate envelope/change-set contracts, `CandidateSandboxValidationService`,
  `CandidateValidationApplicationGate`, `ProjectRevisionWorkflowService`, and
  the existing Changes inspector.
- Reuse decision: the V2 path calls only the mature Candidate artifact service
  after a successful V2 terminal cut. It does not reuse the legacy Agent
  planner, completion verifier, Candidate tool executor, or fixed execution
  chain. Existing sandbox validation and revision application sources remain
  unchanged.
- Authority: V58 binds authenticated user/Project/session/request, the frozen
  ProjectVersion, objective, exact 1-4 paths, Plan, Workspace, and each Step
  effect. `project.candidate.compose` accepts only the exact code-owned
  `{"operation":"compose"}` intent. Provider replacement JSON is untrusted
  content and must contain every frozen path exactly once and no other path.
- Mutation: replacements are bounded UTF-8 full text and are written only to
  the isolated V2 Workspace. A successful effect requires a canonical,
  non-empty MODIFY-only WorkspaceDiff for exactly the frozen paths. The
  original Project is reread only for Candidate attestation and remains
  unchanged.
- Delivery: only a terminal successful Plan may publish the existing-format
  Candidate. Artifact identity, Candidate fingerprint, and a stable diff
  fingerprint are bound durably before one assistant handoff. Exact replay and
  concurrent delivery converge on that binding; durable failure has no
  Candidate.
- UI: the explicit form uses POST plus scoped GET recovery, refreshes and
  selects the returned Candidate, then leaves the existing sandbox validation,
  selected-change review, If-Match, idempotency, and explicit apply
  confirmation as the sole ProjectVersion mutation route.
- Excluded: ADD/DELETE, automatic apply, arbitrary command/network/Sandbox
  execution, retry/repair/replan, generic message routing, legacy Agent
  orchestration, and changes to the V2 core or mature Candidate/revision
  implementations.

## V2 product availability boundary

- Status: `V2_AVAILABLE_WITH_ROLLBACK_GATE`.
- Assessed product entries: the existing explicit V2 literature search,
  Project read-analysis, and Project Candidate controllers and UI controls.
- Authority: one server-bound `yanban.agent.v2.product.enabled` property,
  enabled by code default. The authenticated capability document exposes only
  format version, enabled state, and the three allowlisted capability names.
  Client fields, headers, cookies, session state, and model output are not
  availability authority.
- Enforcement: every existing V2 start, read, and cancel controller checks the
  same gate before service lookup or delegation. Disabled requests return one
  sanitized service-unavailable response and cannot create a Plan, task,
  Candidate, or message.
- UI: Chat and Project pages treat a failed capability read as unavailable for
  explicit V2 controls only. Ordinary chat, Project messages, legacy Plans,
  RAG, paper, Candidate validation, and explicit Candidate apply remain
  available and are never used as a silent fallback.
- Retirement decision: all legacy Agent orchestration remains present and
  unchanged. Removing it requires stable production evidence and a later
  explicit user decision; it is not part of the current migration plan.

## Adaptive natural-language V2 execution

- Status: `V2_COMPOSE_WITH_PRODUCT_ADAPTERS`.
- Assessed entries: the product execution-start recovery, Project execution
  context, persistent Agent loop, append-only active-Step replan, Project
  effects, the shared E2B broker, user model endpoint resolution, and
  assistant-message persistence.
- Intake and Plan: one natural-language request creates a frozen TaskFrame and
  a bounded, persistent Plan whose Steps describe goals and completion
  criteria, not fixed tools. Replan replacement Steps use the same tool-free
  schema. Legacy replacement capability hints remain parser-compatible but
  are not authority for the autonomous path.
- Execution: the active Step receives the bounded product tool catalog. The
  model may select another tool after inspecting a Receipt while the Step
  remains ACTIVE. Each tool slot has a stable ToolCall identity across replay;
  a later slot receives a new identity. Reflection receives bounded
  conversation context, prior failures, final Receipts, Workspace/Candidate
  references, and unfinished work. `CONTINUE`, `REPLAN`, `COMPLETE`, and
  `FAIL` remain bounded; COMPLETE cannot override durable completion facts.
- Provider: the natural intake's settings-page provider/model/key endpoint is
  adapted per request and carried only in memory through kernel, Candidate,
  and reflection calls. It does not use the explicit-delivery plan resolver
  and does not persist or log the key.
- Replan and completion: replacement revisions retain completed Steps and
  facts, mark the obsolete active Step `SUPERSEDED_BY_REPLAN`, and append only
  new `NOT_STARTED` Steps under the existing fenced persistence boundary.
  Step completion aggregates all final Receipt references, requires at least
  one SUCCESS and no pending or unknown effect, then invokes the stable
  completion boundary once.
- Delivery: only a durable terminal-success cut plus accepted COMPLETE
  reflection creates the single replayable assistant message. A natural
  Candidate instead uses a V62 natural authority/prepared binding, publishes
  the mature numeric Candidate artifact only after terminal success, and
  returns `WAITING_CONFIRMATION`; Project writes remain isolated and Candidate
  application remains an explicit later action.
- Sandbox and resume: `sandbox.execute` uses only the existing shared E2B
  broker, with the existing preparation/upload/execute-offline policy. It
  never falls back to a host process or creates a second broker. A bounded
  request may return `RUNNING`; repeating the same POST with the same
  `clientRequestId` resumes the same persistent turn and the same in-flight
  ToolCall rather than replanning or redispatching it. GET remains read-only.
- UI: V2 Project mode has one Chinese conversation input. It shows the ordered
  server-owned Steps, each Step result, and one final result without exposing
  separate read/Candidate forms or internal tool identifiers.
- Excluded: legacy Agent orchestration, V2 core changes, automatic Candidate
  apply, a new Sandbox protocol or schema, and unrelated RAG/paper behavior.

## Legacy V1 Agent orchestration retirement audit

- Issue: `#106`.
- Status: `ASSESSED_FOR_STAGED_RETIREMENT`.
- Scope: call-graph and ownership audit only. This assessment does not delete
  production code, database rows, migrations, or stored user messages.
- Baseline: the Project page cutover in `codex/v2-baseline-20260801` makes the
  visible Project conversation V2-only, but the branch still contains the
  workspace `ChatPage`, its `/chat` route and WebSocket, legacy REST clients,
  and dead V1 state and handlers in `ProjectPreviewPage.vue`. Backend removal
  must therefore follow, not precede, the frontend entry-point cleanup.

### Dependency finding

The V2 production package has no direct reference to `PlanAgentService`,
`PlanningAgentPlanner`, `CompletionVerifier`, `AgentRuntimeCoordinator`,
`AgentRuntimeService`, `AgentStrategySelector`, `PlanRuntimeAdapter`,
`PlanReflectionRuntimeAdapter`, `LangChain4jToolCallingStrategy`,
`LangChain4jToolProvider`, or `AgentLangChain4jTools`. Those classes are not
part of the V2 runtime contract.

V2 does, however, directly reuse the following product capabilities that
currently live in legacy-named packages. Their package name is not evidence
that they are safe to delete:

- session/message persistence, summaries, context snapshots, long-term
  memory, RAG experiment context, and `AgentContextBuilder` contracts;
- `LiteratureSearchStartToolExecutor` and the existing literature task
  implementation;
- `CandidateChangeArtifactService`, `ProjectRuntimeContext`, Candidate
  contracts, Candidate validation/apply gates, and Project revision services;
- `V2SandboxEffectExecutionComposer` and the shared E2B broker/client
  infrastructure;
- `ChatModelProvider`, provider settings and user-key resolution, and
  `LangChain4jChatModelAdapter`. The latter is also used by paper workflows and
  `AgentExperimentService` independently of the old tool-calling loop.

### Retirement classification

| Classification | Components | Decision and prerequisite |
| --- | --- | --- |
| `DELETE_AFTER_CUTOVER` | `PlanAgentController`, `PlanAgentService`, `PlanningAgentPlanner`, `CompletionVerifier`, `PlanRuntimeAdapter`, `PlanReflectionRuntimeAdapter`, `PlanStepVerifier`, and the legacy `FinalSynthesisService` | These implement the old Plan lifecycle and are not V2 dependencies. Delete only after all REST, Project-runtime, and internal callers are removed. Preserve V2's separate `V2AdaptiveFinalSynthesisService`. |
| `DELETE_AFTER_CUTOVER` | `AgentRuntimeCoordinator`, `AgentRuntimeService`, `AgentStrategySelector`, `AgentLlmRouter`, `LangChain4jRuntimeAdapter`, and the legacy controlled-worker orchestration | These form the old chat/Plan runtime. Delete after the V1 message endpoint and both V1 WebSockets no longer have production callers. |
| `DELETE_AFTER_CUTOVER` | `SandboxPlanAuthorityResolver`, `SandboxPlanConfirmationService`, `SandboxExecutionOutboxService`, `SandboxOutboxDispatcher`, `SandboxReceiptProjectionService`, and `SandboxOutputAnalysisProjectionService` | These are coupled to the old `AgentPlan` authority/outbox projection. Delete only after the old Plan endpoints and background beans are gone. Do not remove the E2B broker, V2 sandbox composer, Candidate validation sandbox, or shared sandbox contracts. |
| `SPLIT_BEFORE_DELETE` | `AgentController` and `AgentService` | `AgentController` mixes V2 endpoints and shared session/context APIs with the V1 `sendMessage` endpoint. `AgentService` mixes reusable session/title/message/summary/memory work with old runtime dispatch. Extract stable session/context services and remove only the V1 dispatch path; the whole classes are not deletion-ready. |
| `SPLIT_BEFORE_DELETE` | `ProjectController`, `ProjectAgentRuntimeService`, `ProjectChatWebSocketHandler`, and `ChatWebSocketHandler` | Keep Project/version/Candidate APIs, but remove old Project Plan/message adapters and V1 WebSockets after frontend callers are deleted. `ProjectController` itself is shared and must not be deleted wholesale. |
| `MIGRATE_ONE_CAPABILITY_AT_A_TIME` | `LangChain4jToolCallingStrategy`, `LangChain4jToolProvider`, `AgentLangChain4jTools`, `AgentToolPolicyEngine`, and reusable tool executors | The old binding/loop can retire only after required executors are assessed and registered through the V2 tool contract. Reuse executor logic where justified; do not migrate the V1 planner or fixed execution chain. `ToolRegistry` is not deletion evidence by association. |
| `KEEP_SHARED` | session/message entities and repositories, summary/memory/RAG/context services, settings/provider/user-key services, Project/version/Candidate/revision services, literature and paper services, model transport, and E2B/V2 Workspace infrastructure | These are product capabilities or explicit V2 dependencies. They remain even if they are later moved from a legacy-named package. |
| `RETAIN_DATA_SCHEMA` | legacy Agent Plan tables, migrations, stored messages, Receipts, and event data | Code retirement does not authorize destructive schema migration or historical-data deletion. A later data-retention Issue must make that decision explicitly. |

### Required retirement order

1. Merge or otherwise accept the V2 Project baseline and remove the remaining
   V1 frontend entry points: `ChatPage`, `/chat` default navigation, V1
   WebSocket usage, legacy REST clients, and dead Project-page V1 handlers.
2. Split `AgentController`/`AgentService` so V2 intake/history and stable
   session/context capabilities no longer require construction of the V1
   runtime. Split legacy Project message/Plan endpoints from shared
   `ProjectController` APIs.
3. Define one V2 tool descriptor, argument, result, Receipt, and registry
   contract. Assess and migrate each required paper, Project, literature, and
   sandbox capability in a separate Issue/PR with success and failure tests.
4. Remove old Plan REST endpoints, Project Plan adapters, WebSockets, runtime
   coordinators, planner/reflection/verifier chain, and Plan-specific sandbox
   outbox workers after call-graph and Spring-bean checks show no production
   callers.
5. Remove now-unreferenced legacy tool bindings and orchestration tests. Keep
   shared executors and model transport until their independent consumers are
   migrated or retired.
6. Leave historical tables and rows intact. Any later schema cleanup requires
   a separate retention and rollback plan.

### Deletion gates

Every deletion PR must stop if any candidate is imported by V2, paper,
literature, Candidate/revision, settings/provider, or Project/version code.
It must also verify that no controller, WebSocket registration, scheduled
worker, Spring bean, or frontend client still calls the removed path. Use
focused compile and behavior tests for the affected boundary; do not treat a
class name containing `Agent`, `Plan`, or `V1` as sufficient deletion proof.

## V1 frontend entry-point retirement

- Issue: `#110`.
- Status: `RETIRED_AFTER_V2_CUTOVER`.
- Removed entry points: `ChatPage`, `/chat`, both browser V1 message clients,
  V1 Project WebSocket usage, legacy Plan controls/clients, and the dead
  Project-page message/Plan/context-debug presentation path.
- Removed superseded explicit forms: the standalone browser literature,
  Project read-analysis, and Project Candidate form/polling clients. Their
  product capabilities and backend services remain available to the unified
  natural-language V2 Agent; this deletion removes only unreachable browser
  adapters and tests.
- Default navigation: authenticated root, guest completion, demo completion,
  brand navigation, admin denial, and unknown routes now enter `/projects`.
- Preserved frontend capabilities: Project/session management, the persisted
  natural-language V2 task list, collapsed execution Steps, file preview and
  search, Candidate review/automatic validation/confirmation validation,
  explicit Candidate apply, immutable revisions/rollback/export, paper,
  knowledge base, memory, settings/provider configuration, and admin pages.
- Backend and data boundary: this frontend retirement does not delete any
  controller, service, WebSocket registration, database schema, historical
  message, Plan, Receipt, or user data. Backend retirement remains a separate
  call-graph-gated change.

## V1 backend orchestration retirement

- Issue: `#112`.
- Status: `RETIRED_AFTER_V2_CUTOVER`.
- Removed entry points: the legacy session-message REST endpoints, Project
  message/Plan/evidence endpoints, the old Plan controller, and both V1 chat
  WebSockets. The remaining `/api/v1` URI prefix is the public API version and
  does not imply that the retired Agent runtime is still active.
- Removed orchestration: the old planner, strategy/router/runtime coordinator,
  completion/reflection/verifier/final-synthesis chain, controlled-worker
  runtime, Project runtime facade, legacy LangChain4j tool-calling binding,
  and Plan-owned sandbox authority/outbox/projection workers.
- Split stable capabilities: `AgentSessionService` now owns authenticated
  session persistence and deletion; `ProjectSessionService` binds Project
  ownership to session creation/listing; `ModelInvocationContext` carries only
  provider endpoint credentials and trace metadata for shared model transport.
  These replacements do not dispatch V1 messages or construct a legacy Plan.
- Preserved product capabilities: V2 intake/history/adaptive execution and
  final synthesis; session/message entities needed by V2; summaries, memory,
  RAG/context, settings/provider/user-key resolution; Project files, versions,
  Candidate validation/apply and revisions; paper/literature services; reusable
  capability executors; and the shared E2B broker, V2 Workspace and sandbox
  composer.
- Preserved data: all migrations, legacy Plan/message/Receipt/event tables and
  existing user rows remain intact. No cleanup migration or bulk data deletion
  is part of this retirement.
- Verification boundary: production and test compilation must succeed with no
  reference to the removed runtime or WebSocket package; V2 controller,
  intake/history/adaptive execution, Candidate, Project, provider, and sandbox
  focused tests remain the regression authority. The deterministic release-gate
  list now names V2/shared tests rather than deleted V1 tests.
- Excluded: adding or migrating tools, changing V2 routing/reflection semantics,
  changing Candidate apply authority, deleting historical schema/data, and
  unrelated frontend layout changes.

## Unified V2 product tool catalog and parameter schemas

- Status: `V2_PRODUCT_CONTRACT`.
- Assessed entries: the stable V2 `ToolDescriptor`, product provider adapter,
  natural-language planner aliases, autonomous Step tool selection, and the
  product effect allowlist.
- Catalog: one product-owned ordered catalog defines the five current tools:
  `literature.search`, `project.read`, `project.search`,
  `project.candidate.compose`, and `sandbox.execute`. It is the single source
  for internal IDs, public planner aliases, model descriptions, required
  capabilities, and machine-readable parameter schemas.
- Provider boundary: the provider adapter maps the framework-neutral schema
  into the existing product `ToolSpec`. Product tools use strict object
  schemas with explicit required fields, types, sizes/ranges, and
  `additionalProperties: false`; the former arbitrary-object placeholder is
  no longer sent to the model.
- Compatibility: the stable descriptor retains its three-argument constructor
  with an explicit permissive object schema for existing non-product callers.
  No reverse dependency from `agent-v2` to product/Jackson code is added.
- Verification: a pure in-memory catalog harness checks valid and invalid
  arguments for every current tool. Provider, planner, autonomous-loop, and
  effect-allowlist tests need no frontend, real model, Project, or Sandbox.
- Authority: schemas guide model output and enable fast contract testing; they
  are not identity or permission authority. Existing authenticated Plan/Step,
  ToolCall, lease/fence, Workspace, Candidate, ProjectVersion, and executor
  validation remains the final deterministic boundary.
- Excluded: new paper tools, public debug endpoints, V1 tool-loop migration or
  deletion, database changes, effect semantic changes, real Provider/E2B
  calls, Candidate apply, and frontend changes.

## V2 Project BibTeX audit

- Issue: `#114`.
- Status: `REUSE_BEHAVIOR_WITH_WORKSPACE_ADAPTER`.
- Assessed entry: `ProjectBibtexAuditToolExecutor` and the frozen
  `project_bibtex_audit` research contract.
- Reuse decision: preserve the existing first-version issue taxonomy
  (`DUPLICATE_KEY`, `MISSING_REQUIRED_FIELD`, `UNUSED_ENTRY`, and
  `MISSING_CITATION_KEY`) and its bounded, deterministic parsing policy. Do
  not invoke the old executor from V2 because it depends on
  `ToolExecutionContext`, `ToolRegistry`, and current `ProjectService` reads.
- V2 identity: `project.bibtex.audit`, with public planner alias
  `project_bibtex_audit`. The strict schema accepts one to twenty unique
  normalized Project-relative `.bib` or `.tex` paths and an optional
  `includeUnusedEntries` boolean.
- Authority: the natural-language persistent Plan, current ACTIVE Step,
  ToolCall, fenced lease, frozen ProjectVersion, confirmed execution context,
  and authenticated Workspace are revalidated before any read. Model paths
  carry no user, Project, version, Workspace, or host-path authority.
- Result: one bounded structured Receipt contains parser metadata, inspected
  Project-relative paths, summary counts, and issue locations. It never
  returns host paths or complete source files and performs no Workspace or
  Project mutation.
- Excluded: document/PDF/DOCX extraction, table or image inspection, DOI or
  citation-network verification, Project/Candidate writes, frontend changes,
  database schema, V1 tool-loop restoration, and LangChain4j annotation
  registration. Later tools require an explicitly scoped Issue/PR; four to
  five closely related tools may share one when their authority, side-effect,
  runtime, and verification boundaries are identical.

## V2 read-only Project analysis tool bundle

- Issue: `#116`.
- Status: `REUSE_BEHAVIOR_WITH_WORKSPACE_ADAPTER`.
- Grouping decision: this Issue contains four capabilities because they share
  the same read-only frozen-Workspace authority, catalog, Receipt, and test
  boundary. Each capability retains its own descriptor, strict schema,
  implementation, parser identity, budgets, failure behavior, and tests.
- `ProjectLatexOutlineToolExecutor`: reuse the conservative line-oriented
  extraction semantics for sections, labels, citations, floats, and optional
  formula references. Do not invoke the old executor because it depends on
  `AbstractResearchProjectToolExecutor`, `ToolExecutionContext`, and
  `ProjectService`; the V2 implementation reads only the confirmed Workspace
  and explicitly does not expand includes or claim a complete LaTeX AST.
- `ProjectCodeSymbolsToolExecutor`: reuse the conservative Java, Python, and
  MATLAB symbol/dependency parsing policy. Do not reuse its V1 execution
  context or claim type inference or a complete call graph. The V2 Receipt
  reports bounded symbol, parameter, dependency, path, and line facts.
- `ProjectExperimentSummaryToolExecutor`: reuse the observation-only policy
  for simple CSV, top-level JSON, simple top-level YAML, and bounded text
  reports. The V2 adapter distinguishes parse failure from valid observed
  values and never presents malformed structured input as a valid summary.
- `ProjectCrossMaterialSearchToolExecutor`: reuse deterministic literal
  matching and the rule that a cross-material link requires matching evidence
  from at least two distinct Project-relative paths. The V2 implementation is
  not semantic/vector/RAG/network search and exposes no host paths.
- V2 identities: `project.latex.outline`, `project.code.symbols`,
  `project.experiment.summary`, and `project.cross-material.search`, with the
  existing public underscore aliases. All require `READ_PROJECT`, `TOOL_USE`,
  and `PROJECT_FILE_ACCESS` through the unified product catalog.
- Catalog adaptation: routing requirements, execution capabilities, and the
  effect target are catalog metadata. Natural-language bootstrap and loop
  dispatch derive from that metadata so a newly registered alias cannot be
  rejected solely because a second hardcoded switch was not updated.
- Authority: authenticated user/turn, persistent Plan, current ACTIVE Step,
  ToolCall, fenced lease, frozen ProjectVersion, confirmed execution context,
  and Workspace are revalidated before reading. Model arguments never carry
  identity, Project, version, lease, Workspace, or host-path authority.
- Result: each successful effect produces a bounded deterministic structured
  Receipt suitable for reflection and final synthesis. No tool mutates the
  Workspace, Candidate, ProjectVersion, revision, or database.
- Excluded: PDF/DOCX/OCR/chart extraction, DOI verification, semantic search,
  network access, Project/Candidate writes, Sandbox execution, frontend
  changes, schema changes, V1 orchestration restoration, and LangChain4j
  annotation registration.

## V2 read-only paper-quality audit tool bundle

- Issue: `#118`.
- Status: `REFERENCE_BEHAVIOR_WITH_WORKSPACE_ADAPTER`.
- Grouping decision: five tools share one authenticated frozen-Workspace,
  read-only, deterministic parser, structured Receipt, and focused verification
  boundary. They retain separate schemas, parser identities, budgets, failure
  codes, and behavior tests.
- Assessed product entries: `LatexParserService`, `LatexMaskingService`,
  `PaperFinalAuditService`, and the protected-token checks in
  `PaperSectionPolishService`. Their useful concepts are conservative LaTeX
  token recognition, float metadata, and preservation checks. The V2 tools do
  not invoke or copy their paper-task orchestration, task entities, model calls,
  repair flow, or direct source-storage path.
- New V2-only behavior: acronym definition/casing observations and descriptive
  prose statistics have no legacy executor to migrate. They are implemented as
  bounded product tools over the same V2 Workspace authority and make no
  semantic correctness, grammar, or publication-quality claim.
- V2 identities: `project.latex.crossref.audit`,
  `project.latex.float.audit`, `project.latex.protected.inventory`,
  `project.paper.acronym.audit`, and `project.paper.language.stats`, with
  matching underscore planner aliases. All require `READ_PROJECT`, `TOOL_USE`,
  and `PROJECT_FILE_ACCESS`, and dispatch through the catalog's Project target.
- Cross-reference audit reports duplicate labels, unresolved references, and
  optional unreferenced labels. Float audit reports figure/table captions,
  labels, references, and existence of normalized local graphics paths; it
  does not expand includes, macros, or `graphicspath`, or inspect image bytes.
- Protected inventory returns citation/reference/label/environment identifiers
  plus optional hashes and lengths for line-local math, never math content.
  Its deterministic inventory fingerprint is suitable for comparing accepted
  facts across Plan Steps, but it does not itself decide whether a Candidate is
  safe or apply one.
- Acronym audit reports local definitions, uppercase use before definition,
  undefined uppercase uses, differing local definitions, and observed casing
  variants. Language statistics report character, word-like-unit, sentence,
  paragraph, section, and long-sentence location facts after conservative
  markup removal. Both explicitly remain heuristics.
- Authority: authenticated user/turn, persistent Plan, current ACTIVE Step,
  ToolCall, fenced lease, frozen ProjectVersion, confirmed execution context,
  and Workspace are revalidated before reading. Model paths and options carry
  no identity, permission, or host-path authority.
- Excluded: semantic peer review, grammar rewriting, novelty assessment,
  PDF/DOCX/OCR or image-content analysis, network/RAG/literature retrieval,
  Workspace writes, Candidate creation/apply, ProjectVersion mutation, Sandbox
  execution, schema/frontend changes, V2 core changes, and V1 orchestration.

## Workspace chat corrective restoration

- Issues: `#120` and `#121`.
- Status: `RESTORE_LEGACY_WORKSPACE_SURFACE_WITH_V2_BOUNDARY`.
- Product decision: restore the workspace `/chat` page and the legacy Agent/Plan
  orchestration it requires after their accidental removal. This is a directed
  corrective restoration, not a precedent for copying the orchestration into
  V2 runtime code.
- `#120` is the mechanical recovery baseline: deleted workspace frontend,
  backend, and focused test sources were restored from their deletion parents
  without semantic edits.
- `#121` adapts that baseline to current `main`: `/chat` is again the default
  authenticated workspace, its HTTP and WebSocket contracts are active, and
  the restored model adapter accepts the legacy runtime request without
  replacing the current V2 invocation context.
- Boundary: `ProjectPreviewPage.vue` remains V2-only. The old Project V1 input,
  V1/V2 switch, and Project WebSocket route are not restored. Current V2
  natural-language history, Candidate/Workspace authority, and the V2
  product tool catalog remain authoritative and unchanged.
- Data boundary: no schema or data migration, deletion, or backfill is added.

## Immutable binary Project assets for V2 Workspace source

- Issue: `#123`.
- Status: `NEW_PRODUCT_SOURCE_BOUNDARY`.
- Scope: the authoritative Project manifest may now admit bounded, validated
  `.pdf`, `.docx`, and `.xlsx` assets alongside existing text files. PDF is
  checked by signature; DOCX/XLSX are checked as bounded OOXML containers with
  safe entry names and the required package parts. Unsupported or disguised
  binary files remain outside the Project cut.
- Version authority: admitted binary bytes contribute path, size, and SHA-256
  to the same existing `ProjectVersion` identity. Local and MinIO-backed
  Projects enforce the same policy and recheck the complete manifest before
  and after materialization.
- V2 adaptation: `ProductProjectVersionSource` uses a product-internal raw-byte
  materialization method and passes exact bytes into the stable V2
  `ProjectVersionSource`. The method has no Controller and exposes neither host
  paths nor object keys.
- Compatibility: existing Project `readFile` and literal search stay
  text-only. Existing sandbox dispatch stays a text map. Binary assets are
  preserved when a revision is created but cannot be selected, validated, or
  applied as Candidate text, including previously persisted Candidate facts.
- Reuse decision: retain the current Project ownership, path policy, traversal
  budgets, manifest identity, object storage, V2 Workspace, Candidate gate,
  and immutable revision services. Do not introduce a second asset table,
  version model, Workspace provider, or Candidate format.
- Excluded: PDF/DOCX/XLSX parsing, OCR, image inspection, tool registration,
  network access, frontend changes, schema changes, and legacy `/chat`
  orchestration. Those remain separate capabilities; Issue `#122` consumes
  this source boundary for bounded document and spreadsheet inspection.

## V2 bounded document and spreadsheet inspection tools

- Issue: `#122`.
- Status: `NEW_READ_ONLY_WORKSPACE_TOOLS`.
- Grouping decision: `project.document.extract` and
  `project.spreadsheet.inspect` share the authenticated frozen-Workspace,
  read-only binary source, structured Receipt, parser-budget, and focused test
  boundary. They remain separate descriptors, schemas, parsers, failure codes,
  and result shapes.
- Document behavior: one exact `.pdf` or `.docx` path is parsed with bounded
  input, locations, text, and metadata. PDF locations are pages; DOCX locations
  are paragraphs and table cells. OCR, image extraction, external-resource
  loading, knowledge-base ingestion, and network access are not invoked.
- Spreadsheet behavior: one exact `.xlsx` path returns bounded sheet metadata,
  dimensions, headers, typed cell samples, and formula-presence observations.
  Formulas are never evaluated, macros are never executed, and external links
  are never resolved. CSV, JSON, YAML, text, Markdown, and logs remain owned by
  `project.experiment.summary`.
- Selection behavior: the product catalog descriptions give positive and
  negative examples for ordinary `project.search` versus cross-file proof via
  `project.cross-material.search`, and for LaTeX outline, cross-reference,
  float, and protected-inventory tools. These examples guide the model and add
  no semantic routing rules or second tool registry.
- Authority: authenticated user/turn, persistent Plan, ACTIVE Step, ToolCall,
  fenced lease, frozen ProjectVersion, confirmed Workspace, immutable effect
  claim, Receipt, and replay remain unchanged. Model arguments contain only
  bounded paths and parser options.
- Excluded: Workspace, Candidate, ProjectVersion, revision, or database writes;
  OCR, image understanding, formula evaluation, macro execution, external-link
  resolution, frontend changes, schema, Sandbox, RAG, paper-polish, legacy
  `/chat`, V2 core changes, or LangChain4j annotation registration.
