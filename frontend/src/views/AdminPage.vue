<template>
  <AppLayout>
    <div class="admin-page">
      <div class="admin-page__heading">
        <div>
          <span>ADMIN</span>
          <h2>账号与额度管理</h2>
          <p>查看账号使用情况，管理 AI 额度，以及清理游客体验数据。</p>
        </div>
        <NSpace>
          <NButton secondary :loading="loading" @click="refresh">刷新</NButton>
          <NPopconfirm @positive-click="handleClearDemoChats">
            <template #trigger><NButton secondary type="warning">清空游客聊天</NButton></template>
            将清空游客账号的全部聊天记录，示例问题和论文示例文件不会受影响。
          </NPopconfirm>
          <NPopconfirm @positive-click="handleClearDemoProjects">
            <template #trigger><NButton secondary type="warning">清空游客项目</NButton></template>
            将清空游客创建的项目及其上传文件。
          </NPopconfirm>
        </NSpace>
      </div>

      <div class="admin-layout">
        <NCard class="admin-card admin-users" :bordered="false">
          <template #header>账号列表</template>
          <NSpin :show="loading && users.length === 0">
            <NEmpty v-if="!loading && users.length === 0" description="暂无账号" />
            <button
              v-for="item in users"
              :key="item.id"
              type="button"
              class="admin-user-row"
              :class="{ 'admin-user-row--active': selectedUserId === item.id }"
              @click="selectUser(item.id)"
            >
              <span class="admin-user-row__name">{{ item.username }}</span>
              <NTag size="small" :type="item.accountType === 'DEMO' ? 'warning' : 'default'">{{ item.accountType }}</NTag>
              <small>{{ quotaText(item) }}</small>
            </button>
          </NSpin>
        </NCard>

        <NSpin :show="detailLoading">
          <NCard v-if="detail" class="admin-card admin-detail" :bordered="false">
            <template #header>
              <div class="admin-detail__title">
                <div>
                  <strong>{{ detail.user.username }}</strong>
                  <small>{{ detail.user.accountType }} · {{ detail.user.role }}</small>
                </div>
                <NTag :type="detail.user.aiQuotaTotal < 0 ? 'success' : 'info'" round>{{ quotaText(detail.user) }}</NTag>
              </div>
            </template>

            <div class="admin-stat-grid">
              <div><span>聊天会话</span><strong>{{ detail.user.chatSessionCount }}</strong></div>
              <div><span>论文任务</span><strong>{{ detail.user.paperTaskCount }}</strong></div>
              <div><span>项目数量</span><strong>{{ detail.user.projectCount }}</strong></div>
              <div><span>最后登录</span><strong>{{ formatDate(detail.user.lastLoginAt) }}</strong></div>
            </div>

            <div class="admin-quota-form">
              <div>
                <strong>AI 总额度（Token）</strong>
                <small>-1 表示不限额；重置只会将已用额度归零。</small>
              </div>
              <NInputNumber v-model:value="quotaTotal" :min="-1" :precision="0" class="admin-quota-input" />
              <NButton type="primary" :loading="quotaSaving" @click="saveQuota(false)">保存额度</NButton>
              <NPopconfirm @positive-click="saveQuota(true)">
                <template #trigger><NButton secondary :loading="quotaSaving">保存并重置</NButton></template>
                将使用当前总额度，并把已用额度清零。
              </NPopconfirm>
              <NPopconfirm @positive-click="resetQuota">
                <template #trigger><NButton tertiary type="warning">仅重置已用额度</NButton></template>
                已用额度将归零，总额度不变。
              </NPopconfirm>
            </div>

            <NTabs type="line" animated>
              <NTabPane name="usage" tab="额度明细">
                <NEmpty v-if="detail.usage.length === 0" description="尚无已记录的 AI 使用量" />
                <div v-else class="admin-list">
                  <div v-for="item in detail.usage" :key="item.id" class="admin-list__row">
                    <NTag size="small">{{ item.feature }}</NTag>
                    <span>输入 {{ formatNumber(item.promptTokens) }} / 输出 {{ formatNumber(item.completionTokens) }}</span>
                    <strong>{{ formatNumber(item.totalTokens) }} Token</strong>
                    <small>{{ formatDate(item.createdAt) }}</small>
                  </div>
                </div>
              </NTabPane>
              <NTabPane name="chat" tab="聊天">
                <NEmpty v-if="detail.chats.length === 0" description="暂无聊天" />
                <div v-else class="admin-chat-list">
                  <section v-for="chat in detail.chats" :key="chat.id" class="admin-chat-session">
                    <header>
                      <strong>{{ chat.title || '未命名会话' }}</strong>
                      <small>{{ chat.modelProvider }} / {{ chat.model }} · {{ formatDate(chat.updatedAt) }}</small>
                    </header>
                    <article v-for="message in chat.messages" :key="message.id" class="admin-message">
                      <div><NTag size="small" :type="message.role === 'user' ? 'info' : 'default'">{{ message.role }}</NTag><small>{{ formatDate(message.createdAt) }}</small></div>
                      <p>{{ message.content || '（无文本内容）' }}</p>
                      <NPopconfirm v-if="detail.user.accountType === 'DEMO'" @positive-click="removeDemoMessage(message.id)">
                        <template #trigger><NButton size="tiny" tertiary type="error">删除此消息</NButton></template>
                        确认删除这条游客聊天消息？
                      </NPopconfirm>
                    </article>
                  </section>
                </div>
              </NTabPane>
              <NTabPane name="paper" tab="论文">
                <NEmpty v-if="detail.papers.length === 0" description="暂无论文任务" />
                <div v-else class="admin-list">
                  <div v-for="item in detail.papers" :key="item.id" class="admin-list__row">
                    <strong>{{ item.title }}</strong>
                    <NTag size="small">{{ item.status }}</NTag>
                    <span>{{ item.currentStage || '-' }}</span>
                    <small>{{ item.sourceFilename || '-' }} · {{ formatDate(item.updatedAt) }}</small>
                  </div>
                </div>
              </NTabPane>
              <NTabPane name="project" tab="项目">
                <NEmpty v-if="detail.projects.length === 0" description="暂无项目" />
                <div v-else class="admin-list">
                  <div v-for="item in detail.projects" :key="item.id" class="admin-list__row">
                    <strong>{{ item.name }}</strong>
                    <NTag size="small">{{ item.rootType }}</NTag>
                    <span>{{ item.indexVersion }}</span>
                    <small>{{ formatDate(item.updatedAt) }}</small>
                  </div>
                </div>
              </NTabPane>
            </NTabs>
          </NCard>
          <NCard v-else class="admin-card admin-empty" :bordered="false"><NEmpty description="从左侧选择一个账号" /></NCard>
        </NSpin>
      </div>

      <NCard class="admin-card admin-invites" :bordered="false">
        <template #header>邀请码使用情况</template>
        <NEmpty v-if="invites.length === 0" description="暂无邀请码" />
        <div v-else class="admin-list">
          <div v-for="invite in invites" :key="invite.id" class="admin-list__row">
            <strong>{{ invite.code }}</strong>
            <span>已使用 {{ invite.usedCount }} / {{ invite.maxUses }} 人</span>
            <NTag size="small" :type="invite.enabled ? 'success' : 'default'">{{ invite.enabled ? '可用' : '已停用' }}</NTag>
          </div>
        </div>
      </NCard>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { NButton, NCard, NEmpty, NInputNumber, NPopconfirm, NSpin, NTabPane, NTag, NTabs, NSpace } from 'naive-ui';
import AppLayout from '@/components/AppLayout.vue';
import {
  clearDemoChats,
  clearDemoProjects,
  deleteDemoMessage,
  getAdminUser,
  listAdminInviteCodes,
  listAdminUsers,
  resetAdminQuota,
  updateAdminQuota,
  type AdminInviteCode,
  type AdminUserDetail,
  type AdminUserSummary,
} from '@/api/admin';
import { ui } from '@/ui';

const users = ref<AdminUserSummary[]>([]);
const invites = ref<AdminInviteCode[]>([]);
const detail = ref<AdminUserDetail | null>(null);
const selectedUserId = ref<number | null>(null);
const quotaTotal = ref<number | null>(null);
const loading = ref(false);
const detailLoading = ref(false);
const quotaSaving = ref(false);

function formatNumber(value: number) {
  return Number(value || 0).toLocaleString('zh-CN');
}

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-';
}

function quotaText(user: Pick<AdminUserSummary, 'aiQuotaTotal' | 'aiQuotaUsed' | 'aiQuotaRemaining'>) {
  if (user.aiQuotaTotal < 0) return `已用 ${formatNumber(user.aiQuotaUsed)} Token · 不限额`;
  return `${formatNumber(user.aiQuotaUsed)} / ${formatNumber(user.aiQuotaTotal)} Token`;
}

async function refresh() {
  loading.value = true;
  try {
    const [userResponse, inviteResponse] = await Promise.all([listAdminUsers(), listAdminInviteCodes()]);
    users.value = userResponse.data;
    invites.value = inviteResponse.data;
    if (selectedUserId.value && !users.value.some((item) => item.id === selectedUserId.value)) {
      detail.value = null;
      selectedUserId.value = null;
    }
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '加载管理数据失败');
  } finally {
    loading.value = false;
  }
}

async function selectUser(userId: number) {
  selectedUserId.value = userId;
  detailLoading.value = true;
  try {
    const response = await getAdminUser(userId);
    if (selectedUserId.value !== userId) return;
    detail.value = response.data;
    quotaTotal.value = response.data.user.aiQuotaTotal;
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '加载账号详情失败');
  } finally {
    detailLoading.value = false;
  }
}

async function reloadSelected() {
  if (selectedUserId.value != null) await selectUser(selectedUserId.value);
  await refresh();
}

async function saveQuota(resetUsed: boolean) {
  if (!detail.value || quotaTotal.value == null) return;
  quotaSaving.value = true;
  try {
    await updateAdminQuota(detail.value.user.id, { totalQuota: Math.trunc(quotaTotal.value), resetUsed });
    ui.message.success(resetUsed ? '额度已保存并重置' : '额度已保存');
    await reloadSelected();
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '保存额度失败');
  } finally {
    quotaSaving.value = false;
  }
}

async function resetQuota() {
  if (!detail.value) return;
  quotaSaving.value = true;
  try {
    await resetAdminQuota(detail.value.user.id);
    ui.message.success('已用额度已重置');
    await reloadSelected();
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '重置额度失败');
  } finally {
    quotaSaving.value = false;
  }
}

async function removeDemoMessage(messageId: number) {
  try {
    await deleteDemoMessage(messageId);
    ui.message.success('游客消息已删除');
    await reloadSelected();
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '删除消息失败');
  }
}

async function handleClearDemoChats() {
  try {
    await clearDemoChats();
    ui.message.success('游客聊天已清空');
    await reloadSelected();
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '清空游客聊天失败');
  }
}

async function handleClearDemoProjects() {
  try {
    await clearDemoProjects();
    ui.message.success('游客项目已清空');
    await reloadSelected();
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '清空游客项目失败');
  }
}

onMounted(refresh);
</script>

<style scoped>
.admin-page { max-width: 1380px; margin: 0 auto; display: grid; gap: 16px; }
.admin-page__heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; padding: 6px 4px; }
.admin-page__heading span { color: var(--yb-link); font-size: 12px; letter-spacing: .16em; font-weight: 800; }
.admin-page__heading h2 { margin: 5px 0; font-size: 28px; letter-spacing: -.03em; }
.admin-page__heading p { margin: 0; color: var(--yb-text-muted); }
.admin-layout { display: grid; grid-template-columns: 300px minmax(0, 1fr); gap: 16px; align-items: start; }
.admin-card { border: 1px solid var(--yb-border); box-shadow: var(--yb-shadow-panel); }
.admin-users { max-height: 680px; overflow: auto; }
.admin-user-row { width: 100%; display: grid; grid-template-columns: 1fr auto; gap: 7px; text-align: left; padding: 12px; border: 0; border-bottom: 1px solid var(--yb-border); color: inherit; background: transparent; cursor: pointer; }
.admin-user-row:hover, .admin-user-row--active { background: var(--yb-sidebar-active); }
.admin-user-row__name { font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.admin-user-row small { grid-column: 1 / -1; color: var(--yb-text-muted); font-size: 12px; }
.admin-detail__title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.admin-detail__title strong { display: block; font-size: 18px; }
.admin-detail__title small { color: var(--yb-text-muted); }
.admin-stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 18px; }
.admin-stat-grid > div { padding: 12px; border-radius: 12px; background: var(--yb-bg-subtle); }
.admin-stat-grid span, .admin-stat-grid strong { display: block; }
.admin-stat-grid span { color: var(--yb-text-muted); font-size: 12px; }
.admin-stat-grid strong { margin-top: 5px; font-size: 15px; overflow-wrap: anywhere; }
.admin-quota-form { display: flex; align-items: center; gap: 10px; padding: 14px; margin-bottom: 18px; border: 1px solid var(--yb-border); border-radius: 12px; }
.admin-quota-form > div:first-child { flex: 1; min-width: 180px; }
.admin-quota-form strong, .admin-quota-form small { display: block; }
.admin-quota-form small { margin-top: 4px; color: var(--yb-text-muted); font-size: 12px; }
.admin-quota-input { width: 150px; }
.admin-list { display: grid; gap: 8px; }
.admin-list__row { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 11px 0; border-bottom: 1px solid var(--yb-border); }
.admin-list__row strong { min-width: 140px; }
.admin-list__row small { margin-left: auto; color: var(--yb-text-muted); }
.admin-chat-list { display: grid; gap: 12px; }
.admin-chat-session { border: 1px solid var(--yb-border); border-radius: 12px; overflow: hidden; }
.admin-chat-session > header { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding: 10px 12px; background: var(--yb-bg-subtle); }
.admin-chat-session > header small, .admin-message small { color: var(--yb-text-muted); font-size: 12px; }
.admin-message { padding: 12px; border-top: 1px solid var(--yb-border); }
.admin-message > div { display: flex; align-items: center; gap: 8px; }
.admin-message p { margin: 8px 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.55; }
.admin-empty { min-height: 220px; display: grid; place-items: center; }
.admin-invites { max-width: 720px; }
@media (max-width: 900px) { .admin-page__heading, .admin-quota-form { align-items: stretch; flex-direction: column; } .admin-layout { grid-template-columns: 1fr; } .admin-users { max-height: 300px; } .admin-stat-grid { grid-template-columns: repeat(2, 1fr); } .admin-quota-input { width: 100%; } .admin-list__row small { width: 100%; margin-left: 0; } }
</style>
