export type UiMockRole = 'admin' | 'user' | 'demo';
export type UiMockProjectState = 'complete' | 'running' | 'waiting' | 'failed' | 'empty';

export interface UiMockScenario {
  role: UiMockRole;
  projectState: UiMockProjectState;
}

const ROLE_STORAGE_KEY = 'paperagent.ui-mock.role';
const PROJECT_STATE_STORAGE_KEY = 'paperagent.ui-mock.project-state';

function isRole(value: string | null): value is UiMockRole {
  return value === 'admin' || value === 'user' || value === 'demo';
}

function isProjectState(value: string | null): value is UiMockProjectState {
  return value === 'complete' || value === 'running' || value === 'waiting'
    || value === 'failed' || value === 'empty';
}

export function readUiMockScenario(): UiMockScenario {
  const query = new URLSearchParams(window.location.search);
  const requestedRole = query.get('uiMock');
  const requestedState = query.get('mockState');

  if (isRole(requestedRole)) sessionStorage.setItem(ROLE_STORAGE_KEY, requestedRole);
  if (isProjectState(requestedState)) sessionStorage.setItem(PROJECT_STATE_STORAGE_KEY, requestedState);

  const storedRole = sessionStorage.getItem(ROLE_STORAGE_KEY);
  const storedState = sessionStorage.getItem(PROJECT_STATE_STORAGE_KEY);
  return {
    role: isRole(requestedRole) ? requestedRole : isRole(storedRole) ? storedRole : 'admin',
    projectState: isProjectState(requestedState)
      ? requestedState
      : isProjectState(storedState) ? storedState : 'complete',
  };
}
