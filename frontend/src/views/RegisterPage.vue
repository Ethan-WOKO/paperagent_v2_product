<template>
  <PublicAccessLayout>
    <template #intro>
      <div class="public-access__intro-copy">
        <h1>{{ t('auth.create') }}</h1>
        <p>{{ t('auth.registerDescription') }}</p>
      </div>
    </template>

    <header class="public-access__form-heading">
      <span>{{ t('auth.register') }}</span>
      <p>{{ t('auth.registerDescription') }}</p>
    </header>
    <NAlert v-if="submitError" type="error" :show-icon="false" class="public-access__error">{{ submitError }}</NAlert>
    <NForm :model="form" @submit.prevent="handleSubmit">
      <NFormItem :label="t('auth.username')" :validation-status="fieldErrors.username ? 'error' : undefined" :feedback="fieldErrors.username">
        <NInput v-model:value="form.username" size="large" :placeholder="t('auth.usernamePlaceholder')" />
      </NFormItem>
      <NFormItem :label="t('auth.password')" :validation-status="fieldErrors.password ? 'error' : undefined" :feedback="fieldErrors.password">
        <NInput v-model:value="form.password" size="large" type="password" show-password-on="click" :placeholder="t('auth.passwordNewPlaceholder')" />
      </NFormItem>
      <NFormItem :label="t('auth.confirmPassword')" :validation-status="fieldErrors.confirmPassword ? 'error' : undefined" :feedback="fieldErrors.confirmPassword">
        <NInput v-model:value="form.confirmPassword" size="large" type="password" show-password-on="click" :placeholder="t('auth.confirmPasswordPlaceholder')" />
      </NFormItem>
      <NFormItem :label="t('auth.inviteCode')" :validation-status="fieldErrors.inviteCode ? 'error' : undefined" :feedback="fieldErrors.inviteCode">
        <NInput v-model:value="form.inviteCode" size="large" :placeholder="t('auth.inviteCodePlaceholder')" />
      </NFormItem>
      <NSpace vertical size="large" class="public-access__actions">
        <NButton type="primary" size="large" block :loading="submitting" @click="handleSubmit">{{ t('auth.register') }}</NButton>
        <NButton block secondary @click="router.push('/demo')">{{ t('auth.tryDemo') }}</NButton>
        <NButton block quaternary @click="router.push('/login')">{{ t('auth.goLogin') }}</NButton>
      </NSpace>
    </NForm>
  </PublicAccessLayout>
</template>

<script setup lang="ts">
import { NAlert, NButton, NForm, NFormItem, NInput, NSpace } from 'naive-ui';
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { ui } from '@/ui';
import { useI18n } from '@/composables/useI18n';
import PublicAccessLayout from '@/components/PublicAccessLayout.vue';
import {
  registrationFailure,
  validateRegistration,
  type RegistrationFieldErrors,
} from '@/auth/registration';

const router = useRouter();
const authStore = useAuthStore();
const { t } = useI18n();
const submitting = ref(false);
const form = reactive({ username: '', password: '', confirmPassword: '', inviteCode: '' });
const fieldErrors = reactive<RegistrationFieldErrors>({});
const submitError = ref('');

async function handleSubmit() {
  clearErrors();
  const validation = validateRegistration(form);
  Object.assign(fieldErrors, validation);
  const firstError = Object.values(validation)[0];
  if (firstError) {
    submitError.value = firstError;
    ui.message.warning(firstError);
    return;
  }
  submitting.value = true;
  try {
    await authStore.signUp({
      username: form.username.trim(),
      password: form.password,
      inviteCode: form.inviteCode.trim(),
    });
    ui.message.success('注册成功，已自动登录');
    await router.push('/chat');
  } catch (error: unknown) {
    const failure = registrationFailure(error);
    Object.assign(fieldErrors, failure.fieldErrors);
    submitError.value = failure.message;
    ui.message.error(failure.message);
  } finally {
    submitting.value = false;
  }
}

function clearErrors() {
  submitError.value = '';
  for (const field of Object.keys(fieldErrors) as Array<keyof RegistrationFieldErrors>) {
    delete fieldErrors[field];
  }
}
</script>
