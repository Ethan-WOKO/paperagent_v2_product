# Bug Verification: Literature tool boundaries by execution profile

- **Slug**: chat-literature-tool-boundary
- **Tested**: 2026-09-03
- **Assessment**: ./assessment.md
- **Fix**: ./fix.md
- **Result**: verified

## Summary

The ordinary-chat exposure bug no longer reproduces, and Project ReactPlan
status polling is bounded without removing the Project or V2 asynchronous
literature capability. Focused Java and TypeScript checks pass.

## Checks Performed

| Check | Command / Action | Result | Notes |
|-------|------------------|--------|-------|
| Reproduction (post-fix) | `LangChain4jToolCallingStrategyTest` in the focused Maven run | pass | Ordinary chat exposes only `search_web`, `recommend_literature`, and `search_knowledge`; the old alias/allow-list cannot re-expose task controls. |
| New / updated tests | `npx vitest run test/engine.test.ts -t "literature status"` | pass | 3 passed: unchanged, terminal/transition, and hard-ceiling cases. |
| Regression suite | focused Maven run across ordinary chat, Project gateway, and V2 composer | pass | 51 passed, 0 failed, 0 skipped. |
| Broader Agent Engine suite | `npm test` | partial | 73 tests passed; one unrelated Windows temporary-file rename test encountered `EPERM`. |
| Lint / type-check | `npm run typecheck` | pass | TypeScript compilation completed without errors. |
| Spec Kit integration | `specify integration status` | pass | Integration status OK; no modified or missing managed files. |
| Diff hygiene | `git diff --check` | pass | No whitespace errors. |

## Output Excerpts

```text
Test Files  1 passed (1)
Tests  3 passed | 54 skipped (57)

Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

Integration status: OK
Modified managed files: 0
Missing managed files: 0
```

## Residual Risks

- Live provider latency and timing are not reproduced by deterministic unit tests; the persisted per-task hard ceiling remains the final guard.
- The unrelated Windows `EPERM` in the task-store cancellation test should be tracked separately if it remains reproducible outside antivirus/indexer contention.

## Recommendation

Close the bug after PR merge. The original exposure and repeated-polling paths
are covered, and the separate Project/V2 literature integrations remain intact.
