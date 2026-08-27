<template>
  <AppLayout>
    <main class="memory-page memory-page--redesign workbench-page scholar-page" data-testid="memory-governance-page">
      <header class="memory-header">
        <div class="memory-breadcrumb">
          <NButton text size="small" @click="router.push('/settings')">{{ t('memory.settings') }}</NButton>
          <span aria-hidden="true">/</span>
          <span>{{ t('memory.title') }}</span>
        </div>
        <div class="memory-header__row">
          <div>
            <h1>{{ t('memory.title') }}</h1>
            <p>{{ t('memory.subtitle') }}</p>
          </div>
          <div class="memory-header__actions">
            <NButton :loading="loading" @click="loadMemories">{{ t('memory.refresh') }}</NButton>
            <NButton type="primary" @click="openCreateModal">{{ t('memory.add') }}</NButton>
          </div>
        </div>
      </header>

      <section class="memory-distillation" data-testid="memory-distillation-panel">
        <div class="memory-distillation__intro">
          <span class="memory-distillation__eyebrow">{{ t('memory.distillation.eyebrow') }}</span>
          <h2>{{ t('memory.distillation.title') }}</h2>
          <p>{{ t('memory.distillation.description') }}</p>
          <p class="memory-distillation__guardrail">{{ t('memory.distillation.guardrail') }}</p>
        </div>
        <div class="memory-distillation__controls">
          <label class="memory-distillation__toggle">
            <NSwitch
              :value="distillationSettings?.autoEnabled ?? false"
              :loading="distillationSaving"
              :disabled="distillationLoading || !distillationSettings?.available"
              data-testid="memory-distillation-auto"
              @update:value="updateAutoDistillation"
            />
            <span>
              <strong>{{ t('memory.distillation.auto') }}</strong>
              <small>{{ t('memory.distillation.autoHint', { hours: distillationIntervalHours }) }}</small>
            </span>
          </label>
          <NButton
            type="primary"
            secondary
            data-testid="memory-distillation-start"
            :loading="distillationActive"
            :disabled="distillationLoading || !distillationSettings?.available"
            @click="runDistillationNow"
          >
            {{ distillationActive ? t('memory.distillation.running') : t('memory.distillation.runNow') }}
          </NButton>
        </div>
        <div class="memory-distillation__status" aria-live="polite">
          <NSpin v-if="distillationLoading" size="small" />
          <template v-else-if="distillationSettings">
            <span>
              <small>{{ t('memory.distillation.latestStatus') }}</small>
              <NTag size="small" :type="distillationStatusType(distillationJob?.status)">
                {{ distillationStatusLabel(distillationJob?.status) }}
              </NTag>
            </span>
            <span>
              <small>{{ t('memory.distillation.lastSuccess') }}</small>
              <strong>{{ formatOptionalDate(distillationSettings.lastSuccessAt) }}</strong>
            </span>
            <span>
              <small>{{ t('memory.distillation.nextRun') }}</small>
              <strong>{{ distillationSettings.autoEnabled ? formatOptionalDate(distillationSettings.nextRunAt) : t('memory.distillation.autoOff') }}</strong>
            </span>
            <span v-if="distillationJob?.status === 'SUCCEEDED'">
              <small>{{ t('memory.distillation.result') }}</small>
              <strong>{{ t('memory.distillation.resultCount', { count: distillationJob.createdMemoryCount }) }}</strong>
            </span>
          </template>
        </div>
        <NAlert
          v-if="distillationError"
          class="memory-distillation__error"
          type="error"
          closable
          @close="distillationError = ''"
        >
          {{ distillationError }}
        </NAlert>
      </section>

      <section class="memory-toolbar" :aria-label="t('memory.listControls')">
        <NButtonGroup>
          <NButton
            data-testid="filter-active"
            :type="listStatus === 'ACTIVE' ? 'primary' : 'default'"
            :secondary="listStatus !== 'ACTIVE'"
            @click="setListStatus('ACTIVE')"
          >
            {{ t('memory.active') }}
          </NButton>
          <NButton
            data-testid="filter-all"
            :type="listStatus === 'ALL' ? 'primary' : 'default'"
            :secondary="listStatus !== 'ALL'"
            @click="setListStatus('ALL')"
          >
            {{ t('memory.allRecords') }}
          </NButton>
        </NButtonGroup>
        <span class="memory-count">{{ t('memory.recordCount', { count: memories.length }) }}</span>
      </section>

      <NAlert
        v-if="loadError"
        class="memory-alert"
        type="error"
        :title="t('memory.error.listTitle')"
        closable
        @close="loadError = ''"
      >
        <span>{{ loadError }}</span>
        <NButton class="memory-alert__retry" size="small" @click="loadMemories">{{ t('memory.retry') }}</NButton>
      </NAlert>

      <NAlert
        v-if="operationError"
        class="memory-alert"
        :type="operationErrorIsStale ? 'warning' : 'error'"
        :title="operationErrorIsStale ? t('memory.error.staleTitle') : t('memory.error.actionFailed')"
        closable
        @close="clearOperationError"
      >
        {{ operationError }}
      </NAlert>

      <NSpin :show="loading" :description="t('memory.loading')">
        <NEmpty
          v-if="!loading && !loadError && memories.length === 0"
          class="memory-empty"
          :description="listStatus === 'ACTIVE' ? t('memory.empty.active') : t('memory.empty.history')"
        >
          <template #extra>
            <NButton type="primary" @click="openCreateModal">{{ t('memory.empty.addFirst') }}</NButton>
          </template>
        </NEmpty>

        <section v-else class="memory-list" aria-live="polite">
          <div class="memory-list__head" aria-hidden="true">
            <span>{{ t('memory.form.content') }}</span>
            <span>{{ t('memory.field.scope') }}</span>
            <span>{{ t('memory.field.memoryType') }}</span>
            <span>{{ t('memory.field.confirmationStatus') }}</span>
            <span>{{ t('memory.field.expiresAt') }}</span>
            <span>{{ t('memory.field.updatedAt') }}</span>
            <span>{{ t('memory.field.actions') }}</span>
          </div>
          <article
            v-for="memory in memories"
            :id="`memory-${memory.id}`"
            :key="memory.id"
            class="memory-record"
            :class="{ 'memory-record--readonly': !hasActions(memory) }"
            :data-testid="`memory-record-${memory.id}`"
          >
            <div class="memory-record__primary">
              <div class="memory-record__head">
                <div class="memory-record__identity">
                  <span class="memory-id">#{{ memory.id }}</span>
                  <NTag size="small" :type="statusTagType(memory.status)">{{ statusLabel(memory.status) }}</NTag>
                  <NTag v-if="isMemoryExpired(memory)" size="small" type="warning">{{ t('memory.status.expired') }}</NTag>
                  <NTag v-if="memory.invalidatedAt" size="small" type="error">{{ t('memory.status.invalidated') }}</NTag>
                </div>
              </div>

              <div class="memory-content" :class="{ 'memory-content--collapsed': isCollapsible(memory) && !isExpanded(memory.id) }">
                {{ memory.content }}
              </div>
              <NButton
                v-if="isCollapsible(memory)"
                text
                size="tiny"
                class="memory-expand"
                @click="toggleExpanded(memory.id)"
              >
                {{ isExpanded(memory.id) ? t('memory.content.showLess') : t('memory.content.showFull') }}
              </NButton>

              <div v-if="memory.tags.length" class="memory-tags" :aria-label="t('memory.tagsAria')">
                <NTag v-for="tag in memory.tags" :key="tag" size="small" :bordered="false">{{ tag }}</NTag>
              </div>
            </div>

            <div class="memory-record__summary-cells">
              <span><small>{{ t('memory.field.scope') }}</small><strong>{{ scopeLabel(memory.scope) }}</strong></span>
              <span><small>{{ t('memory.field.memoryType') }}</small><strong>{{ memoryTypeLabel(memory.memoryType) }}</strong></span>
              <span>
                <small>{{ t('memory.field.confirmationStatus') }}</small>
                <NTag size="small" :type="confirmationTagType(memory.confirmationStatus)">
                  {{ confirmationStatusLabel(memory.confirmationStatus) }}
                </NTag>
              </span>
              <span>
                <small>{{ t('memory.field.expiresAt') }}</small>
                <strong>{{ memory.expiresAt ? formatOptionalDate(memory.expiresAt) : statusLabel(memory.status) }}</strong>
              </span>
              <span><small>{{ t('memory.field.updatedAt') }}</small><strong>{{ formatDate(memory.updatedAt) }}</strong></span>
            </div>

            <details class="memory-record__details">
              <summary>{{ t('memory.content.showDetails') }}</summary>
              <dl class="memory-fields">
              <div>
                <dt>{{ t('memory.field.scope') }}</dt>
                <dd>{{ scopeLabel(memory.scope) }}</dd>
              </div>
              <div>
                <dt>{{ t('memory.field.projectId') }}</dt>
                <dd>{{ valueOrDash(memory.projectId) }}</dd>
              </div>
              <div class="memory-field--wide">
                <dt>{{ t('memory.field.projectVersion') }}</dt>
                <dd>
                  <span v-if="!memory.projectVersion">{{ t('memory.value.notBound') }}</span>
                  <span v-else class="copyable-value">
                    <code :title="memory.projectVersion">{{ memory.projectVersion }}</code>
                    <NButton text size="tiny" @click="copyValue(memory.projectVersion, t('memory.field.projectVersion'))">{{ t('memory.copy') }}</NButton>
                  </span>
                </dd>
              </div>
              <div>
                <dt>{{ t('memory.field.memoryType') }}</dt>
                <dd>{{ memoryTypeLabel(memory.memoryType) }}</dd>
              </div>
              <div>
                <dt>{{ t('memory.field.confidence') }}</dt>
                <dd>{{ valueOrDash(memory.confidence) }}</dd>
              </div>
              <div>
                <dt>{{ t('memory.field.confirmedSource') }}</dt>
                <dd>{{ valueOrDash(memory.confirmedSource) }}</dd>
              </div>
              <div>
                <dt>{{ t('memory.field.confirmedAt') }}</dt>
                <dd>{{ formatOptionalDate(memory.confirmedAt) }}</dd>
              </div>
              <div>
                <dt>{{ t('memory.field.sourceType') }}</dt>
                <dd>{{ valueOrDash(memory.sourceType) }}</dd>
              </div>
              <div class="memory-field--wide">
                <dt>{{ t('memory.field.sourceReference') }}</dt>
                <dd><CopyableValue :value="memory.sourceRefId || undefined" :label="t('memory.field.sourceReference')" @copy="copyValue" /></dd>
              </div>
              <div>
                <dt>{{ t('memory.field.provenanceType') }}</dt>
                <dd>{{ valueOrDash(memory.provenanceType) }}</dd>
              </div>
              <div class="memory-field--wide">
                <dt>{{ t('memory.field.provenanceReference') }}</dt>
                <dd><CopyableValue :value="memory.provenanceRef || undefined" :label="t('memory.field.provenanceReference')" @copy="copyValue" /></dd>
              </div>
              <div>
                <dt>{{ t('memory.field.expiresAt') }}</dt>
                <dd>{{ formatOptionalDate(memory.expiresAt) }}</dd>
              </div>
              <div>
                <dt>{{ t('memory.field.invalidatedAt') }}</dt>
                <dd>{{ formatOptionalDate(memory.invalidatedAt) }}</dd>
              </div>
              <div class="memory-field--wide">
                <dt>{{ t('memory.field.invalidationReason') }}</dt>
                <dd>{{ valueOrDash(memory.invalidationReason) }}</dd>
              </div>
              <div>
                <dt>{{ t('memory.field.supersedes') }}</dt>
                <dd><RelationshipLink :memory-id="memory.supersedesMemoryId ?? undefined" @navigate="jumpToMemory" /></dd>
              </div>
              <div>
                <dt>{{ t('memory.field.supersededBy') }}</dt>
                <dd><RelationshipLink :memory-id="memory.supersededByMemoryId ?? undefined" @navigate="jumpToMemory" /></dd>
              </div>
              <div>
                <dt>{{ t('memory.field.createdAt') }}</dt>
                <dd>{{ formatDate(memory.createdAt) }}</dd>
              </div>
              <div>
                <dt>{{ t('memory.field.deletedAt') }}</dt>
                <dd>{{ formatOptionalDate(memory.deletedAt) }}</dd>
              </div>
              </dl>
            </details>

            <footer class="memory-record__actions">
              <template v-if="hasActions(memory)">
                <NButton
                  v-if="actionsFor(memory).confirm"
                  size="small"
                  type="primary"
                  :loading="isActionPending(memory.id, 'confirm')"
                  :disabled="isMemoryPending(memory.id)"
                  @click="handleConfirm(memory)"
                >
                  {{ t('memory.action.confirm') }}
                </NButton>
                <NPopconfirm
                  v-if="actionsFor(memory).reject"
                  :positive-text="t('memory.action.reject')"
                  :negative-text="t('common.cancel')"
                  @positive-click="handleReject(memory)"
                >
                  <template #trigger>
                    <NButton
                      size="small"
                      type="warning"
                      secondary
                      :loading="isActionPending(memory.id, 'reject')"
                      :disabled="isMemoryPending(memory.id)"
                    >
                      {{ t('memory.action.reject') }}
                    </NButton>
                  </template>
                  {{ t('memory.confirm.rejectPrompt') }}
                </NPopconfirm>
                <NButton
                  v-if="actionsFor(memory).correct"
                  size="small"
                  :disabled="isMemoryPending(memory.id)"
                  @click="openCorrectionModal(memory)"
                >
                  {{ t('memory.action.correct') }}
                </NButton>
                <NButton
                  v-if="actionsFor(memory).expiry"
                  size="small"
                  :disabled="isMemoryPending(memory.id)"
                  @click="openExpiryModal(memory)"
                >
                  {{ memory.expiresAt ? t('memory.action.changeExpiry') : t('memory.action.setExpiry') }}
                </NButton>
                <NPopconfirm
                  v-if="actionsFor(memory).delete"
                  :positive-text="t('memory.action.delete')"
                  :negative-text="t('common.cancel')"
                  @positive-click="handleDelete(memory)"
                >
                  <template #trigger>
                    <NButton
                      size="small"
                      type="error"
                      tertiary
                      :loading="isActionPending(memory.id, 'delete')"
                      :disabled="isMemoryPending(memory.id)"
                    >
                      {{ t('memory.action.delete') }}
                    </NButton>
                  </template>
                  {{ t('memory.confirm.deletePrompt', { id: memory.id }) }}
                </NPopconfirm>
              </template>
              <span v-else class="memory-readonly-reason">{{ readonlyReason(memory) }}</span>
            </footer>
          </article>
        </section>
      </NSpin>

      <NModal
        v-model:show="editorVisible"
        preset="card"
        class="memory-modal"
        :style="{ width: 'min(620px, calc(100vw - 24px))', maxHeight: 'calc(100dvh - 24px)' }"
        content-scrollable
        :title="editingMemory ? t('memory.editor.correctTitle', { id: editingMemory.id }) : t('memory.editor.createTitle')"
        :bordered="false"
        :mask-closable="!editorSaving"
      >
        <NAlert v-if="editorError" type="error" class="memory-modal__alert">{{ editorError }}</NAlert>
        <NForm label-placement="top" @submit.prevent="submitEditor">
          <div v-if="editingMemory" class="memory-immutable">
            <span>{{ t('memory.editor.immutableIdentity') }}</span>
            <strong>{{ scopeLabel(editingMemory.scope) }}<template v-if="editingMemory.projectId"> / {{ t('memory.scope.project') }} #{{ editingMemory.projectId }}</template></strong>
          </div>
          <div v-else class="memory-form-grid">
            <NFormItem :label="t('memory.form.scope')">
              <NSelect v-model:value="editorForm.scope" :options="scopeOptions" />
            </NFormItem>
            <NFormItem
              v-if="editorForm.scope === 'PROJECT'"
              :label="t('memory.form.projectId')"
              :validation-status="editorErrors.projectId ? 'error' : undefined"
              :feedback="editorErrors.projectId"
            >
              <NInputNumber v-model:value="editorForm.projectId" :min="1" :precision="0" style="width: 100%" />
            </NFormItem>
          </div>
          <NFormItem :label="t('memory.form.memoryType')">
            <NSelect v-model:value="editorForm.memoryType" :options="memoryTypeOptions" />
          </NFormItem>
          <NFormItem
            :label="t('memory.form.content')"
            :validation-status="editorErrors.content ? 'error' : undefined"
            :feedback="editorErrors.content"
          >
            <NInput
              v-model:value="editorForm.content"
              type="textarea"
              :autosize="{ minRows: 5, maxRows: 12 }"
              :placeholder="t('memory.form.contentPlaceholder')"
            />
          </NFormItem>
          <NFormItem
            :label="t('memory.form.tags')"
            :validation-status="editorErrors.tags ? 'error' : undefined"
            :feedback="editorErrors.tags || t('memory.form.tagsHint')"
          >
            <NDynamicTags v-model:value="editorForm.tags" :max="12" />
          </NFormItem>
          <NFormItem
            :label="t('memory.form.confidence')"
            :validation-status="editorErrors.confidence ? 'error' : undefined"
            :feedback="editorErrors.confidence || t('memory.form.confidenceHint')"
          >
            <NInputNumber
              v-model:value="editorForm.confidence"
              :min="0"
              :max="1"
              :step="0.05"
              :precision="2"
              clearable
              style="width: 100%"
            />
          </NFormItem>
          <div class="memory-modal__actions">
            <NButton :disabled="editorSaving" @click="editorVisible = false">{{ t('common.cancel') }}</NButton>
            <NButton attr-type="submit" type="primary" :loading="editorSaving">
              {{ editingMemory ? t('memory.form.saveCorrection') : t('memory.form.create') }}
            </NButton>
          </div>
        </NForm>
      </NModal>

      <NModal
        v-model:show="expiryVisible"
        preset="card"
        class="memory-modal memory-modal--compact"
        :style="{ width: 'min(500px, calc(100vw - 24px))', maxHeight: 'calc(100dvh - 24px)' }"
        content-scrollable
        :title="expiryMemory ? t('memory.expiry.title', { id: expiryMemory.id }) : t('memory.expiry.fallbackTitle')"
        :bordered="false"
        :mask-closable="!expirySaving"
      >
        <NAlert v-if="expiryError" type="error" class="memory-modal__alert">{{ expiryError }}</NAlert>
        <NForm label-placement="top" @submit.prevent="saveExpiry">
          <NFormItem
            :label="t('memory.expiry.label')"
            :validation-status="expiryError ? 'error' : undefined"
            :feedback="t('memory.expiry.hint')"
          >
            <NDatePicker
              v-model:value="expiryTimestamp"
              type="datetime"
              clearable
              :actions="['now', 'confirm']"
              style="width: 100%"
            />
          </NFormItem>
          <div class="memory-modal__actions memory-modal__actions--spread">
            <NButton
              v-if="expiryMemory?.expiresAt"
              :loading="expirySaving"
              @click="clearExpiry"
            >
              {{ t('memory.expiry.clear') }}
            </NButton>
            <span v-else />
            <div>
              <NButton :disabled="expirySaving" @click="expiryVisible = false">{{ t('common.cancel') }}</NButton>
              <NButton attr-type="submit" type="primary" :loading="expirySaving">{{ t('memory.expiry.save') }}</NButton>
            </div>
          </div>
        </NForm>
      </NModal>
    </main>
  </AppLayout>
</template>

<script setup lang="ts">
import {
  NAlert,
  NButton,
  NButtonGroup,
  NDatePicker,
  NDynamicTags,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  NSpin,
  NSwitch,
  NTag,
} from 'naive-ui';
import { computed, defineComponent, h, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import AppLayout from '@/components/AppLayout.vue';
import {
  confirmLongTermMemory,
  correctLongTermMemory,
  createLongTermMemory,
  deleteLongTermMemory,
  getMemoryDistillationJob,
  getMemoryDistillationSettings,
  listLongTermMemories,
  rejectLongTermMemory,
  startMemoryDistillation,
  updateMemoryDistillationSettings,
  updateLongTermMemoryExpiry,
  type LongTermMemoryResponse,
  type MemoryDistillationJobResponse,
  type MemoryDistillationJobStatus,
  type MemoryDistillationSettingsResponse,
  type MemoryListStatus,
  type MemoryScope,
} from '@/api/memory';
import { isMemoryExpired, isStaleMemoryApiError, memoryActions, memoryApiError } from '@/utils/memoryGovernance';
import { ui } from '@/ui';
import { useI18n } from '@/composables/useI18n';

const MEMORY_TYPES = [
  'PREFERENCE',
  'RESEARCH_PROFILE',
  'RESEARCH_FIELD',
  'STYLE',
  'FACT',
  'WARNING',
  'DECISION',
  'TERMINOLOGY',
] as const;

const { locale, t } = useI18n();

const CopyableValue = defineComponent({
  props: {
    value: { type: String, default: null },
    label: { type: String, required: true },
  },
  emits: ['copy'],
  setup(props, { emit }) {
    return () => props.value
      ? h('span', { class: 'copyable-value' }, [
          h('code', { title: props.value }, props.value),
          h(NButton, { text: true, size: 'tiny', onClick: () => emit('copy', props.value, props.label) }, () => t('memory.copy')),
        ])
      : h('span', t('memory.value.notSet'));
  },
});

const RelationshipLink = defineComponent({
  props: { memoryId: { type: Number, default: null } },
  emits: ['navigate'],
  setup(props, { emit }) {
    return () => props.memoryId == null
      ? h('span', t('memory.value.none'))
      : h(NButton, { text: true, size: 'tiny', onClick: () => emit('navigate', props.memoryId) }, () => `#${props.memoryId}`);
  },
});

const router = useRouter();
const listStatus = ref<MemoryListStatus>('ACTIVE');
const memories = ref<LongTermMemoryResponse[]>([]);
const loading = ref(false);
const loadError = ref('');
const operationError = ref('');
const operationErrorIsStale = ref(false);
const pendingAction = ref('');
const expandedIds = ref(new Set<number>());
const distillationSettings = ref<MemoryDistillationSettingsResponse | null>(null);
const distillationLoading = ref(false);
const distillationSaving = ref(false);
const distillationError = ref('');
let distillationPollTimer: number | undefined;

const editorVisible = ref(false);
const editorSaving = ref(false);
const editorError = ref('');
const editingMemory = ref<LongTermMemoryResponse | null>(null);
const editorForm = reactive({
  scope: 'USER' as MemoryScope,
  projectId: null as number | null,
  memoryType: 'FACT',
  content: '',
  tags: [] as string[],
  confidence: null as number | null,
});
const editorErrors = reactive({ projectId: '', content: '', tags: '', confidence: '' });

const expiryVisible = ref(false);
const expirySaving = ref(false);
const expiryError = ref('');
const expiryMemory = ref<LongTermMemoryResponse | null>(null);
const expiryTimestamp = ref<number | null>(null);

const scopeOptions = computed(() => [
  { label: t('memory.scope.user'), value: 'USER' },
  { label: t('memory.scope.project'), value: 'PROJECT' },
]);
const memoryTypeOptions = computed(() => MEMORY_TYPES.map((value) => ({ label: memoryTypeLabel(value), value })));
const distillationJob = computed(() => distillationSettings.value?.latestJob ?? null);
const distillationActive = computed(() => ['PENDING', 'RUNNING'].includes(distillationJob.value?.status ?? ''));
const distillationIntervalHours = computed(() => Math.max(
  1,
  Math.round((distillationSettings.value?.intervalSeconds ?? 86400) / 3600),
));

onMounted(() => {
  void loadMemories();
  void loadDistillationSettings();
});

onBeforeUnmount(() => {
  if (distillationPollTimer != null) window.clearTimeout(distillationPollTimer);
});

async function loadDistillationSettings() {
  distillationLoading.value = true;
  distillationError.value = '';
  try {
    const { data } = await getMemoryDistillationSettings();
    distillationSettings.value = data;
    const latestJob = data.latestJob;
    if (latestJob && isDistillationActive(latestJob)) scheduleDistillationPoll(latestJob.id);
  } catch (error: unknown) {
    distillationError.value = memoryApiError(error, t('memory.distillation.failure.load'));
  } finally {
    distillationLoading.value = false;
  }
}

async function updateAutoDistillation(autoEnabled: boolean) {
  distillationSaving.value = true;
  distillationError.value = '';
  try {
    const { data } = await updateMemoryDistillationSettings(autoEnabled);
    distillationSettings.value = data;
    ui.message.success(autoEnabled
      ? t('memory.distillation.toast.autoOn')
      : t('memory.distillation.toast.autoOff'));
  } catch (error: unknown) {
    distillationError.value = memoryApiError(error, t('memory.distillation.failure.update'));
  } finally {
    distillationSaving.value = false;
  }
}

async function runDistillationNow() {
  distillationError.value = '';
  try {
    const { data } = await startMemoryDistillation();
    setDistillationJob(data);
    if (data.status === 'NO_WORK') {
      ui.message.info(t('memory.distillation.toast.noWork'));
      await loadDistillationSettings();
      return;
    }
    ui.message.success(t('memory.distillation.toast.started'));
    scheduleDistillationPoll(data.id);
  } catch (error: unknown) {
    distillationError.value = memoryApiError(error, t('memory.distillation.failure.start'));
  }
}

function scheduleDistillationPoll(jobId: number) {
  if (distillationPollTimer != null) window.clearTimeout(distillationPollTimer);
  distillationPollTimer = window.setTimeout(() => void pollDistillationJob(jobId), 1500);
}

async function pollDistillationJob(jobId: number) {
  try {
    const { data } = await getMemoryDistillationJob(jobId);
    setDistillationJob(data);
    if (isDistillationActive(data)) {
      scheduleDistillationPoll(jobId);
      return;
    }
    if (data.status === 'SUCCEEDED') {
      ui.message.success(t('memory.distillation.toast.completed', { count: data.createdMemoryCount }));
      await Promise.all([loadMemories(), refreshDistillationSettings()]);
    } else if (data.status === 'NO_WORK') {
      ui.message.info(t('memory.distillation.toast.noWork'));
      await refreshDistillationSettings();
    } else if (data.status === 'FAILED') {
      distillationError.value = data.errorMessage || t('memory.distillation.failure.run');
      await refreshDistillationSettings();
    }
  } catch (error: unknown) {
    distillationError.value = memoryApiError(error, t('memory.distillation.failure.poll'));
  }
}

async function refreshDistillationSettings() {
  const { data } = await getMemoryDistillationSettings();
  distillationSettings.value = data;
}

function setDistillationJob(job: MemoryDistillationJobResponse) {
  if (!distillationSettings.value) return;
  distillationSettings.value = { ...distillationSettings.value, latestJob: job };
}

function isDistillationActive(job: MemoryDistillationJobResponse | null) {
  return job != null && (job.status === 'PENDING' || job.status === 'RUNNING');
}

function distillationStatusLabel(status?: MemoryDistillationJobStatus) {
  if (status === 'PENDING') return t('memory.distillation.status.pending');
  if (status === 'RUNNING') return t('memory.distillation.status.running');
  if (status === 'SUCCEEDED') return t('memory.distillation.status.succeeded');
  if (status === 'FAILED') return t('memory.distillation.status.failed');
  if (status === 'NO_WORK') return t('memory.distillation.status.no_work');
  return t('memory.distillation.status.never');
}

function distillationStatusType(status?: MemoryDistillationJobStatus): 'success' | 'warning' | 'error' | 'info' | 'default' {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'FAILED') return 'error';
  if (status === 'PENDING' || status === 'RUNNING') return 'info';
  if (status === 'NO_WORK') return 'default';
  return 'default';
}

async function loadMemories() {
  loading.value = true;
  loadError.value = '';
  try {
    const { data } = await listLongTermMemories(listStatus.value, 200);
    memories.value = data;
  } catch (error: unknown) {
    loadError.value = localizedMemoryApiError(error, t('memory.error.loadFallback'));
  } finally {
    loading.value = false;
  }
}

function setListStatus(status: MemoryListStatus) {
  if (listStatus.value === status) return;
  listStatus.value = status;
  void loadMemories();
}

function actionsFor(memory: LongTermMemoryResponse) {
  return memoryActions(memory);
}

function hasActions(memory: LongTermMemoryResponse) {
  return Object.values(actionsFor(memory)).some(Boolean);
}

function readonlyReason(memory: LongTermMemoryResponse) {
  if (memory.status === 'SUPERSEDED' || memory.supersededByMemoryId != null) return t('memory.readonly.superseded');
  if (memory.status === 'DELETED') return t('memory.readonly.deleted');
  if (memory.invalidatedAt) return t('memory.readonly.invalidated');
  if (isMemoryExpired(memory)) return t('memory.readonly.expired');
  return t('memory.readonly.unavailable');
}

function statusLabel(status: string) {
  if (status === 'ACTIVE') return t('memory.status.active');
  if (status === 'SUPERSEDED') return t('memory.status.superseded');
  if (status === 'DELETED') return t('memory.status.deleted');
  return status;
}

function confirmationStatusLabel(status: string) {
  if (status === 'UNCONFIRMED') return t('memory.confirmation.unconfirmed');
  if (status === 'CONFIRMED') return t('memory.confirmation.confirmed');
  if (status === 'REJECTED') return t('memory.confirmation.rejected');
  return status;
}

function scopeLabel(scope: string) {
  if (scope === 'USER') return t('memory.scope.user');
  if (scope === 'PROJECT') return t('memory.scope.project');
  return scope;
}

function memoryTypeLabel(memoryType: string) {
  const keyByType = {
    PREFERENCE: 'memory.type.preference',
    RESEARCH_PROFILE: 'memory.type.researchProfile',
    RESEARCH_FIELD: 'memory.type.researchField',
    STYLE: 'memory.type.style',
    FACT: 'memory.type.fact',
    WARNING: 'memory.type.warning',
    DECISION: 'memory.type.decision',
    TERMINOLOGY: 'memory.type.terminology',
  } as const;
  const key = keyByType[memoryType as keyof typeof keyByType];
  return key ? t(key) : memoryType;
}

function statusTagType(status: string): 'success' | 'warning' | 'error' | 'default' {
  if (status === 'ACTIVE') return 'success';
  if (status === 'SUPERSEDED') return 'warning';
  if (status === 'DELETED') return 'error';
  return 'default';
}

function confirmationTagType(status: string): 'success' | 'warning' | 'error' | 'default' {
  if (status === 'CONFIRMED') return 'success';
  if (status === 'UNCONFIRMED') return 'warning';
  if (status === 'REJECTED') return 'error';
  return 'default';
}

function isCollapsible(memory: LongTermMemoryResponse) {
  return memory.content.length > 420 || memory.content.split('\n').length > 8;
}

function isExpanded(memoryId: number) {
  return expandedIds.value.has(memoryId);
}

function toggleExpanded(memoryId: number) {
  const next = new Set(expandedIds.value);
  if (next.has(memoryId)) next.delete(memoryId);
  else next.add(memoryId);
  expandedIds.value = next;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

function formatOptionalDate(value: string | null) {
  return value ? formatDate(value) : t('memory.value.notSet');
}

function valueOrDash(value: string | number | null) {
  return value == null || value === '' ? t('memory.value.notSet') : String(value);
}

async function copyValue(value: string, label: string) {
  try {
    await navigator.clipboard.writeText(value);
    ui.message.success(t('memory.copy.success', { label }));
  } catch {
    ui.message.error(t('memory.copy.failed', { label }));
  }
}

async function jumpToMemory(memoryId: number) {
  if (listStatus.value !== 'ALL') {
    listStatus.value = 'ALL';
    await loadMemories();
  }
  requestAnimationFrame(() => document.getElementById(`memory-${memoryId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }));
}

function openCreateModal() {
  editingMemory.value = null;
  editorForm.scope = 'USER';
  editorForm.projectId = null;
  editorForm.memoryType = 'FACT';
  editorForm.content = '';
  editorForm.tags = [];
  editorForm.confidence = null;
  clearEditorErrors();
  editorVisible.value = true;
}

function openCorrectionModal(memory: LongTermMemoryResponse) {
  editingMemory.value = memory;
  editorForm.scope = memory.scope;
  editorForm.projectId = memory.projectId;
  editorForm.memoryType = memory.memoryType;
  editorForm.content = memory.content;
  editorForm.tags = [...memory.tags];
  editorForm.confidence = memory.confidence;
  clearEditorErrors();
  editorVisible.value = true;
}

function clearEditorErrors() {
  editorError.value = '';
  editorErrors.projectId = '';
  editorErrors.content = '';
  editorErrors.tags = '';
  editorErrors.confidence = '';
}

function validateEditor() {
  clearEditorErrors();
  if (!editingMemory.value && editorForm.scope === 'PROJECT' && (!editorForm.projectId || editorForm.projectId < 1)) {
    editorErrors.projectId = t('memory.validation.projectId');
  }
  if (!editorForm.content.trim()) editorErrors.content = t('memory.validation.content');
  if (editorForm.tags.length > 12 || editorForm.tags.some((tag) => !tag.trim() || tag.trim().length > 64)) {
    editorErrors.tags = t('memory.validation.tags');
  }
  if (editorForm.confidence != null && (editorForm.confidence < 0 || editorForm.confidence > 1)) {
    editorErrors.confidence = t('memory.validation.confidence');
  }
  return !Object.values(editorErrors).some(Boolean);
}

async function submitEditor() {
  if (!validateEditor()) return;
  editorSaving.value = true;
  editorError.value = '';
  try {
    if (editingMemory.value) {
      await correctLongTermMemory(editingMemory.value.id, {
        memoryType: editorForm.memoryType,
        content: editorForm.content.trim(),
        tags: editorForm.tags.map((tag) => tag.trim()),
        ...(editorForm.confidence == null ? {} : { confidence: editorForm.confidence }),
      });
      ui.message.success(t('memory.toast.correctionSaved'));
    } else {
      await createLongTermMemory({
        scope: editorForm.scope,
        ...(editorForm.scope === 'PROJECT' && editorForm.projectId ? { projectId: editorForm.projectId } : {}),
        memoryType: editorForm.memoryType,
        content: editorForm.content.trim(),
        tags: editorForm.tags.map((tag) => tag.trim()),
        ...(editorForm.confidence == null ? {} : { confidence: editorForm.confidence }),
      });
      ui.message.success(t('memory.toast.created'));
    }
    editorVisible.value = false;
    await loadMemories();
  } catch (error: unknown) {
    editorError.value = localizedMemoryApiError(
      error,
      editingMemory.value ? t('memory.failure.correct') : t('memory.failure.create'),
    );
  } finally {
    editorSaving.value = false;
  }
}

async function handleConfirm(memory: LongTermMemoryResponse) {
  await runMemoryAction(
    memory.id,
    'confirm',
    () => confirmLongTermMemory(memory.id),
    t('memory.toast.confirmed'),
    t('memory.failure.confirm'),
  );
}

async function handleReject(memory: LongTermMemoryResponse) {
  await runMemoryAction(
    memory.id,
    'reject',
    () => rejectLongTermMemory(memory.id),
    t('memory.toast.rejected'),
    t('memory.failure.reject'),
  );
}

async function handleDelete(memory: LongTermMemoryResponse) {
  await runMemoryAction(
    memory.id,
    'delete',
    () => deleteLongTermMemory(memory.id),
    t('memory.toast.deleted'),
    t('memory.failure.delete'),
  );
}

async function runMemoryAction(
  memoryId: number,
  action: string,
  request: () => Promise<unknown>,
  successMessage: string,
  failureMessage: string,
) {
  pendingAction.value = `${memoryId}:${action}`;
  clearOperationError();
  try {
    await request();
    ui.message.success(successMessage);
    await loadMemories();
  } catch (error: unknown) {
    operationErrorIsStale.value = isStaleMemoryApiError(error);
    operationError.value = localizedMemoryApiError(error, failureMessage);
    ui.message.error(operationError.value);
  } finally {
    pendingAction.value = '';
  }
}

function isActionPending(memoryId: number, action: string) {
  return pendingAction.value === `${memoryId}:${action}`;
}

function isMemoryPending(memoryId: number) {
  return pendingAction.value.startsWith(`${memoryId}:`);
}

function openExpiryModal(memory: LongTermMemoryResponse) {
  expiryMemory.value = memory;
  expiryTimestamp.value = memory.expiresAt ? new Date(memory.expiresAt).getTime() : null;
  expiryError.value = '';
  expiryVisible.value = true;
}

async function saveExpiry() {
  if (!expiryMemory.value) return;
  expiryError.value = '';
  if (expiryTimestamp.value == null) {
    expiryError.value = t('memory.validation.expiryRequired');
    return;
  }
  if (expiryTimestamp.value <= Date.now()) {
    expiryError.value = t('memory.validation.expiryFuture');
    return;
  }
  await persistExpiry(new Date(expiryTimestamp.value).toISOString(), t('memory.toast.expiryUpdated'));
}

async function clearExpiry() {
  await persistExpiry(null, t('memory.toast.expiryCleared'));
}

async function persistExpiry(expiresAt: string | null, successMessage: string) {
  if (!expiryMemory.value) return;
  expirySaving.value = true;
  expiryError.value = '';
  try {
    await updateLongTermMemoryExpiry(expiryMemory.value.id, expiresAt);
    ui.message.success(successMessage);
    expiryVisible.value = false;
    await loadMemories();
  } catch (error: unknown) {
    expiryError.value = localizedMemoryApiError(error, t('memory.failure.expiry'));
  } finally {
    expirySaving.value = false;
  }
}

function localizedMemoryApiError(error: unknown, fallback: string) {
  const detail = memoryApiError(error, fallback);
  return isStaleMemoryApiError(error) ? t('memory.error.stale', { detail }) : detail;
}

function clearOperationError() {
  operationError.value = '';
  operationErrorIsStale.value = false;
}
</script>

<style scoped>
.memory-page {
  color: var(--scholar-ink);
}

.memory-header {
  margin-bottom: 18px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--scholar-line);
}

.memory-breadcrumb,
.memory-header__row,
.memory-header__actions,
.memory-toolbar,
.memory-record__head,
.memory-record__identity,
.memory-record__actions,
.memory-tags,
.copyable-value,
.memory-modal__actions {
  display: flex;
  align-items: center;
}

.memory-breadcrumb {
  gap: 7px;
  margin-bottom: 10px;
  color: var(--scholar-muted);
  font-size: 12px;
}

.memory-header__row {
  justify-content: space-between;
  gap: 24px;
}

.memory-header h1 {
  margin: 0 0 5px;
  font-size: 30px;
  line-height: 1.2;
  letter-spacing: 0;
}

.memory-header p {
  max-width: 760px;
  margin: 0;
  color: var(--scholar-muted);
  line-height: 1.5;
}

.memory-header__actions {
  flex: 0 0 auto;
  gap: 10px;
}

.memory-toolbar {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.memory-count,
.memory-updated,
.memory-readonly-reason {
  color: var(--scholar-muted);
  font-size: 12px;
}

.memory-alert {
  margin-bottom: 14px;
}

.memory-alert__retry {
  margin-left: 10px;
}

.memory-empty {
  padding: 72px 24px;
  border-top: 1px solid var(--scholar-line);
  border-bottom: 1px solid var(--scholar-line);
}

.memory-list {
  display: grid;
  gap: 12px;
}

.memory-record {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--scholar-line);
  border-radius: 8px;
  background: var(--scholar-card-bg);
}

.memory-record--readonly {
  border-left: 3px solid var(--scholar-line-strong);
}

.memory-record__head {
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.memory-record__identity,
.memory-tags,
.memory-record__actions {
  flex-wrap: wrap;
  gap: 7px;
}

.memory-id {
  color: var(--scholar-muted);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  font-weight: 700;
}

.memory-content {
  margin-top: 14px;
  color: var(--scholar-ink);
  font-size: 15px;
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.memory-content--collapsed {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 6;
}

.memory-expand {
  margin-top: 5px;
}

.memory-tags {
  margin-top: 12px;
}

.memory-fields {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  margin: 15px 0 0;
  border-top: 1px solid var(--scholar-line);
  border-left: 1px solid var(--scholar-line);
}

.memory-fields > div {
  min-width: 0;
  padding: 10px 12px;
  border-right: 1px solid var(--scholar-line);
  border-bottom: 1px solid var(--scholar-line);
}

.memory-field--wide {
  grid-column: span 2;
}

.memory-fields dt {
  margin-bottom: 4px;
  color: var(--scholar-muted);
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
}

.memory-fields dd {
  min-width: 0;
  margin: 0;
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.copyable-value {
  gap: 7px;
  min-width: 0;
}

.copyable-value code {
  display: block;
  min-width: 0;
  overflow: hidden;
  color: var(--scholar-ink);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.copyable-value :deep(.n-button) {
  flex: 0 0 auto;
}

.memory-record__actions {
  min-height: 34px;
  margin-top: 14px;
}

.memory-modal {
  width: min(620px, calc(100vw - 32px));
  max-height: calc(100dvh - 24px);
  display: flex;
  flex-direction: column;
}

.memory-modal--compact {
  width: min(500px, calc(100vw - 32px));
}

.memory-modal :deep(.n-card-header) {
  flex: 0 0 auto;
}

.memory-modal :deep(.n-card__content) {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
}

.memory-modal__alert {
  margin-bottom: 14px;
}

.memory-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.memory-immutable {
  display: grid;
  gap: 3px;
  margin-bottom: 16px;
  padding: 10px 12px;
  border-left: 3px solid var(--scholar-blue);
  background: var(--scholar-blue-soft);
}

.memory-immutable span {
  color: var(--scholar-muted);
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
}

.memory-modal__actions {
  position: sticky;
  bottom: 0;
  z-index: 2;
  justify-content: flex-end;
  gap: 9px;
  margin-top: 8px;
  padding: 10px 0 2px;
  border-top: 1px solid var(--pa-line);
  background: var(--pa-surface);
}

.memory-modal__actions--spread {
  justify-content: space-between;
}

.memory-modal__actions--spread > div {
  display: flex;
  gap: 9px;
}

@media (max-width: 1280px) {
  .memory-fields {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .memory-field--wide {
    grid-column: span 1;
  }
}

@media (max-width: 900px) {
  .memory-header__row {
    align-items: flex-start;
    flex-direction: column;
  }

  .memory-fields {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .memory-page {
    padding: 18px 14px 28px !important;
  }

  .memory-header h1 {
    font-size: 25px;
  }

  .memory-header__actions,
  .memory-header__actions :deep(.n-button),
  .memory-toolbar,
  .memory-toolbar :deep(.n-button-group) {
    width: 100%;
  }

  .memory-header__actions :deep(.n-button),
  .memory-toolbar :deep(.n-button) {
    flex: 1 1 0;
  }

  .memory-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .memory-record {
    padding: 13px;
  }

  .memory-record__head {
    align-items: flex-start;
    flex-direction: column;
  }

  .memory-fields,
  .memory-form-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .memory-field--wide {
    grid-column: span 1;
  }

  .memory-record__actions :deep(.n-button) {
    flex: 1 1 auto;
  }
}
</style>
