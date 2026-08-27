export interface RegistrationValues {
  username: string;
  password: string;
  confirmPassword: string;
  inviteCode: string;
}

export type RegistrationField = keyof RegistrationValues;
export type RegistrationFieldErrors = Partial<Record<RegistrationField, string>>;

export interface RegistrationFailure {
  message: string;
  fieldErrors: RegistrationFieldErrors;
}

const USERNAME_PATTERN = /^[a-zA-Z0-9_@.\-]+$/;

export function validateRegistration(values: RegistrationValues): RegistrationFieldErrors {
  const errors: RegistrationFieldErrors = {};
  const username = values.username.trim();
  const inviteCode = values.inviteCode.trim();

  if (!username) errors.username = '请输入用户名';
  else if (username.length < 3 || username.length > 64) errors.username = '用户名长度必须为 3 到 64 个字符';
  else if (!USERNAME_PATTERN.test(username)) errors.username = '用户名只允许字母、数字、下划线、@、点和横线，不能含有中文';

  if (!values.password) errors.password = '请输入密码';
  else if (values.password.length < 8 || values.password.length > 128) errors.password = '密码长度必须为 8 到 128 个字符';

  if (!inviteCode) errors.inviteCode = '请输入邀请码';
  if (values.password && values.password !== values.confirmPassword) {
    errors.confirmPassword = '两次输入的密码不一致';
  }
  return errors;
}

export function registrationFailure(error: unknown): RegistrationFailure {
  const response = isRecord(error) && isRecord(error.response) ? error.response : undefined;
  const data = response && isRecord(response.data) ? response.data : undefined;
  const rawFields = data && isRecord(data.fieldErrors) ? data.fieldErrors : undefined;
  const fieldErrors: RegistrationFieldErrors = {};
  for (const field of ['username', 'password', 'confirmPassword', 'inviteCode'] as const) {
    const value = rawFields?.[field];
    if (typeof value === 'string' && value.trim()) fieldErrors[field] = value;
  }
  const candidates = [data?.message, data?.detail, data?.error];
  const message = candidates.find((value): value is string => typeof value === 'string' && Boolean(value.trim()))
    ?? '注册失败，请检查填写的信息后重试';
  return { message, fieldErrors };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
