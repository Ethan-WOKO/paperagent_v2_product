<template>
  <AppLayout>
    <div
      class="chat-page research-chat-page research-chat-page--redesign"
      :class="{
        'research-chat-page--sessions-collapsed': chatSidebarCollapsed,
      }"
    >
      <button
        v-if="chatSidebarCollapsed"
        type="button"
        class="chat-rail-toggle chat-rail-toggle--sessions"
        :title="t('chat.showSessions')"
        @click="setChatSidebarCollapsed(false)"
      >
        ☰
      </button>

      <aside class="chat-sidebar" :aria-hidden="chatSidebarCollapsed">
        <NCard size="small" class="chat-panel chat-session-panel">
          <template #header>
            <div class="panel-heading">
              <span>{{ t('chat.recent') }}</span>
              <small>{{ sessions.length }} {{ t('chat.sessions') }}</small>
            </div>
          </template>
          <template #header-extra>
            <div class="chat-session-actions">
              <NButton type="primary" size="small" circle @click="handleCreateSession">+</NButton>
              <NButton secondary size="small" class="chat-panel-collapse" :title="t('chat.hideSessions')" @click="setChatSidebarCollapsed(true)">{{ t('common.hide') }}</NButton>
            </div>
          </template>


          <div class="chat-session-list-scroll">
          <NSpace vertical :size="8">
            <NEmpty v-if="sessions.length === 0" :description="t('chat.emptySessions')" size="small" />
            <div
              v-for="session in sessions"
              :key="session.id"
              role="button"
              tabindex="0"
              class="session-item"
              :class="{ 'session-item--active': selectedSessionId === session.id }"
              @click="selectSession(session.id)"
              @keydown.enter.prevent="selectSession(session.id)"
            >
              <div class="session-item__content">
                <div class="session-item__title">{{ session.title || ('会话 #' + session.id) }}</div>
                <div class="session-item__meta">最近更新 {{ formatSessionUpdatedAt(session.updatedAt || session.createdAt) }}</div>
              </div>
              <NDropdown trigger="click" :options="sessionMenuOptions" @select="(key) => handleSessionMenuSelect(key, session)">
                <NButton quaternary circle size="tiny" class="session-item__more" @click.stop>...</NButton>
              </NDropdown>
            </div>
          </NSpace>
          </div>
        </NCard>
      </aside>

      <section class="chat-main">
        <NCard
          class="chat-panel chat-workspace-panel"
          :content-style="{ display: 'grid', gridTemplateRows: 'auto minmax(0, 1fr) auto', gap: '12px', minHeight: '0' }"
        >
          <template #header>
            <div class="chat-room-title">
              <div>
                <h2>{{ activeSessionTitle }}</h2>
              </div>
            </div>
          </template>
          <template #header-extra>
            <div class="chat-toolbar">
              <NButton
                v-if="isDemoExperience"
                secondary
                round
                class="demo-question-toggle"
                :aria-expanded="demoQuestionPanelOpen"
                @click="setDemoQuestionPanelOpen(!demoQuestionPanelOpen)"
              >{{ t('chat.exampleQuestions') }}</NButton>
              <NCheckbox v-model:checked="ragDisabled">{{ t('chat.disableKnowledge') }}</NCheckbox>
              <NCheckbox v-model:checked="planMode">{{ t('chat.planMode') }}</NCheckbox>
              <NSelect
                v-model:value="selectedSkillId"
                style="width: 190px"
                clearable
                :options="skillOptions"
                :placeholder="t('chat.skillPlaceholder')"
              />
              <NCheckbox v-model:checked="showProcessMessages">{{ t('chat.process') }}</NCheckbox>
              <NButton secondary round @click="() => reloadCurrentMessages()" :disabled="!selectedSessionId">{{ t('common.refresh') }}</NButton>
            </div>
          </template>

          <div v-if="showDemoQuestions" class="demo-question-strip" role="region" :aria-label="t('chat.exampleQuestions')">
            <div class="demo-question-strip__head">
              <strong>{{ t('chat.exampleQuestions') }}</strong>
              <span>{{ t('chat.exampleHelp') }}</span>
            </div>
            <div class="demo-question-strip__grid">
              <button v-for="question in demoQuestions" :key="question" type="button" @click="useDemoQuestion(question)">
                {{ question }}
              </button>
            </div>
          </div>

          <div class="chat-thread-shell">
            <div ref="messagesContainerRef" class="chat-messages" @scroll="handleMessagesScroll">
              <div v-if="messagesLoading" class="chat-loading">{{ t('chat.loading') }}</div>
              <NEmpty v-else-if="filteredMessages.length === 0" :description="t('chat.empty')" class="chat-empty" />
              <div
                v-for="message in filteredMessages"
                :key="message.localId"
                :ref="(el) => setMessageRowRef(el, message.localId)"
                class="message-row"
                :class="'message-row--' + message.role"
              >
                <template v-if="message.role === 'process'">
                  <details
                    class="process-message-card"
                    :class="{ 'process-message-card--live': !message.processDone }"
                    :open="message.processOpen"
                  >
                    <summary class="process-message-card__summary">
                      <span class="process-message-card__label">{{ processSummaryLabel(message) }}</span>
                      <span class="process-message-card__chevron">›</span>
                    </summary>
                    <div class="process-message-card__content">
                      <template v-for="(segment, index) in getMessageSegments(message.content)" :key="message.localId + '-' + index">
                        <pre v-if="segment.type === 'code'" class="message-code-block"><code>{{ segment.content }}</code></pre>
                        <p v-else class="message-text-block">{{ segment.content }}</p>
                      </template>
                    </div>
                  </details>
                </template>

                <template v-else-if="message.role === 'system' || message.role === 'tool'">
                  <details class="process-message-card">
                    <summary class="process-message-card__summary">
                      <span>{{ message.role === 'system' ? '系统过程' : '工具输出' }}</span>
                      <span class="process-message-card__meta">展开</span>
                    </summary>
                    <div class="process-message-card__content">
                      <template v-for="(segment, index) in getMessageSegments(message.content)" :key="message.localId + '-' + index">
                        <pre v-if="segment.type === 'code'" class="message-code-block"><code>{{ segment.content }}</code></pre>
                        <p v-else class="message-text-block">{{ segment.content }}</p>
                      </template>
                    </div>
                  </details>
                </template>

                <template v-else>
                  <div class="message-avatar" :class="'message-avatar--' + message.role">{{ message.role === 'user' ? '你' : 'AI' }}</div>
                  <div class="message-bubble">
                    <div class="message-role">{{ message.role === 'user' ? '你' : 'ScholarAI' }}</div>
                    <div class="message-content">
                      <template v-if="message.role === 'assistant'">
                        <MarkdownMessage :content="message.content || '正在思考...'" />
                        <div v-if="message.artifacts?.length" class="chat-artifact-list">
                          <article v-for="artifact in message.artifacts" :key="artifact.id" class="chat-artifact-card">
                            <div class="chat-artifact-card__icon">{{ artifactIconLabel(artifact) }}</div>
                            <div class="chat-artifact-card__main">
                              <strong>{{ artifact.title || artifact.downloadFilename || ('Artifact #' + artifact.id) }}</strong>
                              <small>{{ artifact.artifactType || 'DOCUMENT' }} · {{ artifact.downloadFilename || 'downloadable file' }}</small>
                              <p v-if="artifact.preview">{{ artifact.preview }}</p>
                            </div>
                            <div class="chat-artifact-card__actions">
                              <NButton size="tiny" secondary :loading="previewingArtifactId === artifact.id" @click="previewArtifact(artifact)">预览</NButton>
                              <NButton size="tiny" type="primary" secondary :loading="downloadingArtifactId === artifact.id" @click="downloadArtifactCard(artifact)">下载</NButton>
                              <NButton size="tiny" secondary :loading="savingArtifactId === artifact.id" @click="saveArtifactCardToKnowledge(artifact)">存入知识库</NButton>
                            </div>
                          </article>
                        </div>
                      </template>
                      <template v-else>
                        <template v-for="(segment, index) in getMessageSegments(message.content || '...')" :key="message.localId + '-' + index">
                          <pre v-if="segment.type === 'code'" class="message-code-block"><code>{{ segment.content }}</code></pre>
                          <p v-else class="message-text-block">{{ segment.content }}</p>
                        </template>
                      </template>
                    </div>
                    <NButton
                      v-if="message.navigationUrl"
                      text
                      type="primary"
                      class="message-link-button"
                      @click="goToNavigation(message.navigationUrl)"
                    >
                      打开论文修改页
                    </NButton>
                  </div>
                </template>
              </div>
            </div>
            <div
              v-if="minimapMessages.length > 0"
              ref="chatMinimapRailRef"
              class="chat-minimap-rail"
              @mouseenter="cancelMinimapHoverClear"
              @mouseleave="handleMinimapRailLeave"
            >
              <div ref="chatMinimapRef" class="chat-minimap" aria-label="当前会话导航条" @scroll="syncMinimapPreviewPosition">
                <button
                  v-for="(message, index) in minimapMessages"
                  :key="'minimap-' + message.localId"
                  :ref="(el) => setMinimapItemRef(el, message.localId)"
                  type="button"
                  class="chat-minimap__item"
                  :class="[
                    'chat-minimap__item--' + message.role,
                    minimapWaveClass(index),
                    { 'chat-minimap__item--active': activeMinimapMessageId === message.localId },
                  ]"
                  @mouseenter="setHoveredMinimapIndex(index)"
                  @focus="setHoveredMinimapIndex(index)"
                  @blur="scheduleMinimapHoverClear"
                  @click="scrollToMessage(message.localId)"
                />
              </div>
              <div
                v-if="hoveredMinimapMessage"
                ref="chatMinimapPreviewRef"
                class="chat-minimap__preview"
                :style="minimapPreviewStyle"
                @mouseenter="keepHoveredMinimapIndex"
                @mouseleave="scheduleMinimapHoverClear"
              >
                <div
                  v-if="hoveredMinimapPreview.user"
                  class="chat-minimap__preview-line chat-minimap__preview-line--user"
                >
                  {{ hoveredMinimapPreview.user }}
                </div>
                <div
                  v-if="hoveredMinimapPreview.assistant"
                  class="chat-minimap__preview-line chat-minimap__preview-line--assistant"
                >
                  {{ hoveredMinimapPreview.assistant }}
                </div>
              </div>
            </div>
          </div>

          <div class="chat-composer" :class="{ 'chat-composer--has-attachments': chatAttachments.length || chatUploading }">
            <div class="chat-composer__topline">
              <div class="chat-composer__model-picker">
                <NSelect
                  v-model:value="selectedModelKey"
                  size="small"
                  class="chat-model-select"
                  :options="modelOptions"
                  placeholder="选择模型"
                  @update:value="handleModelChange"
                />
              </div>
              <div class="chat-composer__quick-actions">
                <span
                  class="v2-availability-indicator"
                  :data-status="v2LiteratureAvailable ? 'available' : 'unavailable'"
                >{{ v2LiteratureAvailabilityLabel }}</span>
                <button
                  type="button"
                  :aria-expanded="literatureFormOpen"
                  :disabled="!v2LiteratureAvailable"
                  @click="openLiteratureForm"
                >{{ t('chat.literature.open') }}</button>
                <button type="button" @click="draft = '帮我润色论文'">Polish Paper</button>
                <button type="button" @click="planMode = !planMode">{{ planMode ? 'ReAct Mode' : 'Plan Mode' }}</button>
                <button type="button" @click="showProcessMessages = !showProcessMessages">Tool Trace</button>
              </div>
            </div>
            <section v-if="literatureFormOpen" class="v2-literature-panel" :aria-label="t('chat.literature.title')">
              <div class="v2-literature-panel__heading">
                <div>
                  <strong>{{ t('chat.literature.title') }}</strong>
                  <small>{{ t('chat.literature.help') }}</small>
                </div>
                <button type="button" class="v2-literature-panel__close" @click="literatureFormOpen = false">×</button>
              </div>
              <div class="v2-literature-form">
                <label class="v2-literature-form__query">
                  <span>{{ t('chat.literature.query') }}</span>
                  <NInput v-model:value="literatureForm.query" :placeholder="t('chat.literature.queryPlaceholder')" />
                </label>
                <label>
                  <span>{{ t('chat.literature.topK') }}</span>
                  <input v-model.number="literatureForm.topK" type="number" min="1" max="20" />
                </label>
                <label>
                  <span>{{ t('chat.literature.yearFrom') }}</span>
                  <input v-model.number="literatureForm.yearFrom" type="number" min="1900" :max="new Date().getFullYear() + 1" :placeholder="t('common.optional')" />
                </label>
                <NCheckbox v-model:checked="literatureForm.includeBibtex">{{ t('chat.literature.includeBibtex') }}</NCheckbox>
              </div>
              <div class="v2-literature-panel__actions">
                <NButton
                  type="primary"
                  size="small"
                  :loading="literatureStarting"
                  :disabled="!v2LiteratureAvailable || literaturePolling || literatureStarting"
                  @click="startLiteratureSearch"
                >{{ t('chat.literature.start') }}</NButton>
                <NButton
                  v-if="literatureOutcome?.cancellable"
                  size="small"
                  secondary
                  :loading="literatureCancelling"
                  @click="cancelLiteratureSearch"
                >{{ t('chat.literature.cancel') }}</NButton>
              </div>
              <div
                v-if="literatureOutcome"
                class="v2-literature-outcome"
                :class="'v2-literature-outcome--' + literaturePresentation?.tone"
              >
                <div class="v2-literature-outcome__status">
                  <strong>{{ literatureStatusLabel }}</strong>
                  <span>{{ literaturePresentation?.stage }}</span>
                  <small v-if="literaturePolling">{{ t('chat.literature.polling') }}</small>
                </div>
                <p v-if="literatureOutcome.sourceFailures.length" class="v2-literature-outcome__warning">
                  {{ t('chat.literature.sourceWarnings') }}: {{ literatureOutcome.sourceFailures.join('; ') }}
                </p>
                <div v-if="literaturePresentation?.papers.length" class="v2-literature-results">
                  <article v-for="paper in literaturePresentation.papers" :key="paper.cardId ?? paper.doi ?? paper.title" class="v2-literature-card">
                    <div class="v2-literature-card__heading">
                      <strong>{{ paper.title }}</strong>
                      <span v-if="paper.score != null">{{ paper.score.toFixed(3) }}</span>
                    </div>
                    <p v-if="paper.authors.length">{{ paper.authors.join(', ') }}</p>
                    <small>{{ [paper.year, paper.venue, paper.source].filter(Boolean).join(' · ') }}</small>
                    <div class="v2-literature-card__identifiers">
                      <span v-if="paper.doi">DOI: {{ paper.doi }}</span>
                      <span v-if="paper.arxivId">arXiv: {{ paper.arxivId }}</span>
                      <span v-if="paper.openAlexId">OpenAlex: {{ paper.openAlexId }}</span>
                      <a v-if="paper.safeUrl" :href="paper.safeUrl" target="_blank" rel="noopener noreferrer">{{ t('chat.literature.openSource') }}</a>
                    </div>
                    <details v-if="paper.bibtex" class="v2-literature-bibtex">
                      <summary>{{ t('chat.literature.bibtex') }}</summary>
                      <pre>{{ paper.bibtex }}</pre>
                    </details>
                  </article>
                </div>
              </div>
              <p v-if="literatureError" class="v2-literature-panel__error">{{ literatureError }}</p>
            </section>
            <NInput
              v-model:value="draft"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 6 }"
              :placeholder="t('chat.inputPlaceholder')"
              @keydown.enter.exact.prevent="handleSend"
            />
            <input
              ref="chatFileInputRef"
              type="file"
              class="chat-file-input"
              multiple
              accept=".pdf,.doc,.docx,.txt,.md,.tex,.bib,.csv,.json"
              @change="handleChatFileChange"
            />
            <div v-if="chatAttachments.length || chatUploading" class="chat-attachment-tray">
              <div v-for="attachment in chatAttachments" :key="attachment.documentId" class="chat-attachment-chip">
                <span>{{ attachment.filename }}</span>
                <small>#{{ attachment.documentId }} · {{ attachment.status }}</small>
                <button type="button" :disabled="sending || chatUploading" @click="removeChatAttachment(attachment.documentId)">×</button>
              </div>
              <div v-if="chatUploading" class="chat-attachment-chip chat-attachment-chip--uploading">
                <span>{{ chatUploadStatus || 'Uploading document...' }}</span>
                <small>{{ chatUploadProgress }}%</small>
              </div>
            </div>
            <div class="chat-composer__footer">
              <span class="chat-hint">{{ t('chat.inputHint') }}</span>
              <div class="chat-composer__send-actions">
                <button
                  type="button"
                  class="chat-upload-button"
                  :disabled="sending || chatUploading"
                  :aria-label="t('chat.upload')"
                  @click="chatFileInputRef?.click()"
                >{{ t('chat.upload') }}</button>
                <NButton
                  type="primary"
                  round
                  class="chat-send-button"
                  :class="{ 'chat-send-button--busy': sending }"
                  :disabled="chatUploading || sending"
                  @click="handleSend"
                >{{ t('chat.send') }}</NButton>
              </div>
            </div>
          </div>
        </NCard>
      </section>

    </div>

    <NModal v-model:show="renameModalVisible" preset="card" :title="t('chat.renameTitle')" style="width: min(420px, 92vw)" :bordered="false">
      <NSpace vertical :size="14">
        <NInput
          v-model:value="renameDraft"
          maxlength="40"
          show-count
          :placeholder="t('chat.renamePlaceholder')"
          @keydown.enter.prevent="confirmRenameSession"
        />
        <NSpace justify="end">
          <NButton secondary @click="renameModalVisible = false">{{ t('common.cancel') }}</NButton>
          <NButton type="primary" :loading="renaming" @click="confirmRenameSession">{{ t('common.save') }}</NButton>
        </NSpace>
      </NSpace>
    </NModal>
    <NModal v-model:show="artifactPreviewVisible" preset="card" :title="artifactPreviewTitle" style="width: min(920px, 92vw)" :bordered="false">
      <div class="chat-artifact-preview">
        <MarkdownMessage v-if="artifactPreviewType === 'MARKDOWN'" :content="artifactPreviewContent" />
        <pre v-else>{{ artifactPreviewContent }}</pre>
      </div>
    </NModal>
  </AppLayout>
</template>

<script setup lang="ts">
import { NButton, NCard, NCheckbox, NDropdown, NEmpty, NInput, NModal, NSelect, NSpace } from 'naive-ui';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppLayout from '@/components/AppLayout.vue';
import MarkdownMessage from '@/components/MarkdownMessage.vue';
import { downloadArtifact, getArtifact, saveArtifactToKnowledge } from '@/api/artifact';
import { getDemoConfig } from '@/api/demo';
import { mergeKbUpload, uploadChunk, type KbDocumentResponse } from '@/api/knowledge';
import {
  cancelV2LiteratureTurn,
  createPlan,
  createSession,
  deleteSession as deleteAgentSession,
  executePlanAsync,
  getV2ProductAvailability,
  getV2LiteratureTurn,
  getPlan,
  listMessages,
  listSessions,
  sendMessage as sendAgentMessage,
  startV2LiteratureTurn,
  updateSession as updateAgentSession,
  type AgentDebugPayload,
  type AgentMessageResponse,
  type AgentPlanResponse,
  type AgentPlanStepResponse,
  type SendMessageResponse,
  type AgentSessionResponse,
  type V2LiteratureTurnOutcomeResponse,
} from '@/api/agent';
import { listSkills, type SkillListItemResponse } from '@/api/skills';
import { getSettings, type UserSettingsResponse } from '@/api/settings';
import { useAuthStore } from '@/stores/auth';
import { useI18n } from '@/composables/useI18n';
import { projectPlanExecutionOutcome, projectPlanFinalAnswer, projectPlanLifecycle } from '@/utils/projectCompletion';
import {
  isCurrentV2LiteratureRequest,
  newV2LiteratureClientRequestId,
  normalizeV2LiteratureForm,
  pollV2Literature,
  presentV2LiteratureOutcome,
} from '@/utils/v2Literature';
import {
  V2_PRODUCT_AVAILABILITY_LOADING,
  isV2CapabilityAvailable,
  loadV2ProductAvailability,
  v2AvailabilityLabel,
  type V2ProductAvailabilityState,
} from '@/utils/v2ProductAvailability';
import { ui } from '@/ui';

type MessageRole = 'user' | 'assistant' | 'system' | 'tool' | 'process';

interface ChatMessageView {
  localId: string;
  role: MessageRole;
  content: string;
  createdAt?: string | null;
  toolCallsJson?: string | null;
  navigationUrl?: string | null;
  artifacts?: ChatArtifactCard[];
  processOpen?: boolean;
  processDone?: boolean;
  processStartedAt?: number | null;
  processElapsedMs?: number | null;
}

interface ChatArtifactCard {
  id: number;
  title: string;
  artifactType: string;
  downloadUrl: string;
  downloadFilename: string;
  downloadContentType: string;
  preview?: string | null;
}

interface ChatUploadAttachment {
  documentId: number;
  filename: string;
  status: string;
}

interface ToolCallSnapshot {
  id?: string | null;
  function?: {
    name?: string | null;
    arguments?: string | null;
  } | null;
}

interface WsChatEvent {
  type: 'ack' | 'process' | 'chunk' | 'done' | 'error' | 'debug';
  content: string | null;
  sessionId: number | null;
  error: string | null;
  finishReason: string | null;
  navigationUrl: string | null;
  clientRequestId: string | null;
  debug: AgentDebugPayload | null;
}

interface MessageSegment {
  type: 'text' | 'code';
  content: string;
}

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const { isEnglish, t } = useI18n();
const uiText = (chinese: string, english: string) => (isEnglish.value ? english : chinese);
const DEFAULT_DEMO_QUESTIONS_ZH = [
  '根据知识库，概括这个项目能解决什么问题？',
  '演示文档里的组会时间、地点和下次 DDL 是什么？',
  '这个项目的 RAG 流程包含哪些步骤？',
  '用计划模式帮我把两周内完善 Agent 能力拆成任务。',
];
const DEFAULT_DEMO_QUESTIONS_EN = [
  'Based on the knowledge base, what problems does this project solve?',
  'What are the next meeting time, location, and deadline in the demo documents?',
  'Which steps are included in this project\'s RAG workflow?',
  'Use Plan Mode to break down two weeks of Agent improvements into tasks.',
];
const sessions = ref<AgentSessionResponse[]>([]);
const selectedSessionId = ref<number | null>(null);
const messages = ref<ChatMessageView[]>([]);
const messagesBySessionId = ref<Record<number, ChatMessageView[]>>({});
const messagesLoading = ref(false);
const draft = ref('');
const sending = ref(false);
const selectedSkillId = ref<string | null>(null);
const availableSkills = ref<SkillListItemResponse[]>([]);
const settings = ref<UserSettingsResponse | null>(null);
const configuredDemoQuestions = ref(DEFAULT_DEMO_QUESTIONS_ZH);
const demoQuestions = computed(() => isEnglish.value ? DEFAULT_DEMO_QUESTIONS_EN : configuredDemoQuestions.value);
const demoQuestionPanelOpen = ref(true);
const demoQuestionPanelState = ref<Record<number, boolean>>({});
const pendingDemoQuestion = ref<string | null>(null);
const selectedModelKey = ref('');
const ragDisabled = ref(false);
const planMode = ref(false);
const showProcessMessages = ref(false);
const currentPlan = ref<AgentPlanResponse | null>(null);
const currentSocket = ref<WebSocket | null>(null);
const currentAssistantMessageId = ref<string | null>(null);
const currentAssistantMessageSessionId = ref<number | null>(null);
const currentProcessMessageId = ref<string | null>(null);
const currentProcessMessageSessionId = ref<number | null>(null);
const messagesContainerRef = ref<HTMLElement | null>(null);
const chatFileInputRef = ref<HTMLInputElement | null>(null);
const chatMinimapRailRef = ref<HTMLElement | null>(null);
const chatMinimapRef = ref<HTMLElement | null>(null);
const chatMinimapPreviewRef = ref<HTMLElement | null>(null);
const messageRowRefs: Record<string, HTMLElement | null> = {};
const minimapItemRefs: Record<string, HTMLElement | null> = {};
const activeMinimapMessageId = ref<string | null>(null);
const hoveredMinimapIndex = ref<number | null>(null);
const pinnedMinimapIndex = ref<number | null>(null);
const minimapViewportTop = ref(0);
const minimapViewportHeight = ref(0.18);
const minimapPreviewTopPx = ref(8);
const sessionScrollPositions = ref<Record<number, number>>({});
const renameModalVisible = ref(false);
const renameSessionId = ref<number | null>(null);
const renameDraft = ref('');
const renaming = ref(false);
const artifactPreviewVisible = ref(false);
const artifactPreviewTitle = ref('');
const artifactPreviewType = ref('MARKDOWN');
const artifactPreviewContent = ref('');
const previewingArtifactId = ref<number | null>(null);
const downloadingArtifactId = ref<number | null>(null);
const savingArtifactId = ref<number | null>(null);
const chatAttachments = ref<ChatUploadAttachment[]>([]);
const chatUploading = ref(false);
const chatUploadProgress = ref(0);
const chatUploadStatus = ref('');
const literatureFormOpen = ref(false);
const literatureForm = ref({
  query: '',
  topK: 5 as number | null,
  yearFrom: null as number | null,
  includeBibtex: false,
});
const literatureOutcome = ref<V2LiteratureTurnOutcomeResponse | null>(null);
const literatureStarting = ref(false);
const literaturePolling = ref(false);
const literatureCancelling = ref(false);
const literatureError = ref('');
const literatureClientRequestId = ref<string | null>(null);
const v2Availability = ref<V2ProductAvailabilityState>(V2_PRODUCT_AVAILABILITY_LOADING);
const literaturePollTimers = new Set<number>();
let literaturePollController: AbortController | null = null;
let literatureRecoverySequence = 0;
let literatureStartSequence = 0;
const CHAT_SIDEBAR_COLLAPSED_KEY = 'yanban.chat.sessionsCollapsed';
const SESSION_STORAGE_KEY = 'yanban.chat.selectedSessionId';
const LITERATURE_REQUEST_STORAGE_PREFIX = 'yanban.chat.v2Literature.';
const chatSidebarCollapsed = ref(readStoredBoolean(CHAT_SIDEBAR_COLLAPSED_KEY, false));
const DEFAULT_DEEPSEEK_MODEL = 'deepseek-v4-flash';
const SUPPORTED_DEEPSEEK_MODELS = new Set(['deepseek-v4-flash', 'deepseek-v4-pro']);
const DEFAULT_GLM_MODEL = 'glm-5.2';
const WS_ACK_TIMEOUT_MS = 4000;
const WS_MAX_RETRIES = 3;
const CHAT_UPLOAD_CHUNK_SIZE = 2 * 1024 * 1024;
let minimapHoverClearTimer: number | null = null;
let minimapActiveLockUntil = 0;
let messagesRequestSeq = 0;

const sessionMenuOptions = computed(() => [
  { label: t('chat.rename'), key: 'rename' },
  { label: t('chat.delete'), key: 'delete' },
]);

const skillOptions = computed(() => availableSkills.value
  .filter((skill) => skill.enabled)
  .map((skill) => ({ label: skill.name + (skill.source === 'builtin' ? uiText('（内置）', ' (built-in)') : ''), value: skill.id })));

const modelOptions = computed(() => {
  const options: { label: string; value: string }[] = [];
  const deepseekModels = (settings.value?.deepseekModels || [])
    .filter(isSupportedDeepseekModel);
  for (const m of deepseekModels) {
    options.push({ label: 'DeepSeek / ' + m, value: toModelKey('deepseek', m) });
  }
  if (!deepseekModels.length) {
    options.push({ label: 'DeepSeek / ' + DEFAULT_DEEPSEEK_MODEL, value: toModelKey('deepseek', DEFAULT_DEEPSEEK_MODEL) });
  }
  const glmModels = settings.value?.glmModels?.length
    ? settings.value.glmModels
    : [DEFAULT_GLM_MODEL];
  for (const m of glmModels) {
    options.push({ label: 'GLM / ' + m, value: toModelKey('glm', m) });
  }
  const customModels = settings.value?.customModels || [];
  for (const cm of customModels) {
    if (!cm.builtin || cm.providerKey.startsWith('openrouter-')) {
      options.push({ label: cm.label + ' / ' + cm.modelName, value: toModelKey(cm.providerKey, cm.modelName) });
    }
  }
  if (selectedModelKey.value && !options.some((option) => option.value === selectedModelKey.value)) {
    const selected = parseModelKey(selectedModelKey.value);
    if (selected.provider !== 'deepseek' || isSupportedDeepseekModel(selected.model)) {
      options.push({ label: formatProviderName(selected.provider) + ' / ' + selected.model, value: selectedModelKey.value });
    }
  }
  return options;
});

const activeSessionTitle = computed(() => {
  const active = sessions.value.find((item) => item.id === selectedSessionId.value);
  return active?.title || t('chat.defaultTitle');
});

const filteredMessages = computed(() => {
  const visibleMessages = messages.value.filter((message) => !isAssistantToolCallMessage(message));
  if (showProcessMessages.value) {
    return visibleMessages;
  }
  return visibleMessages.filter((message) => message.role === 'user'
    || message.role === 'assistant'
    || message.role === 'process');
});

const minimapMessages = computed(() => filteredMessages.value.filter((message) => message.role === 'user'));

const minimapViewportStyle = computed(() => ({
  top: (minimapViewportTop.value * 100).toFixed(2) + '%',
  height: Math.max(minimapViewportHeight.value * 100, 8).toFixed(2) + '%',
}));

const hoveredMinimapMessage = computed(() => {
  const index = hoveredMinimapIndex.value;
  if (index == null) {
    return null;
  }
  return minimapMessages.value[index] || null;
});

const hoveredMinimapPreview = computed(() => buildMinimapPreview(hoveredMinimapMessage.value));

const minimapPreviewStyle = computed(() => {
  return {
    top: minimapPreviewTopPx.value.toFixed(2) + 'px',
  };
});

const isDemoExperience = computed(() => route.query.demo === '1' || Boolean(authStore.currentUser?.demo));
const showDemoQuestions = computed(() => isDemoExperience.value && !sending.value && demoQuestionPanelOpen.value);
const v2LiteratureAvailable = computed(() => (
  isV2CapabilityAvailable(v2Availability.value, 'literature.search')
));
const v2LiteratureAvailabilityLabel = computed(() => (
  v2AvailabilityLabel(v2Availability.value, 'literature.search')
));
const literaturePresentation = computed(() => (
  literatureOutcome.value ? presentV2LiteratureOutcome(literatureOutcome.value) : null
));
const literatureStatusLabel = computed(() => {
  const status = literatureOutcome.value?.status;
  if (!status) return '';
  const keys = {
    PENDING: 'chat.literature.status.pending',
    RUNNING: 'chat.literature.status.running',
    CANCEL_REQUESTED: 'chat.literature.status.cancelRequested',
    CANCELLING: 'chat.literature.status.cancelling',
    COMPLETED: 'chat.literature.status.completed',
    PARTIAL: 'chat.literature.status.partial',
    FAILED: 'chat.literature.status.failed',
    CANCELLED: 'chat.literature.status.cancelled',
  } as const;
  return t(keys[status]);
});

onMounted(async () => {
  applyMobileChatDefaults();
  void loadProductV2Availability();
  await Promise.all([loadSettings(), loadSkills()]);
  await loadDemoConfigIfNeeded();
  await loadSessions();
  applyQuestionFromRoute();
  syncMinimapViewport();
});

watch(
  () => route.query.sessionId,
  async (value) => {
    const sessionId = parseSessionId(value);
    if (!sessionId || sessionId === selectedSessionId.value) {
      return;
    }
    if (sessions.value.some((item) => item.id === sessionId)) {
      await selectSession(sessionId);
    }
  }
);

watch(
  () => filteredMessages.value.map((message) => message.localId).join('|'),
  async () => {
    await nextTick();
    syncMinimapViewport();
    syncMinimapPreviewPosition();
  }
);

watch(
  () => hoveredMinimapIndex.value,
  async () => {
    await nextTick();
    syncMinimapPreviewPosition();
  }
);

watch(selectedSessionId, (sessionId, previousSessionId) => {
  if (sessionId === previousSessionId) return;
  literatureStartSequence += 1;
  stopLiteraturePolling();
  literatureOutcome.value = null;
  literatureError.value = '';
  literatureClientRequestId.value = null;
  if (sessionId) {
    void recoverLiteratureSearch(sessionId);
  }
});

onBeforeUnmount(() => {
  literatureStartSequence += 1;
  currentSocket.value?.close();
  stopLiteraturePolling();
  cancelMinimapHoverClear();
  Object.keys(messageRowRefs).forEach((key) => {
    delete messageRowRefs[key];
  });
  Object.keys(minimapItemRefs).forEach((key) => {
    delete minimapItemRefs[key];
  });
});

function openLiteratureForm() {
  if (!v2LiteratureAvailable.value) return;
  literatureFormOpen.value = true;
  if (literaturePresentation.value?.terminal) {
    literatureOutcome.value = null;
    literatureClientRequestId.value = null;
    literatureError.value = '';
  }
}

async function ensureLiteratureSession() {
  if (selectedSessionId.value) {
    return selectedSessionId.value;
  }
  const selectedModel = parseModelKey(selectedModelKey.value || defaultModelKeyFromSettings(settings.value));
  const { data } = await createSession({
    title: t('chat.newSession'),
    ragDisabled: ragDisabled.value,
    modelProvider: selectedModel.provider,
    model: selectedModel.model,
  });
  sessions.value = [data, ...sessions.value];
  applySelectedSession(data.id);
  await nextTick();
  return data.id;
}

async function startLiteratureSearch() {
  if (!v2LiteratureAvailable.value) {
    literatureError.value = v2LiteratureAvailabilityLabel.value;
    return;
  }
  if (literatureStarting.value || literaturePolling.value) return;
  literatureError.value = '';
  if (literaturePresentation.value?.terminal) {
    literatureOutcome.value = null;
    literatureClientRequestId.value = null;
  }
  let request;
  try {
    const stableRequestId = literatureClientRequestId.value ?? newV2LiteratureClientRequestId();
    request = normalizeV2LiteratureForm(literatureForm.value, stableRequestId);
    literatureClientRequestId.value = stableRequestId;
  } catch (error) {
    literatureError.value = literatureValidationMessage(error);
    return;
  }
  literatureStarting.value = true;
  try {
    const sessionId = await ensureLiteratureSession();
    const startSequence = ++literatureStartSequence;
    literatureClientRequestId.value = request.clientRequestId;
    await startV2LiteratureTurn(sessionId, request);
    if (!isCurrentV2LiteratureRequest(
      {
        sessionId,
        clientRequestId: request.clientRequestId,
        sequence: startSequence,
      },
      {
        sessionId: selectedSessionId.value,
        clientRequestId: literatureClientRequestId.value,
        sequence: literatureStartSequence,
      },
    )) return;
    storeLiteratureRequestId(sessionId, request.clientRequestId);
    await runLiteraturePolling(sessionId, request.clientRequestId);
  } catch (error) {
    if (!isAbortError(error)) {
      literatureError.value = t('chat.literature.failure');
    }
  } finally {
    literatureStarting.value = false;
  }
}

async function runLiteraturePolling(sessionId: number, clientRequestId: string) {
  stopLiteraturePolling();
  const controller = new AbortController();
  literaturePollController = controller;
  literaturePolling.value = true;
  try {
    const terminal = await pollV2Literature(
      async () => (await getV2LiteratureTurn(sessionId, clientRequestId)).data,
      {
        signal: controller.signal,
        sleep: (milliseconds) => literaturePollSleep(milliseconds, controller.signal),
        onOutcome: (outcome) => {
          if (selectedSessionId.value === sessionId && literatureClientRequestId.value === clientRequestId) {
            literatureOutcome.value = outcome;
          }
        },
      },
    );
    if (selectedSessionId.value === sessionId && terminal.resultMessageId != null) {
      await reloadCurrentMessages(sessionId);
    }
  } catch (error) {
    if (!isAbortError(error)) {
      literatureError.value = error instanceof Error && error.message === 'literature-poll-timeout'
        ? t('chat.literature.timeout')
        : t('chat.literature.failure');
    }
  } finally {
    if (literaturePollController === controller) {
      literaturePollController = null;
      literaturePolling.value = false;
    }
  }
}

async function recoverLiteratureSearch(sessionId: number) {
  if (!v2LiteratureAvailable.value) return;
  const recoverySequence = ++literatureRecoverySequence;
  const clientRequestId = readLiteratureRequestId(sessionId);
  if (!clientRequestId) return;
  literatureClientRequestId.value = clientRequestId;
  literatureFormOpen.value = true;
  try {
    const { data } = await getV2LiteratureTurn(sessionId, clientRequestId);
    if (recoverySequence !== literatureRecoverySequence || selectedSessionId.value !== sessionId) return;
    literatureOutcome.value = data;
    if (!data.terminal) {
      await runLiteraturePolling(sessionId, clientRequestId);
    }
  } catch (error) {
    if (!isAbortError(error) && recoverySequence === literatureRecoverySequence) {
      clearLiteratureRequestId(sessionId);
      literatureClientRequestId.value = null;
    }
  }
}

async function cancelLiteratureSearch() {
  if (!v2LiteratureAvailable.value) {
    literatureError.value = v2LiteratureAvailabilityLabel.value;
    return;
  }
  const sessionId = selectedSessionId.value;
  const clientRequestId = literatureClientRequestId.value;
  if (!sessionId || !clientRequestId || literatureCancelling.value) return;
  literatureCancelling.value = true;
  literatureError.value = '';
  try {
    const { data } = await cancelV2LiteratureTurn(sessionId, clientRequestId);
    literatureOutcome.value = data;
    if (!data.terminal && !literaturePolling.value) {
      await runLiteraturePolling(sessionId, clientRequestId);
    }
  } catch {
    literatureError.value = t('chat.literature.cancelFailure');
  } finally {
    literatureCancelling.value = false;
  }
}

async function loadProductV2Availability() {
  v2Availability.value = await loadV2ProductAvailability(
    async () => (await getV2ProductAvailability()).data,
  );
  if (v2LiteratureAvailable.value) {
    const sessionId = selectedSessionId.value;
    if (sessionId && !literatureStarting.value && !literaturePolling.value) {
      void recoverLiteratureSearch(sessionId);
    }
    return;
  }
  literatureStartSequence += 1;
  stopLiteraturePolling();
  literatureStarting.value = false;
  literatureCancelling.value = false;
  literatureFormOpen.value = false;
}

function literaturePollSleep(milliseconds: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const timer = window.setTimeout(() => {
      literaturePollTimers.delete(timer);
      signal.removeEventListener('abort', abort);
      resolve();
    }, milliseconds);
    const abort = () => {
      window.clearTimeout(timer);
      literaturePollTimers.delete(timer);
      reject(new DOMException('Polling aborted', 'AbortError'));
    };
    literaturePollTimers.add(timer);
    signal.addEventListener('abort', abort, { once: true });
  });
}

function stopLiteraturePolling() {
  literatureRecoverySequence += 1;
  literaturePollController?.abort();
  literaturePollController = null;
  literaturePollTimers.forEach((timer) => window.clearTimeout(timer));
  literaturePollTimers.clear();
  literaturePolling.value = false;
}

function literatureValidationMessage(error: unknown) {
  if (!(error instanceof Error)) return t('chat.literature.validation');
  if (error.message === 'query-required') return t('chat.literature.queryRequired');
  if (error.message === 'top-k-out-of-range') return t('chat.literature.topKInvalid');
  if (error.message === 'year-out-of-range') return t('chat.literature.yearInvalid');
  return t('chat.literature.validation');
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError';
}

function storeLiteratureRequestId(sessionId: number, clientRequestId: string) {
  window.localStorage.setItem(LITERATURE_REQUEST_STORAGE_PREFIX + sessionId, clientRequestId);
}

function readLiteratureRequestId(sessionId: number) {
  return window.localStorage.getItem(LITERATURE_REQUEST_STORAGE_PREFIX + sessionId);
}

function clearLiteratureRequestId(sessionId: number) {
  window.localStorage.removeItem(LITERATURE_REQUEST_STORAGE_PREFIX + sessionId);
}

async function loadDemoConfigIfNeeded() {
  if (!isDemoExperience.value) {
    return;
  }
  try {
    const { data } = await getDemoConfig();
    if (data.exampleQuestions?.length) {
      configuredDemoQuestions.value = data.exampleQuestions;
    }
  } catch {
    configuredDemoQuestions.value = DEFAULT_DEMO_QUESTIONS_ZH;
  }
}

function applyQuestionFromRoute() {
  const queryQuestion = route.query.q;
  if (typeof queryQuestion === 'string' && queryQuestion.trim()) {
    useDemoQuestion(queryQuestion);
  }
}

function useDemoQuestion(question: string) {
  draft.value = question;
  pendingDemoQuestion.value = question;
  if (question.includes('计划模式') || question.includes('拆成任务') || question.includes('Plan Mode')) {
    planMode.value = true;
  }
}

function setDemoQuestionPanelOpen(open: boolean) {
  demoQuestionPanelOpen.value = open;
  if (selectedSessionId.value) {
    demoQuestionPanelState.value = {
      ...demoQuestionPanelState.value,
      [selectedSessionId.value]: open,
    };
  }
}

function applyMobileChatDefaults() {
  if (typeof window !== 'undefined' && window.matchMedia('(max-width: 980px)').matches) {
    setChatSidebarCollapsed(true);
  }
}

async function loadSettings() {
  try {
    const { data } = await getSettings();
    settings.value = data;
    if (!selectedModelKey.value) {
      selectedModelKey.value = defaultModelKeyFromSettings(data);
    }
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '加载模型设置失败');
    if (!selectedModelKey.value) {
      selectedModelKey.value = toModelKey('deepseek', DEFAULT_DEEPSEEK_MODEL);
    }
  }
}

async function loadSkills() {
  try {
    const { data } = await listSkills();
    availableSkills.value = data;
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '加载 Skills 失败');
  }
}

async function loadSessions(selectLatest = true) {
  const { data } = await listSessions();
  sessions.value = data;
  if (!selectLatest) {
    return;
  }
  if (data.length === 0) {
    clearSessionSelection();
    return;
  }
  const preferredSessionId = resolvePreferredSessionId(data);
  if (preferredSessionId) {
    await selectSession(preferredSessionId);
  }
}

async function selectSession(sessionId: number) {
  if (!sessions.value.some((item) => item.id === sessionId)) {
    return;
  }
  saveCurrentScrollPosition(selectedSessionId.value);
  applySelectedSession(sessionId);
  currentPlan.value = null;
  messages.value = messagesBySessionId.value[sessionId] || [];
  const session = sessions.value.find((item) => item.id === sessionId);
  ragDisabled.value = Boolean(session?.ragDisabled);
  if (session?.modelProvider && session?.model) {
    selectedModelKey.value = toModelKey(session.modelProvider, normalizeModelName(session.modelProvider, session.model));
  }
  await reloadCurrentMessages(sessionId);
  const storedPanelState = demoQuestionPanelState.value[sessionId];
  demoQuestionPanelOpen.value = storedPanelState ?? !messages.value.some((message) => message.role === 'user');
  pendingDemoQuestion.value = null;
  await restoreScrollPosition(sessionId);
  syncMinimapViewport();
}

async function reloadCurrentMessages(sessionId = selectedSessionId.value) {
  if (!sessionId) {
    messages.value = [];
    syncMinimapViewport();
    return;
  }
  const requestSeq = ++messagesRequestSeq;
  messagesLoading.value = selectedSessionId.value === sessionId && !messagesBySessionId.value[sessionId]?.length;
  try {
    const { data } = await listMessages(sessionId, { limit: 50, view: 'all' });
    const nextMessages = buildViewMessages(data);
    pruneMessageRowRefs(nextMessages);
    setSessionMessages(sessionId, nextMessages);
  } finally {
    if (selectedSessionId.value === sessionId && requestSeq === messagesRequestSeq) {
      messagesLoading.value = false;
    }
    syncMinimapViewport();
  }
}

function setSessionMessages(sessionId: number, nextMessages: ChatMessageView[]) {
  messagesBySessionId.value = {
    ...messagesBySessionId.value,
    [sessionId]: nextMessages,
  };
  if (selectedSessionId.value === sessionId) {
    messages.value = nextMessages;
  }
}

function appendSessionMessage(sessionId: number, message: ChatMessageView) {
  const nextMessages = [...(messagesBySessionId.value[sessionId] || []), message];
  setSessionMessages(sessionId, nextMessages);
}

function updateSessionMessage(sessionId: number, localId: string | null, updater: (message: ChatMessageView) => void) {
  if (!localId) {
    return;
  }
  const currentMessages = messagesBySessionId.value[sessionId] || [];
  const nextMessages = currentMessages.map((message) => {
    if (message.localId !== localId) {
      return message;
    }
    const copy = { ...message };
    updater(copy);
    return copy;
  });
  setSessionMessages(sessionId, nextMessages);
}

function removeSessionMessage(sessionId: number | null, localId: string | null) {
  if (!sessionId || !localId) {
    return;
  }
  const nextMessages = (messagesBySessionId.value[sessionId] || []).filter((message) => message.localId !== localId);
  setSessionMessages(sessionId, nextMessages);
}

function findSessionMessage(sessionId: number | null, localId: string | null) {
  if (!sessionId || !localId) {
    return null;
  }
  return (messagesBySessionId.value[sessionId] || []).find((message) => message.localId === localId) || null;
}

function insertProcessMessageAfterLatestUser(sessionId: number, processMessage: ChatMessageView | null) {
  if (!processMessage) {
    return;
  }
  const existingMessages = messagesBySessionId.value[sessionId] || [];
  const withoutDuplicate = existingMessages.filter((message) => message.localId !== processMessage.localId);
  let insertIndex = withoutDuplicate.map((message) => message.role).lastIndexOf('user');
  if (insertIndex < 0) {
    insertIndex = Math.max(0, withoutDuplicate.length - 1);
  }
  const nextMessages = [
    ...withoutDuplicate.slice(0, insertIndex + 1),
    processMessage,
    ...withoutDuplicate.slice(insertIndex + 1),
  ];
  setSessionMessages(sessionId, nextMessages);
}

async function handleCreateSession() {
  try {
    const selectedModel = parseModelKey(selectedModelKey.value || defaultModelKeyFromSettings(settings.value));
    const { data } = await createSession({
      title: t('chat.newSession'),
      ragDisabled: false,
      modelProvider: selectedModel.provider,
      model: selectedModel.model,
    });
    sessions.value = [data, ...sessions.value];
    demoQuestionPanelState.value = { ...demoQuestionPanelState.value, [data.id]: true };
    await selectSession(data.id);
    ui.message.success('已创建新会话');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '创建会话失败');
  }
}

async function handleChatFileChange(event: Event) {
  const target = event.target as HTMLInputElement;
  const files = Array.from(target.files || []);
  target.value = '';
  if (files.length === 0) {
    return;
  }
  if (sending.value) {
    ui.message.warning('当前正在回复，稍后再上传资料');
    return;
  }
  chatUploading.value = true;
  chatUploadProgress.value = 0;
  try {
    const uploaded: ChatUploadAttachment[] = [];
    for (let index = 0; index < files.length; index += 1) {
      const document = await uploadChatFile(files[index], index, files.length);
      uploaded.push({
        documentId: document.id,
        filename: document.filename,
        status: document.status,
      });
    }
    chatAttachments.value = [...chatAttachments.value, ...uploaded];
    ui.message.success(uploaded.length === 1 ? '资料已上传，将随下一条消息使用' : `已上传 ${uploaded.length} 个资料文件`);
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || error.message || '上传资料失败');
  } finally {
    chatUploading.value = false;
    chatUploadProgress.value = 0;
    chatUploadStatus.value = '';
  }
}

async function uploadChatFile(file: File, fileIndex: number, fileCount: number): Promise<KbDocumentResponse> {
  const uploadId = globalThis.crypto?.randomUUID?.() || `chat-upload-${Date.now()}-${fileIndex}`;
  const totalChunks = Math.max(1, Math.ceil(file.size / CHAT_UPLOAD_CHUNK_SIZE));
  for (let chunkNumber = 0; chunkNumber < totalChunks; chunkNumber += 1) {
    const start = chunkNumber * CHAT_UPLOAD_CHUNK_SIZE;
    const end = Math.min(start + CHAT_UPLOAD_CHUNK_SIZE, file.size);
    const chunk = file.slice(start, end);
    chatUploadStatus.value = fileCount > 1
      ? `Uploading ${fileIndex + 1}/${fileCount}: ${file.name}`
      : `Uploading ${file.name}`;
    await uploadChatChunkWithRetry({
      uploadId,
      filename: file.name,
      chunkNumber,
      totalChunks,
      file: chunk,
    });
    const fileProgress = ((chunkNumber + 1) / totalChunks) * 90;
    chatUploadProgress.value = Math.round(((fileIndex + fileProgress / 100) / fileCount) * 100);
  }
  chatUploadStatus.value = `Processing ${file.name}`;
  const { data } = await mergeKbUpload({
    uploadId,
    filename: file.name,
    totalChunks,
    mimeType: file.type || 'application/octet-stream',
    isPublic: false,
  });
  chatUploadProgress.value = Math.round(((fileIndex + 1) / fileCount) * 100);
  return data;
}

async function uploadChatChunkWithRetry(payload: Parameters<typeof uploadChunk>[0]) {
  let lastError: unknown = null;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      await uploadChunk(payload);
      return;
    } catch (error) {
      lastError = error;
      await new Promise((resolve) => window.setTimeout(resolve, attempt * 300));
    }
  }
  throw lastError;
}

function removeChatAttachment(documentId: number) {
  chatAttachments.value = chatAttachments.value.filter((item) => item.documentId !== documentId);
}

async function handleSend() {
  if (!draft.value.trim()) {
    ui.message.warning('请输入消息内容');
    return;
  }
  if (sending.value) {
    return;
  }
  if (chatUploading.value) {
    ui.message.warning('资料还在上传，请稍后再发送');
    return;
  }

  let activeSendSessionId: number | null = null;
  try {
    sending.value = true;
    let sessionId = selectedSessionId.value;
    if (!sessionId) {
      const selectedModel = parseModelKey(selectedModelKey.value || defaultModelKeyFromSettings(settings.value));
      const { data } = await createSession({
        title: t('chat.newSession'),
        ragDisabled: ragDisabled.value,
        modelProvider: selectedModel.provider,
        model: selectedModel.model,
      });
      sessions.value = [data, ...sessions.value];
      applySelectedSession(data.id);
      sessionId = data.id;
    } else {
      await ensureActiveSessionModelSynced(sessionId);
    }
    activeSendSessionId = sessionId;

    const rawContent = draft.value.trim();
    const sentFromDemoQuestion = pendingDemoQuestion.value != null;
    const attachmentsForSend = [...chatAttachments.value];
    const content = buildContentWithChatAttachments(rawContent, attachmentsForSend);
    const displayContent = buildDisplayContentWithChatAttachments(rawContent, attachmentsForSend);
    draft.value = '';
    chatAttachments.value = [];
    appendSessionMessage(sessionId, {
      localId: 'user-' + Date.now(),
      role: 'user',
      content: displayContent,
    });
    if (sentFromDemoQuestion) {
      setDemoQuestionPanelOpen(false);
      pendingDemoQuestion.value = null;
    }

    const willUsePlanExecution = planMode.value && !isPlanArtifactRequest(content);
    const processId = 'process-' + Date.now();
    currentProcessMessageId.value = processId;
    currentProcessMessageSessionId.value = sessionId;
    appendSessionMessage(sessionId, {
      localId: processId,
      role: 'process',
      content: willUsePlanExecution ? '正在创建并执行计划…' : '正在处理请求…',
      processOpen: true,
      processDone: false,
      processStartedAt: Date.now(),
      processElapsedMs: null,
    });

    const assistantId = 'assistant-' + Date.now();
    currentAssistantMessageId.value = assistantId;
    currentAssistantMessageSessionId.value = sessionId;
    appendSessionMessage(sessionId, {
      localId: assistantId,
      role: 'assistant',
      content: '',
      navigationUrl: null,
    });
    await scrollMessagesToBottom();

    if (willUsePlanExecution) {
      await sendPlanMessage(sessionId, content, ragDisabled.value, selectedSkillId.value);
    } else {
      await sendMessageWithFallback(sessionId, content, ragDisabled.value, selectedSkillId.value);
    }
  } catch (error: any) {
    collapseCurrentProcessMessage();
    removePendingAssistant();
    if (activeSendSessionId) {
      await reloadCurrentMessages(activeSendSessionId).catch(() => undefined);
    }
    ui.message.error(error.response?.data?.message || error.message || '发送失败');
    sending.value = false;
  }
}

function isPlanArtifactRequest(content: string) {
  const text = content.trim().toLowerCase();
  if (!text) {
    return false;
  }
  const asksForPlanArtifact = /(制定|设计|生成|输出|给我|帮我).{0,16}(研究计划|学习计划|学习路线|路线图|roadmap|plan)/i.test(text)
    || /(研究计划|学习计划|学习路线|路线图|roadmap|plan).{0,16}(制定|设计|生成|输出)/i.test(text);
  const asksToExecute = /(执行|开始执行|直接执行|搜索|检索|查找|调研|收集|爬取|调用|运行|分析文献|找.{0,8}论文|search|execute|run)/i.test(text);
  return asksForPlanArtifact && !asksToExecute;
}

function buildContentWithChatAttachments(content: string, attachments: ChatUploadAttachment[]) {
  if (attachments.length === 0) {
    return content;
  }
  const lines = attachments.map((item) => `- documentId=${item.documentId}, filename=${item.filename}, status=${item.status}`);
  return [
    content,
    '',
    '本轮对话已上传以下知识库文档，请优先使用 search_knowledge / read_document 检索这些资料后再回答：',
    ...lines,
  ].join('\n');
}

function buildDisplayContentWithChatAttachments(content: string, attachments: ChatUploadAttachment[]) {
  if (attachments.length === 0) {
    return content;
  }
  return [
    content,
    '',
    '已附加资料：',
    ...attachments.map((item) => `- ${item.filename} (#${item.documentId})`),
  ].join('\n');
}

async function sendPlanMessage(sessionId: number, content: string, disableRag: boolean, skillId: string | null) {
  currentSocket.value?.close();
  const { data: createdPlan } = await createPlan(sessionId, {
    content,
    ragDisabled: disableRag,
    skillId,
    autoExecute: false,
  });
  currentPlan.value = createdPlan;
  await setProcessContent(buildPlanProcessContent(createdPlan));

  let refreshing = false;
  const refreshPlan = async () => {
    if (refreshing) {
      return currentPlan.value;
    }
    refreshing = true;
    try {
      const { data } = await getPlan(createdPlan.id);
      currentPlan.value = data;
      await setProcessContent(buildPlanProcessContent(data));
      return data;
    } catch {
      return currentPlan.value;
    } finally {
      refreshing = false;
    }
  };

  try {
    const { data: queuedPlan } = await executePlanAsync(createdPlan.id);
    currentPlan.value = queuedPlan;
    await setProcessContent(buildPlanProcessContent(queuedPlan));

    await new Promise<void>((resolve) => {
      let pollTimer: number | undefined;
      let timeoutTimer: number | undefined;
      timeoutTimer = window.setTimeout(() => {
        if (pollTimer !== undefined) {
          window.clearInterval(pollTimer);
        }
        void refreshPlan().finally(resolve);
      }, 300000);
      pollTimer = window.setInterval(() => {
        void refreshPlan().then((plan) => {
          if (plan && isTerminalPlanStatus(plan.status)) {
            if (pollTimer !== undefined) {
              window.clearInterval(pollTimer);
            }
            if (timeoutTimer !== undefined) {
              window.clearTimeout(timeoutTimer);
            }
            resolve();
          }
        });
      }, 1500);
      void refreshPlan().then((plan) => {
        if (plan && isTerminalPlanStatus(plan.status)) {
          if (pollTimer !== undefined) {
            window.clearInterval(pollTimer);
          }
          if (timeoutTimer !== undefined) {
            window.clearTimeout(timeoutTimer);
          }
          resolve();
        }
      });
    });

    const finalPlan = currentPlan.value || queuedPlan;
    await setProcessContent(buildPlanProcessContent(finalPlan));
    await setAssistantContent(buildPlanAssistantContent(finalPlan));
    await afterSendFinished(sessionId);
  } catch (error: any) {
    const message = error.response?.data?.message || error.message || 'Plan execution failed';
    const refreshedPlan = await refreshPlan();
    if (refreshedPlan) {
      await setProcessContent(buildPlanProcessContent(refreshedPlan));
    }
    await appendProcessLine('计划执行失败：' + message);
    await setAssistantContent('本次计划未能完成：' + message);
    collapseCurrentProcessMessage();
    ui.message.error(message);
    sending.value = false;
    currentAssistantMessageId.value = null;
  }
}

function isTerminalPlanStatus(status: string) {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED';
}

async function sendMessageWithFallback(sessionId: number, content: string, disableRag: boolean, skillId: string | null) {
  const clientRequestId = globalThis.crypto?.randomUUID?.()
    || `chat-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  console.info('[chat] send start', { sessionId, mode: 'ws-preferred', clientRequestId });
  try {
    await sendWsMessageWithRetry(sessionId, content, disableRag, skillId, clientRequestId);
  } catch (wsError: any) {
    console.warn('[chat] websocket send failed, switching to http fallback', {
      sessionId,
      clientRequestId,
      error: wsError?.message || String(wsError),
    });
    currentSocket.value?.close();
    currentSocket.value = null;
    ui.message.warning(
      wsError?.message
        ? (wsError.message + '，已自动切换到稳定发送模式。')
        : '实时连接失败，已自动切换到稳定发送模式。',
    );
    await appendProcessLine('WebSocket failed, switching to stable HTTP fallback.');
    await sendHttpMessage(sessionId, content, disableRag, skillId, clientRequestId);
  }
}

async function sendWsMessageWithRetry(
  sessionId: number,
  content: string,
  disableRag: boolean,
  skillId: string | null,
  clientRequestId: string,
) {
  let lastError: Error | null = null;
  for (let attempt = 1; attempt <= WS_MAX_RETRIES; attempt += 1) {
    try {
      await sendWsMessage(sessionId, content, disableRag, skillId, clientRequestId, attempt);
      return;
    } catch (error: any) {
      lastError = error instanceof Error ? error : new Error(String(error));
      console.warn('[chat] websocket attempt failed', {
        sessionId,
        clientRequestId,
        attempt,
        error: lastError.message,
      });
    }
  }
  throw lastError || new Error('WebSocket ack failed');
}

async function sendWsMessage(
  sessionId: number,
  content: string,
  disableRag: boolean,
  skillId: string | null,
  clientRequestId: string,
  attempt: number,
) {
  currentSocket.value?.close();

  await new Promise<void>((resolve, reject) => {
    let settled = false;
    let ackReceived = false;
    let ackTimeout: number | null = null;
    const token = authStore.token;
    if (!token) {
      reject(new Error('未登录'));
      return;
    }

    const finishResolve = () => {
      if (settled) {
        return;
      }
      settled = true;
      if (ackTimeout !== null) {
        window.clearTimeout(ackTimeout);
      }
      resolve();
    };

    const finishReject = (error: Error) => {
      if (settled) {
        return;
      }
      settled = true;
      if (ackTimeout !== null) {
        window.clearTimeout(ackTimeout);
      }
      reject(error);
    };

    const wsUrl = buildChatWebSocketUrl(token);
    console.info('[chat] websocket connect start', { sessionId, wsUrl, clientRequestId, attempt });
    const ws = new WebSocket(wsUrl);
    currentSocket.value = ws;
    ackTimeout = window.setTimeout(() => {
      if (ackReceived) {
        return;
      }
      finishReject(new Error('WebSocket 连接超时'));
      ws.close();
    }, WS_ACK_TIMEOUT_MS);

    ws.onopen = () => {
      console.info('[chat] websocket open', { sessionId, clientRequestId, attempt });
      ws.send(JSON.stringify({
        sessionId,
        content,
        ragDisabled: disableRag,
        skillId,
        clientRequestId,
      }));
    };

    ws.onmessage = async (event) => {
      const payload = JSON.parse(event.data) as WsChatEvent;
      if (payload.clientRequestId && payload.clientRequestId !== clientRequestId) {
        console.info('[chat] websocket ignore stale event', {
          sessionId,
          clientRequestId,
          payloadRequestId: payload.clientRequestId,
          type: payload.type,
        });
        return;
      }
      if (payload.type === 'ack') {
        ackReceived = true;
        if (ackTimeout !== null) {
          window.clearTimeout(ackTimeout);
          ackTimeout = null;
        }
        console.info('[chat] websocket ack', { sessionId, clientRequestId, attempt });
        return;
      }
      if (payload.type === 'chunk' && payload.content) {
        appendAssistantChunk(payload.content);
        return;
      }
      if (payload.type === 'process' && payload.content) {
        await appendProcessLine(payload.content);
        return;
      }
      if (payload.type === 'debug') {
        console.info('[chat] websocket debug payload', {
          sessionId,
          clientRequestId,
          hasDebug: !!payload.debug,
          toolTraceCount: payload.debug?.toolTrace?.length || 0,
          fallbackCount: payload.debug?.fallbacks?.length || 0,
        });
        return;
      }
      if (payload.type === 'error') {
        console.warn('[chat] websocket payload error', { sessionId, clientRequestId, error: payload.error });
        collapseCurrentProcessMessage();
        finishReject(new Error(payload.error || 'WebSocket 对话失败'));
        ws.close();
        return;
      }
      if (payload.type === 'done') {
        console.info('[chat] websocket done', { sessionId, clientRequestId, navigationUrl: payload.navigationUrl });
        if (payload.navigationUrl) {
          attachAssistantNavigation(payload.navigationUrl);
        }
        collapseCurrentProcessMessage();
        await afterSendFinished(sessionId);
        ws.close();
        finishResolve();
      }
    };

    ws.onerror = () => {
      console.warn('[chat] websocket error event', { sessionId, clientRequestId, attempt, readyState: ws.readyState });
      if (!ackReceived) {
        finishReject(new Error('WebSocket connection failed before ack'));
        return;
      }
      finishReject(new Error('WebSocket 连接失败'));
    };
    ws.onclose = () => {
      currentSocket.value = null;
      console.info('[chat] websocket closed', { sessionId, clientRequestId, attempt, readyState: ws.readyState, settled, ackReceived });
      if (!settled && sending.value && !ackReceived) {
        finishReject(new Error('WebSocket 连接已关闭'));
      }
    };
  });
}

async function sendHttpMessage(
  sessionId: number,
  content: string,
  disableRag: boolean,
  skillId: string | null,
  clientRequestId: string,
) {
  console.info('[chat] http fallback send start', { sessionId, clientRequestId });
  const { data } = await sendAgentMessage(sessionId, {
    content,
    ragDisabled: disableRag,
    skillId,
    clientRequestId,
  });
  console.info('[chat] http fallback send success', {
    sessionId,
    clientRequestId,
    success: data.success,
    steps: data.steps,
    navigationUrl: data.navigationUrl,
  });
  applyHttpSendResponse(sessionId, data);
  await afterSendFinished(sessionId);
}

function applyHttpSendResponse(sessionId: number, response: SendMessageResponse) {
  if (!response.success) {
    throw new Error(response.errorMessage || '发送失败');
  }
  if (response.navigationUrl) {
    attachAssistantNavigation(response.navigationUrl);
  }
  const finalContent = response.assistantContent || '';
  if (currentAssistantMessageId.value) {
    updateSessionMessage(sessionId, currentAssistantMessageId.value, (message) => {
      message.content = finalContent;
      if (response.navigationUrl) {
        message.navigationUrl = response.navigationUrl;
      }
    });
  }
}

async function appendAssistantChunk(chunk: string) {
  const sessionId = currentAssistantMessageSessionId.value;
  if (sessionId && currentAssistantMessageId.value) {
    updateSessionMessage(sessionId, currentAssistantMessageId.value, (message) => {
      message.content += chunk;
    });
    if (selectedSessionId.value === sessionId) {
      await scrollMessagesToBottom();
    }
  }
}

async function appendProcessLine(line: string) {
  const sessionId = currentProcessMessageSessionId.value;
  if (!sessionId || !currentProcessMessageId.value) {
    return;
  }
  updateSessionMessage(sessionId, currentProcessMessageId.value, (message) => {
    const nextLine = line.trim();
    if (!nextLine) {
      return;
    }
    message.content = message.content?.trim()
      ? message.content + '\n' + nextLine
      : nextLine;
    message.processOpen = true;
    message.processDone = false;
  });
  if (selectedSessionId.value === sessionId) {
    await scrollMessagesToBottom();
  }
}

async function setProcessContent(content: string) {
  const sessionId = currentProcessMessageSessionId.value;
  if (!sessionId || !currentProcessMessageId.value) {
    return;
  }
  updateSessionMessage(sessionId, currentProcessMessageId.value, (message) => {
    message.content = content;
    message.processOpen = true;
    message.processDone = false;
  });
  if (selectedSessionId.value === sessionId) {
    await scrollMessagesToBottom();
  }
}

function collapseCurrentProcessMessage() {
  const sessionId = currentProcessMessageSessionId.value;
  if (!sessionId || !currentProcessMessageId.value) {
    return;
  }
  updateSessionMessage(sessionId, currentProcessMessageId.value, (message) => {
    const now = Date.now();
    message.processOpen = false;
    message.processDone = true;
    message.processElapsedMs = message.processStartedAt ? Math.max(0, now - message.processStartedAt) : message.processElapsedMs;
  });
}

async function setAssistantContent(content: string) {
  const sessionId = currentAssistantMessageSessionId.value;
  if (sessionId && currentAssistantMessageId.value) {
    updateSessionMessage(sessionId, currentAssistantMessageId.value, (message) => {
      message.content = content;
    });
    if (selectedSessionId.value === sessionId) {
      await scrollMessagesToBottom();
    }
  }
}

async function scrollMessagesToBottom() {
  await nextTick();
  const container = messagesContainerRef.value;
  if (!container) {
    return;
  }
  const bottom = Math.max(container.scrollHeight - container.clientHeight, 0);
  container.scrollTo({ top: bottom, behavior: 'auto' });
  window.requestAnimationFrame(() => {
    const current = messagesContainerRef.value;
    if (!current) {
      return;
    }
    current.scrollTop = Math.max(current.scrollHeight - current.clientHeight, 0);
    syncMinimapViewport();
  });
  syncMinimapViewport();
}

function saveCurrentScrollPosition(sessionId: number | null) {
  if (!sessionId) {
    return;
  }
  const container = messagesContainerRef.value;
  if (!container) {
    return;
  }
  sessionScrollPositions.value = {
    ...sessionScrollPositions.value,
    [sessionId]: container.scrollTop,
  };
}

function setMessageRowRef(el: any, localId: string) {
  if (el instanceof HTMLElement) {
    messageRowRefs[localId] = el;
    return;
  }
  if (!el && messageRowRefs[localId]) {
    delete messageRowRefs[localId];
  }
}

function setMinimapItemRef(el: any, localId: string) {
  if (el instanceof HTMLElement) {
    minimapItemRefs[localId] = el;
    return;
  }
  if (!el && minimapItemRefs[localId]) {
    delete minimapItemRefs[localId];
  }
}

function pruneMessageRowRefs(currentMessages: ChatMessageView[]) {
  const validIds = new Set(currentMessages.map((message) => message.localId));
  Object.keys(messageRowRefs).forEach((localId) => {
    if (!validIds.has(localId)) {
      delete messageRowRefs[localId];
    }
  });
  Object.keys(minimapItemRefs).forEach((localId) => {
    if (!validIds.has(localId)) {
      delete minimapItemRefs[localId];
    }
  });
}

function handleMessagesScroll() {
  const sessionId = selectedSessionId.value;
  if (sessionId) {
    saveCurrentScrollPosition(sessionId);
  }
  syncMinimapViewport();
}

function syncMinimapPreviewPosition() {
  const hovered = hoveredMinimapMessage.value;
  const railEl = chatMinimapRailRef.value;
  const minimapEl = chatMinimapRef.value;
  const previewEl = chatMinimapPreviewRef.value;
  const itemEl = hovered ? minimapItemRefs[hovered.localId] : null;
  if (!hovered || !railEl || !minimapEl || !previewEl || !itemEl) {
    minimapPreviewTopPx.value = 8;
    return;
  }
  const railRect = railEl.getBoundingClientRect();
  const itemRect = itemEl.getBoundingClientRect();
  const itemCenter = itemRect.top - railRect.top + itemRect.height / 2;
  const previewHeight = previewEl.offsetHeight || 112;
  const minTop = 8;
  const maxTop = Math.max(minTop, railEl.clientHeight - previewHeight - 8);
  const nextTop = Math.min(Math.max(itemCenter - previewHeight / 2, minTop), maxTop);
  minimapPreviewTopPx.value = nextTop;
}

function syncMinimapViewport() {
  const container = messagesContainerRef.value;
  const visibleMessages = filteredMessages.value;
  if (!container || visibleMessages.length === 0) {
    minimapViewportTop.value = 0;
    minimapViewportHeight.value = 0.18;
    activeMinimapMessageId.value = null;
    return;
  }
  const maxScroll = Math.max(container.scrollHeight - container.clientHeight, 1);
  minimapViewportTop.value = Math.min(container.scrollTop / maxScroll, 1);
  minimapViewportHeight.value = Math.min(container.clientHeight / Math.max(container.scrollHeight, 1), 1);
  if (Date.now() < minimapActiveLockUntil) {
    return;
  }

  const containerTop = container.getBoundingClientRect().top;
  const threshold = containerTop + 24;
  let activeId = minimapMessages.value[minimapMessages.value.length - 1]?.localId || null;
  for (const message of visibleMessages) {
    const rowEl = messageRowRefs[message.localId];
    if (!rowEl) {
      continue;
    }
    if (rowEl.getBoundingClientRect().top >= threshold) {
      activeId = resolveMinimapActiveId(message.localId);
      break;
    }
  }
  activeMinimapMessageId.value = activeId;
}

async function scrollToMessage(localId: string) {
  const container = messagesContainerRef.value;
  const target = messageRowRefs[localId];
  if (!container || !target) {
    return;
  }
  const index = minimapMessages.value.findIndex((message) => message.localId === localId);
  if (index >= 0) {
    hoveredMinimapIndex.value = index;
    pinnedMinimapIndex.value = index;
  }
  minimapActiveLockUntil = Date.now() + 420;
  container.scrollTo({ top: Math.max(target.offsetTop - 70, 0), behavior: 'smooth' });
  activeMinimapMessageId.value = localId;
  saveCurrentScrollPosition(selectedSessionId.value);
}

function resolveMinimapActiveId(localId: string | null) {
  if (!localId) {
    return null;
  }
  const directMatch = minimapMessages.value.find((message) => message.localId === localId);
  if (directMatch) {
    return directMatch.localId;
  }
  const sourceIndex = filteredMessages.value.findIndex((message) => message.localId === localId);
  if (sourceIndex < 0) {
    return minimapMessages.value[minimapMessages.value.length - 1]?.localId || null;
  }
  for (let i = sourceIndex; i >= 0; i -= 1) {
    const candidate = filteredMessages.value[i];
    if (candidate?.role === 'user') {
      return candidate.localId;
    }
  }
  return minimapMessages.value[0]?.localId || null;
}

function setHoveredMinimapIndex(index: number) {
  cancelMinimapHoverClear();
  hoveredMinimapIndex.value = index;
  pinnedMinimapIndex.value = index;
}

function clearHoveredMinimapIndex() {
  cancelMinimapHoverClear();
  hoveredMinimapIndex.value = null;
  pinnedMinimapIndex.value = null;
}

function keepHoveredMinimapIndex() {
  cancelMinimapHoverClear();
  if (pinnedMinimapIndex.value != null) {
    hoveredMinimapIndex.value = pinnedMinimapIndex.value;
  }
}

function cancelMinimapHoverClear() {
  if (minimapHoverClearTimer !== null) {
    window.clearTimeout(minimapHoverClearTimer);
    minimapHoverClearTimer = null;
  }
}

function scheduleMinimapHoverClear() {
  cancelMinimapHoverClear();
  minimapHoverClearTimer = window.setTimeout(() => {
    hoveredMinimapIndex.value = null;
    pinnedMinimapIndex.value = null;
    minimapHoverClearTimer = null;
  }, 90);
}

function handleMinimapRailLeave(event?: MouseEvent) {
  const railEl = chatMinimapRailRef.value;
  const nextTarget = event?.relatedTarget;
  if (railEl && nextTarget instanceof Node && railEl.contains(nextTarget)) {
    return;
  }
  scheduleMinimapHoverClear();
}

function minimapWaveClass(index: number) {
  const hoveredIndex = hoveredMinimapIndex.value;
  if (hoveredIndex == null) {
    return '';
  }
  const distance = Math.abs(index - hoveredIndex);
  if (distance === 0) {
    return 'chat-minimap__item--wave-0';
  }
  if (distance === 1) {
    return 'chat-minimap__item--wave-1';
  }
  if (distance === 2) {
    return 'chat-minimap__item--wave-2';
  }
  if (distance === 3) {
    return 'chat-minimap__item--wave-3';
  }
  return '';
}

function buildMinimapPreview(message: ChatMessageView | null) {
  if (!message) {
    return { user: '', assistant: '' };
  }
  return {
    user: clampMinimapText(message.content),
    assistant: '',
  };
}

function findAssistantReplyForUser(localId: string) {
  const sourceIndex = filteredMessages.value.findIndex((message) => message.localId === localId);
  if (sourceIndex < 0) {
    return '';
  }
  for (let i = sourceIndex + 1; i < filteredMessages.value.length; i += 1) {
    const candidate = filteredMessages.value[i];
    if (candidate?.role === 'assistant') {
      return candidate.content;
    }
    if (candidate?.role === 'user') {
      break;
    }
  }
  return '';
}

function clampMinimapText(content: string) {
  const normalized = (content || '').replace(/\s+/g, ' ').trim();
  if (!normalized) {
    return '';
  }
  return normalized.length > 84 ? normalized.slice(0, 84) + '...' : normalized;
}

async function restoreScrollPosition(sessionId: number) {
  await nextTick();
  await new Promise<void>((resolve) => {
    window.requestAnimationFrame(() => resolve());
  });
  const container = messagesContainerRef.value;
  if (!container) {
    return;
  }
  const savedPosition = sessionScrollPositions.value[sessionId];
  if (typeof savedPosition === 'number') {
    container.scrollTop = savedPosition;
    syncMinimapViewport();
    return;
  }
  container.scrollTop = 0;
  syncMinimapViewport();
}

function attachAssistantNavigation(navigationUrl: string) {
  const sessionId = currentAssistantMessageSessionId.value;
  if (sessionId && currentAssistantMessageId.value) {
    updateSessionMessage(sessionId, currentAssistantMessageId.value, (message) => {
      message.navigationUrl = navigationUrl;
    });
  }
}

function removePendingAssistant() {
  if (!currentAssistantMessageId.value) {
    return;
  }
  removeSessionMessage(currentAssistantMessageSessionId.value, currentAssistantMessageId.value);
  currentAssistantMessageId.value = null;
  currentAssistantMessageSessionId.value = null;
}

async function handleModelChange(value: string) {
  selectedModelKey.value = value;
  if (!selectedSessionId.value) {
    return;
  }
  try {
    const selectedModel = parseModelKey(value);
    const { data } = await updateAgentSession(selectedSessionId.value, {
      modelProvider: selectedModel.provider,
      model: selectedModel.model,
    });
    replaceSession(data);
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '切换模型失败');
  }
}

async function ensureActiveSessionModelSynced(sessionId: number) {
  const selectedModel = parseModelKey(selectedModelKey.value || defaultModelKeyFromSettings(settings.value));
  const active = sessions.value.find((item) => item.id === sessionId);
  if (active?.modelProvider === selectedModel.provider && active?.model === selectedModel.model) {
    return;
  }
  const { data } = await updateAgentSession(sessionId, {
    modelProvider: selectedModel.provider,
    model: selectedModel.model,
  });
  replaceSession(data);
}

async function handleSessionMenuSelect(key: string | number, session: AgentSessionResponse) {
  if (key === 'rename') {
    openRenameSession(session);
    return;
  }
  if (key === 'delete') {
    await handleDeleteSession(session);
  }
}

function readStoredBoolean(key: string, fallback: boolean) {
  if (typeof window === 'undefined') {
    return fallback;
  }
  const value = window.localStorage.getItem(key);
  if (value == null) {
    return fallback;
  }
  return value === 'true';
}

function setStoredBoolean(key: string, value: boolean) {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(key, String(value));
  }
}

function setChatSidebarCollapsed(collapsed: boolean) {
  chatSidebarCollapsed.value = collapsed;
  setStoredBoolean(CHAT_SIDEBAR_COLLAPSED_KEY, collapsed);
}

function openRenameSession(session: AgentSessionResponse) {
  renameSessionId.value = session.id;
  renameDraft.value = session.title || '';
  renameModalVisible.value = true;
}

async function confirmRenameSession() {
  const title = renameDraft.value.trim();
  if (!renameSessionId.value || !title) {
    ui.message.warning('请输入会话名称');
    return;
  }
  try {
    renaming.value = true;
    const { data } = await updateAgentSession(renameSessionId.value, { title });
    replaceSession(data);
    renameModalVisible.value = false;
    ui.message.success('宸查噸鍛藉悕');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '重命名失败');
  } finally {
    renaming.value = false;
  }
}

async function handleDeleteSession(session: AgentSessionResponse) {
  if (sending.value) {
    ui.message.warning('当前正在回复，暂不能删除会话');
    return;
  }
  const sessionTitle = session.title || ('会话 #' + session.id);
  if (!window.confirm('确定删除「' + sessionTitle + '」吗？')) {
    return;
  }
  try {
    await deleteAgentSession(session.id);
    sessions.value = sessions.value.filter((item) => item.id !== session.id);
    if (selectedSessionId.value === session.id) {
      const next = sessions.value[0];
      if (next) {
        await selectSession(next.id);
      } else {
        clearSessionSelection();
      }
    }
    ui.message.success('已删除会话');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '删除会话失败');
  }
}

function replaceSession(session: AgentSessionResponse) {
  const index = sessions.value.findIndex((item) => item.id === session.id);
  if (index >= 0) {
    sessions.value.splice(index, 1, session);
  } else {
    sessions.value = [session, ...sessions.value];
  }
}

async function afterSendFinished(sessionId: number) {
  sending.value = false;
  collapseCurrentProcessMessage();
  const processMessage = findSessionMessage(currentProcessMessageSessionId.value, currentProcessMessageId.value);
  currentAssistantMessageId.value = null;
  currentAssistantMessageSessionId.value = null;
  await reloadCurrentMessages(sessionId);
  if (!hasProcessMessageAfterLatestUser(sessionId)) {
    insertProcessMessageAfterLatestUser(sessionId, processMessage);
  }
  currentProcessMessageId.value = null;
  currentProcessMessageSessionId.value = null;
  const { data } = await listSessions();
  sessions.value = data;
  if (!sessions.value.some((item) => item.id === sessionId)) {
    clearSessionSelection();
    return;
  }
  applySelectedSession(sessionId);
  const active = sessions.value.find((item) => item.id === selectedSessionId.value);
  if (selectedSessionId.value === sessionId && active?.modelProvider && active?.model) {
    selectedModelKey.value = toModelKey(active.modelProvider, active.model);
  }
}

function applySelectedSession(sessionId: number | null) {
  selectedSessionId.value = sessionId;
  if (sessionId == null) {
    clearStoredSessionId();
    replaceRouteSessionId(null);
    messages.value = [];
    return;
  }
  storeSelectedSessionId(sessionId);
  replaceRouteSessionId(sessionId);
}

function clearSessionSelection() {
  selectedSessionId.value = null;
  messages.value = [];
  currentPlan.value = null;
  clearStoredSessionId();
  replaceRouteSessionId(null);
}

function resolvePreferredSessionId(items: AgentSessionResponse[]) {
  const candidates = [
    parseSessionId(route.query.sessionId),
    readStoredSessionId(),
  ];
  for (const candidate of candidates) {
    if (candidate && items.some((item) => item.id === candidate)) {
      return candidate;
    }
  }
  return items[0]?.id || null;
}

function parseSessionId(value: unknown) {
  const raw = Array.isArray(value) ? value[0] : value;
  if (typeof raw !== 'string' && typeof raw !== 'number') {
    return null;
  }
  const parsed = Number(raw);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

function readStoredSessionId() {
  if (typeof window === 'undefined') {
    return null;
  }
  return parseSessionId(window.localStorage.getItem(SESSION_STORAGE_KEY));
}

function storeSelectedSessionId(sessionId: number) {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(SESSION_STORAGE_KEY, String(sessionId));
  }
}

function clearStoredSessionId() {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(SESSION_STORAGE_KEY);
  }
}

function replaceRouteSessionId(sessionId: number | null) {
  const currentSessionId = parseSessionId(route.query.sessionId);
  if (currentSessionId === sessionId) {
    return;
  }
  const nextQuery = { ...route.query };
  if (sessionId == null) {
    delete nextQuery.sessionId;
  } else {
    nextQuery.sessionId = String(sessionId);
  }
  void router.replace({ query: nextQuery });
}

function buildChatWebSocketUrl(token: string) {
  const wsOrigin = window.location.origin.replace(/^http/, 'ws');
  return wsOrigin + '/api/v1/ws/chat?token=' + encodeURIComponent(token);
}

function buildPlanAssistantContent(plan: AgentPlanResponse) {
  const finalStepResult = projectPlanFinalAnswer(plan);
  if (finalStepResult) {
    return finalStepResult;
  }
  if (plan.errorMessage) {
    return plan.errorMessage;
  }
  if (plan.summary) {
    return plan.summary;
  }
  return uiText(
    '计划已结束，但没有返回可展示的最终结果。',
    'The plan finished without a displayable final result.',
  );
}

function buildPlanProcessContent(plan: AgentPlanResponse) {
  const lines = [
    `Plan lifecycle status: ${projectPlanLifecycle(plan)}.`,
    `Plan execution outcome: ${projectPlanExecutionOutcome(plan)}.`,
    plan.summary ? ('摘要：' + plan.summary) : '',
    '',
    '步骤：',
    ...plan.steps.map((step) => '- [' + step.status + '] ' + step.stepKey + ' ' + (step.title || step.description) + (step.errorMessage ? ('：' + step.errorMessage) : '')),
  ].filter(Boolean);
  if (plan.errorMessage) {
    lines.push('', '错误：' + plan.errorMessage);
  }
  return lines.join('\n');
}

function artifactIconLabel(artifact: ChatArtifactCard) {
  const type = (artifact.artifactType || '').toUpperCase();
  if (type === 'TEXT') {
    return 'TXT';
  }
  return 'MD';
}

async function previewArtifact(artifact: ChatArtifactCard) {
  previewingArtifactId.value = artifact.id;
  try {
    const { data } = await getArtifact(artifact.id);
    artifactPreviewTitle.value = data.title || artifact.title || artifact.downloadFilename;
    artifactPreviewType.value = data.artifactType || artifact.artifactType || 'MARKDOWN';
    artifactPreviewContent.value = data.content || '';
    artifactPreviewVisible.value = true;
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '预览文档失败');
  } finally {
    previewingArtifactId.value = null;
  }
}

async function downloadArtifactCard(artifact: ChatArtifactCard) {
  downloadingArtifactId.value = artifact.id;
  try {
    const { data, headers } = await downloadArtifact(artifact.id);
    const filename = filenameFromDisposition(headers['content-disposition']) || artifact.downloadFilename || `artifact-${artifact.id}.md`;
    const blob = new Blob([data], { type: artifact.downloadContentType || data.type || 'text/markdown;charset=UTF-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
    ui.message.success('下载已开始');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '下载文档失败');
  } finally {
    downloadingArtifactId.value = null;
  }
}

async function saveArtifactCardToKnowledge(artifact: ChatArtifactCard) {
  savingArtifactId.value = artifact.id;
  try {
    const { data } = await saveArtifactToKnowledge(artifact.id);
    ui.message.success(`已存入知识库：${data.filename || '文档 #' + data.documentId}`);
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '存入知识库失败');
  } finally {
    savingArtifactId.value = null;
  }
}

function filenameFromDisposition(disposition?: string | null) {
  if (!disposition) {
    return '';
  }
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (utf8Match?.[1]) {
    return decodeURIComponent(utf8Match[1].replace(/"/g, ''));
  }
  const plainMatch = /filename="?([^";]+)"?/i.exec(disposition);
  return plainMatch?.[1] || '';
}

function toViewMessage(message: AgentMessageResponse): ChatMessageView {
  const content = message.content || '';
  const role = normalizeRole(message.role);
  return {
    localId: 'server-' + message.id,
    role,
    content,
    createdAt: message.createdAt,
    toolCallsJson: message.toolCallsJson,
    navigationUrl: extractNavigationUrl(content),
    processOpen: role === 'process' ? false : undefined,
    processDone: role === 'process' ? true : undefined,
  };
}

function buildViewMessages(serverMessages: AgentMessageResponse[]) {
  if (serverMessages.some((message) => normalizeRole(message.role) === 'process')) {
    const result: ChatMessageView[] = [];
    let latestUserCreatedAt: string | null = null;
    let pendingProcess: ChatMessageView | null = null;
    let pendingProcessIds: string[] = [];
    let pendingToolNames: string[] = [];
    let pendingArtifacts: ChatArtifactCard[] = [];
    const flushProcess = () => {
      if (!pendingProcess) {
        return;
      }
      if (pendingProcessIds.length) {
        pendingProcess.localId = 'process-server-' + pendingProcessIds.join('-');
      }
      result.push(pendingProcess);
      pendingProcess = null;
      pendingProcessIds = [];
    };

    for (const serverMessage of serverMessages) {
      const viewMessage = toViewMessage(serverMessage);
      if (isAssistantToolCallMessage(viewMessage)) {
        pendingToolNames.push(...summarizeToolRequestProcess(viewMessage).toolNames);
        continue;
      }
      if (viewMessage.role === 'tool') {
        const toolPayload = parseJsonObject(viewMessage.content);
        const artifact = extractArtifactFromToolResult(pendingToolNames.shift(), toolPayload);
        if (artifact) {
          pendingArtifacts = [...pendingArtifacts, artifact];
        }
        continue;
      }
      if (viewMessage.role === 'user') {
        flushProcess();
        latestUserCreatedAt = viewMessage.createdAt || null;
        result.push(viewMessage);
        continue;
      }
      if (viewMessage.role === 'process') {
        const nextContent = viewMessage.content.trim();
        if (!pendingProcess) {
          pendingProcess = { ...viewMessage, content: nextContent };
        } else {
          pendingProcess.content = [pendingProcess.content, nextContent].filter(Boolean).join('\n\n');
          pendingProcess.createdAt = viewMessage.createdAt || pendingProcess.createdAt;
        }
        pendingProcessIds.push(String(serverMessage.id));
        pendingProcess.processElapsedMs = elapsedBetween(latestUserCreatedAt, pendingProcess.createdAt);
        continue;
      }
      flushProcess();
      if (viewMessage.role === 'assistant' && pendingArtifacts.length > 0) {
        viewMessage.artifacts = pendingArtifacts;
        pendingArtifacts = [];
      }
      result.push(viewMessage);
    }
    flushProcess();
    return result;
  }

  const result: ChatMessageView[] = [];
  let pendingProcess: {
    ids: number[];
    lines: string[];
    toolNames: string[];
    startedAt: string | null;
    endedAt: string | null;
  } | null = null;
  let pendingArtifacts: ChatArtifactCard[] = [];

  const flushProcess = () => {
    if (!pendingProcess || pendingProcess.lines.length === 0) {
      pendingProcess = null;
      return;
    }
    const ids = pendingProcess.ids.length ? pendingProcess.ids.join('-') : String(Date.now());
    result.push({
      localId: 'process-server-' + ids,
      role: 'process',
      content: pendingProcess.lines.join('\n\n'),
      createdAt: pendingProcess.endedAt || pendingProcess.startedAt,
      processOpen: false,
      processDone: true,
      processElapsedMs: elapsedBetween(pendingProcess.startedAt, pendingProcess.endedAt),
    });
    pendingProcess = null;
  };

  for (const serverMessage of serverMessages) {
    const viewMessage = toViewMessage(serverMessage);
    if (isAssistantToolCallMessage(viewMessage)) {
      pendingProcess ||= { ids: [], lines: [], toolNames: [], startedAt: null, endedAt: null };
      const summary = summarizeToolRequestProcess(viewMessage);
      pendingProcess.ids.push(serverMessage.id);
      pendingProcess.lines.push(...summary.lines);
      pendingProcess.toolNames.push(...summary.toolNames);
      pendingProcess.startedAt ||= serverMessage.createdAt;
      pendingProcess.endedAt = serverMessage.createdAt;
      continue;
    }
    if (viewMessage.role === 'tool') {
      pendingProcess ||= { ids: [], lines: [], toolNames: [], startedAt: null, endedAt: null };
      pendingProcess.ids.push(serverMessage.id);
      const toolName = pendingProcess.toolNames.shift();
      const toolPayload = parseJsonObject(viewMessage.content);
      pendingProcess.lines.push(formatToolResultProcess(viewMessage, toolName, toolPayload));
      const artifact = extractArtifactFromToolResult(toolName, toolPayload);
      if (artifact) {
        pendingArtifacts = [...pendingArtifacts, artifact];
      }
      pendingProcess.startedAt ||= serverMessage.createdAt;
      pendingProcess.endedAt = serverMessage.createdAt;
      continue;
    }
    flushProcess();
    if (viewMessage.role === 'assistant' && pendingArtifacts.length > 0) {
      viewMessage.artifacts = pendingArtifacts;
      pendingArtifacts = [];
    }
    result.push(viewMessage);
  }
  flushProcess();
  return result;
}

function summarizeToolRequestProcess(message: ChatMessageView) {
  const calls = parseToolCalls(message.toolCallsJson);
  if (calls.length === 0) {
    return {
      lines: [uiText('正在选择合适的工具。', 'Choosing the right tool.')],
      toolNames: [],
    };
  }
  const toolNames = calls.map((call) => call.function?.name || 'unknown_tool');
  return {
    lines: toolNames.map((name) => toolRequestLabel(name)),
    toolNames,
  };
}

function formatAssistantToolCallProcess(message: ChatMessageView) {
  return summarizeToolRequestProcess(message).lines.join('\n');
}

function formatToolResultProcess(message: ChatMessageView, toolName?: string | null, payload = parseJsonObject(message.content)) {
  if (payload?.success === false) {
    return uiText('工具调用未完成，已尝试继续处理。', 'Tool execution did not complete; processing continued.');
  }
  return toolResultLabel(toolName, payload);
}

function toolRequestLabel(toolName: string) {
  if (toolName === 'write_document') {
    return uiText('正在生成可下载文档。', 'Generating a downloadable document.');
  }
  switch (toolName) {
    case 'search_knowledge':
      return uiText('正在检索知识库。', 'Searching the Knowledge Base.');
    case 'search_web':
      return uiText('正在联网搜索资料。', 'Searching the web.');
    case 'recommend_literature':
      return uiText('正在检索并整理相关文献。', 'Searching and organizing relevant literature.');
    case 'literature_search_start':
      return uiText('正在创建文献检索任务。', 'Creating a literature-search task.');
    case 'literature_search_status':
      return uiText('正在查看文献检索进度。', 'Checking literature-search progress.');
    case 'literature_search_result':
      return uiText('正在读取文献检索结果。', 'Reading literature-search results.');
    case 'literature_search_cancel':
    case 'paper_task_cancel':
      return uiText('正在取消后台任务。', 'Cancelling the background task.');
    case 'paper_polish_status':
      return uiText('正在查看论文润色进度。', 'Checking paper-polish progress.');
    case 'paper_polish_result':
      return uiText('正在读取论文润色结果。', 'Reading paper-polish results.');
    default:
      return uiText('正在调用辅助工具。', 'Calling a supporting tool.');
  }
}

function toolResultLabel(toolName?: string | null, payload?: Record<string, any> | null) {
  if (toolName === 'write_document') {
    return payload?.artifactId
      ? uiText('文档已生成，可以预览、下载或存入知识库。', 'Document generated. You can preview, download, or save it to the Knowledge Base.')
      : uiText('文档生成工具已完成。', 'The document generation tool has finished.');
  }
  const count = extractResultCount(payload);
  switch (toolName) {
    case 'search_knowledge':
      return count > 0
        ? uiText(`知识库检索完成，找到 ${count} 个相关片段。`, `Knowledge search complete; ${count} relevant chunks found.`)
        : uiText('知识库检索完成，未找到明显相关片段。', 'Knowledge search complete; no clearly relevant chunks found.');
    case 'search_web':
      return count > 0
        ? uiText(`联网搜索完成，获取 ${count} 条候选结果。`, `Web search complete; ${count} candidate results found.`)
        : uiText('联网搜索完成，未获取到可靠结果。', 'Web search complete; no reliable results found.');
    case 'recommend_literature':
    case 'literature_search_result':
      return count > 0
        ? uiText(`文献检索完成，找到 ${count} 条候选文献。`, `Literature search complete; ${count} candidate papers found.`)
        : uiText('文献检索完成，暂未找到候选文献。', 'Literature search complete; no candidate papers found.');
    case 'literature_search_start':
      return uiText('文献检索任务已创建。', 'Literature-search task created.');
    case 'literature_search_status':
    case 'paper_polish_status':
      return formatTaskStatus(payload) || uiText('后台任务状态已更新。', 'Background-task status updated.');
    case 'literature_search_cancel':
    case 'paper_task_cancel':
      return uiText('后台任务已取消。', 'Background task cancelled.');
    case 'paper_polish_result':
      return uiText('论文润色结果已读取。', 'Paper-polish results loaded.');
    default:
      return count > 0
        ? uiText(`工具调用完成，获取 ${count} 条结果。`, `Tool completed with ${count} results.`)
        : uiText('工具调用完成。', 'Tool completed.');
  }
}

function parseToolCalls(value?: string | null): ToolCallSnapshot[] {
  if (!value) {
    return [];
  }
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function parseJsonObject(value?: string | null): Record<string, any> | null {
  if (!value) {
    return null;
  }
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function extractArtifactFromToolResult(toolName?: string | null, payload?: Record<string, any> | null): ChatArtifactCard | null {
  if (toolName !== 'write_document' || !payload || payload.success === false) {
    return null;
  }
  const artifactId = Number(payload.artifactId);
  if (!Number.isFinite(artifactId) || artifactId <= 0) {
    return null;
  }
  return {
    id: artifactId,
    title: String(payload.title || payload.downloadFilename || `Artifact #${artifactId}`),
    artifactType: String(payload.artifactType || 'MARKDOWN'),
    downloadUrl: String(payload.downloadUrl || `/api/v1/artifacts/${artifactId}/download`),
    downloadFilename: String(payload.downloadFilename || `artifact-${artifactId}.md`),
    downloadContentType: String(payload.downloadContentType || 'text/markdown;charset=UTF-8'),
    preview: typeof payload.preview === 'string' ? payload.preview : null,
  };
}

function extractResultCount(payload?: Record<string, any> | null) {
  if (!payload) {
    return 0;
  }
  const direct = payload.resultCount ?? payload.count ?? payload.total ?? payload.totalCount;
  if (typeof direct === 'number' && Number.isFinite(direct)) {
    return Math.max(0, direct);
  }
  for (const key of ['items', 'results', 'chunks', 'documents', 'papers', 'citations']) {
    const value = payload[key];
    if (Array.isArray(value)) {
      return value.length;
    }
  }
  return 0;
}

function formatTaskStatus(payload?: Record<string, any> | null) {
  if (!payload) {
    return '';
  }
  const rawStatus = String(payload.status || payload.state || '').toLowerCase();
  if (!rawStatus) {
    return '';
  }
  if (['completed', 'success', 'done', 'finished'].includes(rawStatus)) {
    return uiText('后台任务已完成。', 'Background task completed.');
  }
  if (['failed', 'error'].includes(rawStatus)) {
    return uiText('后台任务执行失败。', 'Background task failed.');
  }
  if (['cancelled', 'canceled'].includes(rawStatus)) {
    return uiText('后台任务已取消。', 'Background task cancelled.');
  }
  return uiText('后台任务仍在处理中。', 'Background task is still processing.');
}

function isAssistantToolCallMessage(message: ChatMessageView) {
  return message.role === 'assistant'
    && Boolean(message.toolCallsJson);
}

function hasProcessMessageAfterLatestUser(sessionId: number) {
  const sessionMessages = messagesBySessionId.value[sessionId] || [];
  const latestUserIndex = sessionMessages.map((message) => message.role).lastIndexOf('user');
  if (latestUserIndex < 0) {
    return false;
  }
  return sessionMessages.slice(latestUserIndex + 1).some((message) => message.role === 'process');
}

function normalizeRole(role: string): MessageRole {
  switch ((role || '').toLowerCase()) {
    case 'assistant':
      return 'assistant';
    case 'system':
      return 'system';
    case 'tool':
      return 'tool';
    case 'process':
      return 'process';
    default:
      return 'user';
  }
}

function getMessageSegments(content: string): MessageSegment[] {
  const source = content || '';
  const fence = String.fromCharCode(96) + String.fromCharCode(96) + String.fromCharCode(96);
  const regex = new RegExp(fence + '([\\\\w-]+)?\\\\n([\\\\s\\\\S]*?)' + fence, 'g');
  const segments: MessageSegment[] = [];
  let lastIndex = 0;
  let matched: RegExpExecArray | null;

  while ((matched = regex.exec(source)) !== null) {
    const [fullMatch, , codeContent] = matched;
    const start = matched.index;
    if (start > lastIndex) {
      const text = source.slice(lastIndex, start).trim();
      if (text) {
        segments.push({ type: 'text', content: text });
      }
    }
    segments.push({ type: 'code', content: codeContent.trimEnd() });
    lastIndex = start + fullMatch.length;
  }

  if (lastIndex < source.length) {
    const text = source.slice(lastIndex).trim();
    if (text) {
      segments.push({ type: 'text', content: text });
    }
  }

  return segments.length > 0 ? segments : [{ type: 'text', content: source }];
}

function processSummaryLabel(message: ChatMessageView) {
  if (!message.processDone) {
    return uiText('处理中', 'Processing');
  }
  return message.processElapsedMs != null
    ? uiText('已处理 ', 'Processed ') + formatProcessElapsed(message.processElapsedMs)
    : uiText('已处理', 'Processed');
}

function formatProcessElapsed(value: number) {
  const totalSeconds = Math.max(0, Math.round(value / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes <= 0) {
    return seconds + 's';
  }
  return minutes + 'm' + String(seconds).padStart(2, '0') + 's';
}

function elapsedBetween(start?: string | null, end?: string | null) {
  if (!start || !end) {
    return null;
  }
  const startTime = new Date(start).getTime();
  const endTime = new Date(end).getTime();
  if (!Number.isFinite(startTime) || !Number.isFinite(endTime) || endTime < startTime) {
    return null;
  }
  return Math.max(0, endTime - startTime);
}

function extractNavigationUrl(content?: string | null) {
  const source = content || '';
  const matched = source.match(/\/paper(?:\?[^\s]+)?/);
  return matched?.[0] || null;
}

function toModelKey(provider: string, model: string) {
  return (provider || 'deepseek') + '::' + (model || DEFAULT_DEEPSEEK_MODEL);
}

function parseModelKey(key: string) {
  const [provider, ...modelParts] = (key || '').split('::');
  const fallback = defaultModelKeyFromSettings(settings.value);
  if (!provider || modelParts.length === 0) {
    return parseModelKey(fallback === key ? toModelKey('deepseek', DEFAULT_DEEPSEEK_MODEL) : fallback);
  }
  return {
    provider,
    model: normalizeModelName(provider, modelParts.join('::') || DEFAULT_DEEPSEEK_MODEL),
  };
}

function defaultModelKeyFromSettings(currentSettings: UserSettingsResponse | null) {
  const provider = currentSettings?.defaultProvider || 'deepseek';
  const customModel = currentSettings?.customModels?.find((item) => item.providerKey === provider);
  const model = customModel?.modelName || (provider === 'glm'
    ? (currentSettings?.glmModel || DEFAULT_GLM_MODEL)
    : (currentSettings?.deepseekModel || DEFAULT_DEEPSEEK_MODEL));
  return toModelKey(provider, normalizeModelName(provider, model));
}

function isSupportedDeepseekModel(model: string) {
  return SUPPORTED_DEEPSEEK_MODELS.has(model);
}

function normalizeModelName(provider: string, model: string) {
  if (provider.toLowerCase() === 'deepseek' && !isSupportedDeepseekModel(model)) {
    return DEFAULT_DEEPSEEK_MODEL;
  }
  return model;
}

function formatProviderName(provider: string) {
  const lower = (provider || '').toLowerCase();
  if (lower === 'glm') return 'GLM';
  if (lower === 'deepseek') return 'DeepSeek';
  return provider;
}

function formatSessionUpdatedAt(value: string) {
  const updatedAt = new Date(value);
  if (Number.isNaN(updatedAt.getTime())) {
    return uiText('未知', 'Unknown');
  }
  const diffMs = Date.now() - updatedAt.getTime();
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diffMs < minute) {
    return uiText('刚刚', 'Just now');
  }
  if (diffMs < hour) {
    const count = Math.floor(diffMs / minute);
    return isEnglish.value ? `${count} minute${count === 1 ? '' : 's'} ago` : `${count} 分钟前`;
  }
  if (diffMs < day) {
    const count = Math.floor(diffMs / hour);
    return isEnglish.value ? `${count} hour${count === 1 ? '' : 's'} ago` : `${count} 小时前`;
  }
  if (diffMs < 7 * day) {
    const count = Math.floor(diffMs / day);
    return isEnglish.value ? `${count} day${count === 1 ? '' : 's'} ago` : `${count} 天前`;
  }
  return updatedAt.toLocaleDateString();
}

function goToNavigation(navigationUrl: string) {
  void router.push(navigationUrl);
}
</script>
