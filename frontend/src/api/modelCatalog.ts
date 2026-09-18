import http from './http';
export interface SharedProvider { id: number; name: string; chatUrl: string; modelsUrl: string | null; apiKeyConfigured: boolean; enabled: boolean }
export interface SharedProviderInput { name: string; chatUrl: string; modelsUrl: string; apiKey?: string; enabled: boolean }
export interface SharedModel { id: number; providerId: number; modelName: string; approved: boolean; supportsVision: boolean; available: boolean }
const path = '/admin/model-providers';
export const listSharedProviders = () => http.get<SharedProvider[]>(path);
export const saveSharedProvider = (id: number | null, input: SharedProviderInput) => id == null ? http.post<number>(path, input) : http.put<number>(`${path}/${id}`, input);
export const listSharedModels = (id: number) => http.get<SharedModel[]>(`${path}/${id}/models`);
export const syncSharedModels = (id: number) => http.post<SharedModel[]>(`${path}/${id}/sync`);
export const saveSharedModel = (id: number, input: Pick<SharedModel, 'modelName' | 'approved' | 'supportsVision'>) => http.put(`${path}/${id}/models`, input);

export const saveSharedModels = (id: number, models: Pick<SharedModel, 'modelName' | 'approved' | 'supportsVision'>[]) => http.put<SharedModel[]>(`${path}/${id}/models/batch`, { models });
