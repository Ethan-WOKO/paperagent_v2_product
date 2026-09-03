# PaperAgent Product Constitution

## Core Principles

### I. Evidence Before Implementation
Every change MUST begin from an active GitHub Issue with a frozen implementation
contract that states the problem, owned paths, required behavior, non-goals,
acceptance criteria, and verification scope. Feature implementation MUST NOT
begin until the specification, unresolved ambiguities, implementation plan, and
task mapping are reviewable. Bug work MUST separate read-only assessment from
the source-changing fix and from read-only validation. Logs, trace IDs,
reproductions, tests, Workspace diffs, Receipts, and repository state are
evidence; chat history and model claims are not.

### II. Preserve Product and Runtime Boundaries
PaperAgent remains the product shell for UI, paper editing, literature,
knowledge, authentication, projects, and deployment. The V2 agent core remains
under `agent-v2/` in the `io.paperagent.v2` namespace. Top-level execution modes
remain `DIRECT` and `PERSISTENT_PLAN_EXECUTE`. Requests involving Projects,
tools, execution, networking, or modification MUST enter a persistent Plan.
Existing behavior is preserved unless the active Issue explicitly changes it.

### III. Immutable Project Authority
A TaskFrame freezes objective, objects, deliverables, constraints,
ProjectVersion, and permission tier. Agent execution may modify only an
isolated Workspace. A ProjectVersion may change only after a final successful
sandbox Receipt proves the exact Candidate file contents, while the previous
immutable revision remains available for rollback. Plans may be revised, but
completed authoritative facts are append-only. No specification or
implementation may weaken these guarantees for convenience.

### IV. Dependency and Migration Discipline
Product adapters may depend on stable V2 contracts and runtime interfaces. V2
modules MUST NOT depend on `com.yanban`, Spring Controllers, product persistence
entities, concrete providers or databases, or legacy agent services. Provider,
Workspace, Persistence, and Sandbox implementations connect through
interfaces. Legacy agent code remains `UNASSESSED` until independently
reviewed, and related capabilities may be migrated only in coherent bundles
sharing authority, side-effect, runtime, and verification boundaries.

### V. Verification and Honest Completion
Every behavioral change MUST include success and failure coverage appropriate
to its risk. Verification follows
`docs/当前有效/开发流程/verification-matrix.md`; focused tests are the minimum,
and broader suites require a directly affected boundary or explicit risk
reason. PR evidence MUST record exact commands, test counts, skipped checks and
reasons, and residual risks. An unexecuted reproduction or required test cannot
be reported as verified. A task is complete only when its acceptance criteria
are evidenced and no required work remains.

## Security and Change Constraints

- Modify only paths owned by the active Issue; preserve unrelated and
  pre-existing worktree changes.
- Never add credentials, `.env` files, runtime logs, generated output, local
  acceptance data, test user data, uploaded files, or host-specific artifacts.
- Authentication, authorization, ownership, ProjectVersion, Workspace,
  Receipt, cancellation, SSE, memory, and rollback behavior MUST fail closed.
- Public API, schema, deployment, and cross-service behavior remain compatible
  unless the active Issue explicitly owns and verifies a change.
- Destructive operations require exact resolved targets and explicit scope;
  recoverable alternatives are preferred.

## Development Workflow and Quality Gates

1. Use one independent worktree, one `codex/` branch, one active Issue, and one
   Draft PR per coherent change. Implementation agents do not merge.
2. Feature flow: Specify -> Clarify -> Plan -> Checklist -> Tasks -> Analyze ->
   Implement -> Converge -> Draft PR. Clarify, Checklist, and Analyze are
   mandatory when behavior, compatibility, authority, persistence, or failure
   handling is materially ambiguous.
3. Bug flow: Assess -> Fix -> Test -> Draft PR. Assessment and validation are
   read-only with respect to product source. Validation MUST rerun the stated
   reproduction or downgrade the result to partial.
4. The plan MUST include compatibility impact, failure behavior, test mapping,
   rollback, and all affected runtime or service restart requirements.
5. Requirements-quality checklists are reviewer-owned. The implementing agent
   MUST NOT silently approve its own ambiguous requirements.
6. Before implementation, cross-artifact analysis MUST show that every
   requirement maps to tasks and tests, and that no task exceeds the frozen
   scope. Deviations discovered during implementation are recorded and routed
   back to the owning artifact.

## Governance

This constitution governs Spec Kit artifacts and workflows but does not replace
the repository root `AGENTS.md`. The authority order remains: current explicit
user decision, root `AGENTS.md`, this constitution, current architecture and
product contracts, then lower-priority documentation. Conflicts MUST stop the
workflow and be resolved at the higher-authority source.

Constitution amendments require a dedicated Issue or an explicitly owned
section of an active Issue, a documented reason, migration impact, and review
before implementation artifacts rely on the new rule. Every plan, analysis,
implementation review, and PR MUST include a constitution compliance check.

**Version**: 1.0.0 | **Ratified**: 2026-09-03 | **Last Amended**: 2026-09-03
