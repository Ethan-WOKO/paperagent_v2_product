# Bug Fix: Shared admin tab content bounds

- **Slug**: admin-shared-tab-panel (from the user-confirmed assessment)
- **Fixed**: 2026-09-14
- **Assessment**: ./assessment.md
- **Issue**: #222
- **Status**: applied

## Summary

All four account-detail panes use one shared scoped height/scroll rule, including empty states.
The previous nested chat-only viewport and tab height animation no longer move invitation management.

## Changes

| File | Change | Notes |
|---|---|---|
| `frontend/src/views/AdminPage.vue` | Modified template attributes and scoped CSS | Shared pane-class; region labels/focus; fixed border-box height; wrap long text; no nested chat scroller |
| `frontend/tests/adminLayout.browser.mjs` | Extended browser regression | Four tabs, two chat categories, populated/empty fixtures, desktop/mobile and animation-frame bounds |

## Tests Added or Updated

Four browser scenarios: 1440x1000 and 390x844, each with 100 records per type or empty records.
Each switches all four panes; populated cases exercise internal/keyboard scrolling, long text,
chat expansion and both categories. Empty states retain the same bounds. Tests assert invitation
document position and pane size/top across live animation frames, accessibility labels, no nested
vertical scroller, no horizontal overflow, visible invitation generation and initially disabled Save.
All API requests are mocked and unexpected endpoints/writes fail the test.

## Local Verification

- `node tests/adminLayout.browser.mjs` on unchanged source at isolated port 5174: failed as expected;
  the initial quota pane measured 4112px instead of the required 240–520px.
- Same command after source fix: four scenarios passed, exit 0.
- Frontend `$env:CI='true'; pnpm build`: TypeScript and Vite passed, exit 0; existing >500kB chunk warning remains.
- `git diff --check`: passed.
- Compared the entire AdminPage script block to HEAD `ad2b3119`: unchanged.
- Initial attempt using default port 5173 failed with connection refused; used a separate temporary
  Vite at `127.0.0.1:5174` without touching the user's service.

## Deviations from Assessment

None. No backend, API, dependency, application script or unrelated source edits.

## Follow-ups

Run the independent read-only verification phase. Refresh/redeploy frontend to use the fix;
backend, ReAct Engine and broker do not require a restart. Preserve shared Draft PR content and
other conversations' commits; no automatic merge.
