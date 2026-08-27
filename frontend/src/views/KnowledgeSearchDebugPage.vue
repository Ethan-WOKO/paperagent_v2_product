<template>
  <AppLayout>
    <div class="search-page search-page--redesign workbench-page scholar-page scholar-page--search">
      <WorkspaceHero
        kicker="Search Debug"
        title="Knowledge Search Debug"
        subtitle="Inspect retrieval quality, score bands, selected chunks, and RAG visibility before shipping answers to users."
        storage-key="yanban.hero.retrieval"
      >
        <template #actions>
          <NSpace align="center">
            <NTag type="info" round>{{ results.length }} results</NTag>
            <NButton v-if="results.length > 0" secondary @click="toggleDiagnostics($event)">
              {{ diagnosticsVisible ? (isEnglish ? 'Hide diagnostics' : '收起诊断') : (isEnglish ? 'Show diagnostics' : '展开诊断') }}
            </NButton>
            <NButton secondary @click="fillSampleQuery">Sample Query</NButton>
          </NSpace>
        </template>
      </WorkspaceHero>

      <div class="search-workspace" :class="{ 'search-workspace--diagnostics-open': results.length > 0 && diagnosticsVisible }">
        <main class="search-workspace__main">
            <NCard class="workbench-card scholar-card search-console-card" :bordered="false">
              <template #header>
                <div class="section-title">Retrieval Console</div>
              </template>
              <NForm :model="form" label-placement="top">
                <NFormItem label="Query">
                  <NInput
                    v-model:value="form.query"
                    type="textarea"
                    :autosize="{ minRows: 1, maxRows: 4 }"
                    placeholder="Example: What is the weekly lab meeting time?"
                  />
                </NFormItem>
                <NGrid :cols="24" :x-gap="16" responsive="screen" item-responsive>
                  <NFormItemGi span="24 m:6" label="Top K">
                    <NInputNumber v-model:value="form.topK" :min="1" :max="20" style="width: 100%" />
                  </NFormItemGi>
                  <NFormItemGi span="24 m:6" label="Document Scope">
                    <div class="search-static-pill" :title="isEnglish ? 'Private and permitted public documents' : '私有文档与获准公开文档'">Private + permitted public</div>
                  </NFormItemGi>
                  <NFormItemGi span="24 m:6" label="Embedding">
                    <div class="search-static-pill" :title="isEnglish ? 'Configured backend embedding model' : '后端已配置的向量模型'">Configured backend model</div>
                  </NFormItemGi>
                  <NFormItemGi span="24 m:6" label="Action">
                    <NButton ref="searchButtonRef" type="primary" block :loading="searching" @click="handleSearch">Search</NButton>
                  </NFormItemGi>
                </NGrid>
              </NForm>

              <div class="search-run-strip">
                <div>
                  <span>{{ isEnglish ? 'Round-trip time' : '请求往返时间' }}</span>
                  <strong>{{ lastDurationMs == null ? '-' : `${lastDurationMs} ms` }}</strong>
                </div>
                <div>
                  <span>Retrieved chunks</span>
                  <strong>{{ results.length }}</strong>
                </div>
                <div>
                  <span>Last run</span>
                  <strong>{{ lastRunAt ? formatDateTime(lastRunAt) : '-' }}</strong>
                </div>
                <NButton secondary @click="clearResults">Clear</NButton>
              </div>
            </NCard>

            <NCard v-if="searching || results.length > 0" class="workbench-card scholar-card" :bordered="false">
              <template #header>
                <div class="section-title">Results</div>
              </template>
              <template #header-extra>
                <span class="chat-hint">Top {{ results.length }} of requested {{ form.topK }}</span>
              </template>

              <NEmpty v-if="!searching && results.length === 0" description="Run a query to inspect retrieval results." />

              <div v-else class="search-result-table">
                <div class="search-result-table__head">
                  <span>Rank</span>
                  <span>File / Chunk</span>
                  <span>Score</span>
                  <span>Band</span>
                  <span>Visibility</span>
                  <span>Snippet</span>
                </div>
                <article
                  v-for="(item, index) in results"
                  :key="`${item.documentId}-${item.chunkIndex}-${index}`"
                  class="search-result-row"
                  :class="{ 'search-result-row--selected': selectedIndex === index }"
                  role="button"
                  tabindex="0"
                  @click="selectedIndex = index"
                  @keydown.enter.prevent="selectedIndex = index"
                  @keydown.space.prevent="selectedIndex = index"
                >
                  <span class="search-result-rank">{{ index + 1 }}</span>
                  <div>
                    <strong>{{ item.filename }}</strong>
                    <small>documentId={{ item.documentId }} · chunk {{ item.chunkIndex }}</small>
                  </div>
                  <span class="search-result-score">{{ formatScore(item.score) }}</span>
                  <NTag class="search-result-band" :type="scoreBandType(item.score)" size="small">{{ scoreBandLabel(item.score) }}</NTag>
                  <NTag class="search-result-visibility" :type="item.isPublic ? 'info' : 'default'" size="small">
                    {{ item.isPublic ? 'Public' : 'Private' }}
                  </NTag>
                  <p class="search-result-snippet">{{ item.chunkText }}</p>
                </article>
              </div>
            </NCard>
        </main>

        <aside
          v-if="results.length > 0 && diagnosticsVisible"
          ref="diagnosticsPanelRef"
          class="search-workspace__diagnostics"
          role="region"
          aria-label="Retrieval diagnostics"
          tabindex="-1"
          @click.self="closeDiagnostics"
          @keydown.esc.stop="closeDiagnostics"
        >
          <NCard class="workbench-card scholar-card search-diagnostics-card" :bordered="false">
            <template #header>
              <div class="section-title">Diagnostics</div>
            </template>
            <template #header-extra>
              <NButton size="small" quaternary @click="closeDiagnostics">{{ isEnglish ? 'Close' : '关闭' }}</NButton>
            </template>

            <NSpace vertical size="large">
              <div class="diagnostic-status">
                <span>Recall Status</span>
                <NTag :type="recallStatusType" round>{{ recallStatusLabel }}</NTag>
              </div>

              <NAlert v-if="lowScoreCount > 0" type="warning" title="Low-confidence results detected">
                {{ lowScoreCount }} of {{ results.length }} results are below 0.50. Consider increasing Top K or rewriting the query.
              </NAlert>
              <NAlert v-else-if="results.length > 0" type="success" title="Retrieval looks usable">
                The current result set has no low-confidence chunks.
              </NAlert>

              <div class="score-panel">
                <div class="score-panel__title">Top-K Score Distribution</div>
                <div v-for="band in scoreBands" :key="band.label" class="score-band-row">
                  <span>{{ band.label }}</span>
                  <div class="score-band-track">
                    <i :style="{ width: `${band.percent}%` }" :class="`score-band-fill score-band-fill--${band.tone}`" />
                  </div>
                  <strong>{{ band.count }}</strong>
                </div>
              </div>

              <div class="diagnostic-grid">
                <div>
                  <span>Average score</span>
                  <strong>{{ averageScore == null ? '-' : formatScore(averageScore) }}</strong>
                </div>
                <div>
                  <span>High score chunks</span>
                  <strong>{{ highScoreCount }}</strong>
                </div>
                <div>
                  <span>{{ isEnglish ? 'Private chunks' : '私有片段' }}</span>
                  <strong>{{ privateResultCount }}</strong>
                </div>
                <div>
                  <span>Requested Top K</span>
                  <strong>{{ form.topK }}</strong>
                </div>
              </div>

              <div class="selected-result-panel">
                <div class="score-panel__title">Selected Result Inspection</div>
                <template v-if="selectedResult">
                  <strong>{{ selectedResult.filename }} · chunk {{ selectedResult.chunkIndex }}</strong>
                  <p>{{ selectedResult.chunkText }}</p>
                  <div class="keyword-chip-row">
                    <span v-for="keyword in queryKeywords" :key="keyword">{{ keyword }}</span>
                  </div>
                </template>
                <NEmpty v-else description="Select a result row to inspect it." />
              </div>
            </NSpace>
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
  NEmpty,
  NForm,
  NFormItem,
  NFormItemGi,
  NGrid,
  NInput,
  NInputNumber,
  NSpace,
  NTag,
} from 'naive-ui';
import { computed, nextTick, reactive, ref } from 'vue';
import AppLayout from '@/components/AppLayout.vue';
import WorkspaceHero from '@/components/WorkspaceHero.vue';
import { searchKnowledge, type KnowledgeSearchResult } from '@/api/knowledge';
import { ui } from '@/ui';
import { apiErrorMessage } from '@/api/errors';
import { useI18n } from '@/composables/useI18n';

const { isEnglish, locale } = useI18n();

const form = reactive({
  query: '',
  topK: 5,
});
const searching = ref(false);
const results = ref<KnowledgeSearchResult[]>([]);
const selectedIndex = ref(0);
const lastDurationMs = ref<number | null>(null);
const lastRunAt = ref<string | null>(null);
const diagnosticsVisible = ref(true);
const diagnosticsPanelRef = ref<HTMLElement | null>(null);
const searchButtonRef = ref<{ $el?: HTMLElement } | null>(null);
let diagnosticsReturnFocus: HTMLElement | null = null;

const selectedResult = computed(() => results.value[selectedIndex.value] || null);
const highScoreCount = computed(() => results.value.filter((item) => item.score >= 0.8).length);
const lowScoreCount = computed(() => results.value.filter((item) => item.score < 0.5).length);
const privateResultCount = computed(() => results.value.filter((item) => !item.isPublic).length);
const averageScore = computed(() => {
  if (results.value.length === 0) {
    return null;
  }
  return results.value.reduce((sum, item) => sum + item.score, 0) / results.value.length;
});
const recallStatusLabel = computed(() => {
  if (results.value.length === 0) {
    return isEnglish.value ? 'No run' : '尚未运行';
  }
  if (lowScoreCount.value >= Math.max(1, Math.floor(results.value.length / 3))) {
    return isEnglish.value ? 'Needs review' : '需要检查';
  }
  return isEnglish.value ? 'Good' : '良好';
});
const recallStatusType = computed(() => {
  if (results.value.length === 0) {
    return 'default';
  }
  return lowScoreCount.value >= Math.max(1, Math.floor(results.value.length / 3)) ? 'warning' : 'success';
});
const scoreBands = computed(() => {
  const bands = [
    { label: '0.80 - 1.00', min: 0.8, max: 1.01, tone: 'green' },
    { label: '0.60 - 0.80', min: 0.6, max: 0.8, tone: 'green' },
    { label: '0.40 - 0.60', min: 0.4, max: 0.6, tone: 'amber' },
    { label: '0.20 - 0.40', min: 0.2, max: 0.4, tone: 'red' },
    { label: '0.00 - 0.20', min: 0, max: 0.2, tone: 'red' },
  ];
  return bands.map((band) => {
    const count = results.value.filter((item) => item.score >= band.min && item.score < band.max).length;
    return {
      ...band,
      count,
      percent: results.value.length === 0 ? 0 : Math.round((count / results.value.length) * 100),
    };
  });
});
const queryKeywords = computed(() =>
  Array.from(new Set(form.query.toLowerCase().match(/[\p{L}\p{N}]{3,}/gu) || [])).slice(0, 8),
);

async function handleSearch() {
  if (!form.query.trim()) {
    ui.message.warning('Please enter a query.');
    return;
  }

  searching.value = true;
  const startedAt = performance.now();
  try {
    const { data } = await searchKnowledge({
      query: form.query.trim(),
      topK: form.topK,
    });
    results.value = data;
    diagnosticsVisible.value = true;
    selectedIndex.value = 0;
    lastDurationMs.value = Math.round(performance.now() - startedAt);
    lastRunAt.value = new Date().toISOString();
    if (data.length === 0) {
      ui.message.info('No retrieval results found.');
    } else {
      diagnosticsReturnFocus = searchButtonRef.value?.$el || null;
      await focusCompactDiagnostics();
    }
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, 'Search failed.'));
  } finally {
    searching.value = false;
  }
}

function toggleDiagnostics(event?: MouseEvent) {
  if (diagnosticsVisible.value) {
    closeDiagnostics();
    return;
  }
  if (event?.currentTarget instanceof HTMLElement) {
    diagnosticsReturnFocus = event.currentTarget;
  }
  diagnosticsVisible.value = true;
  void focusCompactDiagnostics();
}

function closeDiagnostics() {
  const returnFocus = diagnosticsReturnFocus;
  diagnosticsVisible.value = false;
  diagnosticsReturnFocus = null;
  void nextTick(() => returnFocus?.focus());
}

async function focusCompactDiagnostics() {
  await nextTick();
  if (window.matchMedia('(max-width: 1180px)').matches) {
    diagnosticsPanelRef.value?.focus({ preventScroll: true });
  }
}

function fillSampleQuery() {
  form.query = isEnglish.value ? 'When is the weekly lab meeting?' : '实验室每周组会时间是什么时候？';
  form.topK = 5;
}

function clearResults() {
  form.query = '';
  form.topK = 5;
  results.value = [];
  selectedIndex.value = 0;
  lastDurationMs.value = null;
  lastRunAt.value = null;
  diagnosticsVisible.value = true;
}

function scoreBandLabel(score: number) {
  if (score >= 0.8) {
    return isEnglish.value ? 'High' : '高';
  }
  if (score >= 0.6) {
    return isEnglish.value ? 'Medium' : '中';
  }
  return isEnglish.value ? 'Low' : '低';
}

function scoreBandType(score: number) {
  if (score >= 0.8) {
    return 'success';
  }
  if (score >= 0.6) {
    return 'warning';
  }
  return 'error';
}

function formatScore(score: number) {
  return Number(score).toFixed(4);
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString(locale.value);
}
</script>
