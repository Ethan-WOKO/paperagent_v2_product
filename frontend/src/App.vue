<template>
  <NConfigProvider :theme="naiveTheme" :theme-overrides="themeOverrides" :locale="naiveLocale" :date-locale="naiveDateLocale">
    <NLoadingBarProvider>
      <NDialogProvider>
        <NNotificationProvider>
          <NMessageProvider>
            <div v-if="useCanvasScale" class="app-scale-root app-scale-root--canvas">
              <RouterView />
            </div>
            <div v-else class="public-page">
              <div class="app-scale-root">
                <RouterView />
              </div>
              <SiteFilingFooter />
            </div>
            <LanguageToggle v-if="!useCanvasScale" class="app-guest-language-toggle" />
          </NMessageProvider>
        </NNotificationProvider>
      </NDialogProvider>
    </NLoadingBarProvider>
  </NConfigProvider>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from 'vue';
import {
  NConfigProvider,
  NDialogProvider,
  NLoadingBarProvider,
  NMessageProvider,
  NNotificationProvider,
  darkTheme,
  dateEnUS,
  dateZhCN,
  enUS,
  lightTheme,
  zhCN,
} from 'naive-ui';
import { RouterView } from 'vue-router';
import { useRoute } from 'vue-router';
import { useTheme } from '@/composables/useTheme';
import LanguageToggle from '@/components/LanguageToggle.vue';
import SiteFilingFooter from '@/components/SiteFilingFooter.vue';
import { useI18n } from '@/composables/useI18n';
import { useUiTranslationBridge } from '@/composables/useUiTranslationBridge';

const { isDark } = useTheme();
const { locale } = useI18n();
useUiTranslationBridge(locale);
const route = useRoute();
// Fixed design canvas: the authenticated app is rendered at this exact size,
// then uniformly scaled (contain-fit = min of width/height ratio) to fill the
// viewport and centered — like zooming a photo. Component layout never
// reflows; only the whole canvas scales.
const DEFAULT_CANVAS_WIDTH = 1600;
const CHAT_CANVAS_WIDTH = 1728;
const MIN_SCALE = 0.2;
const MAX_SCALE = 3.0;

const naiveTheme = computed(() => (isDark.value ? darkTheme : lightTheme));
const naiveLocale = computed(() => (locale.value === 'zh-CN' ? zhCN : enUS));
const naiveDateLocale = computed(() => (locale.value === 'zh-CN' ? dateZhCN : dateEnUS));
const useCanvasScale = computed(() => route.meta.requiresAuth === true);
const canvasWidth = computed(() => (route.path.startsWith('/chat') ? CHAT_CANVAS_WIDTH : DEFAULT_CANVAS_WIDTH));

function updateCanvasScale() {
  if (typeof window === 'undefined') return;
  const designWidth = canvasWidth.value;
  const viewportWidth = Math.max(320, window.innerWidth || designWidth);
  const viewportHeight = Math.max(320, window.innerHeight || 720);
  // Width-fit uniform scaling: scale the fixed 1440-wide design to fill the
  // viewport width. The canvas height flows (viewportHeight / scale) so the
  // scaled canvas also fills the viewport height exactly — no letterboxing on
  // either axis, nothing gets clipped. Long content scrolls inside the canvas.
  // Horizontal component layout never reflows; only the whole canvas zooms.
  const scale = Math.min(
    MAX_SCALE,
    Math.max(MIN_SCALE, viewportWidth / designWidth)
  );
  const canvasHeight = viewportHeight / scale;
  const root = document.documentElement;
  root.style.setProperty('--yb-ui-scale', scale.toFixed(4));
  root.style.setProperty('--yb-canvas-width', `${designWidth}px`);
  root.style.setProperty('--yb-canvas-height', `${canvasHeight}px`);
  root.style.setProperty('--yb-canvas-vh', `${canvasHeight}px`);
  root.style.setProperty('--yb-canvas-scaled-width', `${viewportWidth}px`);
  root.style.setProperty('--yb-canvas-scaled-height', `${viewportHeight}px`);
}

function syncCanvasClass(active: boolean) {
  if (typeof document === 'undefined') return;
  document.documentElement.classList.toggle('canvas-scale-active', active);
}

watch(
  () => useCanvasScale.value,
  (active) => {
    syncCanvasClass(active);
    updateCanvasScale();
  },
  { immediate: true }
);

onMounted(() => {
  syncCanvasClass(useCanvasScale.value);
  updateCanvasScale();
  window.addEventListener('resize', updateCanvasScale);
  window.visualViewport?.addEventListener('resize', updateCanvasScale);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateCanvasScale);
  window.visualViewport?.removeEventListener('resize', updateCanvasScale);
  document.documentElement.classList.remove('canvas-scale-active');
});

const themeOverrides = computed(() => ({
  common: {
    primaryColor: isDark.value ? '#6d5dfc' : '#5b5cf6',
    primaryColorHover: isDark.value ? '#8275ff' : '#4f46e5',
    primaryColorPressed: isDark.value ? '#5a4be7' : '#4338ca',
    primaryColorSuppl: isDark.value ? '#22d3ee' : '#2563eb',
    borderRadius: '12px',
    fontFamily: 'Geist, Inter, Arial, "PingFang SC", "Microsoft YaHei", sans-serif',
    fontFamilyMono: '"Geist Mono", ui-monospace, SFMono-Regular, Menlo, monospace',
    ...(isDark.value
      ? {
          bodyColor: '#07101d',
          cardColor: '#101827',
          modalColor: '#101827',
          popoverColor: '#101827',
          tableColor: '#101827',
          borderColor: 'rgba(148, 163, 184, 0.18)',
          dividerColor: 'rgba(148, 163, 184, 0.16)',
          textColorBase: '#eef4ff',
          textColor1: '#eef4ff',
          textColor2: '#aeb9cc',
          textColor3: '#748198',
          inputColor: '#0b1423',
          hoverColor: '#172238',
        }
      : {
          bodyColor: '#fafafa',
          cardColor: '#ffffff',
          modalColor: '#ffffff',
          popoverColor: '#ffffff',
          tableColor: '#ffffff',
          borderColor: '#ebebeb',
          dividerColor: '#ebebeb',
          textColorBase: '#171717',
          textColor1: '#171717',
          textColor2: '#4d4d4d',
          textColor3: '#8f8f8f',
          inputColor: '#ffffff',
          hoverColor: '#f2f2f2',
        }),
  },
  Card: {
    borderRadius: '18px',
  },
  Input: {
    borderRadius: '14px',
  },
  Button: {
    borderRadiusMedium: '12px',
    borderRadiusLarge: '999px',
  },
}));
</script>

<style scoped>
.public-page {
  display: grid;
  min-height: 100dvh;
  grid-template-rows: minmax(0, 1fr) auto;
}

.public-page :deep(.page-shell),
.public-page :deep(.demo-page) {
  min-height: 100%;
}
</style>
