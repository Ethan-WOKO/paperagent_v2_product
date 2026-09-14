# Bug Verification: Shared admin tab content bounds

- **Slug**: admin-shared-tab-panel (from assessment/fix context)
- **Tested**: 2026-09-14
- **Assessment**: ./assessment.md
- **Fix**: ./fix.md
- **Issue**: #222
- **Result**: verified (scoped browser layout reproduction)

## Summary

Before the fix, 100 quota rows produced a 4112px pane. After the fix, the same browser reproduction
and its empty/mobile variants pass. All four tabs share the same responsive height and invitation
document position, even during switching and long chat expansion. No business-script changes.

## Checks Performed

Commands run in `frontend` unless noted. Temporary Vite started using
`npm run dev -- --host 127.0.0.1 --port 5174 --strictPort`.

| Check | Command / Action | Result | Notes |
|---|---|---|---|
| Pre-fix reproduction | `$env:PAPERAGENT_EVAL_WEB_ORIGIN='http://127.0.0.1:5174'; node tests/adminLayout.browser.mjs` | Expected failure | Quota pane 4112px |
| Post-fix reproduction and independent rerun | Same command | Pass, exit 0 | 4/4 scenarios; 16 primary-tab visits plus both chat categories |
| TypeScript / production build, independently rerun | `$env:CI='true'; pnpm build` | Pass, exit 0 | Existing large-chunk warning only |
| Whitespace (repository root) | `git diff --check` | Pass | Owned changes only |
| Business-script preservation | Compare complete AdminPage script block with baseline `ad2b3119` | Pass | Includes existing chat grouping and 15-second refresh |
| Backend / Engine / real administrator writes | Not run | Skipped | Pure scoped frontend layout change; no API contract or logic edits |

## Output Excerpts

```text
PASS all 4 admin tabs, both chat categories, shared bounds, keyboard and invites at 1440px (100 records/type)
PASS all 4 admin tabs, both chat categories, shared bounds, keyboard and invites at 1440px (empty)
PASS all 4 admin tabs, both chat categories, shared bounds, keyboard and invites at 390px (100 records/type)
PASS all 4 admin tabs, both chat categories, shared bounds, keyboard and invites at 390px (empty)
```

## Residual Risks

- Browser uses system Edge with fully mocked APIs; real account writes are deliberately not exercised.
- Other browser engines and deployed CSS caches are not tested; refresh or redeploy frontend assets.
- This verifies only Issue #222, not unrelated pending acceptance gates on the shared Draft PR.
- No virtualization is introduced; this bounds visual space, not the existing number of fetched records.

## Recommendation

The reported layout bug is resolved in the reproduced desktop/mobile scenarios. Deliver this isolated
patch through existing Draft PR #220 without merging or overwriting other conversations' contributions.
No backend, ReAct Engine or broker restart is required.
