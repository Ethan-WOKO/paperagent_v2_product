<template>
  <AppLayout>
    <main class="settings-page settings-page--redesign workbench-page scholar-page scholar-page--settings">
      <header class="settings-header">
        <div>
          <h1>{{ settingsCopy('设置', 'Settings') }}</h1>
          <p>{{ settingsCopy('配置模型、Agent 工具权限与凭据。密钥只显示配置状态，不会回显原值。', 'Configure models, Agent tool permissions, and credentials without exposing stored secrets.') }}</p>
          <span class="settings-header__updated">{{ settingsCopy('最后更新', 'Last updated') }} · {{ updatedAtText }}</span>
        </div>
        <NButton type="primary" :loading="saving" :disabled="isDemoUser" @click="handleSave">
          {{ settingsCopy('保存设置', 'Save settings') }}
        </NButton>
      </header>

      <NAlert v-if="isDemoUser" type="info" class="settings-demo-alert" title="Demo 账号为只读配置">
        游客体验可以使用预置模型和样本文档，但不能修改 API Key、模型、MCP、Skills 或自定义模型。
      </NAlert>

      <div class="settings-page__layout">
        <nav class="settings-section-nav" :aria-label="settingsCopy('设置分区', 'Settings sections')">
          <a href="#provider-settings">{{ settingsCopy('模型提供商', 'Model providers') }}</a>
          <a href="#default-model-settings">{{ settingsCopy('默认模型', 'Default model') }}</a>
          <a href="#agent-settings">{{ settingsCopy('Agent 与 MCP / 工具', 'Agent & MCP / Tools') }}</a>
          <a href="#skills-settings">{{ settingsCopy('技能', 'Skills') }}</a>
          <a href="#custom-model-settings">{{ settingsCopy('自定义模型', 'Custom models') }}</a>
        </nav>

        <NForm class="settings-form" :model="form" label-placement="top">
        <NSpace vertical size="large">
          <NCard id="provider-settings" class="workbench-card scholar-card settings-section-card" :bordered="false">
            <template #header>
              <div class="section-title">{{ settingsCopy('模型提供商', 'Model providers') }}</div>
            </template>

            <NGrid :cols="24" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
              <NGridItem span="24">
                <article id="default-model-settings" class="settings-default-card">
                  <div class="settings-default-card__identity">
                    <span>{{ settingsCopy('当前生效', 'Active configuration') }}</span>
                    <strong>{{ defaultProviderLabel }}</strong>
                    <small>{{ defaultModel }}</small>
                  </div>
                  <NFormItem :label="settingsCopy('默认提供商', 'Default provider')">
                    <NSelect
                      v-model:value="form.defaultProvider"
                      :options="providerOptions"
                      :input-props="{ autocomplete: 'off', name: 'paperagent-default-provider' }"
                    />
                  </NFormItem>
                  <NFormItem class="settings-default-card__model" :label="settingsCopy('默认模型', 'Default model')">
                    <NSelect
                      v-model:value="defaultModel"
                      filterable
                      tag
                      :title="defaultModel"
                      :consistent-menu-width="false"
                      :options="defaultModelOptions"
                      :disabled="!isBuiltinDefaultProvider"
                      :input-props="{ autocomplete: 'off', name: 'paperagent-default-model' }"
                    />
                  </NFormItem>
                  <NFormItem :label="settingsCopy('温度', 'Temperature')">
                    <NInputNumber v-model:value="form.deepseekTemperature" :min="0" :max="2" :step="0.1" style="width: 100%" />
                  </NFormItem>
                  <NFormItem :label="settingsCopy('最大计划步骤', 'Max plan steps')">
                    <NInputNumber v-model:value="form.maxSteps" :min="1" :max="100" style="width: 100%" />
                  </NFormItem>
                </article>
              </NGridItem>

              <NGridItem span="24 l:12">
                <article class="settings-provider-card">
                  <div class="settings-provider-card__head">
                    <div class="settings-provider-mark settings-provider-mark--deepseek">DS</div>
                    <div>
                      <strong>DeepSeek</strong>
                      <span>{{ settingsCopy('用于推理与写作', 'Reasoning and drafting provider') }}</span>
                    </div>
                    <NTag :type="deepseekConfigured ? 'success' : 'warning'" round>
                      {{ deepseekConfigured ? settingsCopy('API 密钥已配置', 'API key configured') : settingsCopy('缺少 API 密钥', 'API key missing') }}
                    </NTag>
                    <NButton size="small" secondary :loading="refreshingProvider === 'deepseek'" :disabled="isDemoUser" @click="handleRefreshModels('deepseek')">
                      {{ settingsCopy('刷新模型', 'Refresh models') }}
                    </NButton>
                  </div>
                  <NGrid :cols="2" :x-gap="12" responsive="screen" item-responsive>
                    <NFormItemGi span="2 m:1" :label="settingsCopy('当前模型', 'Current model')">
                      <NSelect
                        v-model:value="form.deepseekModel"
                        filterable
                        tag
                        :title="form.deepseekModel"
                        :consistent-menu-width="false"
                        :options="deepseekModelOptions"
                        :input-props="{ autocomplete: 'off', name: 'paperagent-deepseek-model' }"
                      />
                    </NFormItemGi>
                    <NFormItemGi span="2 m:1" label="API Key">
                      <NInput
                        v-model:value="form.deepseekApiKey"
                        type="password"
                        show-password-on="click"
                        :placeholder="settingsCopy('留空以保留当前密钥', 'Leave blank to keep current key')"
                        :input-props="{ autocomplete: 'new-password', name: 'paperagent-deepseek-api-key' }"
                      />
                    </NFormItemGi>
                    <NGridItem span="2">
                      <details class="settings-model-catalog">
                        <summary>
                          <span>
                            <strong>{{ settingsCopy('可用模型目录', 'Available model catalog') }}</strong>
                            <small>{{ settingsCopy(`当前使用 ${form.deepseekModel}`, `Selected ${form.deepseekModel}`) }}</small>
                          </span>
                          <span class="settings-model-catalog__count">
                            {{ settingsCopy(`${form.deepseekModels.length} 个模型`, `${form.deepseekModels.length} models`) }}
                          </span>
                        </summary>
                        <div class="settings-model-catalog__body">
                          <NDynamicTags v-model:value="form.deepseekModels" :max="50" />
                        </div>
                      </details>
                    </NGridItem>
                  </NGrid>
                </article>
              </NGridItem>

              <NGridItem span="24 l:12">
                <article class="settings-provider-card">
                  <div class="settings-provider-card__head">
                    <div class="settings-provider-mark settings-provider-mark--glm">GL</div>
                    <div>
                      <strong>GLM</strong>
                      <span>{{ settingsCopy('用于评测的备用提供商', 'Alternate provider for evaluation') }}</span>
                    </div>
                    <NTag :type="glmConfigured ? 'success' : 'warning'" round>
                      {{ glmConfigured ? settingsCopy('API 密钥已配置', 'API key configured') : settingsCopy('缺少 API 密钥', 'API key missing') }}
                    </NTag>
                    <NButton size="small" secondary :loading="refreshingProvider === 'glm'" :disabled="isDemoUser" @click="handleRefreshModels('glm')">
                      {{ settingsCopy('同步模型目录', 'Sync catalog') }}
                    </NButton>
                  </div>
                  <NGrid :cols="2" :x-gap="12" responsive="screen" item-responsive>
                    <NFormItemGi span="2 m:1" :label="settingsCopy('当前模型', 'Current model')">
                      <NSelect
                        v-model:value="form.glmModel"
                        filterable
                        tag
                        :title="form.glmModel"
                        :consistent-menu-width="false"
                        :options="glmModelOptions"
                        :input-props="{ autocomplete: 'off', name: 'paperagent-glm-model' }"
                      />
                    </NFormItemGi>
                    <NFormItemGi span="2 m:1" label="API Key">
                      <NInput
                        v-model:value="form.glmApiKey"
                        type="password"
                        show-password-on="click"
                        :placeholder="settingsCopy('留空以保留当前密钥', 'Leave blank to keep current key')"
                        :input-props="{ autocomplete: 'new-password', name: 'paperagent-glm-api-key' }"
                      />
                    </NFormItemGi>
                    <NGridItem span="2">
                      <details class="settings-model-catalog">
                        <summary>
                          <span>
                            <strong>{{ settingsCopy('可用模型目录', 'Available model catalog') }}</strong>
                            <small>{{ settingsCopy(`当前使用 ${form.glmModel}`, `Selected ${form.glmModel}`) }}</small>
                          </span>
                          <span class="settings-model-catalog__count">
                            {{ settingsCopy(`${form.glmModels.length} 个模型`, `${form.glmModels.length} models`) }}
                          </span>
                        </summary>
                        <div class="settings-model-catalog__body">
                          <NDynamicTags v-model:value="form.glmModels" :max="50" />
                        </div>
                      </details>
                    </NGridItem>
                  </NGrid>
                </article>
              </NGridItem>
            </NGrid>
          </NCard>

          <NCard class="workbench-card scholar-card settings-section-card" :bordered="false">
            <template #header>
              <div class="section-title">{{ settingsCopy('网页搜索提供商', 'Web search provider') }}</div>
            </template>
            <div class="settings-search-provider">
              <div class="settings-provider-mark settings-provider-mark--tavily">TV</div>
              <div>
                <strong>{{ settingsCopy('Tavily / 正式网页搜索', 'Tavily / formal web search') }}</strong>
                <span>{{ settingsCopy('通过 WEB_SEARCH_PROVIDER、TAVILY_API_KEY 等后端环境变量配置。', 'Configured through backend environment variables such as WEB_SEARCH_PROVIDER and TAVILY_API_KEY.') }}</span>
              </div>
              <NTag type="info" round>{{ settingsCopy('已启用节省额度的默认配置', 'Credit-saving defaults enabled') }}</NTag>
              <div class="settings-search-provider__controls">
                <div>
                  <span>{{ settingsCopy('搜索深度', 'Search depth') }}</span>
                  <strong>{{ settingsCopy('默认 basic', 'basic by default') }}</strong>
                </div>
                <div>
                  <span>{{ settingsCopy('缓存有效期', 'Cache TTL') }}</span>
                  <strong>{{ settingsCopy('默认 15 分钟', '15 min default') }}</strong>
                </div>
                <div>
                  <span>{{ settingsCopy('最大结果数', 'Max results') }}</span>
                  <strong>{{ settingsCopy('默认 8 条', '8 default') }}</strong>
                </div>
              </div>
            </div>
          </NCard>

          <NGrid :cols="24" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
            <NGridItem span="24 l:12">
              <NCard id="agent-settings" class="workbench-card scholar-card settings-section-card" :bordered="false">
                <template #header>
                  <div class="section-title">{{ settingsCopy('Agent 与 MCP / 工具', 'Agent and MCP / Tools') }}</div>
                </template>
                <NSpace vertical size="large">
                  <div class="settings-toggle-row">
                    <div>
                      <strong>{{ settingsCopy('知识库 RAG', 'Knowledge Base RAG') }}</strong>
                      <span>{{ settingsCopy('新会话默认可以使用私有检索。', 'New sessions can use private retrieval by default.') }}</span>
                    </div>
                    <NSwitch v-model:value="form.ragDefaultEnabled" />
                  </div>
                  <div class="settings-tool-row">
                    <div class="settings-provider-mark settings-provider-mark--github">GH</div>
                    <div>
                      <strong>GitHub MCP</strong>
                      <span>{{ settingsCopy('为代码和文档工作流提供仓库访问。', 'Repository access for code and documentation workflows.') }}</span>
                    </div>
                    <NTag :type="githubConfigured ? 'success' : 'warning'" round>
                      {{ githubConfigured ? settingsCopy('PAT 已配置', 'PAT configured') : settingsCopy('缺少 PAT', 'PAT missing') }}
                    </NTag>
                  </div>
                  <NFormItem label="GitHub PAT">
                    <NInput
                      v-model:value="form.githubPat"
                    type="password"
                    show-password-on="click"
                    :placeholder="settingsCopy('留空以保留当前令牌', 'Leave blank to keep current token')"
                    :input-props="{ autocomplete: 'new-password', name: 'paperagent-github-pat' }"
                  />
                  </NFormItem>
                  <NFormItem :label="settingsCopy('文件系统允许的根目录', 'Filesystem allowed roots')">
                    <NInput
                      v-model:value="filesystemRootsText"
                      type="textarea"
                      :autosize="{ minRows: 4, maxRows: 7 }"
                      :placeholder="settingsCopy('每行一个路径，例如：workspace', 'One path per line, for example: workspace')"
                    />
                  </NFormItem>
                </NSpace>
              </NCard>
            </NGridItem>

            <NGridItem span="24 l:12">
              <NCard id="skills-settings" class="workbench-card scholar-card settings-section-card" :bordered="false">
                <template #header>
                  <div class="section-title">{{ settingsCopy('技能', 'Skills') }}</div>
                </template>
                <template #header-extra>
                  <span class="chat-hint">{{ settingsCopy('从后端 Skill 注册表加载', 'Loaded from backend skill registry') }}</span>
                </template>
                <div class="settings-skill-grid">
                  <NEmpty v-if="skills.length === 0" :description="settingsCopy('暂无可用技能。', 'No skills found.')" />
                  <article v-for="skill in skills" :key="skill.id" class="settings-skill-pill">
                    <div>
                      <strong>{{ skill.name }}</strong>
                      <span>{{ skill.source }}</span>
                    </div>
                    <NCheckbox :checked="!disabledSkillsSet.has(skill.id)" @update:checked="(checked) => toggleSkill(skill.id, checked)">
                      {{ settingsCopy('已启用', 'Enabled') }}
                    </NCheckbox>
                  </article>
                </div>
              </NCard>
            </NGridItem>
          </NGrid>

          <NCard id="custom-model-settings" class="workbench-card scholar-card settings-section-card" :bordered="false">
            <template #header>
              <div class="section-title">{{ settingsCopy('自定义模型', 'Custom models') }}</div>
            </template>
            <template #header-extra>
              <NButton size="small" type="primary" :disabled="isDemoUser" @click="openCreateModelModal">+ 添加模型</NButton>
            </template>
            <div class="settings-skill-grid">
              <NEmpty v-if="customModels.filter((m) => !m.builtin).length === 0" description="尚未添加自定义模型。点击右上角添加。" />
              <article v-for="model in customModels.filter((m) => !m.builtin)" :key="model.id" class="settings-skill-pill">
                <div>
                  <strong>{{ model.label }}</strong>
                  <span>{{ model.modelName }}</span>
                  <span class="chat-hint">{{ model.apiUrl }}</span>
                </div>
                <NSpace>
                  <NTag :type="model.apiKeyConfigured ? 'success' : 'warning'" round size="small">
                  {{ model.apiKeyConfigured ? settingsCopy('密钥已配置', 'Key set') : settingsCopy('缺少密钥', 'Key missing') }}
                  </NTag>
                  <NButton size="small" :loading="testingModelId === model.id" @click="handleTestModel(model)">测试</NButton>
                  <NButton size="small" secondary :disabled="isDemoUser" @click="openEditModelModal(model)">编辑</NButton>
                  <NButton size="small" tertiary type="error" :disabled="isDemoUser" @click="handleDeleteModel(model)">删除</NButton>
                </NSpace>
              </article>
            </div>
          </NCard>

          <div class="settings-footer-bar">
            <span class="chat-hint">{{ settingsCopy('更改将保存到后端设置；密钥字段留空会保留现有值。', 'Changes are saved to backend settings; blank secret fields keep existing values.') }}</span>
            <NButton type="primary" :loading="saving" :disabled="isDemoUser" @click="handleSave">{{ settingsCopy('保存设置', 'Save settings') }}</NButton>
          </div>
        </NSpace>
        </NForm>
      </div>

      <NModal v-model:show="modelModalVisible" preset="card" :title="editingModelId ? '编辑自定义模型' : '添加自定义模型'" style="width: 520px" :bordered="false">
        <NForm label-placement="top">
          <NFormItem label="模型名称（显示用）">
            <NInput v-model:value="modelForm.label" placeholder="例如：我的 DeepSeek V4 Pro" />
          </NFormItem>
          <NFormItem label="API 地址">
            <NInput v-model:value="modelForm.apiUrl" placeholder="https://api.deepseek.com/v1/chat/completions" />
          </NFormItem>
          <NFormItem label="模型 ID">
            <NInput v-model:value="modelForm.modelName" placeholder="例如：deepseek-v4-flash" />
          </NFormItem>
          <NFormItem :label="editingModelId ? 'API Key（留空保持不变）' : 'API Key'">
            <NInput
              v-model:value="modelForm.apiKey"
              type="password"
              show-password-on="click"
              placeholder="sk-..."
              :input-props="{ autocomplete: 'new-password', name: 'paperagent-custom-model-api-key' }"
            />
          </NFormItem>
          <NSpace justify="end">
            <NButton @click="modelModalVisible = false">取消</NButton>
            <NButton type="primary" :disabled="isDemoUser" @click="handleSaveModel">保存</NButton>
          </NSpace>
        </NForm>
      </NModal>
    </main>
  </AppLayout>
</template>

<script setup lang="ts">
import {
  NAlert,
  NButton,
  NCard,
  NCheckbox,
  NEmpty,
  NForm,
  NFormItem,
  NFormItemGi,
  NGrid,
  NGridItem,
  NInput,
  NInputNumber,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  NModal,
  NDynamicTags,
} from 'naive-ui';
import { computed, onMounted, reactive, ref } from 'vue';
import AppLayout from '@/components/AppLayout.vue';
import { listSkills, type SkillListItemResponse } from '@/api/skills';
import { getSettings, updateSettings, refreshProviderModels, createModel, updateModel, deleteModel, testModel, type UserModelResponse, type UserSettingsResponse } from '@/api/settings';
import { useAuthStore } from '@/stores/auth';
import { ui } from '@/ui';
import { apiErrorMessage } from '@/api/errors';
import { useI18n } from '@/composables/useI18n';

const DEFAULT_DEEPSEEK_MODELS = ['deepseek-v4-flash', 'deepseek-v4-pro'];
const DEFAULT_GLM_MODELS = [
  'glm-5.2',
  'glm-5.1',
  'glm-5',
  'glm-5-turbo',
  'glm-4.7',
  'glm-4.7-flashx',
  'glm-4.6',
  'glm-4.5-air',
  'glm-4.5-airx',
  'glm-4-long',
  'glm-4.7-flash',
  'glm-4.5-flash',
  'glm-4-flash-250414',
  'glm-4-flash',
];

const providerOptions = computed(() => {
  const customProviders = new Map<string, { label: string; value: string }>();
  for (const model of customModels.value) {
    if (!model.builtin && !customProviders.has(model.providerKey)) {
      customProviders.set(model.providerKey, { label: model.label, value: model.providerKey });
    }
  }
  return [
    { label: 'DeepSeek', value: 'deepseek' },
    { label: 'GLM', value: 'glm' },
    ...customProviders.values(),
  ];
});

const saving = ref(false);
const authStore = useAuthStore();
const { isEnglish, locale } = useI18n();
const settingsCopy = (zh: string, en: string) => isEnglish.value ? en : zh;
const deepseekConfigured = ref(false);
const glmConfigured = ref(false);
const githubConfigured = ref(false);
const updatedAt = ref<string | null>(null);
const filesystemRootsText = ref('workspace');
const skills = ref<SkillListItemResponse[]>([]);
const disabledSkills = ref<string[]>([]);
const form = reactive({
  defaultProvider: 'deepseek',
  deepseekApiKey: '',
  glmApiKey: '',
  githubPat: '',
  deepseekModel: 'deepseek-v4-flash',
  glmModel: 'glm-5.2',
  deepseekModels: [...DEFAULT_DEEPSEEK_MODELS] as string[],
  glmModels: [...DEFAULT_GLM_MODELS] as string[],
  deepseekTemperature: 0.7,
  maxSteps: 20,
  ragDefaultEnabled: true,
});

// Custom models state
const customModels = ref<UserModelResponse[]>([]);
const modelModalVisible = ref(false);
const editingModelId = ref<number | null>(null);
const modelForm = reactive({ label: '', apiUrl: '', apiKey: '', modelName: '' });
const testingModelId = ref<number | null>(null);
const refreshingProvider = ref<string | null>(null);

const disabledSkillsSet = computed(() => new Set(disabledSkills.value));
const isDemoUser = computed(() => Boolean(authStore.currentUser?.demo));
const updatedAtText = computed(() => updatedAt.value
  ? new Date(updatedAt.value).toLocaleString(locale.value)
  : (isEnglish.value ? 'Never' : '从未'));
const deepseekModelOptions = computed(() => form.deepseekModels.map((m) => ({ label: m, value: m })));
const glmModelOptions = computed(() => form.glmModels.map((m) => ({ label: m, value: m })));
const isBuiltinDefaultProvider = computed(() => form.defaultProvider === 'deepseek' || form.defaultProvider === 'glm');
const defaultModelOptions = computed(() => {
  if (form.defaultProvider === 'deepseek') {
    return deepseekModelOptions.value;
  }
  if (form.defaultProvider === 'glm') {
    return glmModelOptions.value;
  }
  return customModels.value
    .filter((model) => model.providerKey === form.defaultProvider)
    .map((model) => ({ label: model.modelName, value: model.modelName }));
});
const defaultProviderLabel = computed(() => (
  providerOptions.value.find((provider) => provider.value === form.defaultProvider)?.label || form.defaultProvider
));
const defaultModel = computed<string>({
  get() {
    if (form.defaultProvider === 'deepseek') {
      return form.deepseekModel;
    }
    if (form.defaultProvider === 'glm') {
      return form.glmModel;
    }
    return customModels.value.find((model) => model.providerKey === form.defaultProvider)?.modelName || '';
  },
  set(value) {
    if (form.defaultProvider === 'deepseek') {
      form.deepseekModel = value;
    } else if (form.defaultProvider === 'glm') {
      form.glmModel = value;
    }
  },
});
const builtinModels = computed(() => customModels.value.filter((m) => m.builtin));

onMounted(async () => {
  await Promise.all([loadSettings(), loadSkills()]);
});

async function loadSettings() {
  try {
    const { data } = await getSettings();
    applySettingsResponse(data);
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, 'Failed to load settings.'));
  }
}

function applySettingsResponse(data: UserSettingsResponse) {
  form.defaultProvider = data.defaultProvider;
  form.deepseekApiKey = '';
  form.glmApiKey = '';
  form.githubPat = '';
  form.deepseekModel = data.deepseekModel;
  form.glmModel = data.glmModel;
  form.deepseekModels = data.deepseekModels?.length ? data.deepseekModels : [...DEFAULT_DEEPSEEK_MODELS];
  form.glmModels = data.glmModels?.length ? data.glmModels : [...DEFAULT_GLM_MODELS];
  form.deepseekTemperature = data.deepseekTemperature;
  form.maxSteps = data.maxSteps;
  form.ragDefaultEnabled = data.ragDefaultEnabled;
  filesystemRootsText.value = (data.filesystemRoots || []).join('\n');
  disabledSkills.value = [...(data.disabledSkills || [])];
  deepseekConfigured.value = data.deepseekApiKeyConfigured;
  glmConfigured.value = data.glmApiKeyConfigured;
  githubConfigured.value = data.githubPatConfigured;
  updatedAt.value = data.updatedAt;
  customModels.value = data.customModels || [];
}

async function loadSkills() {
  try {
    const { data } = await listSkills();
    skills.value = data;
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, 'Failed to load skills.'));
  }
}

function toggleSkill(skillId: string, enabled: boolean) {
  if (enabled) {
    disabledSkills.value = disabledSkills.value.filter((item) => item !== skillId);
  } else if (!disabledSkills.value.includes(skillId)) {
    disabledSkills.value = [...disabledSkills.value, skillId];
  }
}

async function handleSave() {
  if (guardDemoSettings()) {
    return;
  }
  saving.value = true;
  try {
    const { data } = await updateSettings({
      defaultProvider: form.defaultProvider,
      deepseekApiKey: form.deepseekApiKey.trim() || undefined,
      glmApiKey: form.glmApiKey.trim() || undefined,
      githubPat: form.githubPat.trim() || undefined,
      deepseekModel: form.deepseekModel,
      glmModel: form.glmModel,
      deepseekModels: form.deepseekModels,
      glmModels: form.glmModels,
      deepseekTemperature: form.deepseekTemperature,
      maxSteps: form.maxSteps,
      ragDefaultEnabled: form.ragDefaultEnabled,
      filesystemRoots: splitLines(filesystemRootsText.value),
      disabledSkills: disabledSkills.value,
    });
    applySettingsResponse(data);
    ui.message.success('Settings saved.');
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, 'Failed to save settings.'));
  } finally {
    saving.value = false;
  }
}

async function handleRefreshModels(provider: 'deepseek' | 'glm') {
  if (guardDemoSettings()) {
    return;
  }
  refreshingProvider.value = provider;
  try {
    const { data } = await refreshProviderModels(provider);
    applySettingsResponse(data);
    ui.message.success(provider === 'deepseek' ? 'DeepSeek models refreshed.' : 'GLM catalog synced.');
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, `Failed to refresh ${provider} models.`));
  } finally {
    refreshingProvider.value = null;
  }
}

function splitLines(value: string) {
  return value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
}

function showModelTestResult(data: Awaited<ReturnType<typeof testModel>>['data']) {
  if (data.success) {
    ui.message.success(`Connection succeeded${data.content ? ': ' + data.content : ''}`);
    return;
  }
  ui.message.error(`Connection failed [${data.errorType || 'UNKNOWN_ERROR'}]: ${data.errorMessage || data.error || 'Unknown error'}`);
}

// ===== Custom model management =====

function openCreateModelModal() {
  if (guardDemoSettings()) {
    return;
  }
  editingModelId.value = null;
  modelForm.label = '';
  modelForm.apiUrl = '';
  modelForm.apiKey = '';
  modelForm.modelName = '';
  modelModalVisible.value = true;
}

function openEditModelModal(model: UserModelResponse) {
  if (guardDemoSettings()) {
    return;
  }
  editingModelId.value = model.id;
  modelForm.label = model.label;
  modelForm.apiUrl = model.apiUrl || '';
  modelForm.apiKey = '';
  modelForm.modelName = model.modelName;
  modelModalVisible.value = true;
}

async function handleSaveModel() {
  if (guardDemoSettings()) {
    return;
  }
  if (!modelForm.label || !modelForm.apiUrl || !modelForm.modelName) {
    ui.message.warning('请填写模型名称、API 地址和模型 ID');
    return;
  }
  try {
    const payload = {
      label: modelForm.label,
      apiUrl: modelForm.apiUrl,
      apiKey: modelForm.apiKey || undefined,
      modelName: modelForm.modelName,
    };
    if (editingModelId.value) {
      await updateModel(editingModelId.value, payload);
      ui.message.success('模型已更新');
    } else {
      await createModel(payload);
      ui.message.success('模型已添加');
    }
    modelModalVisible.value = false;
    await loadSettings();
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '保存模型失败'));
  }
}

async function handleDeleteModel(model: UserModelResponse) {
  if (guardDemoSettings()) {
    return;
  }
  try {
    await deleteModel(model.id);
    ui.message.success('模型已删除');
    await loadSettings();
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '删除模型失败'));
  }
}

async function handleTestModel(model: UserModelResponse) {
  testingModelId.value = model.id;
  try {
    const { data } = await testModel(model.id);
    showModelTestResult(data);
    return;
    if (data.success) {
      ui.message.success(`连接成功${data.content ? '：' + data.content : ''}`);
    } else {
      ui.message.error(`连接失败：${data.error || '未知错误'}`);
    }
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '测试失败'));
  } finally {
    testingModelId.value = null;
  }
}

function guardDemoSettings() {
  if (!isDemoUser.value) {
    return false;
  }
  ui.message.info('Demo 账号不能修改配置');
  return true;
}
</script>
