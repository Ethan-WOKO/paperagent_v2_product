# Bug Assessment: Admin detail tabs share a bounded content panel

- **Slug**: admin-shared-tab-panel (confirmed by user)
- **Created**: 2026-09-14
- **Source**: pasted user request
- **Verdict**: valid
- **Severity**: medium

## Report

The user reports that quota usage, papers and projects still push invitations down when their
lists grow. All four detail tabs should share the same bounded area as conversations. The user
explicitly requests continuing on local 185 and preserving other conversations' changes.

## Symptom

Only nonempty `.admin-chat-list` has a fixed viewport height. Other tab contents, chat category
controls and empty states determine their own heights, so switching tabs moves invitation management.

## Reproduction

1. Open `/admin`, select an account with many usage, paper, project and conversation records.
2. Switch among quota usage, chat, paper and project tabs, and expand a long chat.
3. Compare invitation document position and content panel height; repeat with empty records and mobile width.
4. Use isolated mocked API fixtures for the automated equivalent; never mutate actual administrator data.

## Suspected Code Paths

- `frontend/src/views/AdminPage.vue`: NTabs/NTabPane children and `.admin-chat-list` height rule.
- `frontend/tests/adminLayout.browser.mjs`: only supplies long chats; the other three arrays are empty.

## Root Cause Hypothesis

High confidence: the bounded scrolling rule is applied to the inner chat list instead of the common
tab-pane boundary. Naive UI's animated pane wrapper also changes height during transitions.

## Proposed Remediation / Frozen Implementation Contract

**Preferred**: use the existing NTabs `pane-class` to apply a single shared bounded-height style to
each active tab pane (including empty states), retain the existing responsive 240–520px clamp, and
remove the nested chat-only scroll viewport. Keep top-level tab headers outside scrolling and disable
pane-height animation so switching cannot transiently shift invitations. Give each pane a meaningful
accessible label and keyboard focus. Wrap long list text within the content width.

**Owned paths**:
- `frontend/src/views/AdminPage.vue` — template attributes and scoped layout CSS only; no script/business logic edits.
- `frontend/tests/adminLayout.browser.mjs` — focused mocked browser reproduction/regression coverage.
- `.specify/bugs/admin-shared-tab-panel/` — assessment, fix and verification records only.

**Tests**: 1440px and 390px widths, all four populated and empty tabs, workspace/project chat
categories, expanded long messages, exact shared height, stable invitation position, one scroll
container, keyboard scrolling, long text without horizontal overflow and retained invitation controls.
Run the browser reproduction before and after the source fix, frontend production build, and diff checks.

## Risks & Considerations

- Preserve current chat grouping, 15-second usage refresh, account/quota/invitation actions and all unrelated edits.
- No backend, API, migration, ReAct, paper processing, knowledge or dependency change.
- Use the existing local `codex/issue-185-react-optimization` branch per explicit user decision;
  do not create a branch/worktree, reset, stash or revert others' work.
- Existing Draft PR on that branch is shared; preserve its body and unrelated commits.
- Real authenticated API writes are out of scope; mock browser fixtures prove the layout only.
- Revert only this bug's patch to roll back; backend/broker restarts are unnecessary.

## Open Questions

None. User confirmed the intended common region and the issue slug.
