<template>
  <div class="product-shell">
    <aside class="product-rail">
      <button type="button" class="product-rail__brand" title="PaperAgent" @click="router.push('/chat')">
        <img src="/logo.png" alt="PaperAgent" />
      </button>

      <nav class="product-rail__nav" :aria-label="t('nav.workspace')">
        <button
          v-for="item in navItems"
          :key="item.path"
          type="button"
          class="product-rail__nav-item"
          :class="{ 'product-rail__nav-item--active': isActiveNav(item.path) }"
          :title="item.label"
          :aria-label="item.label"
          @click="router.push(item.path)"
        >
          <AppNavIcon :name="item.icon" />
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <div class="product-rail__spacer" />

      <div class="product-rail__utilities">
        <button type="button" class="product-rail__utility product-rail__quota" :title="`${t('nav.credits')} · ${quotaSummary}`" :aria-label="`${t('nav.credits')} · ${quotaSummary}`">
          AI
        </button>
        <LanguageToggle class="product-rail__language" />
        <button type="button" class="product-rail__utility" :title="themeTitle" :aria-label="themeTitle" @click="toggleTheme">
          {{ isDark ? '☀' : '◐' }}
        </button>
        <div class="product-rail__avatar" :title="authStore.currentUser?.username || t('nav.signedOut')">{{ userInitial }}</div>
        <button type="button" class="product-rail__logout" :title="t('nav.signOut')" :aria-label="t('nav.signOut')" @click="logout">↪</button>
      </div>
    </aside>

    <details class="product-mobile-account">
      <summary :title="authStore.currentUser?.username || t('nav.signedOut')" :aria-label="authStore.currentUser?.username || t('nav.signedOut')">
        {{ userInitial }}
      </summary>
      <div class="product-mobile-account__menu">
        <strong>{{ authStore.currentUser?.username || t('nav.signedOut') }}</strong>
        <span>{{ quotaSummary }}</span>
        <LanguageToggle class="product-mobile-account__language" />
        <button type="button" @click="toggleTheme">{{ themeTitle }}</button>
        <button type="button" @click="logout">{{ t('nav.signOut') }}</button>
      </div>
    </details>

    <main class="product-main">
      <section class="product-content">
        <slot />
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useTheme } from '@/composables/useTheme';
import { useI18n } from '@/composables/useI18n';
import LanguageToggle from '@/components/LanguageToggle.vue';
import AppNavIcon from '@/components/AppNavIcon.vue';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const { isDark, toggleTheme } = useTheme();
const { t } = useI18n();
let quotaRefreshTimer: number | undefined;

const navItems = computed(() => [
  { label: t('nav.workspace'), path: '/chat', icon: 'workspace' },
  { label: t('nav.papers'), path: '/paper', icon: 'paper' },
  { label: t('nav.projects'), path: '/projects', icon: 'projects' },
  { label: t('nav.knowledge'), path: '/knowledge-base', icon: 'knowledge' },
  { label: t('nav.retrieval'), path: '/knowledge-base/search-debug', icon: 'search' },
  { label: t('nav.memory'), path: '/settings/memory', icon: 'memory' },
  { label: t('nav.settings'), path: '/settings', icon: 'settings' },
  ...(authStore.currentUser?.role === 'ADMIN' ? [{ label: '管理后台', path: '/admin', icon: 'admin' }] : []),
]);

const userInitial = computed(() => (authStore.currentUser?.username || 'U').slice(0, 1).toUpperCase());
const themeTitle = computed(() => (isDark.value ? '切换为浅色模式' : '切换为深色模式'));

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
