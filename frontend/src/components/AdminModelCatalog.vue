<template>
  <NCard title="全局模型厂商" :bordered="false" class="catalog">
    <p class="hint">保存厂商后自动读取模型列表，新模型默认开放。关闭不想提供的模型后统一保存，再次同步会保留排除设置。</p>
    <NSpace class="provider-tabs">
      <NButton v-for="provider in providers" :key="provider.id" :type="selectedId === provider.id ? 'primary' : 'default'" :disabled="busy" @click="select(provider)">{{ provider.name }}{{ provider.enabled ? '' : '（已停用）' }}</NButton>
      <NButton dashed :disabled="busy" @click="newProvider">新增厂商</NButton>
    </NSpace>
    <NForm label-placement="top" class="provider-form">
      <NFormItem label="厂商名称"><NInput v-model:value="form.name" :disabled="busy" placeholder="例如：千问、GLM" /></NFormItem>
      <NFormItem label="OpenAI 兼容接口地址"><NInput v-model:value="form.chatUrl" :disabled="busy" placeholder="https://服务地址/v1 或完整 /chat/completions 地址" /></NFormItem>
      <NFormItem label="模型列表接口（可选）"><NInput v-model:value="form.modelsUrl" :disabled="busy" placeholder="留空自动使用同一接口的 /models；特殊地址可在此填写" /></NFormItem>
      <NFormItem :label="selectedId ? 'API Key（留空保留原密钥）' : 'API Key'"><NInput v-model:value="form.apiKey" :disabled="busy" type="password" show-password-on="click" autocomplete="new-password" /></NFormItem>
      <NFormItem label="向用户开放厂商"><NSwitch v-model:value="form.enabled" :disabled="busy" /></NFormItem>
    </NForm>
    <NSpace><NButton type="primary" :loading="busy" @click="saveProvider">保存厂商</NButton><NButton v-if="selectedId" :disabled="busy" @click="sync">重新读取模型列表</NButton></NSpace>
    <template v-if="selectedId">
      <div class="manual-model"><NInput v-model:value="newModel" :disabled="busy" placeholder="手动添加模型 ID，例如 glm-5.3" @keydown.enter="addModel" /><NButton :disabled="busy || !newModel.trim()" @click="addModel">添加模型</NButton></div>
      <NEmpty v-if="!models.length" description="尚无模型，请同步列表或手动添加" />
      <NSpace v-if="models.length" class="model-actions">
        <NButton :disabled="busy" @click="setAll(true)">全部开放</NButton>
        <NButton :disabled="busy" @click="setAll(false)">全部关闭</NButton>
        <NButton type="primary" :loading="busy" @click="saveAllModels">保存模型设置</NButton>
      </NSpace>
      <div v-if="models.length" class="model-table">
        <div class="model-row model-heading"><span>模型 ID</span><span>允许用户使用</span><span>支持图片</span></div>
        <div v-for="model in models" :key="model.id" class="model-row">
          <span>{{ model.modelName }} <small v-if="!model.available">（厂商列表已移除）</small></span>
          <NSwitch v-model:value="model.approved" :disabled="busy || !model.available" :aria-label="'批准 ' + model.modelName" />
          <NSwitch v-model:value="model.supportsVision" :disabled="busy" :aria-label="model.modelName + ' 支持图片'" />
        </div>
      </div>
      <p class="hint">图片能力需按厂商说明设置；模型名称不能证明能力。点击“保存模型设置”后生效，停用厂商会阻止其所有模型的新请求。</p>
    </template>
  </NCard>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { NButton, NCard, NEmpty, NForm, NFormItem, NInput, NSpace, NSwitch, useMessage } from 'naive-ui';
import { apiErrorMessage } from '@/api/errors';
import { listSharedProviders, saveSharedProvider, listSharedModels, syncSharedModels, saveSharedModels, saveSharedModel, type SharedProvider, type SharedModel } from '@/api/modelCatalog';
const message = useMessage();
const providers = ref<SharedProvider[]>([]);
const models = ref<SharedModel[]>([]);
const selectedId = ref<number | null>(null);
const busy = ref(false);
const newModel = ref('');
const form = reactive({ name: '', chatUrl: '', modelsUrl: '', apiKey: '', enabled: false });
function newProvider() { selectedId.value = null; models.value = []; newModel.value = ''; Object.assign(form, { name: '', chatUrl: '', modelsUrl: '', apiKey: '', enabled: false }); }
async function run(action: () => Promise<void>) { if (busy.value) return; busy.value = true; try { await action(); } catch (error) { message.error(apiErrorMessage(error, '模型配置操作失败')); } finally { busy.value = false; } }
async function select(provider: SharedProvider) { await run(async () => { const { data } = await listSharedModels(provider.id); selectedId.value = provider.id; Object.assign(form, { name: provider.name, chatUrl: provider.chatUrl, modelsUrl: provider.modelsUrl || '', apiKey: '', enabled: provider.enabled }); models.value = data; newModel.value = ''; }); }
async function saveProvider() {
  await run(async () => { const { data: id } = await saveSharedProvider(selectedId.value, { ...form, apiKey: form.apiKey || undefined }); selectedId.value = id; form.apiKey = ''; providers.value = (await listSharedProviders()).data; models.value = (await listSharedModels(id)).data;
    try { models.value = (await syncSharedModels(id)).data; message.success('厂商已保存，模型列表已自动读取；可关闭不想提供的模型'); }
    catch (error) { message.warning('厂商已保存，但自动读取失败：' + apiErrorMessage(error, '请检查列表接口或手动添加模型')); } });
}
async function sync() { const id = selectedId.value; if (!id) return; await run(async () => { models.value = (await syncSharedModels(id)).data; message.success('列表已更新，新模型默认开放，已有排除设置已保留'); }); }
async function addModel() { const id = selectedId.value; if (!id || !newModel.value.trim()) return; await run(async () => {
  if (models.value.some(model => model.modelName === newModel.value.trim())) { message.warning('该模型已存在，请在列表中编辑'); return; }
  await saveSharedModel(id, { modelName: newModel.value.trim(), approved: true, supportsVision: false }); models.value = (await listSharedModels(id)).data; newModel.value = '';
}); }
function setAll(approved: boolean) { for (const model of models.value) if (model.available) model.approved = approved; }
async function saveAllModels() { const id = selectedId.value; if (!id) return; await run(async () => { models.value = (await saveSharedModels(id, models.value)).data; message.success('模型设置已保存'); }); }
onMounted(() => run(async () => { providers.value = (await listSharedProviders()).data; }));
</script>
<style scoped>
.catalog { margin-top: 24px; }
.hint { color: var(--pa-text-muted, #64748b); font-size: 13px; }
.provider-tabs { margin: 16px 0; }
.provider-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.manual-model { display: flex; gap: 12px; margin: 24px 0 16px; }
.model-actions { margin-bottom: 12px; }
.model-table { overflow-x: auto; }
.model-row { display: grid; grid-template-columns: minmax(200px, 1fr) 120px 100px; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--pa-line, #e2e8f0); min-width: 540px; }
.model-heading { color: var(--pa-text-muted, #64748b); }
@media (max-width: 700px) { .provider-form { grid-template-columns: 1fr; } }
</style>
