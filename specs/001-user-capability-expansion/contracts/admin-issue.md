fix(admin): contain long conversation lists in a scrollable panel

Keep visitor/workspace/project conversation content in a bounded-height internally scrolling region. Long expanded messages and many sessions must not push invitation management further down. Preserve actions/accessibility and mobile width. Owned paths: frontend/src/views/AdminPage.vue and focused layout verification. Acceptance US4 FR007/008 T401-402.

Implementation authorized directly in local codex/issue-185-react-optimization by user on 2026-09-09. Shared specification: specs/001-user-capability-expansion/spec.md; plan.md and tasks.md are frozen implementation contract. No agent-v2 core or broker changes; Redis optimization is follow-up. Preserve existing data, auth, immutable ProjectVersion and publication proofs. Add success/failure/isolation tests, report exact verification and live checks not run. Do not merge PRs.
