<template>
  <div
    class="app-frame"
    :class="{
      'app-frame--chat': route.path.startsWith('/chat'),
      'app-frame--paper': route.path.startsWith('/paper'),
      'app-frame--settings': route.path.startsWith('/settings'),
      'app-frame--non-chat': !route.path.startsWith('/chat'),
    }"
  >
    <aside class="app-sidebar">
      <div class="app-sidebar__brand" @click="router.push('/chat')">
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
        'app-workspace--topbar-collapsed': showTopbar && topbarCollapsed,
        'app-workspace--no-topbar': !showTopbar,
        'app-workspace--chat': route.path.startsWith('/chat'),
        'app-workspace--paper': route.path.startsWith('/paper'),
        'app-workspace--settings': route.path.startsWith('/settings'),
      }"
    >
      <header v-if="showTopbar" class="app-topbar">
        <div>
          <h1>{{ routeTitle }}</h1>
        </div>
        <NSpace align="center" :size="10" wrap>
          <NButton secondary round @click="router.push('/chat')">+ {{ t('top.newTask') }}</NButton>
          <NButton secondary round @click="router.push('/paper')">{{ t('top.uploadPaper') }}</NButton>
          <NButton secondary round @click="router.push('/chat')">{{ t('top.searchLiterature') }}</NButton>
          <NButton type="primary" round class="agent-mode-button">{{ t('top.agentLive') }}</NButton>
          <NButton quaternary round size="small" class="theme-toggle-button" @click="toggleTheme">
            {{ isDark ? t('common.light') : t('common.dark') }}
          </NButton>
        </NSpace>
        <button type="button" class="app-topbar__collapse" :title="t('top.hide')" @click="setTopbarCollapsed(true)">-</button>
      </header>

      <button
        v-if="showTopbar && topbarCollapsed"
        type="button"
        class="app-topbar__restore"
        :title="t('top.show')"
        @click="setTopbarCollapsed(false)"
      >
        +
      </button>

      <section class="app-content-shell">
        <slot />
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { NButton, NSpace } from 'naive-ui';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useTheme } from '@/composables/useTheme';
import { useI18n } from '@/composables/useI18n';
import LanguageToggle from '@/components/LanguageToggle.vue';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const { isDark, toggleTheme } = useTheme();
const { t } = useI18n();
const TOPBAR_COLLAPSED_KEY = 'yanban.app.topbarCollapsed';
const topbarCollapsed = ref(readStoredBoolean(TOPBAR_COLLAPSED_KEY, false));
const showTopbar = computed(() => route.path.startsWith('/chat'));
let quotaRefreshTimer: number | undefined;

const navItems = computed(() => [
  { label: t('nav.workspace'), path: '/chat' },
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

const routeTitle = computed(() => {
  if (route.path.startsWith('/paper')) return t('route.paper');
  if (route.path.startsWith('/projects')) return t('route.projects');
  if (route.path.startsWith('/knowledge-base/search-debug')) return t('route.retrieval');
  if (route.path.startsWith('/knowledge-base')) return t('route.knowledge');
  if (route.path.startsWith('/settings')) return t('route.settings');
  if (route.path.startsWith('/admin')) return '管理后台';
  return t('route.chat');
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

function readStoredBoolean(key: string, fallback: boolean) {
  if (typeof window === 'undefined') {
    return fallback;
  }
  const value = window.localStorage.getItem(key);
  if (value == null) {
    return fallback;
  }
  return value === 'true';
}

function setStoredBoolean(key: string, value: boolean) {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(key, String(value));
  }
}

function setTopbarCollapsed(collapsed: boolean) {
  topbarCollapsed.value = collapsed;
  setStoredBoolean(TOPBAR_COLLAPSED_KEY, collapsed);
}

async function logout() {
  authStore.clear();
  await router.push('/login');
}
</script>
