import http from './http';

export interface UserSettingsResponse {
  defaultProvider: string;
  deepseekApiKeyConfigured: boolean;
  glmApiKeyConfigured: boolean;
  githubPatConfigured: boolean;
  deepseekModel: string;
  glmModel: string;
  deepseekModels: string[];
  glmModels: string[];
  deepseekTemperature: number;
  maxSteps: number;
  ragDefaultEnabled: boolean;
  filesystemRoots: string[];
  disabledSkills: string[];
  customModels: UserModelResponse[];
  updatedAt: string | null;
}

export interface UserSettingsRequest {
  defaultProvider: string;
  deepseekApiKey?: string;
  glmApiKey?: string;
  githubPat?: string;
  deepseekModel: string;
  glmModel: string;
  deepseekModels?: string[];
  glmModels?: string[];
  deepseekTemperature: number;
  maxSteps: number;
  ragDefaultEnabled: boolean;
  filesystemRoots: string[];
  disabledSkills?: string[];
}

export interface UserModelResponse {
  id: number;
  providerKey: string;
  label: string;
  modelName: string;
  apiUrl: string | null;
  apiKeyConfigured: boolean;
  builtin: boolean;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
}

export interface UserModelRequest {
  label: string;
  apiUrl: string;
  apiKey?: string;
  modelName: string;
}

export function getSettings() {
  return http.get<UserSettingsResponse>('/settings');
}

export function updateSettings(payload: UserSettingsRequest) {
  return http.put<UserSettingsResponse>('/settings', payload);
}

export function refreshProviderModels(provider: string) {
  return http.post<UserSettingsResponse>(`/settings/providers/${provider}/models/refresh`, {});
}

export function listModels() {
  return http.get<UserModelResponse[]>('/models');
}

export function createModel(payload: UserModelRequest) {
  return http.post<UserModelResponse>('/models', payload);
}

export function updateModel(id: number, payload: UserModelRequest) {
  return http.put<UserModelResponse>(`/models/${id}`, payload);
}

export function deleteModel(id: number) {
  return http.delete<void>(`/models/${id}`);
}

export function testModel(id: number) {
  return http.post<{
    success: boolean;
    content?: string;
    errorType?: 'ADDRESS_ERROR' | 'API_KEY_ERROR' | 'MODEL_NAME_ERROR' | 'INTERFACE_INCOMPATIBLE' | 'UNKNOWN_ERROR';
    errorMessage?: string;
    error?: string;
    providerKey: string;
    modelName: string;
  }>(`/models/${id}/test`, {});
}
