# Bug Fix: Enforce literature tool boundaries by execution profile

- **Slug**: chat-literature-tool-boundary
- **Fixed**: 2026-09-03
- **Assessment**: ./assessment.md
- **Status**: applied

## Summary

Ordinary LangChain4j chat now excludes asynchronous literature task-control
tools even when a compatibility allow-list includes them. Project ReactPlan and
V2 retain those tools, while ReactPlan now stops unchanged, terminal, or
over-budget status polling within the current task.

## Changes

| File | Change | Notes |
|------|--------|-------|
| `yanban-api/src/main/java/com/yanban/api/agent/LangChain4jToolProvider.java` | modified | Added a defense-in-depth ordinary-chat deny boundary for the four asynchronous literature orchestration tools. |
| `yanban-api/src/test/java/com/yanban/api/agent/LangChain4jToolCallingStrategyTest.java` | updated tests | Pins the ordinary-chat research-tool allow-list and removes the obsolete repeated asynchronous polling expectation. |
| `agent-engine-reactplan/src/types.ts` | modified | Persists registered-tool poll observations and per-task tool suppression. |
| `agent-engine-reactplan/src/engine.ts` | modified | Fingerprints meaningful status fields and suppresses polling after unchanged state, terminal state, or four attempts. |
| `agent-engine-reactplan/test/engine.test.ts` | added tests | Covers unchanged observations, meaningful transitions, terminal state, and the absolute poll ceiling. |

## Tests Added or Updated

- `LangChain4jToolCallingStrategyTest::providerExposesOnlyTheNormalChatResearchPolicy` — asynchronous task-control tools cannot leak into ordinary chat.
- `AgentEngine::stops Project literature status polling after two unchanged observations` — repeated stable state is bounded.
- `AgentEngine::allows meaningful literature status transitions before terminal suppression` — real progress is preserved and terminal state stops polling.
- `AgentEngine::caps Project literature status polling even while non-terminal stages keep changing` — the per-task hard ceiling is enforced.

## Local Verification

- Commands run: `npm run typecheck` → passed.
- Commands run: `npx vitest run test/engine.test.ts -t "literature status"` → 3 passed.
- Commands run: `mvn -pl yanban-api -am "-Dtest=LangChain4jToolCallingStrategyTest,AgentEngineRegisteredToolGatewayTest,AuthenticatedLiteratureSearchEffectExecutionComposerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` → 51 passed, build successful.
- Broader check: `npm test` → 73 passed; one pre-existing Windows temporary-file `EPERM` failure occurred in the unrelated cancel-idempotency test.
- Manual checks: reviewed tool catalogs and persisted task-state serialization; no database schema or public API change was introduced.

## Deviations from Assessment

The old `allowsRepeatedPollingStatusToolCalls` test was removed because it
encoded the behavior identified as the bug. The equivalent Project behavior is
now covered at the ReactPlan gateway and Engine layers, where the asynchronous
tool is intentionally supported.

## Follow-ups

- Monitor Windows antivirus/file-indexer interference with ReactPlan temporary task-store atomic rename tests separately from this bug.
