<template>
  <main class="public-demo">
    <section class="public-demo__workspace">
      <div class="public-demo__intro">
        <div class="public-demo__copy">
          <div class="public-demo__eyebrow">PAPERAGENT DEMO</div>
          <h1>{{ t('demo.title') }}</h1>
          <p>{{ t('demo.description') }}</p>
          <div class="public-demo__actions">
            <NButton type="primary" size="large" :loading="starting" :disabled="config?.enabled === false" @click="handleStartDemo">
              {{ t('demo.start') }}
            </NButton>
            <NButton size="large" secondary @click="router.push('/login')">{{ t('demo.login') }}</NButton>
          </div>
          <NAlert v-if="config && !config.enabled" type="warning" :title="t('demo.disabledTitle')">
            {{ t('demo.disabledBody') }}
          </NAlert>
          <NAlert v-else type="info" :title="t('demo.noticeTitle')">
            {{ isEnglish ? t('demo.notice') : (config?.notice || t('demo.notice')) }}
          </NAlert>
        </div>

        <section class="public-demo__questions">
          <header>{{ t('demo.questions') }}</header>
          <NSpin :show="loadingConfig">
            <div class="public-demo__question-list">
              <button
                v-for="question in exampleQuestions"
                :key="question"
                type="button"
                @click="startWithQuestion(question)"
              >
                {{ question }}
              </button>
            </div>
          </NSpin>
        </section>
      </div>

      <section class="public-demo__features">
        <article>
          <strong>{{ t('demo.featureKnowledge') }}</strong>
          <span>{{ t('demo.featureKnowledgeBody') }}</span>
        </article>
        <article>
          <strong>{{ t('demo.featureStreaming') }}</strong>
          <span>{{ t('demo.featureStreamingBody') }}</span>
        </article>
        <article>
          <strong>{{ t('demo.featurePlan') }}</strong>
          <span>{{ t('demo.featurePlanBody') }}</span>
        </article>
        <article>
          <strong>{{ t('demo.featureMobile') }}</strong>
          <span>{{ t('demo.featureMobileBody') }}</span>
        </article>
      </section>

      <section v-if="config?.limitations?.length" class="public-demo__limitations">
        <span v-for="item in config.limitations" :key="item">{{ item }}</span>
      </section>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { NAlert, NButton, NSpin } from 'naive-ui';
import { useRouter } from 'vue-router';
import { getDemoConfig, type DemoConfigResponse } from '@/api/demo';
import { useAuthStore } from '@/stores/auth';
import { ui } from '@/ui';
import { apiErrorMessage } from '@/api/errors';
import { useI18n } from '@/composables/useI18n';

const DEFAULT_QUESTIONS = [
  '根据知识库，概括这个项目能解决什么问题。',
  '演示文档里的组会时间、地点和下次 DDL 是什么？',
  '这个项目的 RAG 流程包含哪些步骤？',
  '用计划模式帮我把两周内完善 Agent 能力拆成任务。',
];

const router = useRouter();
const authStore = useAuthStore();
const { isEnglish, t } = useI18n();
const config = ref<DemoConfigResponse | null>(null);
const loadingConfig = ref(false);
const starting = ref(false);
const pendingQuestion = ref<string | null>(null);

const DEFAULT_QUESTIONS_EN = [
  'What problems does this project solve according to the knowledge base?',
  'What are the next meeting time, location, and deadline?',
  'Which steps are included in this project\'s RAG workflow?',
  'Use Plan Mode to turn two weeks of Agent improvements into tasks.',
];
const exampleQuestions = computed(() => isEnglish.value
  ? DEFAULT_QUESTIONS_EN
  : (config.value?.exampleQuestions?.length ? config.value.exampleQuestions : DEFAULT_QUESTIONS));

onMounted(loadConfig);

async function loadConfig() {
  loadingConfig.value = true;
  try {
    const { data } = await getDemoConfig();
    config.value = data;
  } catch {
    config.value = null;
  } finally {
    loadingConfig.value = false;
  }
}

async function startWithQuestion(question: string) {
  pendingQuestion.value = question;
  await handleStartDemo();
}

async function handleStartDemo() {
  if (config.value && !config.value.enabled) {
    ui.message.warning('Demo 入口尚未开启');
    return;
  }
  starting.value = true;
  try {
    await authStore.signInDemo();
    const query: Record<string, string> = { demo: '1' };
    if (pendingQuestion.value) {
      query.q = pendingQuestion.value;
    }
    await router.push({ path: '/chat', query });
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '进入 Demo 失败'));
  } finally {
    starting.value = false;
    pendingQuestion.value = null;
  }
}
</script>
