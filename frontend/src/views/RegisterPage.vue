<template>
  <div class="page-shell auth-shell">
    <div class="auth-panel">
      <div class="auth-brand-block">
        <div class="auth-logo">
          <img src="/logo.png" alt="Yanban Agent logo" />
        </div>
        <div class="workbench-kicker">Yanban Agent</div>
        <h1>{{ t('auth.create') }}</h1>
        <p>{{ t('auth.registerDescription') }}</p>
      </div>
      <NCard class="auth-card" :bordered="false">
        <NForm :model="form" @submit.prevent="handleSubmit">
          <NFormItem :label="t('auth.username')">
            <NInput v-model:value="form.username" size="large" :placeholder="t('auth.usernamePlaceholder')" />
          </NFormItem>
          <NFormItem :label="t('auth.password')">
            <NInput v-model:value="form.password" size="large" type="password" show-password-on="click" :placeholder="t('auth.passwordNewPlaceholder')" />
          </NFormItem>
          <NFormItem :label="t('auth.confirmPassword')">
            <NInput v-model:value="form.confirmPassword" size="large" type="password" show-password-on="click" :placeholder="t('auth.confirmPasswordPlaceholder')" />
          </NFormItem>
          <NFormItem :label="t('auth.inviteCode')">
            <NInput v-model:value="form.inviteCode" size="large" :placeholder="t('auth.inviteCodePlaceholder')" />
          </NFormItem>
          <NSpace vertical size="large">
            <NButton type="primary" size="large" block :loading="submitting" @click="handleSubmit">{{ t('auth.register') }}</NButton>
            <NButton block secondary @click="router.push('/demo')">{{ t('auth.tryDemo') }}</NButton>
            <NButton block quaternary @click="router.push('/login')">{{ t('auth.goLogin') }}</NButton>
          </NSpace>
        </NForm>
      </NCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { NButton, NCard, NForm, NFormItem, NInput, NSpace } from 'naive-ui';
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { ui } from '@/ui';
import { useI18n } from '@/composables/useI18n';

const router = useRouter();
const authStore = useAuthStore();
const { t } = useI18n();
const submitting = ref(false);
const form = reactive({ username: '', password: '', confirmPassword: '', inviteCode: '' });

async function handleSubmit() {
  if (!form.username || !form.password) {
    ui.message.warning('请输入用户名和密码');
    return;
  }
  if (!form.inviteCode) {
    ui.message.warning('请输入邀请码');
    return;
  }
  if (form.password !== form.confirmPassword) {
    ui.message.warning('两次输入的密码不一致');
    return;
  }
  submitting.value = true;
  try {
    await authStore.signUp({ username: form.username, password: form.password, inviteCode: form.inviteCode });
    ui.message.success('注册成功，已自动登录');
    await router.push('/chat');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '注册失败');
  } finally {
    submitting.value = false;
  }
}
</script>
