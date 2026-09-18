import { afterEach, describe, expect, it } from 'vitest';
import { messages, useI18n } from '../src/composables/useI18n';

const memoryKeys = (locale: keyof typeof messages) => Object.keys(messages[locale])
  .filter((key) => key === 'nav.memory' || key.startsWith('memory.'))
  .sort();

describe('long-term memory localization', () => {
  afterEach(() => useI18n().setLocale('zh-CN'));

  it('keeps the complete memory key set aligned across zh-CN and en-US', () => {
    expect(memoryKeys('zh-CN')).toEqual(memoryKeys('en-US'));
    expect(memoryKeys('zh-CN').length).toBeGreaterThan(100);
    for (const key of memoryKeys('zh-CN')) {
      expect(messages['zh-CN'][key as keyof typeof messages['zh-CN']].trim()).not.toBe('');
      expect(messages['en-US'][key as keyof typeof messages['en-US']].trim()).not.toBe('');
    }
  });

  it('switches navigation, state, actions, validation, and parameterized errors deterministically', () => {
    const { setLocale, t } = useI18n();

    setLocale('zh-CN');
    expect(t('nav.memory')).toBe('长期记忆');
    expect(t('memory.confirmation.unconfirmed')).toBe('待确认');
    expect(t('memory.action.correct')).toBe('更正');
    expect(t('memory.validation.content')).toBe('请填写记忆内容。');
    expect(t('memory.distillation.progressCount', { processed: 24, total: 92 }))
      .toBe('已处理 24 / 92 条消息');
    expect(t('memory.error.stale', { detail: 'PROJECT memory is stale' }))
      .toContain('请刷新列表');

    setLocale('en-US');
    expect(t('nav.memory')).toBe('Long-term memory');
    expect(t('memory.confirmation.unconfirmed')).toBe('Unconfirmed');
    expect(t('memory.action.correct')).toBe('Correct');
    expect(t('memory.validation.content')).toBe('Memory content is required.');
    expect(t('memory.distillation.progressCount', { processed: 24, total: 92 }))
      .toBe('24 / 92 messages processed');
    expect(t('memory.error.stale', { detail: 'PROJECT memory is stale' }))
      .toContain('Refresh the list');
  });
});
