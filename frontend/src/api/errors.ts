export interface ApiErrorPayload {
  code: string;
  message: string;
  fieldErrors: Record<string, string>;
}

export function apiErrorPayload(error: unknown, fallback = '请求失败，请稍后重试'): ApiErrorPayload {
  const response = isRecord(error) && isRecord(error.response) ? error.response : undefined;
  const data = response && isRecord(response.data) ? response.data : undefined;
  const fields = data && isRecord(data.fieldErrors) ? data.fieldErrors : undefined;
  const fieldErrors: Record<string, string> = {};
  for (const [field, value] of Object.entries(fields ?? {})) {
    if (typeof value === 'string' && value.trim()) fieldErrors[field] = value.trim();
  }
  const message = typeof data?.message === 'string' && data.message.trim()
    ? data.message.trim()
    : safeLocalMessage(error) ?? fallback;
  const code = typeof data?.code === 'string' && data.code.trim()
    ? data.code.trim()
    : 'REQUEST_FAILED';
  return { code, message, fieldErrors };
}

export function apiErrorMessage(error: unknown, fallback: string): string {
  return apiErrorPayload(error, fallback).message;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function safeLocalMessage(error: unknown): string | undefined {
  const message = isRecord(error) && typeof error.message === 'string' ? error.message.trim() : '';
  if (!message || /^(network(?: error)?|failed to fetch|load failed|request failed with status code \d+)$/i.test(message)) {
    return undefined;
  }
  return message;
}
