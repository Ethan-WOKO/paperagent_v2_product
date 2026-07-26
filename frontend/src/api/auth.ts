import http from './http';

export interface AuthResponse {
  tokenType: string;
  accessToken: string;
  refreshToken: string;
  expiresIn?: number;
  expiresInSeconds?: number;
}

export interface UserMeResponse {
  id: number;
  username: string;
  accountType: 'NORMAL' | 'DEMO' | string;
  demo: boolean;
  role: 'ADMIN' | 'USER' | string;
  aiQuotaTotal: number;
  aiQuotaUsed: number;
  aiQuotaRemaining: number;
}

export function register(payload: { username: string; password: string; inviteCode?: string }) {
  return http.post<AuthResponse>('/auth/register', payload);
}

export function login(payload: { username: string; password: string }) {
  return http.post<AuthResponse>('/auth/login', payload);
}

export function demoLogin() {
  return http.post<AuthResponse>('/auth/demo-login', {});
}

export function me() {
  return http.get<UserMeResponse>('/users/me');
}
