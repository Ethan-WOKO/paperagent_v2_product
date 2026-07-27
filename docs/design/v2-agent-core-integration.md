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
