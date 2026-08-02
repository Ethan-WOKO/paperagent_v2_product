<template>
  <NConfigProvider :theme="naiveTheme" :theme-overrides="themeOverrides" :locale="naiveLocale" :date-locale="naiveDateLocale">
    <NLoadingBarProvider>
      <NDialogProvider>
        <NNotificationProvider>
          <NMessageProvider>
            <div v-if="useCanvasScale" class="app-scale-root app-scale-root--canvas">
              <RouterView />
            </div>
            <template v-else-if="isAuthenticatedRoute">
              <RouterView />
            </template>
            <div v-else class="public-page">
              <div class="app-scale-root">
                <RouterView />
              </div>
              <SiteFilingFooter />
            </div>
            <LanguageToggle v-if="!isAuthenticatedRoute" class="app-guest-language-toggle" />
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
const MIN_SCALE = 0.2;
const MAX_SCALE = 3.0;

const naiveTheme = computed(() => (isDark.value ? darkTheme : lightTheme));
const naiveLocale = computed(() => (locale.value === 'zh-CN' ? zhCN : enUS));
const naiveDateLocale = computed(() => (locale.value === 'zh-CN' ? dateZhCN : dateEnUS));
const isAuthenticatedRoute = computed(() => route.meta.requiresAuth === true);
const isResponsiveWorkspaceRoute = computed(() => (
  route.path.startsWith('/chat')
  || route.path.startsWith('/projects')
  || route.path.startsWith('/paper')
  || route.path.startsWith('/knowledge-base')
));
// Each redesigned workspace opts into real responsive layout as it is
// migrated. Routes not migrated yet retain the established canvas behavior.
const useCanvasScale = computed(() => isAuthenticatedRoute.value && !isResponsiveWorkspaceRoute.value);
const canvasWidth = computed(() => DEFAULT_CANVAS_WIDTH);

function updateCanvasScale() {
  if (typeof window === 'undefined') return;
  const root = document.documentElement;

  if (isResponsiveWorkspaceRoute.value) {
    // The Project workspace deliberately opts out of the fixed canvas. Reset
    // every canvas dimension so the global non-canvas rules remain viewport
    // sized instead of inheriting a pixel width from a previous route.
    root.style.setProperty('--yb-ui-scale', '1');
    root.style.setProperty('--yb-canvas-width', '100%');
    root.style.setProperty('--yb-canvas-min-height', '100dvh');
    root.style.setProperty('--yb-canvas-height', '100dvh');
    root.style.setProperty('--yb-canvas-vh', '100dvh');
    root.style.setProperty('--yb-canvas-scaled-width', '100%');
    root.style.setProperty('--yb-canvas-scaled-min-height', '100dvh');
    root.style.setProperty('--yb-canvas-scaled-height', '100dvh');
    return;
  }

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
    primaryColor: isDark.value ? '#22aaac' : '#07888b',
    primaryColorHover: isDark.value ? '#35bec0' : '#067579',
    primaryColorPressed: isDark.value ? '#1b9699' : '#05666a',
    primaryColorSuppl: isDark.value ? '#52c7ca' : '#087f86',
    borderRadius: '7px',
    fontFamily: 'Inter, "Segoe UI Variable", "PingFang SC", "Microsoft YaHei", sans-serif',
    fontFamilyMono: '"Geist Mono", ui-monospace, SFMono-Regular, Menlo, monospace',
    ...(isDark.value
      ? {
          bodyColor: '#0b1519',
          cardColor: '#101e23',
          modalColor: '#101e23',
          popoverColor: '#101e23',
          tableColor: '#101e23',
          borderColor: '#26383e',
          dividerColor: '#26383e',
          textColorBase: '#e7efef',
          textColor1: '#e7efef',
          textColor2: '#afbec1',
          textColor3: '#778b91',
          inputColor: '#0f1d22',
          hoverColor: '#193037',
        }
      : {
          bodyColor: '#f7f9f8',
          cardColor: '#ffffff',
          modalColor: '#ffffff',
          popoverColor: '#ffffff',
          tableColor: '#ffffff',
          borderColor: '#dfe7e5',
          dividerColor: '#dfe7e5',
          textColorBase: '#17272d',
          textColor1: '#17272d',
          textColor2: '#52666d',
          textColor3: '#809096',
          inputColor: '#ffffff',
          hoverColor: '#edf4f3',
        }),
  },
  Card: {
    borderRadius: '10px',
  },
  Input: {
    borderRadius: '7px',
  },
  Button: {
    borderRadiusMedium: '7px',
    borderRadiusLarge: '8px',
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
