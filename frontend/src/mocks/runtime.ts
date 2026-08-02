import http from '@/api/http';
import { createUiMockAdapter } from './httpAdapter';
import { readUiMockScenario } from './scenario';

const ACCESS_TOKEN_KEY = 'yanban_access_token';
const REFRESH_TOKEN_KEY = 'yanban_refresh_token';

function base64Url(value: object) {
  return btoa(JSON.stringify(value))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

function createMockJwt() {
  const header = base64Url({ alg: 'none', typ: 'JWT' });
  const payload = base64Url({
    sub: 'paperagent-ui-mock',
    exp: Math.floor(Date.now() / 1000) + 24 * 60 * 60,
  });
  return `${header}.${payload}.ui-mock`;
}

export function installUiMock() {
  const scenario = readUiMockScenario();
  http.defaults.adapter = createUiMockAdapter(scenario);
  localStorage.setItem(ACCESS_TOKEN_KEY, createMockJwt());
  localStorage.setItem(REFRESH_TOKEN_KEY, 'ui-mock-refresh-token');
  document.documentElement.dataset.uiMock = `${scenario.role}:${scenario.projectState}`;
}
