import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const pagePath = fileURLToPath(new URL('../src/views/AdminPage.vue', import.meta.url));
const appPath = fileURLToPath(new URL('../src/App.vue', import.meta.url));
const page = readFileSync(pagePath, 'utf8');
const app = readFileSync(appPath, 'utf8');

describe('admin console presentation contract', () => {
  it('uses a compact master-detail layout with flat metrics and quota controls', () => {
    expect(page).toContain('admin-page--redesign');
    expect(page).toContain('class="admin-layout"');
    expect(page).toContain('class="admin-card admin-users"');
    expect(page).toContain('class="admin-card admin-detail"');
    expect(page).toContain('class="admin-stat-grid"');
    expect(page).toContain('class="admin-quota-form"');
    expect(page).toContain('class="admin-maintenance-menu"');
    expect(page).toContain('class="admin-quota-more"');
    expect(page).toContain('class="admin-user-list"');
    expect(page).toContain('class="admin-list__row admin-invite-row"');
    expect(page).toContain('name="usage"');
    expect(page).toContain('name="chat"');
    expect(page).toContain('name="paper"');
    expect(page).toContain('name="project"');
  });

  it('preserves quota semantics and every existing admin operation', () => {
    for (const operation of [
      'listAdminUsers',
      'getAdminUser',
      'updateAdminQuota',
      'resetAdminQuota',
      'listAdminInviteCodes',
      'deleteAdminUser',
      'generateAdminInviteCode',
      'createAdminInviteCode',
      'deleteAdminInviteCode',
      'deleteDemoMessage',
      'deleteArchivedDemoMessage',
      'clearDemoChats',
      'clearDemoProjects',
    ]) {
      expect(page).toContain(operation);
    }
    expect(page).toContain('saveQuota(false)');
    expect(page).toContain('saveQuota(true)');
    expect(page).toContain("if (user.aiQuotaTotal < 0)");
    expect(page).toContain("detail.user.accountType === 'DEMO'");
    expect(page).toContain('删除账号');
    expect(page).toContain('生成邀请码');
    expect(page).toContain('邀请码已保存');
    expect(page).toContain('已使用 {{ invite.usedCount }} / {{ invite.maxUses }} 人');
    expect(page).toContain('删除后该邀请码立即不可使用');
  });

  it('separates workspace and project conversations without duplicating the data source', () => {
    expect(page).toContain("chat.scope !== 'PROJECT'");
    expect(page).toContain("chat.scope === 'PROJECT'");
    expect(page).toContain('工作区对话');
    expect(page).toContain('项目对话');
    expect(page).toContain('projectNames.get(chat.projectId)');
    expect(page).toContain('chat.messages.length');
    expect(page).toContain('<details v-for="chat in group.chats"');
    expect(page).toContain("detail.user.accountType === 'DEMO' && message.deletable");
    expect(page).toContain('chat.archived');
    expect(page).toContain('往期游客会话');
  });

  it('uses shared theme tokens and opts into true responsive layout', () => {
    expect(page).toContain('var(--pa-surface)');
    expect(page).toContain('var(--pa-line)');
    expect(page).toContain('@media (max-width: 900px)');
    expect(page).toContain('@media (max-width: 760px)');
    expect(page).toContain('min-height: clamp(200px, 28vh, 300px);');
    expect(page).toContain('max-height: clamp(240px, 36vh, 420px);');
    expect(page).toContain('overflow-x: auto;');
    expect(app).toContain("route.path.startsWith('/admin')");
  });
});
