# PaperAgent V2 Product Development Rules

## Start here

Before changing the repository, read:

1. the active GitHub Issue and its frozen implementation contract;
2. `docs/design/v2-agent-core-integration.md`;
3. `docs/document-map-20260708.md`;
4. `docs/process/verification-matrix.md`.

## Product and runtime boundaries

- This repository is the product shell. Existing UI, paper editing, literature
  retrieval, knowledge, authentication, and deployment behavior remain product
  capabilities unless an Issue explicitly changes them.
- The V2 agent core lives under `agent-v2/` and uses the
  `io.paperagent.v2` namespace.
- Top-level execution modes are only `DIRECT` and
  `PERSISTENT_PLAN_EXECUTE`.
- Requests involving a Project, tools, execution, networking, or modification
  must enter a persistent Plan.
- A TaskFrame freezes the objective, objects, deliverables, constraints,
  ProjectVersion, and permission tier. A Plan may be revised, but completed
  authoritative facts are append-only and cannot be rewritten.
- Agent execution may modify only an isolated Workspace. The original
  ProjectVersion changes only after the user accepts the Workspace diff.
- Workspace diffs, execution receipts, and event logs are authoritative result
  facts. Evidence supports conclusions; it is not a fixed tool-call script.

## Dependency direction

- Product adapters may depend on stable V2 contracts and runtime interfaces.
- V2 modules must not depend on `com.yanban` modules, Spring Controllers,
  product persistence entities, concrete providers/databases, or legacy agent
  services.
- Provider, Workspace, Persistence, and Sandbox implementations connect to the
  Runtime through interfaces.
- Do not introduce circular dependencies or a service that centralizes several
  module responsibilities.

## Migration rules

- Legacy agent code is `UNASSESSED` until independently reviewed.
- Migrate one capability per Issue and PR.
- Do not copy the legacy `PlanAgentService`, planner, completion verifier, or
  fixed candidate execution chain into V2.
- Do not weaken V2 contracts to support legacy Plan data.
- Do not copy generated output, `.env`, credentials, logs, PDFs, local
  acceptance data, test data, or user files.

## Tests and Git

- Each Issue uses an independent worktree, a `codex/` branch, and a Draft PR.
- Modify only the Issue's owned paths.
- Add automated behavior and failure tests for new behavior.
- Run focused tests for the current Issue. Expand the test scope only when a
  directly affected build or runtime boundary requires it, and record why.
- PR evidence must list exact commands, test counts, skipped checks with
  reasons, and residual risks.
- Implementation agents commit, push, and open a Draft PR, but do not merge.
