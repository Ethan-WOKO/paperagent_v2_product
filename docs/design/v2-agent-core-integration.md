# V2 Agent Core Integration

Status: **CURRENT**

## Decision

This repository is the PaperAgent product repository. It keeps the mature
Yanban application shell—including the Vue UI, paper editing, literature
retrieval, knowledge, authentication, project data, and deployment—and imports
the independently developed V2 agent core under `agent-v2/`.

The initial import is pinned to:

- product base commit:
  `074b3ae179282f1ce264aa7e574ece4d15e02f46`;
- V2 source commit:
  `ad7eff6c7b23de64bbd69e42e583340b5c3c2499`.

This decision supersedes older documents where they propose evolving the
legacy `PlanAgentService` into the target runtime.

## Module boundary

`agent-v2/pom.xml` is an isolated nested parent and aggregator containing only:

```text
agent-v2/
├── agent-contracts
├── agent-runtime
├── agent-persistence
├── agent-workspace
├── agent-sandbox
└── agent-providers
```

The product root reactor aggregates `agent-v2`. This establishes build
composition only. It does not route a Controller, API request, Provider,
database, Sandbox, or Workspace operation into V2.

The imported production and test source trees are copied from the frozen V2
commit without behavioral edits. The only source-build adjustment is the new
nested parent POM, whose module list excludes V2 application and end-to-end
modules.

## Dependency direction

Allowed:

```text
Yanban product API / future adapter
                  |
                  v
       V2 contracts and runtime ports
```

Forbidden:

```text
V2 core -> com.yanban
V2 core -> Spring Controller
V2 core -> product persistence entity
V2 core -> concrete Provider or database
V2 core -> legacy PlanAgentService / planner / completion verifier
```

Provider, persistence, Workspace, and Sandbox integrations must implement V2
ports from product-side adapter modules in later Issues. Any need for a reverse
dependency is a stop condition, not a reason to weaken the core.

## Frozen runtime authority

- Top-level modes are only `DIRECT` and `PERSISTENT_PLAN_EXECUTE`.
- Project, tool, execution, network, or modification requests enter a
  persistent Plan.
- TaskFrame freezes objective, objects, deliverables, constraints,
  ProjectVersion, and permission tier.
- Plans may be revised, but completed authoritative facts are append-only.
- Agents modify an isolated Workspace. The original ProjectVersion changes
  only after the user accepts the diff.
- Workspace diff, execution receipts, and event logs are result facts.

## Migration sequence

Each later capability uses a separate Issue and Draft PR:

1. product identity, session, Project, and TaskFrame adapters;
2. persistence, Workspace, Provider, and Sandbox adapters;
3. compatibility API cutover with focused end-to-end acceptance;
4. retirement of the legacy agent core only after the new path is accepted.

The existing UI, paper, literature, knowledge, auth, schema, deployment, and
legacy runtime behavior remain unchanged during this import.

## Verification policy

For the initial import, verification is deliberately limited to the affected
boundary:

1. byte-for-byte provenance of imported production and test trees;
2. the complete six-module V2 core test reactor;
3. product-root Maven composition for `agent-v2`;
4. a static ban on `com.yanban` dependencies in V2;
5. owned-path and repository hygiene checks.

The full product, paper, knowledge, and frontend suites are not required
because this change does not alter those sources or activate V2 at runtime.
Future adapter and cutover Issues must add focused product tests for the paths
they change.

## Product TaskFrame intake boundary

`yanban-agent-v2-adapter` is the first product-side adapter. It depends one way
on `yanban-core`, V2 contracts, and V2 runtime. It does not activate or persist
the runtime and has no Spring, JPA, Controller, database, or authorization
lookup.

The TaskFrame intake accepts an already authenticated and authorized
`AgentRunIdentity`, an optional frozen product version, a V2 routing decision,
a V2 TaskFrame draft, an execution profile, and a caller-owned creation time.
It performs only fail-closed consistency checks on these product facts:

- user IDs are positive and optional session IDs are positive when present;
- workspace runs have neither a project nor a project version;
- project runs have a positive project ID and a nonblank frozen version ID.

For a consistent request, the adapter maps the product project/version pair to
`ProjectVersionRef`, derives the opaque TaskFrame ID as `product.` plus the
lowercase SHA-256 of the UTF-8 product `runId`, and delegates persistent-route
and canonical TaskFrame validation to `DeterministicTaskFrameFreezer`. The
result retains the unchanged product identity alongside the canonical
TaskFrame. The adapter catches or translates none of the typed V2 validation
failures.

Additional intake sources, runtime bootstrap, V2 persistence, Workspace,
Provider, Sandbox, API routing, and legacy retirement remain later Issue
boundaries.

## Authenticated Agent turn context boundary

The first concrete product intake source resolves a persisted `AGENT_TURN`
through the product-side `yanban-api` module. The resolver accepts only the
authenticated user ID and turn ID. It loads the turn and session with
owner-qualified repository queries, validates their internal identity and
scope facts, and constructs an `AgentRunIdentity` only after those checks pass.

Workspace sessions must not carry a Project and return no ProjectVersion.
Project sessions must carry a positive Project ID and reuse
`ProjectService.manifest(userId, projectId)` for both product ownership and the
current immutable manifest version. The resolver does not read Project content
through any other path and propagates existing `ProjectService` failures
unchanged.

This boundary returns verified product facts for the deterministic TaskFrame
adapter. It does not construct a TaskFrame draft or routing decision, activate
the V2 runtime, persist V2 state, expose an endpoint, or change any V2 module.

## Product Plan bootstrap persistence boundary

The first production V2 persistence adapter lives in product-side
`yanban-api` and implements only `PlanBootstrapRepository`. Migration V42 owns
an independent `agent_v2_plan_bootstraps` table; it neither reads nor changes
the legacy Plan tables.

Each row atomically stores one canonical TaskFrame, initial Plan, and version-1
initial Checkpoint as an explicit format-1 JSON document. Collection fields
with set or map semantics are sorted before encoding, and the UTF-8 document is
protected by a lowercase SHA-256 digest. Every replay verifies the format and
digest, reconstructs the aggregate through V2 constructors, and verifies that
the reconstructed document is exactly canonical. Integrity failures expose no
stored payload content.

Plan ID and TaskFrame ID uniqueness provide the two bootstrap authorities.
Exact tuples replay, same-Plan changes conflict at `plan.id`, and a different
Plan claiming an existing TaskFrame fails as bootstrap partial state. Inserts
run in their own transaction; after a unique-key race has rolled back, winner
classification runs through a fresh read transaction.

This adapter does not authorize or route requests, compose bootstrap inputs,
start execution, update Plans, or implement any later read/update persistence
port. Authenticated composition is described below; execution activation
remains a later Issue boundary.

## Authenticated persistent Plan bootstrap composition boundary

The first internal product bootstrap path composes an already-authenticated
Agent turn without exposing an API or starting execution. It resolves the turn
through the owner-qualified `AgentTurnProductContextResolver` before adapting
any V2 request. The caller supplies only the routing decision, TaskFrame and
initial Plan drafts, execution profile, and the three creation instants;
identity and ProjectVersion come exclusively from the resolver.

The pure product adapter now separates TaskFrame preparation from freezing.
Existing TaskFrame binding delegates to that preparation, preserving its
validation and deterministic TaskFrame ID behavior. The bootstrap request
adapter derives stable, domain-separated IDs from the verified product
`runId`: `product-plan.` plus SHA-256 of `plan\0` and the run ID, and
`product-revision.` plus SHA-256 of `revision-1\0` and the run ID.

Spring configuration wires the deterministic TaskFrame, initial Plan, and
initial Checkpoint freezers through `DefaultPersistentPlanBootstrapper` to the
product `PlanBootstrapRepository`. The composer delegates exactly once and
returns the persistence result unchanged. Nonpersistent routing therefore
fails through the existing V2 typed validation before persistence.

This boundary does not generate routing or Plan content, read Project files,
call a Provider, start the Agent loop, execute a tool, expose a Controller, or
cut over legacy traffic. API activation, execution start, Workspace, Provider,
Sandbox, recovery, and legacy retirement remain later Issue boundaries.

## Product Plan lease persistence boundary

The product database implements only the stable V2 `LeaseRepository` through
an independent `agent_v2_plan_leases` table. Every acquisition generation is
retained. A release marks the current generation and a renewal updates only its
expiry; the next acquisition after release or expiry appends exactly the next
fencing token. Lease tokens are globally unique and can never be reused.

Every operation locks the existing V2 bootstrap row for its Plan in a new
transaction before observing one trusted UTC persistence time and reading the
current generation. This serializes same-Plan authority without coupling the
V2 core to Spring or the product schema. Cross-Plan token uniqueness remains a
database authority: after an insert constraint race rolls back, a fresh
transaction confirms token ownership before classifying the loser.

The adapter does not start execution, mutate a Project, expose an API, run a
cleanup scheduler, or read legacy Plan or lease tables. Execution-start
persistence and composition remain later Issue boundaries.

## Product execution-start persistence boundary

The product database implements only the stable V2
`ExecutionStartRepository`. One permanent `agent_v2_execution_starts` row is
the immutable authority for the sequence-1 start event, checkpoint version 2,
and start marker. The row stores separately versioned canonical request and
result documents protected by SHA-256, while extracting the Plan, event, lease
owner, fence, and commit time needed for database authority and inspection.

Every first-start attempt locks the existing V2 bootstrap row in a new
transaction. A permanent exact replay is returned before observing time or
consulting mutable lease state. A new start decodes and verifies the bootstrap,
observes one trusted microsecond persistence time, validates the current lease
and frozen NOT_STARTED-to-ACTIVE transition, and inserts the authority row
atomically. Same-Plan contenders serialize through the bootstrap lock;
cross-Plan event-id races are classified only after the losing transaction
rolls back and a fresh transaction confirms the winner.

This adapter does not create a generic event or checkpoint store, start a
Runtime loop, call a Provider, reserve a Workspace, execute a tool, expose an
API, mutate a Project, or read legacy execution tables. Fresh-start Runtime
composition and later execution persistence remain separate Issue boundaries.

## Authenticated fresh execution-start composition boundary

The first internal product fresh-start path resolves the authenticated Agent
turn before adapting and persistently bootstrapping it. A narrow command holds
only the existing bootstrap command and an optional stable fresh-start
attempt; it has no identity, Project path, Provider, tool, Sandbox, or second
authority source. The exact bootstrap persistence result and caller-owned
attempt are passed unchanged to the stable Runtime starter.

Spring exposes one product `FreshExecutionStarter`, composed from the
deterministic fresh-execution gate and start materializer with the relational
lease and execution-start repository ports. A newly applied bootstrap may
acquire one fenced lease and atomically persist the start marker. A replayed
bootstrap requires recovery before lease acquisition, while rejected
bootstrap, lease, and start outcomes retain their stable Runtime identities.
The product composer does not catch, remap, retry, release, or repair them.

This boundary activates no Controller or traffic, starts no Agent step or
loop, derives no lease/event authority, and implements no recovery. Those
remain later, independently frozen Issue boundaries.

## Product execution-start recovery inspection boundary

The product database now implements the stable V2
`ExecutionStartRecoveryRepository` as a read-only view over the existing
canonical Plan-bootstrap and atomic execution-start authorities. Inspection
locks the bootstrap authority for a transactionally consistent cut shared
with atomic start: a concurrent start is observed either before it commits as
`PersistedExecutionStartReady` or after it commits as
`PersistedExecutionStartCommitted`, never between its internal statements.

READY contains the exact canonical bootstrap and its unchanged Plan. COMMITTED
additionally verifies both canonical execution-start documents against every
extracted Plan, TaskFrame, event, owner, fence, checkpoint, and row binding
before returning the persisted start. Missing authority is distinguished from
invalid input; any occupied incomplete, corrupt, cross-bound, or non-canonical
cut fails closed through the single sanitized execution-recovery partial-state
failure.

Inspection never evaluates or mutates a lease, creates or updates a row,
observes time, retries, sleeps, or performs recovery. This is only the current
pre-step relational cut. Runtime recovery composition and later Plan,
execution-context, step, Workspace, Provider, Sandbox, loop, API, and traffic
boundaries remain separate Issues.

## Authenticated execution-start recovery composition boundary

The internal authenticated recovery path resolves the owner-qualified Agent
turn before deriving its Plan identity. Bootstrap adaptation and recovery use
one pure product PlanId derivation, preserving the existing domain-separated
`product-plan.` format and preventing callers from supplying a second Plan
authority. The caller command contains only an optional stable fresh-start
attempt.

The composer passes the exact derived PlanId and optional attempt to the stable
`ExecutionStartRecoverer`. Spring wires one recoverer from the deterministic
recovery-ready materializer and the relational recovery-inspection, lease, and
atomic-start ports. A READY bootstrap may retain a fenced lease and persist the
atomic start; a later request without an attempt observes the committed start
without another write. Stable rejected, advanced, retry-required, validation,
protocol, and reconciliation results are neither caught nor remapped.

This path exposes no Controller or traffic, schedules no retry, starts no
execution step or loop, reads no Project content, and introduces no Provider,
Workspace, Sandbox, tool, or legacy Agent dependency. Those remain later
Issue boundaries.

## Product Plan execution-context persistence boundary

The product database implements the stable pre-step
`PlanExecutionContextRepository` through V45. One
`agent_v2_plan_execution_contexts` row durably owns a Plan's canonical
Workspace reservation and the globally unique Workspace ID; the same row may
later append the canonical confirmation without rewriting reservation owner
or fence authority.

Reservation, confirmation, and inspection serialize on the existing Plan
bootstrap row. They accept only a canonical source-backed bootstrap plus its
committed sequence-1 execution start and version-2 checkpoint. Reservation
binds the requested materialization specification to the authoritative source
ProjectVersion and current revision/head expectations, then validates the
current lease token and fence. Confirmation requires the exact reservation
and may use a valid takeover lease while retaining both generations of owner
and fence authority.

All four request/result documents use deterministic format-1 JSON protected
by SHA-256 and are cross-checked against extracted relational columns. Exact
reservation and confirmation replays are permanent and precede mutable lease
checks. Inspection is read-only, and incomplete, digest-invalid,
cross-bound, or structurally corrupt occupied cuts fail closed through the
single sanitized Plan execution-context partial-state failure. Database
constraints arbitrate concurrent same-Workspace contenders; no production
sleep, spin, or retry is used.

This boundary describes Workspace intent only. It does not read or
materialize a Workspace, activate a step, compose Runtime context, call a
Provider/Sandbox/tool, expose an API, mutate a Project, or read legacy Agent
tables. Those remain later Issue boundaries.

## Authenticated product Project-version source boundary

The product now exposes one owner-qualified immutable Project cut through the
stable V2 `ProjectVersionSource` contract. A product-side factory first resolves
the authenticated Agent turn through `AgentTurnProductContextResolver`; only a
verified Project-backed turn can bind the resolver's exact user, Project, and
frozen version authority. Workspace-scoped turns fail closed before any Project
access.

An exact source load reuses the existing admitted-text
`ProjectService.manifest/materializeSandbox` behavior. This reuse decision is
`REUSE_WITH_ADAPTER`: the adapter cross-checks the product manifest, sandbox
snapshot, and returned text map as one project/version/path/size/lowercase
SHA-256 cut, then maps UTF-8 content bytes into a sorted, defensive V2 snapshot.
Changed references and corrupt or incomplete cuts fail through sanitized
Workspace errors. Existing Project authorization, storage, and runtime failures
propagate unchanged; there is no cache, retry, fallback authority, host path,
or direct file read.

This boundary creates no Workspace and activates no Runtime composition,
Controller, API, step, Provider, Sandbox, tool, or Project mutation. Binary and
general Project-source expansion, real Workspace materialization, and Runtime
execution-context composition remain later Issues. No legacy Agent planner,
executor, verifier, or service code is reused.

## Local Workspace restart-recovery boundary

The generic local Workspace provider can recover an exact, completely
published Workspace after provider or process recreation. Recovery is entered
only through `inspectMaterialization` with the frozen materialization
specification. It serializes through the existing provider-root and Workspace
claim, verifies the canonical published container without following links,
loads the exact source Project version once, and reuses the existing manifest,
limit, path, collision, hash, and fingerprint validation.

Adoption is deliberately read-only on disk. The published `data` tree must
match the source manifest byte for byte, `staging` must be empty, and no
pending, extra, missing, linked, ambiguous, or partially published entry is
accepted. A successful inspection restores only the in-memory active
registration, original baseline hashes, opaque Workspace reference, and source
manifest fingerprint. Exact re-inspection then replays that fact without
reloading the source.

Fresh materialization still rejects every occupied final or pending path.
Recovery neither creates a marker nor repairs, deletes, takes over, scans for,
or mutates an occupied tree. Product Workspace configuration, authenticated
source binding, persistent execution-context Runtime composition, steps,
Provider/Sandbox/tool execution, API/UI activation, and legacy retirement
remain later Issue boundaries.

## Authenticated local Workspace boundary

The product exposes one authenticated factory that returns the stable V2
`WorkspacePort`. It first binds an owner-qualified Project source through
`AuthenticatedAgentTurnProjectVersionSourceFactory`, then constructs a private
`LocalWorkspaceProvider` at one API-owned root configured by
`yanban.agent.v2.workspace.root`. The default is
`data/agent-v2-workspaces`, with an optional
`YANBAN_AGENT_V2_WORKSPACE_ROOT` override. Relative configuration is resolved
once against the API process working directory; host paths are never returned,
logged, or included in configuration failures.

Each call creates an independently source-bound provider while reusing the
same resolved root. Creation does not load Project files or perform a
Workspace operation. Provider recreation can therefore use the existing
exact-spec inspection contract to adopt a fully published Workspace without
introducing a global Project source or a second identity authority.

This boundary does not compose persisted Plan execution context, expose an
API, start a step or loop, call a Provider/Sandbox/tool, mutate a Project,
define cleanup policy, or change a V2 contract. Those remain later Issues.

## Authenticated persisted Plan execution-context composition boundary

The internal product composition path now resolves an owner-qualified Project
turn exactly once before consulting any context, Workspace, or persistence
collaborator. That single verified product context supplies the Plan identity,
the Project version, and a domain-separated Workspace identity:
`product-workspace.` plus the lowercase SHA-256 of UTF-8 `workspace\0` and the
product run ID. The caller contributes only an optional stable lease attempt.

For a context that is canonically absent, the product proposes one
materialization specification using a snapshot of the configured Project file,
aggregate, and count limits. A read-only product preflight shapes only this
optional proposal; the stable V2 composer re-inspects and arbitrates all
authority. If a reservation or confirmation already exists, no current-limit
proposal is supplied, so the persisted exact specification remains
authoritative across product configuration changes and provider recreation.
Partial, rejected, cross-bound, or protocol-invalid preflight state cannot
authorize initialization.

The existing authenticated Project-source and local Workspace factories have
package-private trusted-context paths for this composition only. Their public
owner-qualified entry points retain their previous behavior. The composer
creates one source-bound Workspace port from the already verified context and
delegates once to `DefaultPlanExecutionContextComposer` with the relational
execution-start, context, and lease repositories. V2 outcomes and repository
or Workspace failures are returned or propagated unchanged.

This boundary activates no Controller or traffic, starts no step or Agent
loop, performs no Provider, Sandbox, or tool call, mutates no Project, adds no
schema, and changes no V2 core contract. Those remain later Issues.

## Product first-Step activation persistence boundary

The product database implements the stable V2 `StepActivationRepository`
through V46. Each immutable activation row is keyed by its globally unique
activation event and extracts the Plan, Step, committed H0 source, activated
checkpoint head, lease owner, and fence from canonical format-1 request and
result documents protected by lowercase SHA-256. The schema remains capable
of holding later activation facts, while this boundary admits exactly one
first activation per Plan.

Every attempt locks the Plan bootstrap authority. A permanent exact replay is
validated and returned before consulting mutable lease expiry. A new
activation reconstructs the canonical bootstrap plus committed sequence-1,
checkpoint-version-2 execution start. Project-backed tasks additionally
require the exact confirmed execution context; source-less tasks require no
context. One trusted database time observation validates the current lease
before the deterministic NOT_STARTED-to-ACTIVE checkpoint transition is
appended atomically.

Concurrent same-Plan attempts serialize through the bootstrap lock, and the
event primary key arbitrates cross-Plan event-id races. Occupied multiple or
advanced activation cuts, malformed documents, digest or extracted-column
mismatches, and cross-bound source/context facts fail closed without updating
prior authority. This boundary does not compose or execute a Step, call a
model, Provider, Sandbox, or tool, expose an API, mutate a Project or
Workspace, or implement completion, interruption, or recovery.

## Authenticated first-Step activation composition boundary

The internal product composition path now resolves one owner-qualified Agent
turn before validating caller activation material or consulting persistence.
The verified run identity alone derives the Plan identity. The caller supplies
only one stable Step ID and one complete, caller-owned activation attempt; it
cannot override Plan, TaskFrame, Project, Workspace, or lease authority.

One read-only execution-start inspection must yield the exact committed H0
cut for the derived Plan. Missing, ready-only, rejected, contradictory,
malformed, or cross-bound results fail closed before lease acquisition,
materialization, or activation. A valid cut is delegated unchanged, together
with the exact Step and attempt, to one stable `StepActivationComposer`.

Spring wires that composer as `DefaultStepActivationComposer` with
`DeterministicCommittedStepActivationMaterializer` and the product
`LeaseRepository` and `StepActivationRepository` adapters. Stable outcomes,
validation failures, protocol failures, persistence failures, and lease
dispositions are not translated, retried, released, or cleaned up here.

This boundary exposes no Controller or traffic, executes no Step, model,
Provider, Sandbox, or tool, reads no Project or Workspace files, and adds no
completion, interruption, recovery, replan, context creation, schema, or V2
contract behavior.

## Product active-Step recovery inspection boundary

The product database now implements the stable V2 `StepRecoveryRepository`
as one read-only relational cut over the existing bootstrap, committed
execution-start H0, optional confirmed Project execution context, and immutable
first-Step activation authorities. Every non-null inspection acquires the Plan
bootstrap inspection lock before reading the remaining Plan-scoped facts in the
same transaction.

Missing unoccupied Plans remain distinct from occupied partial state. Canonical
H0 without an activation is not yet eligible for active-Step recovery. A found
snapshot requires exactly one canonical activation linking checkpoint version
2/event sequence 1 to version 3/sequence 2, with only its selected Step moving
from `NOT_STARTED` to `ACTIVE`. Project-backed Tasks additionally require their
exact fully confirmed execution context; source-less Tasks require none.
Malformed documents, extracted-column or cross-authority mismatches, multiple
activation rows, orphan occupancy, and invalid transition structure fail
closed as partial state.

Inspection returns the exact immutable TaskFrame, current Plan, version-3
checkpoint, activation marker, and optional confirmed context. It neither
reads nor evaluates lease rows or time, so expired, deleted, or replaced
leases do not change recovery facts. The adapter performs no write, repair,
retry, cleanup, Project/Workspace access, execution, model, Provider, Sandbox,
tool, Controller, API, UI, schema, or legacy Agent operation.

## Authenticated active-Step recovery composition boundary

The internal product recovery handoff resolves one owner-qualified Agent turn
before validating caller recovery material. Its verified run identity alone
derives the Plan identity, and the command carries exactly one caller-owned
stable lease attempt. It cannot supply or override Plan, TaskFrame, Project,
Workspace, Step, checkpoint, activation, owner, token, or expiry authority.

The product delegates the exact derived Plan and exact lease attempt to one
stable `StepRecoverer`. Spring wires `DefaultStepRecoverer` directly from the
product `StepRecoveryRepository` and `LeaseRepository` implementations. Stable
recovery performs the read-only initial active-snapshot inspection, acquires
or replays one fenced product lease, re-inspects the active snapshot, and
returns the exact version-3 activation authority with the lease retained for
the later executor.

Authentication, validation, persistence, lease, and protocol outcomes remain
unchanged. The wrapper does not inspect independently, generate authority,
retry, sleep, release, repair, or clean up. It executes no Step and performs no
Project or Workspace access, file or network operation, model, Provider,
Sandbox, tool, Controller, API, UI, schema, or legacy Agent behavior.

## Product first active-Step interruption persistence boundary

The product database implements the stable V2
`StepInterruptionRepository` through V47. One immutable interruption row
records exactly one first active-Step `PAUSE`, `FAIL`, or `CANCEL` transition.
Its explicit kind, canonical format-1 request and result documents, extracted
authority columns, and lowercase SHA-256 digests bind the version-3,
sequence-2 activation cut to version 4, sequence 3.

Every attempt locks the Plan bootstrap authority before reconstructing the
canonical committed execution start, optional exact confirmed Project
execution context, and single first-Step activation. A permanent exact replay
is validated before mutable lease state or time. A new write observes one
trusted database time, validates the exact current lease and active Step, and
atomically appends only the matching terminal or paused Step and Plan state.
Same-Plan contenders serialize on the bootstrap row; the global event key and
unique Plan key arbitrate cross-Plan and cross-kind races. Corrupt, duplicate,
or cross-bound occupied cuts fail closed without changing prior authority.

This adapter does not execute a Step, release or retry a lease, persist an
effect or receipt, resume or recover execution, read or mutate Project or
Workspace files, call a model, Provider, Sandbox, network, or tool, expose a
Controller/API/UI path, or reuse a legacy Agent planner or service.

## Deterministic active-Step interruption materialization boundary

The V2 Runtime now has one pure materialization boundary between an already
recovered active Step and the stable interruption persistence requests. Its
only authority input is the exact `RecoveredActiveStep`; callers add one
explicit interruption kind, an event draft, and a checkpoint timestamp, but
cannot supply Plan, revision, Step, lease, fence, checkpoint-version, or event
sequence authority.

Materialization fails closed unless the recovered cut is the canonical
version-3, sequence-2 activation with exactly one eligible active Step and one
retained recovery lease. It deterministically derives sequence 3 and a
version-4 proposal that preserves the revision, peer states, receipts, and all
other immutable facts while changing only the target Step and Plan to the
matching paused, failed, or cancelled states. The proposal is validated
through `CheckpointValidators` and returned as exactly one typed stable
`StepPauseRequest`, `StepFailRequest`, or `StepCancelRequest`.

This boundary has no repository or other collaborator. It observes no time,
persists nothing, does not acquire or release a lease, and performs no Step,
completion, effect, receipt, retry, resume, repair, replan, Project,
Workspace, file, network, model, Provider, Sandbox, tool, product, API, UI, or
legacy Agent operation. Runtime interruption persistence composition remains
a later Issue.

## Active-Step interruption persistence composition boundary

The V2 Runtime now composes one already-recovered active Step into one atomic
interruption persistence call. The composer accepts the exact deterministic
materialization request, invokes its materializer once, and dispatches the
resulting typed request exactly once to `pause`, `fail`, or `cancel` on the
stable `StepInterruptionRepository`.

Applied and replayed results are committed only when every Plan, Step, kind,
event, checkpoint, retained lease owner, and fencing fact exactly matches the
derived materialization. A typed rejection is preserved unchanged. Missing,
unexpected, contradictory, or mismatched collaborator results fail through a
sanitized protocol error and always state that the lease is retained for
recovery; no uncertain write is repeated.

This composition owns no lease mutation or recovery inspection and does not
execute a Step, create completions, effects, or receipts, retry, resume,
repair, replan, access a Project or Workspace, call a file, network, model,
Provider, Sandbox, or tool port, expose product/API/UI behavior, or depend on
legacy Agent code. Authenticated product composition and execution traffic
remain later Issue boundaries.

## Authenticated active-Step interruption composition boundary

The internal product interruption path resolves one owner-qualified Agent turn
and derives its Plan identity before validating caller interruption material.
Its command contains exactly one stable Step-recovery lease attempt, one
interruption kind, one event draft, and one checkpoint timestamp. It cannot
supply Plan, TaskFrame, Project, Workspace, revision, Step, checkpoint, lease,
or fencing authority.

After validation, the product performs exactly one stable active-Step recovery
attempt. Lease and persistence rejections remain closed typed product outcomes
that preserve the exact stable recovery result and never call interruption
composition. Only an exact same-Plan `RecoveredActiveStep` is passed once,
together with the unchanged caller intent, to the stable Runtime interruption
composer. Its exact outcome and retained-lease disposition are preserved
without exposing the recovered lease authority again through the product
result.

Spring wires the deterministic interruption materializer and core composer
directly to the existing product `StepInterruptionRepository`. The product
wrapper has no repository collaborator and does not inspect persistence,
retry, release or renew a lease, regenerate event or checkpoint authority, run
a Step, create a completion, effect, or receipt, resume, repair, replan, access
a Project or Workspace, use files, network, model, Provider, Sandbox, or tools,
expose a Controller/API/UI path, change schema, or reuse legacy Agent code.

## Product active-Step effect intent persistence boundary

The product database implements the stable V2 `EffectIntentRepository`
through V48. One immutable `agent_v2_effect_intents` row is globally keyed by
the ToolCall ID and binds the exact provider-neutral effect kind and structured
arguments to its Plan, active Step, activation event, durable lease owner, and
fencing generation. Canonical format-1 request and result documents are
protected independently by lowercase SHA-256 digests.

Before a first write, the adapter uses the existing canonical active-Step
recovery inspection, then locks the Plan bootstrap authority and rechecks the
single activation and absence of a committed interruption. It validates one
current unreleased and unexpired lease without acquiring, renewing, releasing,
or replacing it. The effect marker is the only row written; execution start,
context, activation, interruption, checkpoint, event, Plan, revision, and
lease authorities remain unchanged.

An exact durable request replays before current lease or clock inspection,
including after lease takeover. Any changed intent, activation, lease token,
or fence conflicts at its stable request path. Read-only lookup is independent
of current execution and lease state, while malformed, non-canonical,
digest-invalid, or cross-bound durable rows fail closed through the sanitized
effect-intent partial-state failure. Same-Plan writes serialize on the
bootstrap row and database constraint losers are classified in a fresh
transaction.

This boundary records intent only. It performs no Provider, Sandbox, tool,
file, or network effect; records no progress, outcome, receipt, or Step
completion; starts no kernel or Agent loop; exposes no Controller/API/UI
traffic; accesses no Project or Workspace; and reuses no legacy Agent code.

## Stable ordinary Receipt partial-state error boundary

The stable persistence contract distinguishes a corrupt or torn durable
ordinary Receipt marker, claim, or fact through
`RECEIPT_PARTIAL_STATE`. This code applies only when occupied Receipt state is
contradictory, incomplete, corrupt, or undecodable.

It does not classify absence, invalid caller input, conflicting replay,
EffectIntent ownership rejection, EffectOutcome progress or result corruption,
or execution-authority failures. Those conditions retain their existing
stable error codes. This contract adds no Receipt implementation, schema,
codec, retry, repair, Provider, Sandbox, tool, Step, kernel, API, UI, or legacy
Agent behavior.

## Product ToolCall ownership and ordinary Receipt persistence boundary

The product database now implements the stable `ReceiptRepository` through
V49. A globally keyed ToolCall claim admits exactly one current ownership
family: `EFFECT_INTENT` or `ORDINARY_RECEIPT`. V49 deterministically backfills
every existing V48 effect intent with its exact effect claim, and composite
foreign keys prevent either an intent or Receipt fact from existing without
the matching claim. Receipt rows separately retain a fact discriminator for
ordinary Receipts and future effect outcomes, while a pair constraint binds
ordinary facts to ordinary claims and reserves effect outcomes for effect
claims.

Both first EffectIntent persistence and first ordinary Receipt append create
or lock the shared claim inside the same transaction as their immutable fact.
The first claimant wins; the opposite family receives the stable
effect-receipt ownership rejection and writes no fact. Constraint, deadlock,
or serialization losers receive at most one fresh classification transaction,
never a retry loop. Assigned identifiers are inserted explicitly so database
uniqueness, rather than JPA merge behavior, remains the race authority.

Ordinary Receipts use canonical format-1 JSON and lowercase SHA-256 across all
statuses, optional values, bounded output captures, artifacts, diffs, and
event references. Exact appends replay permanently, changed same-ID appends
conflict, and multiple Receipt IDs may share one ordinary ToolCall claim.
Find is read-only and independent of time or live execution authority.
Orphaned, mismatched, undecodable, noncanonical, digest-invalid, or
cross-column claim/fact cuts fail closed through the corresponding sanitized
effect-intent or Receipt partial-state code; no claim is repaired, replaced,
or deleted.

This boundary records durable Receipt facts only. It performs no effect,
Provider, Sandbox, tool, file, network, lease, Step, kernel, Agent Loop,
Controller, API, UI, Project, or Workspace operation and reuses no legacy
Agent implementation. Effect progress/result and execution completion remain
later Issue boundaries.

## Product EffectOutcome persistence boundary

The product database implements the stable `EffectOutcomeRepository` through
V50. Immutable effect progress is globally keyed by its progress ID and
uniquely sequenced from one within each ToolCall stream. One final-result
marker may bind that ToolCall to exactly one shared V49 Receipt fact. The
result marker and Receipt use a composite `(receipt_id, tool_call_id)`
constraint and are inserted in one transaction, so neither can become visible
without the other.

New progress and results require the existing canonical EffectIntent claim,
the exact current active-Step recovery cut, and the current live lease token
and fence. The Plan bootstrap is locked before these authorities are rechecked.
No bootstrap, execution-start, context, activation, interruption, lease,
intent, claim, event, checkpoint, Plan, or revision row is mutated.

Exact progress and result replay is decoded and validated before recovery,
lease, or clock inspection and remains permanent after lease expiry or
takeover. Progress streams must start at one and remain contiguous, and no new
progress is accepted after a valid final result. Canonical format-1 documents
and lowercase SHA-256 digests protect every progress detail shape and every
Receipt field. Missing counterparts, corrupt documents, discriminator or
cross-column mismatches, noncontiguous streams, and orphaned occupied cuts
fail closed as sanitized EffectOutcome partial state.

EffectOutcome Receipts retain the EffectIntent ToolCall claim and use the
`EFFECT_OUTCOME` receipt-fact discriminator. A Receipt ID already owned by an
ordinary Receipt or another complete outcome cannot be taken over, and the
ordinary Receipt adapter recognizes complete outcome Receipts for read while
rejecting append ownership in both race directions. Constraint, deadlock, and
serialization losers receive at most one fresh classification; there is no
production retry loop.

This boundary persists provider-neutral progress and result facts only. It
does not execute an effect or Step, complete a Step, mutate a checkpoint,
invoke a Provider, Sandbox, tool, file, or network, start a kernel or Agent
Loop, expose Controller/API/UI traffic, access Project/Workspace content, or
reuse legacy Agent code.

## Product first active-Step completion persistence boundary

The product database implements the stable `StepCompletionRepository` for the
current first active-Step cut through V51. One immutable completion marker is
bound to the exact V46 activation and stores canonical hash-checked request and
result documents. Ordered child rows bind the completion fact to every final
V50 EffectOutcome Receipt for that Plan, Step, and activation; an effect-free
Step has no evidence rows. Each evidence row repeats the Plan, Step, and
activation authority and uses composite foreign keys to the exact completion
marker and EffectIntent, plus the exact ToolCall/Receipt EffectOutcome pair,
so a cross-Plan, Step, or activation association cannot commit.

Every new completion locks the Plan bootstrap authority and revalidates the
canonical version-3, sequence-2 activation, the absence of interruption or
completion, the current live lease, the expected source head, one eligible
active Step, and the complete final EffectOutcome set. Receipts are ordered by
ToolCall ID and must exactly equal the CompletionFact references. The marker
and evidence rows use explicit persistence and commit atomically without
updating bootstrap, execution-start, context, activation, effect, receipt,
lease, Plan, revision, event, or checkpoint source rows.

Exact replay is validated before mutable lease or clock inspection and remains
permanent after lease expiry or takeover. Corrupt or torn marker, activation,
intent, outcome, receipt, claim, or evidence cuts fail closed through the
sanitized completion partial-state error; missing final outcomes and evidence
mismatches are not eligible. Completion, interruption, EffectIntent, and
EffectOutcome writers serialize on the same bootstrap lock. After permanent
exact replay classification, the effect writers reject a completed Plan, and
completion replay reconstructs the complete canonical intent/final-outcome
set before accepting its evidence. A completed Step therefore cannot acquire
later intent, progress, or result facts in either lock ordering.

This boundary does not activate the next Step or implement a general
completion chain, recovery composition, Runtime kernel, Agent Loop, Provider,
Sandbox, tool, file, network, Project or Workspace mutation, Controller,
API/UI traffic, or legacy Agent behavior. Those remain later Issues.
