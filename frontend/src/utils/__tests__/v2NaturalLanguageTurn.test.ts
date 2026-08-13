import { describe, expect, it } from 'vitest';

import {
  isCurrentV2NaturalLanguageRequest,
  newV2NaturalLanguageClientRequestId,
  normalizeV2NaturalLanguageRequest,
} from '../v2NaturalLanguageTurn';

describe('V2 自然语言请求', () => {
  it('生成稳定请求编号并规范化单一自然语言输入', () => {
    const clientRequestId = newV2NaturalLanguageClientRequestId(() => 'fixed');
    expect(clientRequestId).toBe('v2-turn-fixed');
    expect(normalizeV2NaturalLanguageRequest('  读取   README 并总结  ', clientRequestId)).toEqual({
      content: '读取   README 并总结',
      clientRequestId,
    });
    expect(() => normalizeV2NaturalLanguageRequest(' ', clientRequestId)).toThrow('content-required');
    expect(() => normalizeV2NaturalLanguageRequest('内容', ' ')).toThrow('client-request-id-required');
  });

  it('项目、会话、请求编号或序列变化都会使旧响应失效', () => {
    const identity = { projectId: 3, sessionId: 7, clientRequestId: 'id', sequence: 1 };
    expect(isCurrentV2NaturalLanguageRequest(identity, identity)).toBe(true);
    expect(isCurrentV2NaturalLanguageRequest(identity, { ...identity, projectId: 4 })).toBe(false);
    expect(isCurrentV2NaturalLanguageRequest(identity, { ...identity, sessionId: 8 })).toBe(false);
    expect(isCurrentV2NaturalLanguageRequest(identity, { ...identity, clientRequestId: 'other' })).toBe(false);
    expect(isCurrentV2NaturalLanguageRequest(identity, { ...identity, sequence: 2 })).toBe(false);
  });
});
