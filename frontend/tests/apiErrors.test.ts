import { describe, expect, it } from 'vitest';
import { apiErrorMessage, apiErrorPayload } from '../src/api/errors';

describe('shared API error protocol', () => {
  it('reads code, message and string field errors from the common envelope', () => {
    expect(apiErrorPayload({
      response: {
        data: {
          code: 'VALIDATION_FAILED',
          message: '项目名称不能为空',
          fieldErrors: { name: '项目名称不能为空', ignored: 42 },
        },
      },
    })).toEqual({
      code: 'VALIDATION_FAILED',
      message: '项目名称不能为空',
      fieldErrors: { name: '项目名称不能为空' },
    });
  });

  it('uses a product-safe fallback for network and legacy failures', () => {
    expect(apiErrorPayload(new Error('Network Error'), '加载论文失败')).toEqual({
      code: 'REQUEST_FAILED',
      message: '加载论文失败',
      fieldErrors: {},
    });
    expect(apiErrorMessage({ response: { data: { detail: 'legacy detail' } } }, '加载知识库失败'))
      .toBe('加载知识库失败');
  });

  it('retains explicit local stream failures without surfacing technical network text', () => {
    expect(apiErrorMessage(new Error('SSE 对话失败'), '发送失败')).toBe('SSE 对话失败');
    expect(apiErrorMessage(new Error('Network Error'), '发送失败')).toBe('发送失败');
  });
});
