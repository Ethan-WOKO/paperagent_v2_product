<template>
  <AppLayout>
    <div class="kb-page kb-page--redesign workbench-page scholar-page scholar-page--knowledge">
      <WorkspaceHero
        kicker="Knowledge Base"
        title="Knowledge Base"
        subtitle="Manage private research documents, parsing status, retrieval visibility, and previewable text assets."
        storage-key="yanban.hero.knowledge"
      >
        <template #actions>
          <NSpace align="center">
            <NButton secondary @click="router.push('/knowledge-base/search-debug')">Search Debug</NButton>
            <NButton secondary @click="toggleUploadPanel">{{ uploadPanelOpen ? (isEnglish ? 'Hide upload' : '收起上传') : (isEnglish ? 'Upload document' : '上传文档') }}</NButton>
            <NButton :loading="loading" @click="loadDocuments">Sync</NButton>
          </NSpace>
        </template>
      </WorkspaceHero>

      <div v-if="documents.length > 0" class="scholar-metric-strip kb-metric-strip">
        <article class="scholar-metric-card">
          <span>Total documents</span>
          <strong>{{ documents.length }}</strong>
        </article>
        <article class="scholar-metric-card">
          <span>Ready for RAG</span>
          <strong>{{ readyCount }}</strong>
        </article>
        <article class="scholar-metric-card">
          <span>Processing</span>
          <strong>{{ processingCount }}</strong>
        </article>
        <article class="scholar-metric-card">
          <span>{{ isEnglish ? 'Listed file size' : '列表文件大小' }}</span>
          <strong>{{ totalStorageText }}</strong>
        </article>
      </div>

      <div class="kb-workspace" :class="{ 'kb-workspace--preview-open': previewDocument }">
        <main class="kb-workspace__main">
            <NCard v-if="uploadPanelOpen" class="workbench-card scholar-card kb-upload-card" :bordered="false">
              <template #header>
                <div class="section-title">Upload Documents</div>
              </template>
              <NSpace vertical size="large">
                <div class="kb-upload-box">
                  <input ref="fileInputRef" type="file" accept=".pdf,.docx,.txt,.md" class="kb-file-input" @change="handleFileChange" />
                  <div
                    class="upload-dropzone scholar-dropzone"
                    role="button"
                    tabindex="0"
                    @click="fileInputRef?.click()"
                    @keydown.enter.prevent="fileInputRef?.click()"
                    @keydown.space.prevent="fileInputRef?.click()"
                  >
                    <div class="scholar-dropzone__icon">UP</div>
                    <strong>{{ selectedFile ? selectedFile.name : (isEnglish ? 'Choose a document' : '选择一个文档') }}</strong>
                    <span>{{ selectedFile ? formatFileSize(selectedFile.size) : (isEnglish ? 'PDF, DOCX, TXT or MD. The backend enforces the size limit.' : '支持 PDF、DOCX、TXT、MD，大小限制由后端校验。') }}</span>
                  </div>
                </div>

                <div class="kb-upload-actions">
                  <NCheckbox v-model:checked="isPublic">{{ isEnglish ? 'Make this document public' : '将此文档设为公开' }}</NCheckbox>
                  <NSpace>
                    <NButton secondary :disabled="uploading || !selectedFile" @click="clearSelectedFile">Clear</NButton>
                    <NButton type="primary" :loading="uploading" :disabled="!selectedFile" @click="handleUpload">
                      {{ isEnglish ? 'Upload document' : '上传文档' }}
                    </NButton>
                  </NSpace>
                </div>

                <div v-if="uploading" class="kb-progress-block">
                  <NProgress type="line" :percentage="uploadProgress" :indicator-placement="'inside'" processing />
                  <div class="chat-hint">{{ uploadStatusText }}</div>
                </div>
              </NSpace>
            </NCard>

            <NCard v-if="documents.length > 0 || !uploadPanelOpen || loading" class="workbench-card scholar-card kb-documents-card" :bordered="false">
              <template #header>
                <div class="section-title">Documents</div>
              </template>
              <template #header-extra>
                <NSpace>
                  <NTag :type="documentHealthType" round>
                    {{ documentHealthLabel }}
                  </NTag>
                  <NButton size="small" secondary :loading="loading" @click="loadDocuments">Refresh</NButton>
                </NSpace>
              </template>

              <NEmpty v-if="documents.length === 0 && !loading" :description="isEnglish ? 'No knowledge base documents yet.' : '知识库中暂无文档。'">
                <template #extra>
                  <NButton type="primary" @click="toggleUploadPanel">
                    {{ isEnglish ? 'Upload the first document' : '上传第一份文档' }}
                  </NButton>
                </template>
              </NEmpty>

              <div v-else class="kb-document-table">
                <div class="kb-document-table__head">
                  <span class="kb-document-head kb-document-head--name">
                    <span class="kb-document-head__badge-spacer" aria-hidden="true" />
                    <span>Filename</span>
                  </span>
                  <span class="kb-document-head kb-document-head--type">Type</span>
                  <span class="kb-document-head kb-document-head--size">Size</span>
                  <span class="kb-document-head kb-document-head--status">Status</span>
                  <span class="kb-document-head kb-document-head--visibility">Visibility</span>
                  <span class="kb-document-head kb-document-head--updated">Updated</span>
                  <span class="kb-document-head kb-document-head--actions">Actions</span>
                </div>
                <article
                  v-for="item in documents"
                  :key="item.id"
                  class="kb-document-row"
                  :class="{ 'kb-document-row--selected': previewDocument?.id === item.id }"
                >
                  <div class="kb-document-name">
                    <span class="kb-file-badge">{{ documentTypeLabel(item) }}</span>
                    <div>
                      <strong>{{ item.filename }}</strong>
                      <small>Document #{{ item.id }} · {{ documentKindText(item) }}</small>
                      <small v-if="item.errorMessage" class="kb-error-text">{{ item.errorMessage }}</small>
                    </div>
                  </div>
                  <span class="kb-document-type">{{ documentTypeLabel(item) }}</span>
                  <span class="kb-document-size">{{ formatFileSize(item.fileSize) }}</span>
                  <NTag class="kb-document-tag kb-document-status" :type="statusTagType(item.status)" size="small">{{ item.status }}</NTag>
                  <NTag class="kb-document-tag kb-document-visibility" :type="item.isPublic ? 'info' : 'default'" size="small">
                    {{ item.isPublic ? 'Public' : 'Private' }}
                  </NTag>
                  <span class="kb-document-updated">{{ formatDateTime(item.updatedAt) }}</span>
                  <NSpace class="kb-document-actions" size="small" justify="end">
                    <NButton text type="primary" @click="handlePreview(item)">Preview</NButton>
                    <NPopconfirm v-if="item.sourceType !== 'DEMO_SEED'" @positive-click="handleDelete(item.id)">
                      <template #trigger>
                        <NButton text type="error">Delete</NButton>
                      </template>
                      Delete this document?
                    </NPopconfirm>
                    <NTag v-else size="small" type="info">Demo seed</NTag>
                  </NSpace>
                </article>
              </div>
            </NCard>
        </main>

        <aside v-if="previewDocument" class="kb-workspace__preview">
          <NCard class="workbench-card scholar-card kb-preview-side-card" :bordered="false">
            <template #header>
              <div class="section-title">Parsed Text Preview</div>
            </template>
            <template #header-extra>
              <NSpace size="small">
                <NButton size="small" secondary :disabled="!previewData?.content" @click="copyPreviewContent">Copy</NButton>
                <NButton size="small" quaternary @click="closePreview">{{ isEnglish ? 'Close' : '关闭' }}</NButton>
              </NSpace>
            </template>

            <NSpin :show="previewLoading">
              <div v-if="previewDocument" class="kb-preview-side">
                <div class="kb-preview-file-head">
                  <span class="kb-file-badge kb-file-badge--large">{{ documentTypeLabel(previewDocument) }}</span>
                  <div>
                    <strong>{{ previewDocument.filename }}</strong>
                    <div class="kb-preview__meta">
                      <NTag :type="statusTagType(previewDocument.status)" size="small">{{ previewDocument.status }}</NTag>
                      <NTag :type="previewDocument.isPublic ? 'info' : 'default'" size="small">
                        {{ previewDocument.isPublic ? 'Public' : 'Private' }}
                      </NTag>
                    </div>
                  </div>
                </div>

                <div class="kb-preview-stat-grid">
                  <div>
                    <span>File size</span>
                    <strong>{{ formatFileSize(previewDocument.fileSize) }}</strong>
                  </div>
                  <div>
                    <span>Updated</span>
                    <strong>{{ formatDateTime(previewDocument.updatedAt) }}</strong>
                  </div>
                  <div>
                    <span>Preview chunks</span>
                    <strong>{{ previewData ? `${previewData.previewChunks}/${previewData.totalChunks}` : '-' }}</strong>
                  </div>
                  <div>
                    <span>Max chars</span>
                    <strong>{{ previewData?.maxChars ?? '-' }}</strong>
                  </div>
                </div>

                <NAlert v-if="previewData?.truncated" type="warning" title="Truncated preview">
                  {{ isEnglish ? `Showing the first ${previewData.maxChars} characters.` : `当前仅展示前 ${previewData.maxChars} 个字符。` }}
                </NAlert>
                <NAlert v-if="previewData && !previewData.content" type="info" title="No parsed text yet">
                  The document may still be processing, or no text could be extracted.
                </NAlert>
                <pre v-if="previewData?.content" class="kb-preview__content">{{ previewData.content }}</pre>
              </div>
              <NEmpty v-else description="Select Preview on a document to inspect parsed text." />
            </NSpin>
          </NCard>
        </aside>
      </div>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import {
  NAlert,
  NButton,
  NCard,
  NCheckbox,
  NEmpty,
  NPopconfirm,
  NProgress,
  NSpace,
  NSpin,
  NTag,
} from 'naive-ui';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import AppLayout from '@/components/AppLayout.vue';
import WorkspaceHero from '@/components/WorkspaceHero.vue';
import {
  deleteKbDocument,
  listKbDocuments,
  mergeKbUpload,
  previewKbDocument,
  uploadChunk,
  type KbDocumentItem,
  type KbDocumentPreviewResponse,
} from '@/api/knowledge';
import { ui } from '@/ui';
import { useI18n } from '@/composables/useI18n';

const CHUNK_SIZE = 1024 * 1024;

const router = useRouter();
const { isEnglish, locale } = useI18n();

const fileInputRef = ref<HTMLInputElement | null>(null);
const uploadPanelOpen = ref(readStoredBoolean('yanban.knowledge.uploadOpen', false));
const selectedFile = ref<File | null>(null);
const isPublic = ref(false);
const uploading = ref(false);
const uploadProgress = ref(0);
const uploadStatusText = ref('');
const loading = ref(false);
const documents = ref<KbDocumentItem[]>([]);
const previewLoading = ref(false);
const previewData = ref<KbDocumentPreviewResponse | null>(null);
const previewDocument = ref<KbDocumentItem | null>(null);
let pollingTimer: number | null = null;

const hasProcessingDocuments = computed(() =>
  documents.value.some((item) => item.status === 'PROCESSING' || item.status === 'UPLOADING'),
);
const readyCount = computed(() => documents.value.filter((item) => item.status === 'READY').length);
const failedCount = computed(() => documents.value.filter((item) => item.status === 'FAILED').length);
const processingCount = computed(() =>
  documents.value.filter((item) => item.status === 'PROCESSING' || item.status === 'UPLOADING').length,
);
const totalStorageText = computed(() =>
  formatFileSize(documents.value.reduce((total, item) => total + (item.fileSize || 0), 0)),
);
const documentHealthLabel = computed(() => {
  if (hasProcessingDocuments.value) return isEnglish.value ? 'Processing' : '处理中';
  if (failedCount.value > 0) return isEnglish.value ? 'Needs attention' : '需要处理';
  return isEnglish.value ? 'Stable' : '稳定';
});
const documentHealthType = computed(() => {
  if (hasProcessingDocuments.value) return 'warning';
  return failedCount.value > 0 ? 'error' : 'success';
});

onMounted(async () => {
  await loadDocuments();
});

onBeforeUnmount(() => {
  stopPolling();
});

function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement;
  selectedFile.value = target.files?.[0] || null;
}

function clearSelectedFile() {
  selectedFile.value = null;
  if (fileInputRef.value) {
    fileInputRef.value.value = '';
  }
}

function readStoredBoolean(key: string, fallback: boolean) {
  const value = window.localStorage.getItem(key);
  return value == null ? fallback : value === 'true';
}

function toggleUploadPanel() {
  uploadPanelOpen.value = !uploadPanelOpen.value;
  window.localStorage.setItem('yanban.knowledge.uploadOpen', String(uploadPanelOpen.value));
}

function closePreview() {
  previewDocument.value = null;
  previewData.value = null;
}

async function handleUpload() {
  const file = selectedFile.value;
  if (!file) {
    ui.message.warning('Please choose a file first.');
    return;
  }

  const uploadId = globalThis.crypto?.randomUUID?.() || `upload-${Date.now()}`;
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE);

  uploading.value = true;
  uploadProgress.value = 0;

  try {
    for (let index = 0; index < totalChunks; index += 1) {
      const start = index * CHUNK_SIZE;
      const end = Math.min(start + CHUNK_SIZE, file.size);
      const chunk = file.slice(start, end);
      await uploadChunkWithRetry({
        uploadId,
        filename: file.name,
        chunkNumber: index,
        totalChunks,
        file: chunk,
      });
      uploadProgress.value = Math.round(((index + 1) / totalChunks) * 90);
    }

    uploadStatusText.value = isEnglish.value
      ? 'Merging chunks and submitting the document for background parsing.'
      : '正在合并分块并提交文档进行后台解析。';
    await mergeKbUpload({
      uploadId,
      filename: file.name,
      totalChunks,
      mimeType: file.type || 'application/octet-stream',
      isPublic: isPublic.value,
    });

    uploadProgress.value = 100;
    uploadStatusText.value = isEnglish.value ? 'Upload complete. Waiting for parsing.' : '上传完成，正在等待解析。';
    ui.message.success('Upload complete. The document is processing.');
    clearSelectedFile();
    await loadDocuments();
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || 'Upload failed.');
  } finally {
    uploading.value = false;
  }
}

async function loadDocuments() {
  loading.value = true;
  try {
    const { data } = await listKbDocuments();
    documents.value = data;
    if (previewDocument.value) {
      previewDocument.value = data.find((item) => item.id === previewDocument.value?.id) || previewDocument.value;
    }
    if (data.some((item) => item.status === 'PROCESSING' || item.status === 'UPLOADING')) {
      ensurePolling();
    } else {
      stopPolling();
    }
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || 'Failed to load knowledge documents.');
  } finally {
    loading.value = false;
  }
}

async function handleDelete(documentId: number) {
  try {
    await deleteKbDocument(documentId);
    ui.message.success('Document deleted.');
    if (previewDocument.value?.id === documentId) {
      previewDocument.value = null;
      previewData.value = null;
    }
    await loadDocuments();
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || 'Delete failed.');
  }
}

async function handlePreview(document: KbDocumentItem) {
  previewDocument.value = document;
  previewData.value = null;
  previewLoading.value = true;
  try {
    const { data } = await previewKbDocument(document.id, 20000);
    previewData.value = data;
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || 'Failed to load preview.');
  } finally {
    previewLoading.value = false;
  }
}

async function copyPreviewContent() {
  const content = previewData.value?.content;
  if (!content) {
    return;
  }
  try {
    await navigator.clipboard.writeText(content);
    ui.message.success('Preview content copied.');
  } catch {
    ui.message.error('Copy failed. Please select the text manually.');
  }
}

function ensurePolling() {
  if (pollingTimer !== null) {
    return;
  }
  pollingTimer = window.setInterval(() => {
    void loadDocuments();
  }, 3000);
}

function stopPolling() {
  if (pollingTimer !== null) {
    window.clearInterval(pollingTimer);
    pollingTimer = null;
  }
}

async function uploadChunkWithRetry(payload: {
  uploadId: string;
  filename: string;
  chunkNumber: number;
  totalChunks: number;
  file: Blob;
}) {
  let lastError: unknown;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      uploadStatusText.value = isEnglish.value
        ? `Uploading chunk ${payload.chunkNumber + 1}/${payload.totalChunks}, attempt ${attempt}.`
        : `正在上传分块 ${payload.chunkNumber + 1}/${payload.totalChunks}，第 ${attempt} 次尝试。`;
      await uploadChunk(payload);
      return;
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError;
}

function statusTagType(status: string) {
  if (status === 'READY') {
    return 'success';
  }
  if (status === 'FAILED') {
    return 'error';
  }
  if (status === 'PROCESSING' || status === 'UPLOADING') {
    return 'warning';
  }
  return 'default';
}

function documentTypeLabel(item: KbDocumentItem) {
  const name = item.filename.toLowerCase();
  const mime = (item.mimeType || '').toLowerCase();
  if (name.endsWith('.pdf') || mime.includes('pdf')) {
    return 'PDF';
  }
  if (name.endsWith('.docx') || mime.includes('word')) {
    return 'DOCX';
  }
  if (name.endsWith('.md') || mime.includes('markdown')) {
    return 'MD';
  }
  if (name.endsWith('.txt') || mime.includes('text')) {
    return 'TXT';
  }
  return 'FILE';
}

function documentKindText(item: KbDocumentItem) {
  const descriptions: Record<string, string> = {
    PDF: 'PDF document',
    DOCX: 'Word document',
    MD: 'Markdown file',
    TXT: 'Text file',
    FILE: 'Document file',
  };
  return descriptions[documentTypeLabel(item)] || 'Document file';
}

function formatDateTime(value: string | null) {
  if (!value) {
    return '-';
  }
  return new Date(value).toLocaleString(locale.value);
}

function formatFileSize(value: number | null) {
  if (value == null || Number.isNaN(value)) {
    return '-';
  }
  if (value < 1024) {
    return `${value} B`;
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`;
  }
  return `${(value / (1024 * 1024)).toFixed(2)} MB`;
}
</script>
