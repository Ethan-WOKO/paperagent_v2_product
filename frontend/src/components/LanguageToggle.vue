<template>
  <div class="language-toggle" :aria-label="t('common.language')">
    <button
      type="button"
      class="active"
      :title="toggleTitle"
      :aria-label="toggleTitle"
      @click="toggleLocale"
    >{{ locale === 'zh-CN' ? '中' : 'EN' }}</button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from '@/composables/useI18n';

const { locale, setLocale, t } = useI18n();
const toggleTitle = computed(() => (
  locale.value === 'zh-CN' ? t('common.english') : t('common.chinese')
));

function toggleLocale() {
  setLocale(locale.value === 'zh-CN' ? 'en-US' : 'zh-CN');
}
</script>
