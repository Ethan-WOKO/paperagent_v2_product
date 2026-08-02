<template>
  <div class="product-shell">
    <aside class="product-rail">
      <button type="button" class="product-rail__brand" title="PaperAgent" @click="router.push('/chat')">
        <img src="/logo.png" alt="PaperAgent" />
      </button>

      <nav class="product-rail__nav" :aria-label="t('nav.workspace')">
        <div class="product-rail__nav-list product-rail__nav-list--desktop">
          <button
            v-for="item in navItems"
            :key="item.path"
            type="button"
            class="product-rail__nav-item"
            :class="{ 'product-rail__nav-item--active': isActiveNav(item.path) }"
            :title="item.label"
            :aria-label="item.label"
            @click="navigateTo(item.path)"
          >
            <AppNavIcon :name="item.icon" />
            <span>{{ item.label }}</span>
          </button>
        </div>

        <div class="product-rail__nav-list product-rail__nav-list--mobile">
          <button
            v-for="item in mobilePrimaryNavItems"
            :key="item.path"
            type="button"
            class="product-rail__nav-item"
            :class="{ 'product-rail__nav-item--active': isActiveNav(item.path) }"
            :title="item.label"
            :aria-label="item.label"
            @click="navigateTo(item.path)"
          >
            <AppNavIcon :name="item.icon" />
            <span>{{ item.label }}</span>
          </button>

          <details ref="mobileMoreRef" class="product-rail__more">
            <summary
              class="product-rail__nav-item product-rail__more-trigger"
              :class="{ 'product-rail__nav-item--active': mobileMoreActive }"
              :title="mobileMoreLabel"
              :aria-label="mobileMoreLabel"
            >
              <span class="product-rail__more-icon" aria-hidden="true">•••</span>
              <span>{{ mobileMoreLabel }}</span>
            </summary>
            <div class="product-rail__more-menu">
              <button
                v-for="item in mobileSecondaryNavItems"
                :key="item.path"
                type="button"
                :class="{ 'product-rail__more-menu-item--active': isActiveNav(item.path) }"
                @click="navigateTo(item.path)"
              >
                <AppNavIcon :name="item.icon" />
                <span>{{ item.label }}</span>
              </button>
            </div>
          </details>
        </div>
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
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
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
const { isEnglish, t } = useI18n();
let quotaRefreshTimer: number | undefined;
const mobileMoreRef = ref<HTMLDetailsElement | null>(null);

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
const mobilePrimaryNavItems = computed(() => navItems.value.slice(0, 5));
const mobileSecondaryNavItems = computed(() => navItems.value.slice(5));
const mobileMoreActive = computed(() => mobileSecondaryNavItems.value.some((item) => isActiveNav(item.path)));
const mobileMoreLabel = computed(() => (isEnglish.value ? 'More' : '更多'));

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

function navigateTo(path: string) {
  if (mobileMoreRef.value) {
    mobileMoreRef.value.open = false;
  }
  void router.push(path);
}

function handleDocumentKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && mobileMoreRef.value?.open) {
    mobileMoreRef.value.open = false;
    mobileMoreRef.value.querySelector<HTMLElement>('summary')?.focus();
  }
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
  document.addEventListener('keydown', handleDocumentKeydown);
  quotaRefreshTimer = window.setInterval(refreshQuota, 30_000);
});

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange);
  document.removeEventListener('keydown', handleDocumentKeydown);
  if (quotaRefreshTimer !== undefined) {
    window.clearInterval(quotaRefreshTimer);
  }
});

async function logout() {
  authStore.clear();
  await router.push('/login');
}
</script>
