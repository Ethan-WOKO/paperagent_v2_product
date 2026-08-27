import { describe, expect, it } from 'vitest';
import { registrationFailure, validateRegistration } from '../src/auth/registration';

describe('registration feedback', () => {
  it('explains invalid usernames and short passwords before submission', () => {
    expect(validateRegistration({
      username: '中文用户',
      password: 'short',
      confirmPassword: 'short',
      inviteCode: 'INVITE',
    })).toEqual({
      username: '用户名只允许字母、数字、下划线、@、点和横线，不能含有中文',
      password: '密码长度必须为 8 到 128 个字符',
    });
  });

  it('retains backend invite-code reasons and field errors', () => {
    expect(registrationFailure({
      response: {
        data: {
          code: 'INVITE_CODE_EXHAUSTED',
          message: '邀请码使用次数已达上限',
          fieldErrors: { inviteCode: '邀请码使用次数已达上限' },
        },
      },
    })).toEqual({
      message: '邀请码使用次数已达上限',
      fieldErrors: { inviteCode: '邀请码使用次数已达上限' },
    });
  });

  it('falls back safely when the server response has no readable detail', () => {
    expect(registrationFailure(new Error('network'))).toEqual({
      message: '注册失败，请检查填写的信息后重试',
      fieldErrors: {},
    });
  });
});
