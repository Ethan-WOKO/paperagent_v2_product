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
