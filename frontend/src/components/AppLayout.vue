<template>
  <div
    class="app-frame"
    :class="{
      'app-frame--paper': route.path.startsWith('/paper'),
      'app-frame--project': route.path.startsWith('/projects'),
      'app-frame--settings': route.path.startsWith('/settings'),
      'app-frame--non-chat': true,
    }"
  >
    <aside class="app-sidebar">
      <div class="app-sidebar__brand" @click="router.push('/projects')">
        <div class="app-sidebar__logo">
          <img src="/logo.png" alt="" />
        </div>
        <div>
          <div class="app-sidebar__name">ScholarAI</div>
          <div class="app-sidebar__sub">Research Copilot</div>
        </div>
      </div>

      <div class="app-sidebar__search">
        <span>S</span>
        <span>{{ t('nav.search') }}</span>
        <kbd>Ctrl+K</kbd>
      </div>

      <nav class="app-sidebar__nav" :aria-label="t('nav.workspace')">
        <button
          v-for="item in navItems"
          :key="item.path"
          type="button"
          class="app-sidebar__nav-item"
          :class="{ 'app-sidebar__nav-item--active': isActiveNav(item.path) }"
          @click="router.push(item.path)"
        >
          <span>{{ item.label }}</span>
        </button>
      </nav>


      <div class="app-sidebar__spacer" />

      <LanguageToggle class="app-sidebar__language" />

      <div class="app-sidebar__plan" aria-live="polite">
        <div>
          <strong>{{ t('nav.credits') }}</strong>
          <span>{{ quotaSummary }}</span>
        </div>
        <div v-if="hasLimitedQuota" class="app-sidebar__meter" :aria-label="quotaSummary">
          <span :style="quotaMeterStyle" />
        </div>
      </div>

      <div class="app-sidebar__user">
        <div class="app-sidebar__avatar">{{ userInitial }}</div>
        <div class="app-sidebar__user-info">
          <strong>{{ authStore.currentUser?.username || t('nav.signedOut') }}</strong>
          <span>{{ t('nav.researcher') }}</span>
        </div>
        <button type="button" class="app-sidebar__logout" @click="logout">{{ t('nav.signOut') }}</button>
      </div>
    </aside>

    <main
      class="app-workspace"
      :class="{
        'app-workspace--no-topbar': true,
        'app-workspace--paper': route.path.startsWith('/paper'),
        'app-workspace--settings': route.path.startsWith('/settings'),
      }"
    >
      <section class="app-content-shell">
        <slot />
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useI18n } from '@/composables/useI18n';
import LanguageToggle from '@/components/LanguageToggle.vue';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const { t } = useI18n();
let quotaRefreshTimer: number | undefined;

const navItems = computed(() => [
  { label: t('nav.papers'), path: '/paper' },
  { label: t('nav.projects'), path: '/projects' },
  { label: t('nav.knowledge'), path: '/knowledge-base' },
  { label: t('nav.retrieval'), path: '/knowledge-base/search-debug' },
  { label: t('nav.memory'), path: '/settings/memory' },
  { label: t('nav.settings'), path: '/settings' },
  ...(authStore.currentUser?.role === 'ADMIN' ? [{ label: '管理后台', path: '/admin' }] : []),
]);

const userInitial = computed(() => (authStore.currentUser?.username || 'U').slice(0, 1).toUpperCase());

const hasLimitedQuota = computed(() => {
  const total = authStore.currentUser?.aiQuotaTotal;
  return typeof total === 'number' && total >= 0;
});

const quotaSummary = computed(() => {
  const user = authStore.currentUser;
  if (!user) return t('nav.quotaLoginHint');
  if (user.aiQuotaTotal < 0) return t('nav.quotaUnlimited');
  return t('nav.quotaUsage', {
    used: formatTokenCount(user.aiQuotaUsed),
    total: formatTokenCount(user.aiQuotaTotal),
  });
});

const quotaMeterStyle = computed(() => {
  const user = authStore.currentUser;
  if (!user || user.aiQuotaTotal <= 0) return { width: '0%' };
  const percentage = Math.min(100, Math.max(0, (user.aiQuotaUsed / user.aiQuotaTotal) * 100));
  return { width: `${percentage}%` };
});

function isActiveNav(path: string) {
  if (path === '/knowledge-base' || path === '/settings') {
    return route.path === path;
  }
  return route.path === path || route.path.startsWith(`${path}/`);
}

function formatTokenCount(value: number) {
  return Number(value || 0).toLocaleString('zh-CN');
}

function refreshQuota() {
  if (authStore.token && document.visibilityState === 'visible') {
    void authStore.fetchCurrentUser();
  }
}

function handleVisibilityChange() {
  if (document.visibilityState === 'visible') {
    refreshQuota();
  }
}

onMounted(() => {
  refreshQuota();
  document.addEventListener('visibilitychange', handleVisibilityChange);
  quotaRefreshTimer = window.setInterval(refreshQuota, 30_000);
});

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange);
  if (quotaRefreshTimer !== undefined) {
    window.clearInterval(quotaRefreshTimer);
  }
});

async function logout() {
  authStore.clear();
  await router.push('/login');
}
</script>
