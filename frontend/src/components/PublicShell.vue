<template>
  <div class="public-shell">
    <header class="public-shell__header">
      <RouterLink class="public-shell__brand" to="/login" aria-label="PaperAgent">
        <img src="/logo.png" alt="" />
        <span>PaperAgent</span>
      </RouterLink>
      <div class="public-shell__tools">
        <LanguageToggle />
        <button
          type="button"
          class="public-shell__theme"
          :aria-label="isDark ? '切换为浅色模式' : '切换为深色模式'"
          @click="toggleTheme"
        >
          {{ isDark ? '☀' : '◐' }}
        </button>
      </div>
    </header>

    <div class="public-shell__content">
      <slot />
    </div>

    <SiteFilingFooter />
  </div>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router';
import LanguageToggle from '@/components/LanguageToggle.vue';
import SiteFilingFooter from '@/components/SiteFilingFooter.vue';
import { useTheme } from '@/composables/useTheme';

const { isDark, toggleTheme } = useTheme();
</script>
