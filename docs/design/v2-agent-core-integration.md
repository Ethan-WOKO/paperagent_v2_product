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
