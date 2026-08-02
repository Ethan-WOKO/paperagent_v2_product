<template>
  <AppLayout>
    <main class="admin-page admin-page--redesign workbench-page">
      <div class="admin-page__heading">
        <div>
          <span>ADMIN CONSOLE</span>
          <h1>账号与额度管理</h1>
          <p>查看账号使用情况，管理 AI 额度，以及清理游客体验数据。</p>
        </div>
        <div class="admin-heading-actions">
          <NButton secondary :loading="loading" @click="refresh">刷新</NButton>
          <details class="admin-maintenance-menu">
            <summary>游客数据</summary>
            <div>
              <NPopconfirm @positive-click="handleClearDemoChats">
                <template #trigger><NButton secondary type="warning">清空游客聊天</NButton></template>
                将清空游客账号的全部聊天记录，示例问题和论文示例文件不会受影响。
              </NPopconfirm>
              <NPopconfirm @positive-click="handleClearDemoProjects">
                <template #trigger><NButton secondary type="warning">清空游客项目</NButton></template>
                将清空游客创建的项目及其上传文件。
              </NPopconfirm>
            </div>
          </details>
        </div>
      </div>

      <div class="admin-layout">
        <NCard class="admin-card admin-users" :bordered="false">
          <template #header>账号列表</template>
          <NSpin :show="loading && users.length === 0">
            <div class="admin-user-list">
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
            </div>
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
              <details class="admin-quota-more">
                <summary>更多额度操作</summary>
                <div>
                  <NPopconfirm @positive-click="saveQuota(true)">
                    <template #trigger><NButton secondary :loading="quotaSaving">保存并重置已用额度</NButton></template>
                    将使用当前总额度，并把已用额度清零。
                  </NPopconfirm>
                  <NPopconfirm @positive-click="resetQuota">
                    <template #trigger><NButton tertiary type="warning">仅重置已用额度</NButton></template>
                    已用额度将归零，总额度不变。
                  </NPopconfirm>
                </div>
              </details>
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
          <div v-for="invite in invites" :key="invite.id" class="admin-list__row admin-invite-row">
            <strong :title="invite.code">{{ invite.code }}</strong>
            <span>已使用 {{ invite.usedCount }} / {{ invite.maxUses }} 人</span>
            <NTag size="small" :type="invite.enabled ? 'success' : 'default'">{{ invite.enabled ? '可用' : '已停用' }}</NTag>
          </div>
        </div>
      </NCard>
    </main>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { NButton, NCard, NEmpty, NInputNumber, NPopconfirm, NSpin, NTabPane, NTag, NTabs } from 'naive-ui';
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
.admin-page {
  display: grid;
  gap: 12px;
  width: min(1500px, calc(100% - 40px));
  max-width: none;
  min-height: 100dvh;
  margin: 0 auto;
  padding: 20px 0 40px;
  color: var(--pa-text);
}

.admin-page__heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  padding: 0 0 14px;
  border-bottom: 1px solid var(--pa-line);
}

.admin-page__heading span {
  color: var(--pa-accent);
  font-size: 8px;
  font-weight: 760;
  letter-spacing: .12em;
}

.admin-page__heading h1 {
  margin: 3px 0;
  color: var(--pa-text);
  font-size: 22px;
  font-weight: 680;
  letter-spacing: -.02em;
}

.admin-page__heading p {
  margin: 0;
  color: var(--pa-text-muted);
  font-size: 10px;
}

.admin-page__heading :deep(.n-button),
.admin-quota-form :deep(.n-button) {
  min-height: 36px;
  border-radius: var(--pa-radius-sm) !important;
  box-shadow: none !important;
  font-size: 11px;
}

.admin-heading-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.admin-maintenance-menu,
.admin-quota-more {
  position: relative;
}

.admin-maintenance-menu > summary,
.admin-quota-more > summary {
  min-height: 36px;
  display: inline-flex;
  align-items: center;
  padding: 0 12px;
  border: 1px solid var(--pa-line);
  border-radius: var(--pa-radius-sm);
  color: var(--pa-text-secondary);
  background: var(--pa-surface-muted);
  cursor: pointer;
  font-size: 11px;
  list-style: none;
}

.admin-maintenance-menu > summary::-webkit-details-marker,
.admin-quota-more > summary::-webkit-details-marker {
  display: none;
}

.admin-maintenance-menu > summary::after,
.admin-quota-more > summary::after {
  margin-left: 7px;
  color: var(--pa-text-muted);
  content: '⌄';
}

.admin-maintenance-menu[open] > summary,
.admin-quota-more[open] > summary {
  border-color: var(--pa-line-strong);
  color: var(--pa-text);
}

.admin-maintenance-menu > div,
.admin-quota-more > div {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  z-index: 20;
  display: grid;
  gap: 6px;
  min-width: 210px;
  padding: 8px;
  border: 1px solid var(--pa-line-strong);
  border-radius: var(--pa-radius-sm);
  background: var(--pa-surface);
  box-shadow: var(--pa-shadow-float);
}

.admin-maintenance-menu > div :deep(.n-button),
.admin-quota-more > div :deep(.n-button) {
  width: 100%;
  justify-content: flex-start;
}

.admin-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  align-items: start;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--pa-line);
  border-radius: var(--pa-radius-sm);
  background: var(--pa-surface);
}

.admin-card {
  border: 0 !important;
  border-radius: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
}

.admin-card :deep(.n-card-header) {
  min-height: 42px;
  padding: 12px !important;
  border-bottom: 1px solid var(--pa-line);
  color: var(--pa-text);
  font-size: 11px;
  font-weight: 680;
}

.admin-card :deep(.n-card__content) {
  padding: 0 !important;
}

.admin-users {
  max-height: min(62vh, 560px);
  overflow: auto;
  overscroll-behavior: contain;
  border-right: 1px solid var(--pa-line) !important;
}

.admin-user-list {
  display: grid;
}

.admin-user-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 5px;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  border-bottom: 1px solid var(--pa-line);
  color: var(--pa-text-secondary);
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.admin-user-row:hover,
.admin-user-row--active {
  color: var(--pa-text);
  background: var(--pa-accent-soft);
}

.admin-user-row--active {
  box-shadow: inset 2px 0 0 var(--pa-accent);
}

.admin-user-row__name {
  overflow: hidden;
  font-size: 11px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin-user-row small {
  grid-column: 1 / -1;
  color: var(--pa-text-muted);
  font-size: 10px;
}

.admin-detail :deep(.n-card-header) {
  padding: 12px 16px !important;
}

.admin-detail :deep(.n-card__content) {
  padding: 0 16px 14px !important;
}

.admin-detail__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.admin-detail__title strong {
  display: block;
  color: var(--pa-text);
  font-size: 13px;
}

.admin-detail__title small {
  color: var(--pa-text-muted);
  font-size: 10px;
}

.admin-stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  margin: 0 -16px;
  border-bottom: 1px solid var(--pa-line);
}

.admin-stat-grid > div {
  min-width: 0;
  padding: 12px 16px;
  border-right: 1px solid var(--pa-line);
  border-radius: 0;
  background: transparent;
}

.admin-stat-grid span,
.admin-stat-grid strong {
  display: block;
}

.admin-stat-grid span {
  color: var(--pa-text-muted);
  font-size: 10px;
}

.admin-stat-grid strong {
  margin-top: 4px;
  overflow-wrap: anywhere;
  color: var(--pa-text);
  font-size: 12px;
  font-weight: 630;
}

.admin-quota-form {
  display: grid;
  grid-template-columns: minmax(190px, 1fr) 150px auto auto;
  align-items: center;
  gap: 7px;
  margin: 0 -16px 8px;
  padding: 12px 16px;
  border: 0;
  border-bottom: 1px solid var(--pa-line);
  border-radius: 0;
}

.admin-quota-form strong,
.admin-quota-form small {
  display: block;
}

.admin-quota-form strong {
  color: var(--pa-text);
  font-size: 11px;
}

.admin-quota-form small {
  margin-top: 3px;
  color: var(--pa-text-muted);
  font-size: 10px;
}

.admin-quota-input {
  width: 150px;
}

.admin-detail :deep(.n-tabs-nav) {
  min-height: 38px;
  border-bottom: 1px solid var(--pa-line);
}

.admin-detail :deep(.n-tabs-tab) {
  padding: 8px 11px !important;
  font-size: 10px;
}

.admin-list,
.admin-chat-list {
  display: grid;
  gap: 0;
}

.admin-list__row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 9px;
  min-height: 40px;
  padding: 9px 2px;
  border-bottom: 1px solid var(--pa-line);
  color: var(--pa-text-secondary);
  font-size: 10px;
}

.admin-list__row strong {
  min-width: 130px;
  color: var(--pa-text);
  font-size: 11px;
}

.admin-list__row small {
  margin-left: auto;
  color: var(--pa-text-muted);
  font-size: 10px;
}

.admin-chat-session {
  overflow: hidden;
  border: 0;
  border-bottom: 1px solid var(--pa-line);
  border-radius: 0;
}

.admin-chat-session > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 2px;
  background: transparent;
}

.admin-chat-session > header small,
.admin-message small {
  color: var(--pa-text-muted);
  font-size: 10px;
}

.admin-message {
  padding: 9px 2px 9px 18px;
  border-top: 1px solid var(--pa-line);
}

.admin-message > div {
  display: flex;
  align-items: center;
  gap: 7px;
}

.admin-message p {
  margin: 6px 0;
  overflow-wrap: anywhere;
  color: var(--pa-text-secondary);
  font-size: 11px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.admin-empty {
  display: grid;
  min-height: clamp(200px, 28vh, 300px);
  place-items: center;
}

.admin-invites {
  max-width: none;
  border: 1px solid var(--pa-line) !important;
  border-radius: var(--pa-radius-sm) !important;
  background: var(--pa-surface) !important;
}

.admin-invites :deep(.n-card__content) {
  padding: 0 12px 10px !important;
}

.admin-invites .admin-list {
  max-height: clamp(240px, 36vh, 420px);
  overflow: auto;
  overscroll-behavior: contain;
}

.admin-invite-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
}

.admin-invite-row strong {
  min-width: 0;
  overflow: hidden;
  font-family: var(--pa-font-mono, ui-monospace, monospace);
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1080px) {
  .admin-quota-form {
    grid-template-columns: minmax(180px, 1fr) 150px auto auto;
  }
}

@media (max-width: 900px) {
  .admin-page {
    width: min(100% - 28px, 760px);
  }

  .admin-page__heading {
    align-items: stretch;
    flex-direction: column;
  }

  .admin-heading-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .admin-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .admin-users {
    max-height: none;
    overflow: visible;
    border-right: 0 !important;
    border-bottom: 1px solid var(--pa-line) !important;
  }

  .admin-user-list {
    display: flex;
    gap: 0;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scrollbar-width: none;
  }

  .admin-user-list::-webkit-scrollbar {
    display: none;
  }

  .admin-user-row {
    flex: 0 0 220px;
    border-right: 1px solid var(--pa-line);
  }

  .admin-stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .admin-quota-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .admin-quota-form > div:first-child {
    grid-column: 1 / -1;
  }

  .admin-quota-form > :last-child {
    grid-column: auto;
  }

  .admin-quota-input {
    width: 100%;
  }
}

@media (max-width: 760px) {
  .admin-page {
    width: 100%;
    min-height: calc(100dvh - 60px);
    padding: 12px 12px 72px;
  }

  .admin-page__heading {
    padding-right: 38px;
  }

  .admin-heading-actions {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .admin-heading-actions > .n-button {
    min-height: 44px;
  }

  .admin-maintenance-menu > summary {
    min-height: 44px;
  }

  .admin-layout {
    min-height: 0;
  }

  .admin-detail :deep(.n-card__content) {
    padding: 0 10px 12px !important;
  }

  .admin-stat-grid,
  .admin-quota-form {
    margin-right: -10px;
    margin-left: -10px;
  }

  .admin-stat-grid > div,
  .admin-quota-form {
    padding-right: 10px;
    padding-left: 10px;
  }

  .admin-quota-form {
    grid-template-columns: minmax(0, 1fr);
  }

  .admin-quota-form > div:first-child {
    grid-column: 1;
  }

  .admin-quota-form > .n-button,
  .admin-quota-more > summary {
    width: 100%;
    min-height: 44px;
    justify-content: center;
  }

  .admin-quota-more > div,
  .admin-maintenance-menu > div {
    position: fixed;
    inset: auto 12px calc(72px + env(safe-area-inset-bottom)) 12px;
    min-width: 0;
  }

  .admin-list__row small {
    width: 100%;
    margin-left: 0;
  }

  .admin-invite-row {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .admin-invite-row > span {
    grid-column: 1 / -1;
  }

  .admin-invite-row strong {
    overflow: visible;
    overflow-wrap: anywhere;
    text-overflow: clip;
    white-space: normal;
  }

  .admin-chat-session > header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
