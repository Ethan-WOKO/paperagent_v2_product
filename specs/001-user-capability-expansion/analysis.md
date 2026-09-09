# Implementation readiness review

2026-09-09 independent read-only review completed. Identified and resolved in plan:
server-owned paper source/request authority, conflicting replay/concurrent dispatch,
invocation-time Skill restriction, precise install format/empty allowed-tools semantics,
assistant-only ReAct history matches and deletion between search/detail.

Traceability: FR001/002 -> T101-103; FR003/004 -> T201-203; FR005/006 -> T301-303;
FR007 -> T401-402; FR008 -> T501-503. Four issues #216/#217/#218/#219.
Spec Kit hooks before/after specify: none (hooks: {}). Specification ready and
user's explicit instruction authorizes implementation on existing local 185.

## Implementation review closure (2026-09-10)

Independent read-only review confirmed frozen Skill authority/digest matches actual checkpoint schema and
coordinator/reflection copies preserve invocationScope. Three findings were fixed and independently rechecked:
stable planId/stepId across retries; sessionId + HTTP clientRequestId digest tied to V103 durable replay across
new turns/processes; per-target paper status suppression that does not block a second task in the same batch.
Separate read-only review confirmed the paper adapter still uses original quota checks, owner model configuration,
usage recording and pipeline. It does not introduce a second paper execution chain.

Main integration review also fixed CHAT capability reachability (not a finite wording list), native history
deduplication compatible with CLOB, test-slice MySQL mode, and kept Skill text subordinate to current task authority.
Exact executed checks, failures corrected, skipped live checks and residual risks are in verification.md.

## Final entry-point and contract review

Independent review found two additional gaps: the workspace's legacy PAPER_REVISION navigation returned
before native tools, including when conditional attachment guidance triggered the keyword; installation
accepted prompts/tool names that the existing ReAct submission schema rejected. Fixes keep the schema
unchanged, enforce its limits at upload, and bypass only the legacy paper navigation return. Existing
literature search/confirmation stays unchanged. Public sendMessage regression tests use the real intent
router, verify runtime entry with attachments, and preserve an empty Skill tool intersection.

No unrelated legacy planner overload mocks were changed. The one plan/step identity compatibility
guard is separately tested; it is not the Project page's execution path. Full ReAct cancellation
stability and live multi-service acceptance remain open as explicitly recorded in tasks/verification.

Final independent recheck closed both entry-point and Skill-schema findings after inspecting the
implementation and assertions; Main ran the final 85-test Java follow-up and 41-test frontend follow-up.
Spec Kit before/after implement hooks are empty (`hooks: {}`); no extension dispatch is required.
T501/T503 remain unchecked; implementation is handed off as a Draft, not full acceptance completion.
