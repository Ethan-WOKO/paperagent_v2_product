<template>
  <section class="model-catalog" :aria-busy="busy" aria-label="全局模型厂商管理">
    <aside class="provider-sidebar">
      <div class="panel-heading">
        <strong>厂商列表</strong>
        <NButton size="small" :disabled="busy" @click="newProvider">新增厂商</NButton>
      </div>
      <div class="provider-list">
        <button v-for="provider in providers" :key="provider.id" type="button"
          class="provider-row" :class="{ 'provider-row--active': selectedId === provider.id }"
          :aria-pressed="selectedId === provider.id" :disabled="busy" @click="select(provider)">
          <strong>{{ provider.name }}</strong>
          <span class="provider-status" :class="{ 'provider-status--enabled': provider.enabled }">{{ provider.enabled ? '已开放' : '已停用' }}</span>
          <small>共享模型厂商</small>
        </button>
        <p v-if="!providers.length" class="sidebar-hint">尚未添加厂商</p>
      </div>
    </aside>
    <div v-if="selectedId || creating" class="provider-detail">
      <div class="panel-heading detail-heading">
        <strong>{{ selectedId ? '厂商配置' : '新增厂商' }}</strong>
        <span class="hint">保存后自动读取模型列表</span>
      </div>
      <div class="detail-body">
        <NForm label-placement="top" class="provider-form">
          <NFormItem label="厂商名称"><NInput v-model:value="form.name" :disabled="busy" placeholder="例如：千问、GLM" /></NFormItem>
          <NFormItem label="OpenAI 兼容接口地址"><NInput v-model:value="form.chatUrl" :disabled="busy" placeholder="https://服务地址/v1" /></NFormItem>
          <NFormItem label="模型列表接口（可选）"><NInput v-model:value="form.modelsUrl" :disabled="busy" :input-props="{ name: 'provider-models-url', autocomplete: 'off' }" placeholder="留空自动读取；也可填写专用接口地址" /></NFormItem>
          <NFormItem :label="selectedId ? 'API Key（留空保留原密钥）' : 'API Key'"><NInput v-model:value="form.apiKey" :disabled="busy" type="password" show-password-on="click" :input-props="{ name: 'provider-api-key', autocomplete: 'new-password' }" placeholder="请输入厂商 API Key" /></NFormItem>
        </NForm>
        <div class="provider-access"><span>向用户开放厂商</span><NSwitch v-model:value="form.enabled" :disabled="busy" aria-label="向用户开放厂商" /></div>
        <NSpace><NButton type="primary" :loading="busy" @click="saveProvider">保存厂商</NButton><NButton v-if="selectedId" :disabled="busy" @click="sync">重新读取模型列表</NButton></NSpace>
      </div>
      <section v-if="selectedId" class="models-section" aria-label="模型设置">
        <div class="panel-heading detail-heading"><strong>模型设置</strong><span class="hint">共 {{ models.length }} 个模型</span></div>
        <div class="detail-body">
          <p class="hint models-hint">新模型默认开放，关闭不需要的模型后保存。再次同步会保留排除设置。</p>
          <NSpace v-if="models.length" class="model-actions">
            <NButton :disabled="busy" @click="setAll(true)">全部开放</NButton>
            <NButton :disabled="busy" @click="setAll(false)">全部关闭</NButton>
            <NButton type="primary" :loading="busy" @click="saveAllModels">保存模型设置</NButton>
          </NSpace>
          <div v-if="models.length" class="model-table">
            <table>
              <thead><tr><th>模型 ID</th><th>允许用户使用</th><th>支持图片</th></tr></thead>
              <tbody><tr v-for="model in models" :key="model.id">
                <td>{{ model.modelName }} <small v-if="!model.available">（厂商列表已移除）</small></td>
                <td><NSwitch v-model:value="model.approved" :disabled="busy || !model.available" :aria-label="'批准 ' + model.modelName" /></td>
                <td><NSwitch v-model:value="model.supportsVision" :disabled="busy" :aria-label="model.modelName + ' 支持图片'" /></td>
              </tr></tbody>
            </table>
          </div>
          <NEmpty v-else class="models-empty" description="尚无模型，请读取模型列表或手动添加" />
          <p v-if="models.length" class="hint">图片能力请按厂商说明设置，保存后生效。</p>
          <details class="manual-model-section">
            <summary>手动添加模型</summary>
            <div class="manual-model"><NInput v-model:value="newModel" :disabled="busy" placeholder="输入模型 ID" @keydown.enter="addModel" /><NButton :disabled="busy || !newModel.trim()" @click="addModel">添加模型</NButton></div>
          </details>
        </div>
      </section>
    </div>
    <div v-else class="provider-empty"><NEmpty :description="busy ? '正在加载厂商列表' : '从左侧选择一个厂商，或新增厂商'" /></div>
  </section>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { NButton, NEmpty, NForm, NFormItem, NInput, NSpace, NSwitch, useMessage } from 'naive-ui';
import { apiErrorMessage } from '@/api/errors';
import { listSharedProviders, saveSharedProvider, listSharedModels, syncSharedModels, saveSharedModels, saveSharedModel, type SharedProvider, type SharedModel } from '@/api/modelCatalog';
const message = useMessage();
const providers = ref<SharedProvider[]>([]);
const models = ref<SharedModel[]>([]);
const selectedId = ref<number | null>(null);
const busy = ref(false);
const creating = ref(false);
const newModel = ref('');
const form = reactive({ name: '', chatUrl: '', modelsUrl: '', apiKey: '', enabled: false });
function newProvider() { creating.value = true; selectedId.value = null; models.value = []; newModel.value = ''; Object.assign(form, { name: '', chatUrl: '', modelsUrl: '', apiKey: '', enabled: false }); }
async function run(action: () => Promise<void>) { if (busy.value) return; busy.value = true; try { await action(); } catch (error) { message.error(apiErrorMessage(error, '模型配置操作失败')); } finally { busy.value = false; } }
async function select(provider: SharedProvider) { await run(async () => { const { data } = await listSharedModels(provider.id); selectedId.value = provider.id; creating.value = false; Object.assign(form, { name: provider.name, chatUrl: provider.chatUrl, modelsUrl: provider.modelsUrl || '', apiKey: '', enabled: provider.enabled }); models.value = data; newModel.value = ''; }); }
async function saveProvider() {
  await run(async () => { const { data: id } = await saveSharedProvider(selectedId.value, { ...form, apiKey: form.apiKey || undefined }); selectedId.value = id; creating.value = false; form.apiKey = ''; providers.value = (await listSharedProviders()).data; models.value = (await listSharedModels(id)).data;
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
onMounted(() => run(async () => { providers.value = (await listSharedProviders()).data; if (!providers.value.length) newProvider(); }));
</script>
<style scoped>
.model-catalog { display: grid; grid-template-columns: 260px minmax(0, 1fr); min-height: 480px; overflow: hidden; border: 1px solid var(--pa-line); border-radius: var(--pa-radius-sm); background: var(--pa-surface); }
.provider-sidebar { border-right: 1px solid var(--pa-line); }
.panel-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; min-height: 48px; padding: 10px 12px; border-bottom: 1px solid var(--pa-line); box-sizing: border-box; }
.panel-heading strong { color: var(--pa-text); font-size: 11px; font-weight: 680; }
.provider-list { max-height: min(62vh, 560px); overflow: auto; }
.provider-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 6px; width: 100%; padding: 12px; border: 0; border-bottom: 1px solid var(--pa-line); color: var(--pa-text-secondary); background: transparent; cursor: pointer; text-align: left; }
.provider-row strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; font-weight: 650; }
.provider-row small { grid-column: 1 / -1; color: var(--pa-text-muted); font-size: 10px; }
.provider-row:hover, .provider-row--active { color: var(--pa-text); background: var(--pa-accent-soft); }
.provider-row--active { box-shadow: inset 2px 0 0 var(--pa-accent); }
.provider-row:focus-visible, summary:focus-visible { outline: 2px solid var(--pa-accent); outline-offset: -2px; }
.provider-row:disabled { cursor: wait; }
.provider-status { padding: 1px 5px; border: 1px solid var(--pa-line); border-radius: 3px; color: var(--pa-text-muted); font-size: 10px; }
.provider-status--enabled { color: var(--pa-accent); border-color: var(--pa-accent); }
.sidebar-hint { margin: 16px 12px; color: var(--pa-text-muted); font-size: 11px; }
.provider-detail { min-width: 0; }
.detail-heading { padding-inline: 16px; }
.detail-body { padding: 16px; }
.hint { margin: 12px 0; color: var(--pa-text-muted); font-size: 11px; line-height: 1.6; }
.panel-heading .hint { margin: 0; font-size: 10px; }
.provider-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.provider-access { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; font-size: 11px; }
.models-section { border-top: 1px solid var(--pa-line); }
.models-hint { margin-top: 0; }
.model-actions { margin-bottom: 12px; }
.model-table { max-height: 460px; overflow: auto; }
.model-table table { width: 100%; min-width: 440px; border-collapse: collapse; text-align: left; font-size: 11px; }
.model-table th, .model-table td { padding: 12px 8px; border-bottom: 1px solid var(--pa-line); }
.model-table th { color: var(--pa-text-muted); background: var(--pa-surface-muted); font-weight: 500; }
.model-table th:not(:first-child) { width: 100px; }
.model-table td:first-child { overflow-wrap: anywhere; }
.model-table small { color: var(--pa-text-muted); }
.manual-model-section { margin-top: 16px; font-size: 11px; color: var(--pa-text-secondary); }
.manual-model-section summary { cursor: pointer; width: fit-content; padding: 4px 0; }
.manual-model { display: flex; gap: 8px; margin-top: 10px; }
.provider-empty { display: grid; place-items: center; min-height: 360px; padding: 24px; }
.models-empty { padding: 24px 0; }
.model-catalog :deep(.n-button) { border-radius: var(--pa-radius-sm) !important; box-shadow: none !important; font-size: 11px; }
.model-catalog :deep(.n-form-item-label) { font-size: 11px; }
.model-catalog :deep(.n-input) { font-size: 12px; }
@media (max-width: 900px) {
  .model-catalog { grid-template-columns: 1fr; min-height: 0; }
  .provider-sidebar { border-right: 0; border-bottom: 1px solid var(--pa-line); }
  .provider-list { max-height: 220px; }
}
@media (max-width: 700px) {
  .provider-form { grid-template-columns: 1fr; }
  .manual-model { flex-wrap: wrap; }
  .manual-model :deep(.n-input) { flex: 1 1 180px; }
}
</style>
