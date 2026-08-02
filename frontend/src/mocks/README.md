# Frontend UI mock

The UI mock is available only in Vite development mode. It installs an Axios adapter before authentication starts, so no backend service is required for layout and screenshot work.

Open one of these URLs after starting the frontend dev server:

- `/projects?uiMock=admin&mockState=complete`
- `/projects?uiMock=user&mockState=running`
- `/projects?uiMock=demo&mockState=waiting`
- `/projects?uiMock=admin&mockState=failed`
- `/projects?uiMock=user&mockState=empty`

Supported roles are `admin`, `user`, and `demo`. Supported project states are `complete`, `running`, `waiting`, `failed`, and `empty`.

Mock fixtures must use the production API response types. Unknown endpoints fail with `UI_MOCK_NOT_IMPLEMENTED` instead of silently inventing data.
