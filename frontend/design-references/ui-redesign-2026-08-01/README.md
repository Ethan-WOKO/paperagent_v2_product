# PaperAgent V2 UI redesign references

These images are visual and information-architecture references for the frontend redesign. They are not runtime assets and must not be imported by application code.

## Prototype index

- `01-project-light.png` / `02-project-dark.png`: project workspace
- `03-paper-light.png` / `04-paper-dark.png`: independent paper-polishing workspace
- `05-workspace-light.png` / `06-workspace-dark.png`: research conversation workspace
- `07-knowledge-base-light.png` / `08-knowledge-base-dark.png`: knowledge-base management
- `09-search-debug-light.png`: retrieval debugging
- `10-long-term-memory-light.png`: long-term-memory governance
- `11-settings-light.png`: user settings
- `12-admin-light.png`: administrator-only account and quota management

Pages without a dark prototype must derive dark mode from the same shared semantic tokens used by the approved dark references. Do not create page-local dark overrides unless a component has a genuinely unique state.

## Product boundaries

- The global shell is shared by ordinary users and administrators. Do not build a second user UI.
- `管理后台` is rendered only when the authenticated user has the `ADMIN` role. Ordinary users see the same shell and page components without this navigation item or the `/admin` page.
- The global shell must preserve the existing AI quota summary, language switch, authenticated user identity, and logout action. Their absence from some prototypes is an incomplete shell depiction, not approval to remove them.
- Theme switching belongs to the shared shell and must work consistently across all pages.
- `论文` is an independent route, not a project child. There is no global `文件` route.
- Prototype sample content and empty states never replace live API data. Implementation must render only existing fields and authorized actions.

## Implementation rules

- Establish shared semantic design tokens before page-level styling.
- Reuse one permission-aware application shell for all roles.
- Keep route guards and backend authorization unchanged.
- Preserve every existing feature; secondary areas may be collapsed or reorganized, but not silently deleted.
- Avoid page-local override chains. Prefer shared primitives for navigation, buttons, inputs, panels, tables, empty states, status treatments, and responsive behavior.
- Validate each completed page in light and dark themes and at desktop, tablet, and mobile widths.
