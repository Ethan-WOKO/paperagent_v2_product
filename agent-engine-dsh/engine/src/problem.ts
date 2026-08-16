export interface Problem {
  contractVersion: '1.0';
  code: string;
  category: 'request' | 'authorization' | 'model' | 'tool' | 'code_validation' | 'sandbox_system' | 'cancelled' | 'internal';
  message: string;
  retryable: boolean;
  sourceRef?: string | null;
}

export function problem(code: string, category: Problem['category'], message: string, retryable = false, sourceRef: string | null = null): Problem {
  return { contractVersion: '1.0', code, category, message, retryable, sourceRef };
}

export const CODE_PATTERN = /^[A-Z][A-Z0-9_]{2,95}$/;
export const TASK_ID_PATTERN = /^task\.[a-f0-9]{64}$/;
export const SHA256_PATTERN = /^[a-f0-9]{64}$/;
export const CALL_ID_PATTERN = /^call\.[A-Za-z0-9_-]{16,120}$/;
export const PROJECT_ID_PATTERN = /^[1-9][0-9]{0,18}$/;
