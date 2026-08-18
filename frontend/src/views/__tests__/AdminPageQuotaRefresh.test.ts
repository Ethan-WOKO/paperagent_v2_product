import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('AdminPage Token 用量刷新', () => {
  const source = readFileSync(new URL('../AdminPage.vue', import.meta.url), 'utf8');

  it('定时和重新回到页面时刷新用户额度，并在卸载时清理', () => {
    expect(source).toContain('const ADMIN_USAGE_REFRESH_MS = 15_000');
    expect(source).toContain('window.setInterval(refreshUsageSnapshot, ADMIN_USAGE_REFRESH_MS)');
    expect(source).toContain("document.addEventListener('visibilitychange', handleAdminVisibility)");
    expect(source).toContain('window.clearInterval(usageRefreshTimer)');
    expect(source).toContain("document.removeEventListener('visibilitychange', handleAdminVisibility)");
  });

  it('同时刷新账号列表和当前选中用户的实际 Token 明细', () => {
    expect(source).toContain('listAdminUsers()');
    expect(source).toContain('userId == null ? Promise.resolve(null) : getAdminUser(userId)');
    expect(source).toContain('detail.value = detailResponse.data');
  });
});
