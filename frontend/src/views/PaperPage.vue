<template>
  <AppLayout>
    <div class="paper-page paper-page--redesign workbench-page scholar-page scholar-page--paper">
      <WorkspaceHero
        :kicker="t('paper.kicker')"
        :title="t('paper.title')"
        :subtitle="t('paper.subtitle')"
        storage-key="yanban.hero.paper"
      >
        <template #actions>
          <NSpace align="center">
            <NButton secondary @click="startNewPaperTask">{{ t('paper.newPolish') }}</NButton>
            <NButton secondary :loading="historyLoading" @click="refreshTaskBoard">{{ t('paper.refreshTasks') }}</NButton>
            <NButton type="primary" :loading="submitting" @click="handleSubmit">{{ t('paper.runWorkflow') }}</NButton>
          </NSpace>
        </template>
      </WorkspaceHero>

      <div class="paper-hero-panel">
        <div class="paper-task-summary">
          <span class="paper-task-summary__file">TEX</span>
          <div>
            <strong>{{ currentTask?.sourceFilename || selectedTexFile?.name || t('paper.chooseTex') }}</strong>
            <div class="paper-task-summary__status">
              <NTag :type="currentTask ? statusTagType(currentTask.status) : 'default'" size="small" round>
                {{ currentTask ? taskStatusLabel(currentTask.status) : t('paper.noTask') }}
              </NTag>
              <span v-if="currentTask">#{{ currentTask.id }}</span>
            </div>
          </div>
        </div>
        <div class="paper-hero-progress">
          <div>
            <span>{{ t('paper.stage') }}</span>
            <strong>{{ currentStageLabel }}</strong>
          </div>
          <strong>{{ progressPercent }}%</strong>
          <NProgress type="line" :percentage="progressPercent" :show-indicator="false" status="success" />
        </div>
        <div v-if="currentTask" class="paper-hero-actions" role="group" :aria-label="isEnglish ? 'Task controls' : '任务控制'">
          <NButton size="small" tertiary :disabled="!canPause" @click="handlePause">{{ isEnglish ? 'Pause' : '暂停' }}</NButton>
          <NButton size="small" tertiary :disabled="!canResume" @click="handleResume">{{ isEnglish ? 'Resume' : '继续' }}</NButton>
          <NButton size="small" tertiary type="error" :disabled="!canStop" @click="handleStop">{{ isEnglish ? 'Stop' : '停止' }}</NButton>
          <NDropdown trigger="click" :options="paperTaskUtilityOptions" @select="handlePaperTaskUtilitySelect">
            <NButton size="small" secondary>{{ isEnglish ? 'More' : '更多' }}</NButton>
          </NDropdown>
        </div>
      </div>

      <section v-if="currentTask?.errorMessage || currentTask?.status === 'WAITING_INPUT' || pendingClarifications.length > 0" class="paper-task-notices" aria-live="polite">
        <NAlert v-if="currentTask?.errorMessage" type="error" title="Task error">
          {{ currentTask.errorMessage }}
        </NAlert>
        <NAlert v-if="currentTask?.status === 'WAITING_INPUT' || pendingClarifications.length > 0" type="warning" title="结构确认待处理">
          {{ clarificationsLoading
            ? '正在读取结构确认内容，请稍候。'
            : '当前任务需要你确认论文结构。请提交选择，任务会继续后台执行。' }}
        </NAlert>
        <div v-if="needsStructureConfirmation" class="paper-structure-confirmation-callout">
          <div>
            <strong>需要确认论文结构</strong>
            <span v-if="clarificationsLoading">正在准备确认项…</span>
            <span v-else-if="clarificationsError">确认项加载失败，可重新读取。</span>
            <span v-else>{{ pendingClarifications.length }} 个确认项等待处理，提交后任务会继续执行。</span>
          </div>
          <NButton
            v-if="clarificationsError"
            type="warning"
            secondary
            :loading="clarificationsLoading"
            @click="retryClarifications"
          >重新读取</NButton>
          <NButton v-else type="warning" secondary :disabled="!structureConfirmationReady" @click="focusStructureConfirmation">去确认</NButton>
        </div>
      </section>

      <section v-if="activeTaskCards.length > 0" class="paper-task-board">
        <div class="paper-task-board__head">
          <div>
            <div class="section-title">{{ t('paper.runningTasks') }}</div>
            <p>{{ t('paper.runningTasksBody') }}</p>
          </div>
          <NButton size="small" secondary :loading="historyLoading" @click="refreshTaskBoard">{{ t('paper.refreshTasks') }}</NButton>
        </div>

        <div class="paper-running-tasks">
          <article
            v-for="task in activeTaskCards"
            :key="task.id"
            class="paper-running-task"
            :class="{
              'paper-running-task--active': task.id === currentTaskId,
              'paper-running-task--waiting': task.status === 'WAITING_INPUT',
            }"
          >
            <button type="button" @click="openHistoryTask(task.id)">
              <span>{{ taskCardTitle(task) }}</span>
              <strong>{{ task.status === 'WAITING_INPUT' ? t('paper.waitingStructure') : taskStatusLabel(task.status) }}</strong>
            </button>
            <div class="paper-running-task__meta">
              <span>{{ taskStageLabel(task) }}</span>
              <small>{{ formatDateTime(task.updatedAt) }}</small>
            </div>
            <div class="paper-running-task__flow" :aria-label="t('paper.workflowAria')">
              <span
                v-for="step in workflowSteps"
                :key="step.stage"
                :class="taskFlowStepClass(task, step.stage)"
                :title="step.label"
              >
                {{ step.shortLabel }}
              </span>
            </div>
          </article>
        </div>
      </section>

      <div class="paper-polish-shell">
        <main class="paper-polish-main">
          <NGrid :cols="24" :x-gap="14" :y-gap="14" responsive="screen" item-responsive>
            <NGridItem span="24">
              <NCard class="workbench-card scholar-card paper-polish-card paper-input-card" :bordered="false">
                <template #header>
                  <div class="section-title">{{ t('paper.inputManuscript') }}</div>
                </template>
                <input ref="texInputRef" type="file" accept=".tex" class="kb-file-input" @change="handleTexFileChange" />
                <input ref="bibInputRef" type="file" accept=".bib" class="kb-file-input" @change="handleBibFileChange" />

                <div class="paper-polish-dropzone" @click="texInputRef?.click()">
                  <div class="paper-file-chip">TEX</div>
                  <div>
                    <strong>{{ selectedTexFile?.name || currentTask?.sourceFilename || t('paper.chooseTex') }}</strong>
                    <span>
                      {{ selectedTexFile ? formatFileSize(selectedTexFile.size) : (currentTask?.sourceFilename ? `${isEnglish ? 'Task' : '任务'} #${currentTask.id}` : t('paper.dropTex')) }}
                    </span>
                  </div>
                  <NTag :type="selectedTexFile || currentTask ? 'success' : 'default'" round>
                    {{ selectedTexFile || currentTask ? t('paper.ready') : t('paper.required') }}
                  </NTag>
                </div>

                <div class="paper-input-actions">
                  <NButton v-if="currentTask" secondary @click="startNewPaperTask">{{ t('paper.startNew') }}</NButton>
                  <NButton secondary @click="texInputRef?.click()">{{ t('paper.uploadNew') }}</NButton>
                  <NButton secondary @click="bibInputRef?.click()">
                    {{ selectedBibFile ? selectedBibFile.name : t('paper.attachBib') }}
                  </NButton>
                </div>

                <section class="paper-sample-panel" :aria-label="t('paper.sampleTitle')">
                  <div class="paper-sample-panel__actions">
                    <NButton type="primary" secondary :loading="sampleLoading" @click="loadPaperSample">
                      {{ sampleLoading ? t('paper.loadingSample') : t('paper.loadSample') }}
                    </NButton>
                    <NButton tag="a" secondary href="/samples/scholarai-paper-sample.tex" download="scholarai-paper-sample.tex">
                      {{ t('paper.downloadTex') }}
                    </NButton>
                    <NButton tag="a" secondary href="/samples/scholarai-paper-sample.bib" download="scholarai-paper-sample.bib">
                      {{ t('paper.downloadBib') }}
                    </NButton>
                  </div>
                </section>

                <details class="paper-config-grid">
                  <summary>{{ isEnglish ? 'Polish parameters' : '润色参数' }}</summary>
                  <div class="paper-config-grid__body">
                  <NForm :model="form" label-placement="top">
                    <NGrid :cols="24" :x-gap="12" responsive="screen" item-responsive>
                      <NFormItemGi span="24 m:8" :label="t('paper.targetLanguage')">
                        <NSelect v-model:value="form.targetLanguage" :options="languageOptions" />
                      </NFormItemGi>
                      <NFormItemGi span="24 m:8" :label="t('paper.scoreThreshold')">
                        <NInputNumber v-model:value="form.scoreThreshold" :min="0" :max="100" style="width: 100%" />
                      </NFormItemGi>
                      <NFormItemGi span="24 m:8" :label="t('paper.maxRounds')">
                        <NInputNumber v-model:value="form.maxRounds" :min="1" :max="20" style="width: 100%" />
                      </NFormItemGi>
                      <NFormItemGi span="24 m:8" :label="t('paper.repairsPerRound')">
                        <NInputNumber v-model:value="form.innerMaxAttempts" :min="1" :max="20" style="width: 100%" />
                      </NFormItemGi>
                      <NFormItemGi span="24 m:8" :label="t('paper.minLiterature')">
                        <NInputNumber v-model:value="form.literatureMinCount" :min="1" :max="form.literatureCount" style="width: 100%" />
                      </NFormItemGi>
                      <NFormItemGi span="24 m:8" :label="t('paper.maxLiterature')">
                        <NInputNumber v-model:value="form.literatureCount" :min="form.literatureMinCount" :max="100" style="width: 100%" />
                      </NFormItemGi>
                    </NGrid>
                  </NForm>
                  </div>
                </details>
              </NCard>
            </NGridItem>

          </NGrid>

          <details v-if="currentTask" class="workbench-card scholar-card paper-polish-card paper-workflow-card-v2">
            <summary>
              <div>
                <div class="section-title">Workflow</div>
                <small>{{ currentStageLabel }} · {{ progressPercent }}%</small>
              </div>
              <span>{{ isEnglish ? 'View 9 stages' : '查看 9 个阶段' }}</span>
            </summary>
            <div class="paper-workflow-card-v2__body">
              <div class="paper-workflow-context">
                <span>{{ isEnglish ? 'Task' : '任务' }} #{{ currentTask.id }}</span>
                <span>{{ isEnglish ? 'Target' : '目标语言' }} {{ currentTask.targetLanguage || form.targetLanguage }}</span>
                <span>{{ isEnglish ? 'Sections' : '章节' }} {{ sectionProgressText }}</span>
                <span>{{ isEnglish ? 'Attempts' : '尝试' }} {{ attemptProgressText }}</span>
                <span>{{ isEnglish ? 'Artifacts' : '产物' }} {{ artifacts.length }}</span>
              </div>
              <div class="paper-workflow-v2">
                <div v-for="step in workflowSteps" :key="step.stage" class="paper-workflow-step-v2" :class="workflowStepClass(step.stage)">
                  <span>{{ step.shortLabel }}</span>
                  <strong>{{ step.label }}</strong>
                  <small>{{ workflowStepDetail(step.stage) }}</small>
                </div>
              </div>
              <div class="paper-workflow-activity" role="status" aria-live="polite">
                <strong>{{ workflowActivityTitle }}</strong>
                <span>{{ workflowActivityDetail }}</span>
              </div>
            </div>
          </details>

          <NCard
            v-if="currentTask"
            class="workbench-card scholar-card paper-polish-card"
            :class="{ 'paper-polish-card--collapsed': revisionSuggestionsCollapsed }"
            :bordered="false"
          >
            <template #header>
              <div class="section-title">Revision Suggestions</div>
            </template>
            <template #header-extra>
              <NSpace size="small" align="center">
                <NTag type="info" round>{{ suggestions.length }} total</NTag>
                <NButton
                  class="paper-card-collapse-button"
                  quaternary
                  circle
                  size="small"
                  :title="revisionSuggestionsCollapsed ? 'Expand revision suggestions' : 'Collapse revision suggestions'"
                  :aria-label="revisionSuggestionsCollapsed ? 'Expand revision suggestions' : 'Collapse revision suggestions'"
                  :aria-expanded="!revisionSuggestionsCollapsed"
                  @click="revisionSuggestionsCollapsed = !revisionSuggestionsCollapsed"
                >
                  <span aria-hidden="true">{{ revisionSuggestionsCollapsed ? '▾' : '▴' }}</span>
                </NButton>
              </NSpace>
            </template>

            <NEmpty v-if="!revisionSuggestionsCollapsed && suggestions.length === 0" description="No revision suggestions yet. Run a workflow or open a completed task." />
            <div v-else-if="!revisionSuggestionsCollapsed" class="paper-suggestion-table-v2">
              <div class="paper-suggestion-table-v2__head">
                <span>Suggestion</span>
                <span>Evidence</span>
                <span>Severity</span>
                <span>State</span>
              </div>
              <article
                v-for="suggestion in visibleSuggestions"
                :key="suggestion.id"
                class="paper-suggestion-row-v2"
                :class="{ 'paper-suggestion-row-v2--selected': suggestion.id === selectedSuggestionId }"
                role="button"
                tabindex="0"
                @click="selectPaperSuggestion(suggestion.id)"
                @keydown.enter.prevent="selectPaperSuggestion(suggestion.id)"
              >
                <div>
                  <strong>{{ suggestion.category }}</strong>
                  <p>{{ suggestionCustomerText(suggestion.statement) }}</p>
                  <small v-if="suggestionDecisionReason(suggestion)" class="paper-suggestion-decision">
                    {{ suggestionDecisionReason(suggestion) }}
                  </small>
                </div>
                <span>{{ isEnglish ? `${suggestion.evidenceCount} supporting papers` : `${suggestion.evidenceCount} 篇支撑文献` }}</span>
                <NTag :type="suggestion.severity === 'HIGH' ? 'error' : suggestion.severity === 'LOW' ? 'success' : 'warning'" size="small">
                  {{ suggestion.severity || 'Info' }}
                </NTag>
                <NTag :type="suggestionStateType(suggestion)" size="small">
                  {{ suggestionStateLabel(suggestion) }}
                </NTag>
              </article>
            </div>
          </NCard>

        </main>

        <aside class="paper-polish-side">
          <NCard class="workbench-card scholar-card paper-polish-card paper-history-card-v2" :bordered="false">
            <template #header>
              <div class="paper-history-card-v2__head">
                <div>
                  <div class="section-title">{{ isEnglish ? 'Polish tasks' : '润色任务' }}</div>
                  <small>{{ isEnglish ? 'Open an earlier task without starting a new workflow.' : '无需新建任务即可打开历史润色记录。' }}</small>
                </div>
                <NButton size="small" secondary :loading="historyLoading" @click="loadHistory">{{ isEnglish ? 'Refresh' : '刷新' }}</NButton>
              </div>
            </template>
            <NSpin v-if="historyLoading && historyTasks.length === 0" size="small" />
            <div v-else class="paper-history-compact-v2">
              <article v-for="task in historyTasks" :key="task.id" :class="{ 'paper-history-compact-v2--active': task.id === currentTaskId }">
                <div class="paper-history-compact-v2__primary">
                  <button
                    type="button"
                    :title="task.title || task.sourceFilename || `Task ${task.id}`"
                    :aria-label="task.title || task.sourceFilename || `Task ${task.id}`"
                    @click="openHistoryTask(task.id)"
                  >{{ task.title || task.sourceFilename || `Task ${task.id}` }}</button>
                  <NTag size="small" :type="statusTagType(task.status)">{{ taskStatusLabel(task.status) }}</NTag>
                </div>
                <div class="paper-history-compact-v2__meta">
                  <span>{{ taskStageLabel(toTaskListItem(task)) }}</span>
                  <small :title="formatDateTime(task.updatedAt)">{{ formatTaskListDate(task.updatedAt) }}</small>
                </div>
                <NButton
                  v-if="historyTaskDownloadable(task)"
                  size="tiny"
                  tertiary
                  :title="isEnglish ? 'Download result' : '下载结果'"
                  :loading="downloadingTaskId === task.id"
                  @click.stop="downloadHistoryTask(task.id)"
                >
                  {{ isEnglish ? 'Download' : '下载' }}
                </NButton>
              </article>
              <NEmpty v-if="historyTasks.length === 0" :description="isEnglish ? 'No history yet.' : '暂无历史任务。'" />
            </div>
          </NCard>

          <NCard v-if="currentTask" class="workbench-card scholar-card paper-polish-card paper-side-tabs" :bordered="false">
            <NTabs v-model:value="activeSideTab" type="line" animated>
              <NTabPane name="evidence" :tab="isEnglish ? 'Evidence' : '证据'">
                <div class="paper-support-list-v2">
                  <article v-for="card in selectedSuggestionEvidence" :key="`support-${card.id}`" class="paper-support-card-v2">
                    <div>
                      <strong>{{ card.title }}</strong>
                      <span>{{ formatAuthors(card.authors) }} · {{ card.publicationYear || '-' }}</span>
                      <p>{{ card.venue || (isEnglish ? 'Venue unavailable' : '来源信息暂缺') }}</p>
                    </div>
                    <NTag type="success" size="small">{{ isEnglish ? 'Evidence' : '证据' }}</NTag>
                  </article>
                  <NEmpty v-if="selectedSuggestionEvidence.length === 0" :description="isEnglish ? 'This suggestion has no linked evidence.' : '这条建议没有关联证据。'" />
                </div>
              </NTabPane>
              <NTabPane name="artifacts" :tab="isEnglish ? 'Artifacts' : '产物'">
                <div class="paper-side-tab__action">
                  <NButton size="small" secondary :disabled="!canDownload" :loading="downloading" @click="handleDownload">{{ isEnglish ? 'Download' : '下载结果' }}</NButton>
                </div>
                <div class="paper-artifact-list-v2">
                  <article v-for="artifact in exportArtifacts" :key="artifact.id" class="paper-artifact-row-v2">
                    <span>{{ artifactDisplayName(artifact).slice(0, 2).toUpperCase() }}</span>
                    <div>
                      <strong>{{ artifactDisplayName(artifact) }}</strong>
                      <small>v{{ artifact.version }} · {{ formatDateTime(artifact.createdAt) }}</small>
                    </div>
                  </article>
                  <NEmpty v-if="exportArtifacts.length === 0" :description="isEnglish ? 'No export artifacts yet.' : '暂无导出产物。'" />
                </div>
              </NTabPane>
              <NTabPane name="events" :tab="isEnglish ? 'Events' : '事件'">
                <div class="paper-side-tab__status">SSE {{ sseStatusText }}</div>
                <div class="paper-live-events-v2">
                  <article v-for="(event, index) in liveTaskEvents" :key="`${event.timestamp}-${index}`">
                    <i />
                    <span>{{ formatDateTime(event.timestamp) }}</span>
                    <strong>{{ event.message }}</strong>
                  </article>
                  <NEmpty v-if="liveTaskEvents.length === 0" :description="isEnglish ? 'No live events yet.' : '暂无任务事件。'" />
                </div>
              </NTabPane>
            </NTabs>
          </NCard>
        </aside>
      </div>

      <NCard v-if="currentTask" id="paper-structure-confirmation" class="workbench-card scholar-card paper-polish-card paper-review-workspace-v2" :bordered="false">
        <template #header>
          <div class="section-title">Review Workspace</div>
        </template>
        <NTabs v-model:value="activeReviewTab" type="segment" animated>
          <NTabPane name="clarification" :tab="pendingClarifications.length ? `Clarifications (${pendingClarifications.length})` : 'Clarifications'">
            <NSpin v-if="clarificationsLoading" size="small" description="Loading structure confirmation items…" />
            <NAlert v-else-if="clarificationsError" type="error" title="Structure confirmation unavailable">
              <NSpace vertical size="small">
                <span>{{ clarificationsError }}</span>
                <NButton size="small" secondary @click="retryClarifications">Retry</NButton>
              </NSpace>
            </NAlert>
            <div v-else-if="clarifications.length > 0" class="paper-clarification-panel">
              <NAlert v-if="pendingBlockingClarifications.length > 0" type="warning" title="Input required">
                Some structure questions require confirmation before the workflow continues.
              </NAlert>
              <div v-for="item in clarifications" :key="item.id" class="paper-clarification-item">
                <div class="paper-clarification-item__title">
                  <span>{{ clarificationQuestion(item).message || item.type }}</span>
                  <NTag size="small" :type="item.status === 'PENDING' ? 'warning' : 'success'">{{ item.status }}</NTag>
                </div>
                <NRadioGroup v-if="item.status === 'PENDING'" v-model:value="clarificationAnswers[item.id]" class="paper-option-group">
                  <NSpace vertical size="small">
                    <NRadio v-for="option in clarificationOptions(item).options" :key="option" :value="option">{{ option }}</NRadio>
                  </NSpace>
                </NRadioGroup>
                <div v-else class="paper-clarification-item__answer">{{ answeredOption(item) }}</div>
                <NSpace v-if="item.status === 'PENDING'" size="small">
                  <NButton size="small" type="primary" :loading="clarificationSubmitting" @click="submitClarification(item)">Submit</NButton>
                  <NButton v-if="!clarificationQuestion(item).blocking" size="small" tertiary :loading="clarificationSubmitting" @click="skipClarification(item)">Skip</NButton>
                </NSpace>
              </div>
              <NButton v-if="pendingClarifications.length > 0" secondary block :loading="clarificationSubmitting" @click="keepAllClarifications">
                Keep all defaults
              </NButton>
            </div>
            <NEmpty v-else description="No clarifications yet." />
          </NTabPane>

          <NTabPane name="sections" :tab="`Sections (${sections.length})`">
            <div v-if="sections.length > 0" class="paper-section-role-panel">
              <div v-for="section in sections" :key="section.id" class="paper-section-role-row">
                <div>
                  <strong>{{ section.orderIndex + 1 }}. {{ section.title }}</strong>
                  <small>{{ section.roleSource || 'auto' }} · confidence {{ formatConfidence(section.roleConfidence) }}</small>
                </div>
                <NSelect :value="section.role" :options="sectionRoleOptions" size="small" @update:value="(role) => handleSectionRoleChange(section.id, role)" />
              </div>
            </div>
            <NEmpty v-else description="No section roles yet." />
          </NTabPane>

          <NTabPane name="preview" :tab="`Preview (${previewSections.length + suggestions.length})`">
            <div class="paper-preview-panel">
              <div class="paper-preview-toolbar">
                <NButtonGroup>
                  <NButton :type="previewMode === 'basic' ? 'primary' : 'default'" size="small" @click="previewMode = 'basic'">Recommendations</NButton>
                  <NButton :type="previewMode === 'advanced' ? 'primary' : 'default'" size="small" @click="previewMode = 'advanced'">Diff + cite patch</NButton>
                </NButtonGroup>
                <NSpace v-if="reviewableSections.length > 0" size="small" align="center">
                  <NCheckbox :checked="allReviewableSectionsSelected" :indeterminate="someReviewableSectionsSelected" @update:checked="toggleAllRevisionSections">
                    {{ isEnglish ? 'Select all' : '全选' }}
                  </NCheckbox>
                  <NButton size="small" type="primary" :loading="batchRevisionSubmitting" :disabled="selectedRevisionSectionIds.length === 0" @click="acceptSelectedRevisions">
                    {{ isEnglish ? `Accept selected (${selectedRevisionSectionIds.length})` : `批量采纳（${selectedRevisionSectionIds.length}）` }}
                  </NButton>
                </NSpace>
              </div>
              <div v-for="section in previewSections" :key="`preview-${section.id}`" class="paper-diff-card">
                <div class="paper-diff-card__title">
                  <NCheckbox
                    v-if="isReviewableSection(section)"
                    :checked="selectedRevisionSectionIds.includes(section.id)"
                    :disabled="batchRevisionSubmitting"
                    @update:checked="(checked) => toggleRevisionSection(section.id, checked)"
                  />
                  <span>{{ section.orderIndex + 1 }}. {{ section.title }} · {{ section.polishStatus || 'NOT_POLISHED' }} · {{ section.revisionStatus || 'PENDING' }}</span>
                  <NSpace v-if="isReviewableSection(section)" size="small">
                    <NButton size="tiny" secondary type="success" :loading="sectionRevisionSubmitting === section.id" @click="updateSectionRevisionStatus(section, 'ACCEPTED')">{{ isEnglish ? 'Accept' : '采纳' }}</NButton>
                    <NButton size="tiny" secondary type="error" :loading="sectionRevisionSubmitting === section.id" @click="updateSectionRevisionStatus(section, 'REJECTED')">{{ isEnglish ? 'Reject' : '拒绝' }}</NButton>
                  </NSpace>
                </div>
                <pre v-if="section.diffJson" class="paper-code-preview">{{ readableDiff(section.diffJson) }}</pre>
                <pre v-else-if="section.reviewJson" class="paper-code-preview">{{ prettyJson(section.reviewJson) }}</pre>
              </div>
              <div v-for="suggestion in suggestions" :key="suggestion.id" class="paper-suggestion-card" :class="`paper-suggestion-card--${suggestion.honestyGrade}`">
                <strong>{{ suggestion.category }} · {{ suggestion.severity || '-' }}</strong>
                <p>{{ suggestionCustomerText(suggestion.statement) }}</p>
                <small>{{ suggestionCustomerText(suggestion.honestyReason) }} · {{ isEnglish ? `${suggestion.evidenceCount} supporting papers` : `${suggestion.evidenceCount} 篇支撑文献` }} · {{ suggestionStateLabel(suggestion) }}</small>
                <br v-if="suggestionDecisionReason(suggestion)" />
                <small v-if="suggestionDecisionReason(suggestion)" class="paper-suggestion-decision">{{ suggestionDecisionReason(suggestion) }}</small>
                <pre v-if="suggestion.patchJson && previewMode === 'advanced'" class="paper-code-preview">{{ prettyJson(suggestion.patchJson) }}</pre>
              </div>
              <NEmpty v-if="suggestions.length === 0 && previewSections.length === 0" description="No preview results yet." />
            </div>
          </NTabPane>

          <NTabPane name="report" :tab="`Report (${selectedLiteratureCount})`">
            <div class="paper-report-panel">
              <div v-if="citationApplicationSummary" class="paper-citation-summary">
                <div><span>New references</span><strong>{{ citationApplicationSummary.newReferences }}</strong></div>
                <div><span>Existing references reused</span><strong>{{ citationApplicationSummary.reusedReferences }}</strong></div>
                <div><span>Citation locations updated</span><strong>{{ citationApplicationSummary.locationsUpdated }}</strong></div>
                <div><span>Existing locations verified</span><strong>{{ citationApplicationSummary.locationsVerified }}</strong></div>
              </div>
              <div v-for="card in bibliographyCards" :key="`bib-${card.id}`" class="paper-bib-card">
                <strong>{{ card.title }}</strong>
                <small>{{ formatAuthors(card.authors) }} · {{ card.publicationYear || '-' }} · {{ card.venue || '-' }}</small>
                <div class="paper-bib-card__links">
                  <a v-if="card.doi" :href="doiUrl(card.doi)" target="_blank" rel="noreferrer">DOI: {{ card.doi }}</a>
                  <a v-if="card.url" :href="card.url" target="_blank" rel="noreferrer">URL</a>
                  <a v-if="card.pdfUrl" :href="card.pdfUrl" target="_blank" rel="noreferrer">PDF</a>
                </div>
              </div>
              <NEmpty
                v-if="bibliographyCards.length === 0"
                :description="selectedLiteratureCount > 0
                  ? (isEnglish ? `${selectedLiteratureCount} papers selected; details are available in the retrieval artifact.` : `已选中 ${selectedLiteratureCount} 篇，详细信息见检索产物。`)
                  : 'No bibliography report yet.'"
              />
            </div>
          </NTabPane>
        </NTabs>
      </NCard>

      <NModal
        v-model:show="structureConfirmationVisible"
        preset="card"
        title="Structure confirmation"
        class="paper-structure-modal"
        :mask-closable="false"
        :close-on-esc="false"
      >
        <NAlert type="warning" title="Input required">
          Confirm the detected manuscript structure before the full workflow continues.
        </NAlert>
        <div class="paper-structure-modal__items">
          <div v-for="item in pendingClarifications" :key="`modal-${item.id}`" class="paper-structure-modal__item">
            <strong>{{ clarificationQuestion(item).message || item.type }}</strong>
            <NRadioGroup v-model:value="clarificationAnswers[item.id]">
              <NSpace vertical size="small">
                <NRadio v-for="option in clarificationOptions(item).options" :key="option" :value="option">{{ option }}</NRadio>
              </NSpace>
            </NRadioGroup>
            <div class="paper-structure-modal__actions">
              <NButton type="primary" size="small" :loading="clarificationSubmitting" @click="submitClarification(item)">Confirm</NButton>
              <NButton size="small" secondary :disabled="clarificationSubmitting" @click="skipClarification(item)">Keep detected structure</NButton>
            </div>
          </div>
        </div>
        <template #footer>
          <NButton v-if="pendingClarifications.length > 1" type="primary" secondary block :loading="clarificationSubmitting" @click="keepAllClarifications">
            Confirm all detected structure
          </NButton>
        </template>
      </NModal>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import {
  NAlert,
  NButton,
  NButtonGroup,
  NCard,
  NCheckbox,
  NDropdown,
  NEmpty,
  NForm,
  NFormItemGi,
  NGrid,
  NGridItem,
  NInputNumber,
  NModal,
  NProgress,
  NRadio,
  NRadioGroup,
  NSelect,
  NSpace,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
} from 'naive-ui';
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppLayout from '@/components/AppLayout.vue';
import WorkspaceHero from '@/components/WorkspaceHero.vue';
import {
  answerPaperClarification,
  createPaperTask,
  downloadPaperTask,
  getPaperAnalysis,
  getPaperArtifacts,
  getPaperClarifications,
  getPaperSections,
  getPaperSuggestions,
  getPaperTask,
  getPaperTasks,
  pausePaperTask,
  resumePaperTask,
  updatePaperSectionRevisionStatus as updatePaperSectionRevisionStatusApi,
  updatePaperSectionRevisionStatuses as updatePaperSectionRevisionStatusesApi,
  updatePaperSectionRole,
  type PaperAnalysisResponse,
  type PaperArtifactResponse,
  type PaperClarificationResponse,
  type PaperSectionResponse,
  type PaperSuggestionResponse,
  type PaperTaskHistoryResponse,
  type PaperTaskResponse,
} from '@/api/paper';
import { cancelTask, getTaskStatus, listTaskEvents, type TaskEventResponse, type TaskStatusResponse } from '@/api/task';
import { ui } from '@/ui';
import { apiErrorMessage } from '@/api/errors';
import { expireAuthSession, isJwtExpired } from '@/auth/session';
import { useI18n } from '@/composables/useI18n';

const route = useRoute();
const router = useRouter();
const { isEnglish, locale, t } = useI18n();
const PAPER_TASK_TYPE = 'paper_polish';
const TERMINAL_TASK_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT']);
const ACTIVE_TASK_STATUSES = new Set(['PENDING', 'RUNNING', 'PAUSED', 'WAITING_INPUT', 'CANCEL_REQUESTED', 'CANCELLING']);
const texInputRef = ref<HTMLInputElement | null>(null);
const bibInputRef = ref<HTMLInputElement | null>(null);
const selectedTexFile = ref<File | null>(null);
const selectedBibFile = ref<File | null>(null);
const submitting = ref(false);
const downloading = ref(false);
const sampleLoading = ref(false);
const currentTask = ref<PaperTaskResponse | null>(null);
const events = ref<PaperTimelineEvent[]>([]);
const clarifications = ref<PaperClarificationResponse[]>([]);
const sections = ref<PaperSectionResponse[]>([]);
const suggestions = ref<PaperSuggestionResponse[]>([]);
const artifacts = ref<PaperArtifactResponse[]>([]);
const analysis = ref<PaperAnalysisResponse | null>(null);
const historyTasks = ref<PaperTaskHistoryResponse[]>([]);
const historyLoading = ref(false);
const downloadingTaskId = ref<number | null>(null);
const previewMode = ref<'basic' | 'advanced'>('basic');
const revisionSuggestionsCollapsed = ref(false);
const activeSideTab = ref('evidence');
const selectedSuggestionId = ref<number | null>(null);
const activeReviewTab = ref('clarification');
const sectionRevisionSubmitting = ref<number | null>(null);
const batchRevisionSubmitting = ref(false);
const selectedRevisionSectionIds = ref<number[]>([]);
const knownReviewableSectionIds = ref<Set<number>>(new Set());
const revisionSelectionTaskId = ref<number | null>(null);
const clarificationAnswers = reactive<Record<number, string>>({});
const clarificationSubmitting = ref(false);
const clarificationsLoading = ref(false);
const clarificationsError = ref('');
const structureConfirmationVisible = ref(false);
const sseStatus = ref<'idle' | 'connecting' | 'connected' | 'closed' | 'error'>('idle');
const taskStatus = ref<TaskStatusResponse | null>(null);
const lastTaskEventId = ref<number | null>(null);
let abortController: AbortController | null = null;
let taskBoardRefreshTimer: number | null = null;
let clarificationRequestSequence = 0;

const form = reactive({
  targetLanguage: 'zh' as 'zh' | 'en',
  scoreThreshold: 75,
  maxRounds: 3,
  innerMaxAttempts: 2,
  literatureMinCount: 8,
  literatureCount: 20,
});

const languageOptions = computed(() => [
  { label: isEnglish.value ? 'Chinese' : '中文', value: 'zh' },
  { label: 'English', value: 'en' },
]);

const sectionRoleOptions = computed(() => [
  { label: isEnglish.value ? 'Abstract' : '摘要', value: 'ABSTRACT' },
  { label: isEnglish.value ? 'Introduction' : '引言', value: 'INTRO' },
  { label: isEnglish.value ? 'Related Work' : '相关工作', value: 'RELATED_WORK' },
  { label: isEnglish.value ? 'Method' : '方法', value: 'METHOD' },
  { label: isEnglish.value ? 'Experiments' : '实验', value: 'EXPERIMENTS' },
  { label: isEnglish.value ? 'Results' : '结果', value: 'RESULTS' },
  { label: isEnglish.value ? 'Discussion' : '讨论', value: 'DISCUSSION' },
  { label: isEnglish.value ? 'Conclusion' : '结论', value: 'CONCLUSION' },
  { label: isEnglish.value ? 'References' : '参考文献', value: 'REFERENCES' },
  { label: isEnglish.value ? 'Appendix' : '附录', value: 'APPENDIX' },
  { label: isEnglish.value ? 'Unknown' : '未知', value: 'UNKNOWN' },
]);

const currentTaskId = computed(() => currentTask.value?.id ?? null);
const canPause = computed(() => currentTask.value?.status === 'RUNNING');
const canResume = computed(() => currentTask.value?.status === 'PAUSED');
const canStop = computed(() => {
  if (taskStatus.value) {
    return taskStatus.value.cancellable;
  }
  return ['PENDING', 'RUNNING', 'PAUSED', 'CANCEL_REQUESTED', 'CANCELLING'].includes(currentTask.value?.status || '');
});
const paperTaskUtilityOptions = computed(() => [
  { label: t('paper.refreshTasks'), key: 'refresh' },
  { label: isEnglish.value ? 'Reconnect SSE' : '重连 SSE', key: 'reconnect-sse' },
]);
const downloadableArtifactTypes = ['polished_tex', 'suggested_bib', 'suggested_bib_novel', 'review_report', 'retrieved_literature_json', 'retrieved_literature_md'];
const activeArtifacts = computed(() => artifacts.value.filter((item) => !item.artifactStatus || item.artifactStatus === 'COMPLETED'));
const hasDownloadableArtifacts = computed(() => activeArtifacts.value.some((item) => downloadableArtifactTypes.includes(item.type)));
const canDownload = computed(() => (Boolean(currentTask.value?.finalObjectKey) || hasDownloadableArtifacts.value) && !downloading.value);
const resultFileText = computed(() => {
  const count = activeArtifacts.value.filter((item) => downloadableArtifactTypes.includes(item.type)).length;
  if (count > 0) return isEnglish.value ? `${count} artifacts generated; zip is ready` : `已生成 ${count} 个产物，可下载 zip`;
  if (currentTask.value?.finalObjectKey) return currentTask.value.finalObjectKey;
  return isEnglish.value ? 'Not generated yet' : '尚未生成';
});
const pendingClarifications = computed(() => clarifications.value.filter((item) => item.status === 'PENDING'));
const pendingBlockingClarifications = computed(() => pendingClarifications.value.filter((item) => clarificationQuestion(item).blocking));
const needsStructureConfirmation = computed(() => currentTask.value?.status === 'WAITING_INPUT' || pendingClarifications.value.length > 0);
const structureConfirmationReady = computed(() => !clarificationsLoading.value
  && !clarificationsError.value
  && pendingClarifications.value.length > 0);
const acceptedSuggestions = computed(() => suggestions.value.filter((item) => item.status === 'ACCEPTED'));
const suggestedBibArtifacts = computed(() => activeArtifacts.value.filter((item) => item.type === 'suggested_bib' || item.type === 'suggested_bib_novel'));
const polishedTexArtifacts = computed(() => activeArtifacts.value.filter((item) => item.type === 'polished_tex'));
const retrievedLiteratureArtifacts = computed(() => activeArtifacts.value.filter((item) => item.type === 'retrieved_literature_json' || item.type === 'retrieved_literature_md'));
const previewSections = computed(() => sections.value.filter((section) => section.diffJson || section.reviewJson || section.polishStatus));
const reviewableSections = computed(() => previewSections.value.filter(isReviewableSection));
const allReviewableSectionsSelected = computed(() => reviewableSections.value.length > 0
  && reviewableSections.value.every((section) => selectedRevisionSectionIds.value.includes(section.id)));
const someReviewableSectionsSelected = computed(() => selectedRevisionSectionIds.value.length > 0 && !allReviewableSectionsSelected.value);
const selectedLiteratureCount = computed(() => {
  const artifact = retrievedLiteratureArtifacts.value
    .filter((item) => item.type === 'retrieved_literature_json')
    .sort((left, right) => right.version - left.version)[0];
  if (!artifact?.metadataJson) return bibliographyCards.value.length;
  const metadata = parseJson<Record<string, unknown>>(artifact.metadataJson, {});
  return Number(metadata.selectedCandidateCount ?? bibliographyCards.value.length);
});
const bibliographyCards = computed(() => {
  const seen = new Set<number>();
  return suggestions.value.flatMap((suggestion) => suggestion.evidenceCards || []).filter((card) => {
    if (seen.has(card.id)) return false;
    seen.add(card.id);
    return true;
  });
});
const citationApplicationSummary = computed(() => {
  const artifact = activeArtifacts.value
    .filter((item) => item.type === 'suggested_bib')
    .sort((left, right) => right.version - left.version)[0];
  if (!artifact?.metadataJson) return null;
  const metadata = parseJson<Record<string, unknown>>(artifact.metadataJson, {});
  if (metadata.newReferences == null && metadata.reusedReferences == null && metadata.citationLocationsUpdated == null) return null;
  return {
    newReferences: Number(metadata.newReferences || 0),
    reusedReferences: Number(metadata.reusedReferences || 0),
    locationsUpdated: Number(metadata.citationLocationsUpdated || 0),
    locationsVerified: Number(metadata.existingCitationLocationsVerified || 0),
  };
});
const visibleSuggestions = computed(() => suggestions.value);
const selectedSuggestion = computed(() => suggestions.value.find((item) => item.id === selectedSuggestionId.value) || null);
const selectedSuggestionEvidence = computed(() => selectedSuggestion.value?.evidenceCards || []);
const exportArtifacts = computed(() => activeArtifacts.value.filter((item) => downloadableArtifactTypes.includes(item.type)));
const liveTaskEvents = computed(() => events.value);
const latestProgressEvent = computed(() => [...events.value].reverse().find((event) => hasProgressMeta(event)) || null);
const latestTaskEvent = computed(() => events.value.length > 0 ? events.value[events.value.length - 1] : null);
const workflowSteps = [
  { stage: 'UPLOAD', label: 'Upload', shortLabel: '1' },
  { stage: 'PARSE', label: 'Parse', shortLabel: '2' },
  { stage: 'STRUCTURE_CHECK', label: 'Structure Check', shortLabel: '3' },
  { stage: 'RESEARCH_PROFILE', label: 'Research Profile', shortLabel: '4' },
  { stage: 'RETRIEVE', label: 'Literature Retrieval', shortLabel: '5' },
  { stage: 'GAP_ANALYSIS', label: 'Critique', shortLabel: '6' },
  { stage: 'POLISH', label: 'Section Polish', shortLabel: '7' },
  { stage: 'GLOBAL_REVIEW', label: 'Global Review', shortLabel: '8' },
  { stage: 'ASSEMBLE', label: 'Export', shortLabel: '9' },
] as const;

watch(suggestions, (items) => {
  if (items.some((item) => item.id === selectedSuggestionId.value)) return;
  selectedSuggestionId.value = items.find((item) => item.evidenceCards?.length)?.id || items[0]?.id || null;
}, { deep: true });

function selectPaperSuggestion(suggestionId: number) {
  selectedSuggestionId.value = suggestionId;
  activeSideTab.value = 'evidence';
}

watch([() => currentTaskId.value, () => reviewableSections.value.map((section) => section.id)], ([taskId, ids]) => {
  const nextIds = new Set(ids);
  if (revisionSelectionTaskId.value !== taskId) {
    revisionSelectionTaskId.value = taskId;
    selectedRevisionSectionIds.value = [...ids];
  } else {
    const selected = new Set(selectedRevisionSectionIds.value.filter((id) => nextIds.has(id)));
    ids.forEach((id) => {
      if (!knownReviewableSectionIds.value.has(id)) selected.add(id);
    });
    selectedRevisionSectionIds.value = [...selected];
  }
  knownReviewableSectionIds.value = nextIds;
});

const legacyStageAlias: Record<string, string> = {
  UPLOAD_RECEIVED: 'UPLOAD',
  SUMMARY: 'PARSE',
  SECTIONS: 'POLISH',
  PROFILE: 'RESEARCH_PROFILE',
  PAPER_REVIEW: 'GAP_ANALYSIS',
  ABSTRACT: 'POLISH',
  REFERENCES: 'ASSEMBLE',
  COMPLETE: 'ASSEMBLE',
};
const progressPercent = computed(() => {
  if (currentTask.value?.status === 'COMPLETED') return 100;
  if (currentTask.value?.status === 'FAILED' || currentTask.value?.status === 'STOPPED' || currentTask.value?.status === 'CANCELLED') return latestProgressEvent.value?.progressPercent ?? 0;
  if (latestProgressEvent.value?.progressPercent != null) return clampProgress(latestProgressEvent.value.progressPercent);
  const index = workflowSteps.findIndex((step) => step.stage === normalizedCurrentStage.value);
  return index >= 0 ? Math.round((index / Math.max(workflowSteps.length - 1, 1)) * 100) : 0;
});
const normalizedCurrentStage = computed(() => normalizeStage(latestProgressEvent.value?.stage || currentTask.value?.currentStage || null));
const currentStageLabel = computed(() => stageLabel(normalizedCurrentStage.value));
const workflowActivityTitle = computed(() => currentTask.value?.status === 'WAITING_INPUT'
  ? 'Structure confirmation required'
  : currentStageLabel.value);
const workflowActivityDetail = computed(() => {
  const event = latestProgressEvent.value || latestTaskEvent.value;
  const stage = normalizedCurrentStage.value;
  if (stage === 'RETRIEVE' && event?.currentSection != null && event.totalSections) {
    return `Analyzing literature cards ${event.currentSection}/${event.totalSections}${event.sectionTitle ? ` · ${event.sectionTitle}` : ''}`;
  }
  if (stage === 'POLISH' && event?.currentSection != null && event.totalSections) {
    const attempt = event.attempt != null && event.maxAttempts ? ` · round ${event.attempt}/${event.maxAttempts}` : '';
    return `Polishing section ${event.currentSection}/${event.totalSections}${event.sectionTitle ? ` · ${event.sectionTitle}` : ''}${attempt}`;
  }
  if (stage === 'GLOBAL_REVIEW') return 'Checking cross-section logic, transitions, terminology, notation, and formula explanations.';
  if (currentTask.value?.status === 'WAITING_INPUT') return `${pendingClarifications.value.length || 1} structure item(s) require confirmation before processing continues.`;
  return event?.message || `Current stage: ${currentStageLabel.value}`;
});
const sectionProgressText = computed(() => {
  const event = latestProgressEvent.value;
  if (!event?.currentSection || !event.totalSections) return isEnglish.value ? 'No section progress' : '暂无章节进度';
  const title = event.sectionTitle ? ` · ${event.sectionTitle}` : '';
  return `${event.currentSection}/${event.totalSections}${title}`;
});
const attemptProgressText = computed(() => {
  const event = latestProgressEvent.value;
  if (!event?.attempt || !event.maxAttempts) return isEnglish.value ? 'No attempt data' : '暂无尝试信息';
  return `${event.attempt}/${event.maxAttempts}`;
});
const citationSlots = computed(() => {
  const concept = parseJson<any>(analysis.value?.conceptLadderJson || '{}', {});
  return Array.isArray(concept.citationSlots) ? concept.citationSlots : [];
});
const rawEventLog = computed(() => JSON.stringify(events.value, null, 2));
const activeTaskCards = computed<PaperTaskListItem[]>(() => {
  const items = historyTasks.value
    .filter((task) => ACTIVE_TASK_STATUSES.has(task.status))
    .map((task) => (currentTask.value?.id === task.id ? toTaskListItem(currentTask.value) : toTaskListItem(task)));
  if (currentTask.value && ACTIVE_TASK_STATUSES.has(currentTask.value.status) && !items.some((task) => task.id === currentTask.value?.id)) {
    items.unshift(toTaskListItem(currentTask.value));
  }
  return items.sort((left, right) => {
    const leftWaiting = left.status === 'WAITING_INPUT' ? 1 : 0;
    const rightWaiting = right.status === 'WAITING_INPUT' ? 1 : 0;
    if (leftWaiting !== rightWaiting) return rightWaiting - leftWaiting;
    return new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime();
  });
});
const sseStatusText = computed(() => {
  if (sseStatus.value === 'connecting') return isEnglish.value ? 'Connecting' : '连接中';
  if (sseStatus.value === 'connected') return isEnglish.value ? 'Connected' : '已连接';
  if (sseStatus.value === 'closed') return isEnglish.value ? 'Closed' : '已关闭';
  if (sseStatus.value === 'error') return isEnglish.value ? 'Disconnected' : '异常断开';
  return isEnglish.value ? 'Not connected' : '未连接';
});

function handlePaperTaskUtilitySelect(key: string | number) {
  if (key === 'refresh') {
    void refreshTask();
    return;
  }
  if (key === 'reconnect-sse') {
    void connectSse();
  }
}

onMounted(async () => {
  await loadHistory();
  taskBoardRefreshTimer = window.setInterval(() => {
    void loadHistoryQuietly();
  }, 15000);
  const taskId = Number(route.query.taskId);
  if (!Number.isNaN(taskId) && taskId > 0) {
    await loadTask(taskId, true);
  } else {
    await restoreInitialTaskSelection();
  }
});

watch(
  () => route.query.taskId,
  async (value) => {
    const taskId = Number(value);
    if (!Number.isNaN(taskId) && taskId > 0 && taskId !== currentTaskId.value) {
      resetTaskEvents();
      await loadTask(taskId, true);
    }
  },
);

watch(structureConfirmationReady, (ready) => {
  if (ready) {
    activeReviewTab.value = 'clarification';
    structureConfirmationVisible.value = true;
  } else {
    structureConfirmationVisible.value = false;
  }
}, { immediate: true });

onBeforeUnmount(() => {
  abortController?.abort();
  if (taskBoardRefreshTimer != null) {
    window.clearInterval(taskBoardRefreshTimer);
    taskBoardRefreshTimer = null;
  }
});

async function startNewPaperTask() {
  abortController?.abort();
  abortController = null;
  currentTask.value = null;
  taskStatus.value = null;
  resetTaskEvents();
  clarifications.value = [];
  clarificationsLoading.value = false;
  clarificationsError.value = '';
  clarificationRequestSequence++;
  sections.value = [];
  suggestions.value = [];
  artifacts.value = [];
  analysis.value = null;
  selectedTexFile.value = null;
  selectedBibFile.value = null;
  sseStatus.value = 'idle';
  Object.keys(clarificationAnswers).forEach((key) => {
    delete clarificationAnswers[Number(key)];
  });
  if (texInputRef.value) {
    texInputRef.value.value = '';
  }
  if (bibInputRef.value) {
    bibInputRef.value.value = '';
  }
  await router.replace({ path: '/paper' });
  ui.message.info('Ready to start a new paper polish task.');
}

function handleTexFileChange(event: Event) {
  const target = event.target as HTMLInputElement;
  selectedTexFile.value = target.files?.[0] || null;
}

function handleBibFileChange(event: Event) {
  const target = event.target as HTMLInputElement;
  selectedBibFile.value = target.files?.[0] || null;
}

async function loadPaperSample() {
  sampleLoading.value = true;
  try {
    const [texResponse, bibResponse] = await Promise.all([
      fetch('/samples/scholarai-paper-sample.tex'),
      fetch('/samples/scholarai-paper-sample.bib'),
    ]);
    if (!texResponse.ok || !bibResponse.ok) {
      throw new Error('Sample assets are unavailable');
    }
    const [texBlob, bibBlob] = await Promise.all([texResponse.blob(), bibResponse.blob()]);
    selectedTexFile.value = new File([texBlob], 'scholarai-paper-sample.tex', { type: 'application/x-tex' });
    selectedBibFile.value = new File([bibBlob], 'scholarai-paper-sample.bib', { type: 'application/x-bibtex' });
    ui.message.success(t('paper.sampleLoaded'));
  } catch {
    ui.message.error(t('paper.sampleLoadFailed'));
  } finally {
    sampleLoading.value = false;
  }
}

async function handleSubmit() {
  if (!selectedTexFile.value) {
    ui.message.warning('请先选择 .tex 主文件');
    return;
  }
  submitting.value = true;
  try {
    const formData = new FormData();
    formData.append('mainTex', selectedTexFile.value);
    if (selectedBibFile.value) {
      formData.append('bibFile', selectedBibFile.value);
    }
    formData.append('targetLanguage', form.targetLanguage);
    formData.append('scoreThreshold', String(form.scoreThreshold));
    formData.append('maxRounds', String(form.maxRounds));
    formData.append('innerMaxAttempts', String(form.innerMaxAttempts));
    formData.append('literatureMinCount', String(form.literatureMinCount));
    formData.append('literatureCount', String(form.literatureCount));
    formData.append('literatureOnly', 'false');
    const { data } = await createPaperTask(formData);
    currentTask.value = data;
    resetTaskEvents();
    clarifications.value = [];
    sections.value = [];
    suggestions.value = [];
    artifacts.value = [];
    analysis.value = null;
    selectedTexFile.value = null;
    selectedBibFile.value = null;
    if (texInputRef.value) {
      texInputRef.value.value = '';
    }
    if (bibInputRef.value) {
      bibInputRef.value.value = '';
    }
    await router.replace({ path: '/paper', query: { taskId: String(data.id) } });
    connectSse();
    await loadHistory();
    ui.message.success('论文任务已创建');
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '创建论文任务失败'));
  } finally {
    submitting.value = false;
  }
}

async function loadHistory() {
  historyLoading.value = true;
  try {
    const { data } = await getPaperTasks();
    historyTasks.value = data;
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '加载历史任务失败'));
  } finally {
    historyLoading.value = false;
  }
}

async function loadHistoryQuietly() {
  if (historyLoading.value) return;
  try {
    const { data } = await getPaperTasks();
    historyTasks.value = data;
    const latestCurrentTask = currentTaskId.value == null ? null : data.find((item) => item.id === currentTaskId.value);
    if (latestCurrentTask?.status === 'WAITING_INPUT') {
      await loadTask(latestCurrentTask.id, false);
    }
  } catch {
    // Background refresh should not interrupt the user's current paper review work.
  }
}

async function refreshTaskBoard() {
  await loadHistory();
  if (currentTaskId.value) {
    await loadTask(currentTaskId.value, false);
  } else {
    await restoreInitialTaskSelection();
  }
}

async function restoreInitialTaskSelection() {
  const preferredTask = pickPreferredTask(historyTasks.value.map(toTaskListItem));
  if (!preferredTask) return;
  await router.replace({ path: '/paper', query: { taskId: String(preferredTask.id) } });
  await loadTask(preferredTask.id, ACTIVE_TASK_STATUSES.has(preferredTask.status));
}

async function openHistoryTask(taskId: number) {
  await router.replace({ path: '/paper', query: { taskId: String(taskId) } });
  await loadTask(taskId, ACTIVE_TASK_STATUSES.has(historyTasks.value.find((item) => item.id === taskId)?.status || ''));
}

async function downloadHistoryTask(taskId: number) {
  downloadingTaskId.value = taskId;
  try {
    await downloadTaskById(taskId, historyTasks.value.find((item) => item.id === taskId)?.sourceFilename || undefined);
  } finally {
    downloadingTaskId.value = null;
  }
}

async function loadTask(taskId: number, autoConnect = false) {
  try {
    const { data } = await getPaperTask(taskId);
    currentTask.value = data;
    const detailsPromise = loadClarificationsAndSections(taskId);
    try {
      const unified = await getTaskStatus(taskId, PAPER_TASK_TYPE);
      taskStatus.value = unified.data;
    } catch {
      taskStatus.value = null;
    }
    if (events.value.length === 0) {
      await loadTaskEventHistory(taskId);
    }
    await detailsPromise;
    const effectiveStatus = taskStatus.value?.status || data.status;
    if (autoConnect && ['PENDING', 'RUNNING', 'PAUSED', 'WAITING_INPUT', 'CANCEL_REQUESTED', 'CANCELLING'].includes(effectiveStatus)) {
      connectSse();
    }
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '加载论文任务失败'));
  }
}

async function refreshTask() {
  if (!currentTaskId.value) return;
  await loadTask(currentTaskId.value, false);
}

async function focusStructureConfirmation() {
  if (!structureConfirmationReady.value) return;
  activeReviewTab.value = 'clarification';
  structureConfirmationVisible.value = true;
  await nextTick();
  document.getElementById('paper-structure-confirmation')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

async function loadClarificationsAndSections(taskId: number) {
  const clarificationPromise = loadClarifications(taskId);
  const [sectionResult, suggestionResult, artifactResult, analysisResult] = await Promise.allSettled([
    getPaperSections(taskId),
    getPaperSuggestions(taskId),
    getPaperArtifacts(taskId),
    getPaperAnalysis(taskId),
  ]);
  if (currentTaskId.value !== taskId) return;
  if (sectionResult.status === 'fulfilled') {
    sections.value = sectionResult.value.data;
  }
  if (suggestionResult.status === 'fulfilled') {
    suggestions.value = suggestionResult.value.data;
  }
  if (artifactResult.status === 'fulfilled') {
    artifacts.value = artifactResult.value.data;
  }
  if (analysisResult.status === 'fulfilled') {
    analysis.value = analysisResult.value.data;
  }
  await clarificationPromise;
}

async function loadClarifications(taskId: number) {
  const requestSequence = ++clarificationRequestSequence;
  clarificationsLoading.value = true;
  clarificationsError.value = '';
  try {
    const { data } = await getPaperClarifications(taskId);
    if (requestSequence !== clarificationRequestSequence || currentTaskId.value !== taskId) return;
    clarifications.value = data;
    for (const item of data) {
      if (item.status === 'PENDING' && !clarificationAnswers[item.id]) {
        clarificationAnswers[item.id] = clarificationOptions(item).defaultOption || '保持原样';
      }
    }
  } catch (error: unknown) {
    if (requestSequence !== clarificationRequestSequence || currentTaskId.value !== taskId) return;
    clarificationsError.value = apiErrorMessage(error, '加载结构确认内容失败');
  } finally {
    if (requestSequence === clarificationRequestSequence) {
      clarificationsLoading.value = false;
    }
  }
}

async function retryClarifications() {
  if (!currentTaskId.value) return;
  await loadClarifications(currentTaskId.value);
}

function connectSse() {
  if (!currentTaskId.value) {
    return;
  }
  abortController?.abort();
  abortController = new AbortController();
  sseStatus.value = 'connecting';
  const token = localStorage.getItem('yanban_access_token');
  if (!token || isJwtExpired(token)) {
    void redirectAfterAuthExpiry();
    return;
  }
  void streamTaskEvents(currentTaskId.value, token, abortController.signal, lastTaskEventId.value);
}

async function streamTaskEvents(taskId: number, token: string, signal: AbortSignal, afterEventId: number | null) {
  try {
    const response = await fetch(`/api/v1/agent/tasks/${PAPER_TASK_TYPE}/${taskId}/events/stream`, {
      headers: {
        Authorization: `Bearer ${token}`,
        ...(afterEventId != null ? { 'Last-Event-ID': String(afterEventId) } : {}),
      },
      signal,
    });
    if (response.status === 401) {
      await redirectAfterAuthExpiry();
      return;
    }
    if (!response.ok || !response.body) {
      throw new Error('SSE 连接失败');
    }
    sseStatus.value = 'connected';
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const chunks = buffer.split('\n\n');
      buffer = chunks.pop() || '';
      for (const chunk of chunks) {
        const event = parseTaskEventChunk(chunk);
        if (event) {
          appendTaskEvents([event]);
          applyTaskEventToTaskState(event);
          const waitingForStructure = event.status === 'WAITING_INPUT'
            && event.stage === 'STRUCTURE_CHECK';
          if (waitingForStructure) {
            await loadClarifications(taskId);
          } else if (event.type !== 'TASK_PROGRESS') {
            await loadTask(taskId, false);
          }
          if (isTerminalTaskEvent(event)) {
            await loadHistory();
            abortController?.abort();
            sseStatus.value = 'closed';
            return;
          }
        }
      }
    }
    sseStatus.value = signal.aborted ? 'closed' : 'closed';
  } catch (error: any) {
    if (error.name !== 'AbortError') {
      sseStatus.value = 'error';
      ui.message.warning(error.message || 'SSE 已断开');
      return;
    }
    sseStatus.value = 'closed';
  }
}

async function redirectAfterAuthExpiry() {
  abortController?.abort();
  sseStatus.value = 'closed';
  expireAuthSession();
  if (route.name !== 'login') {
    await router.replace({ name: 'login', query: { redirect: route.fullPath } });
  }
}

function parseTaskEventChunk(chunk: string): PaperTimelineEvent | null {
  const lines = chunk.split('\n');
  let idText = '';
  let data = '';
  for (const line of lines) {
    if (line.startsWith(':')) {
      continue;
    }
    if (line.startsWith('id:')) {
      idText = line.slice(3).trim();
    }
    if (line.startsWith('data:')) {
      data += line.slice(5).trim();
    }
  }
  if (!data) return null;
  try {
    const parsed = JSON.parse(data) as TaskEventResponse;
    if (idText && parsed.id == null) {
      parsed.id = Number(idText);
    }
    return normalizeTaskEvent(parsed);
  } catch {
    return null;
  }
}

async function handlePause() {
  if (!currentTaskId.value) return;
  await pausePaperTask(currentTaskId.value);
  await refreshTask();
}

async function handleResume() {
  if (!currentTaskId.value) return;
  await resumePaperTask(currentTaskId.value);
  await refreshTask();
  connectSse();
}

async function handleStop() {
  if (!currentTaskId.value) return;
  try {
    const { data } = await cancelTask(currentTaskId.value, {
      taskType: PAPER_TASK_TYPE,
      cancelReason: 'user requested stop',
    });
    if (data.cancelAccepted) {
      ui.message.success(`停止请求已提交 (${data.beforeStatus} -> ${data.afterStatus})`);
    } else {
      ui.message.info(data.message || '任务已处于终态，无法再次停止');
    }
    await refreshTask();
    if (data.cancelAccepted && !TERMINAL_TASK_STATUSES.has(data.afterStatus)) {
      if (sseStatus.value !== 'connected') {
        connectSse();
      }
    } else {
      abortController?.abort();
      sseStatus.value = 'closed';
    }
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '停止请求失败'));
  }
}

async function handleDownload() {
  if (!currentTaskId.value) return;
  downloading.value = true;
  try {
    await downloadTaskById(currentTaskId.value, currentTask.value?.sourceFilename || undefined);
  } finally {
    downloading.value = false;
  }
}

async function downloadTaskById(taskId: number, fallbackFilename?: string) {
  try {
    const { data, headers } = await downloadPaperTask(taskId);
    const contentDisposition = String(headers['content-disposition'] || '');
    const matched = contentDisposition.match(/filename=\"?([^\";]+)\"?/i);
    const filename = decodeURIComponent(matched?.[1] || fallbackFilename || 'paper-result.zip');
    const blobUrl = window.URL.createObjectURL(data);
    const anchor = document.createElement('a');
    anchor.href = blobUrl;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.URL.revokeObjectURL(blobUrl);
    ui.message.success('下载已开始');
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '下载结果文件失败'));
  }
}

function downloadableHistoryArtifacts(task: PaperTaskHistoryResponse) {
  return (task.artifacts || []).filter((item) => downloadableArtifactTypes.includes(item.type));
}

function historyTaskDownloadable(task: PaperTaskHistoryResponse) {
  return Boolean(task.finalObjectKey) || downloadableHistoryArtifacts(task).length > 0;
}

function artifactDisplayName(artifact: PaperArtifactResponse) {
  const labels: Record<string, string> = {
    polished_tex: 'polished.tex',
    suggested_bib: 'suggested.bib',
    suggested_bib_novel: 'suggested-novel.bib',
    review_report: 'review-report.md',
    retrieved_literature_json: 'retrieved-literature.json',
    retrieved_literature_md: 'retrieved-literature.md',
    source_tex: 'source.tex',
    source_bib: 'source.bib',
  };
  let base = labels[artifact.type] || artifact.type;
  if (artifact.metadataJson) {
    try {
      const metadata = JSON.parse(artifact.metadataJson) as { filename?: string };
      if (metadata.filename) base = metadata.filename;
    } catch {
      // Keep the type-based fallback for legacy artifact metadata.
    }
  }
  return artifact.version && artifact.version > 1 ? `${base} v${artifact.version}` : base;
}

function formatFileSize(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(2)} MB`;
}

function statusTagType(status: string) {
  if (status === 'COMPLETED') return 'success';
  if (status === 'FAILED' || status === 'STOPPED' || status === 'CANCELLED') return 'error';
  if (status === 'CANCEL_REQUESTED' || status === 'CANCELLING') return 'warning';
  if (status === 'RUNNING' || status === 'PENDING') return 'info';
  if (status === 'PAUSED' || status === 'WAITING_INPUT') return 'warning';
  return 'default';
}

function taskCardTitle(task: PaperTaskListItem) {
  return task.title || task.sourceFilename || `${isEnglish.value ? 'Paper task' : '论文任务'} #${task.id}`;
}

function taskStatusLabel(status: string) {
  const labels: Record<string, string> = isEnglish.value ? {
    PENDING: 'Pending',
    RUNNING: 'Running',
    PAUSED: 'Paused',
    WAITING_INPUT: 'Awaiting review',
    CANCEL_REQUESTED: 'Stopping',
    CANCELLING: 'Stopping',
    COMPLETED: 'Completed',
    FAILED: 'Failed',
    CANCELLED: 'Cancelled',
    TIMED_OUT: 'Timed out',
  } : {
    PENDING: '等待中',
    RUNNING: '运行中',
    PAUSED: '已暂停',
    WAITING_INPUT: '等待确认',
    CANCEL_REQUESTED: '等待停止',
    CANCELLING: '停止中',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    TIMED_OUT: '已超时',
  };
  return labels[status] || status;
}

function taskStageLabel(task: PaperTaskListItem) {
  if (task.status === 'WAITING_INPUT') return isEnglish.value ? 'Structure review required' : '结构确认等待处理';
  if (task.status === 'COMPLETED') return isEnglish.value ? 'Results generated' : '结果已生成';
  if (task.status === 'FAILED') return isEnglish.value ? 'Task failed' : '任务执行失败';
  return stageLabel(task.currentStage || task.status);
}

function toTaskListItem(task: PaperTaskHistoryResponse | PaperTaskResponse): PaperTaskListItem {
  return {
    id: task.id,
    title: task.title,
    sourceFilename: task.sourceFilename,
    status: task.status,
    currentStage: task.currentStage,
    updatedAt: task.updatedAt,
  };
}

function pickPreferredTask(tasks: PaperTaskListItem[]) {
  const activeTasks = tasks.filter((task) => ACTIVE_TASK_STATUSES.has(task.status));
  if (activeTasks.length === 0) return null;
  return [...activeTasks].sort((left, right) => {
    const leftWaiting = left.status === 'WAITING_INPUT' ? 1 : 0;
    const rightWaiting = right.status === 'WAITING_INPUT' ? 1 : 0;
    if (leftWaiting !== rightWaiting) return rightWaiting - leftWaiting;
    return new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime();
  })[0];
}

function taskFlowStepClass(task: PaperTaskListItem, stage: string) {
  const currentIndex = taskFlowIndex(task);
  const index = workflowSteps.findIndex((step) => step.stage === stage);
  const terminalComplete = task.status === 'COMPLETED';
  return {
    'paper-running-task__step--done': terminalComplete || (currentIndex >= 0 && index < currentIndex),
    'paper-running-task__step--active': !terminalComplete && currentIndex >= 0 && index === currentIndex,
    'paper-running-task__step--waiting': task.status === 'WAITING_INPUT' && stage === 'STRUCTURE_CHECK',
  };
}

function taskFlowIndex(task: PaperTaskListItem) {
  if (task.status === 'COMPLETED') return workflowSteps.length - 1;
  if (task.status === 'WAITING_INPUT') return workflowSteps.findIndex((step) => step.stage === 'STRUCTURE_CHECK');
  const normalizedStage = normalizeStage(task.currentStage || null);
  if (!normalizedStage) return workflowSteps.findIndex((step) => step.stage === 'UPLOAD');
  return workflowSteps.findIndex((step) => step.stage === normalizedStage);
}

async function submitClarification(item: PaperClarificationResponse) {
  if (!currentTaskId.value) return;
  const option = clarificationAnswers[item.id] || clarificationOptions(item).defaultOption || '保持原样';
  await answerClarificationWithOption(item, option, 'answer');
}

async function skipClarification(item: PaperClarificationResponse) {
  await answerClarificationWithOption(item, clarificationAnswers[item.id] || '跳过', 'skip');
}

async function keepAllClarifications() {
  clarificationSubmitting.value = true;
  try {
    for (const item of pendingClarifications.value) {
      const option = clarificationOptions(item).defaultOption || '保持原样';
      clarificationAnswers[item.id] = option;
      await answerClarificationWithOption(item, option, 'keep', false);
    }
    if (currentTaskId.value) await loadTask(currentTaskId.value, false);
    ui.message.success('已全部保持原样');
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '提交结构确认失败'));
  } finally {
    clarificationSubmitting.value = false;
  }
}

async function answerClarificationWithOption(item: PaperClarificationResponse, option: string, action: string, manageLoading = true) {
  if (!currentTaskId.value) return;
  if (manageLoading) clarificationSubmitting.value = true;
  try {
    const answerJson = JSON.stringify({ action, option, source: 'paper-page' });
    await answerPaperClarification(currentTaskId.value, item.id, answerJson);
    await loadTask(currentTaskId.value, false);
    if (manageLoading) ui.message.success('结构确认已提交');
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '提交结构确认失败'));
    throw error;
  } finally {
    if (manageLoading) clarificationSubmitting.value = false;
  }
}

async function handleSectionRoleChange(sectionId: number, role: string) {
  if (!currentTaskId.value) return;
  try {
    await updatePaperSectionRole(currentTaskId.value, sectionId, role);
    await loadClarificationsAndSections(currentTaskId.value);
    ui.message.success('章节角色已更新');
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '更新章节角色失败'));
  }
}

function suggestionStateLabel(suggestion: PaperSuggestionResponse) {
  const localize = (english: string, chinese: string) => isEnglish.value ? english : chinese;
  const closure = suggestionClosure(suggestion);
  if (closure?.status === 'SUPPORTED') {
    return localize(`Applied after review (R${Number(closure.attempts || 1)})`, `审查后已应用（第 ${Number(closure.attempts || 1)} 轮）`);
  }
  if (closure?.status === 'REPORT_ONLY') {
    return localize(`Report only (R${Number(closure.attempts || 0)})`, `仅写入报告（第 ${Number(closure.attempts || 0)} 轮）`);
  }
  const decision = suggestionDecision(suggestion);
  if (decision?.verdict === 'PARTIAL') return suggestion.status === 'ACCEPTED' ? localize('Applied with supporting evidence', '补充证据后已应用') : localize('Partially supported', '部分支持');
  if (decision?.verdict === 'REJECTED') return localize('Not supported', '证据不足');
  if (decision?.verdict === 'UNREVIEWED') return localize('Review unavailable', '暂未审查');
  if (suggestion.status === 'ACCEPTED') return localize('Accepted for application', '已接受应用');
  if (suggestion.status === 'REJECTED') return localize('Not applied', '未应用');
  return suggestion.applicable ? localize('Pending application', '待应用') : localize('Report only', '仅报告');
}

function suggestionStateType(suggestion: PaperSuggestionResponse): 'success' | 'warning' | 'default' {
  const closure = suggestionClosure(suggestion);
  if (closure?.status === 'SUPPORTED') return 'success';
  if (closure?.status === 'REPORT_ONLY') return 'default';
  const decision = suggestionDecision(suggestion);
  if (decision?.verdict === 'PARTIAL' && suggestion.status === 'ACCEPTED') return 'success';
  if (decision?.verdict === 'PARTIAL' || decision?.verdict === 'UNREVIEWED') return 'warning';
  if (decision?.verdict === 'REJECTED') return 'default';
  if (suggestion.status === 'ACCEPTED') return 'success';
  return suggestion.applicable ? 'warning' : 'default';
}

function suggestionDecision(suggestion: PaperSuggestionResponse): Record<string, any> | null {
  if (!suggestion.patchJson) return null;
  try {
    const patch = JSON.parse(suggestion.patchJson);
    return patch.citationClosure || patch.citationCritic || patch.applicationDecision || null;
  } catch {
    return null;
  }
}

function suggestionClosure(suggestion: PaperSuggestionResponse): Record<string, any> | null {
  if (!suggestion.patchJson) return null;
  try {
    return JSON.parse(suggestion.patchJson).citationClosure || null;
  } catch {
    return null;
  }
}

function suggestionDecisionReason(suggestion: PaperSuggestionResponse) {
  const decision = suggestionDecision(suggestion);
  return typeof decision?.reason === 'string' ? suggestionCustomerText(decision.reason) : '';
}

function suggestionCustomerText(value: string | null | undefined) {
  return (value || '')
    .replace(/\bcard[-\s]?\d+\b/gi, 'the selected literature')
    .replace(/\bslot[-\s]?\d+\b/gi, 'the target claim')
    .replace(/citation critic/gi, 'evidence review')
    .replace(/citation closure/gi, 'citation review')
    .replace(/\s+/g, ' ')
    .trim();
}

async function updateSectionRevisionStatus(section: PaperSectionResponse, status: string) {
  if (!currentTaskId.value) return;
  sectionRevisionSubmitting.value = section.id;
  try {
    await updatePaperSectionRevisionStatusApi(currentTaskId.value, section.id, status);
    await loadClarificationsAndSections(currentTaskId.value);
    ui.message.success(status === 'ACCEPTED' ? '已采纳章节润色' : status === 'REJECTED' ? '已拒绝章节润色' : '章节润色已标记为待处理');
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, '更新章节采纳状态失败'));
  } finally {
    sectionRevisionSubmitting.value = null;
  }
}

function isReviewableSection(section: PaperSectionResponse) {
  return section.polishStatus === 'POLISHED' && section.revisionStatus !== 'ACCEPTED' && section.revisionStatus !== 'REJECTED';
}

function toggleRevisionSection(sectionId: number, checked: boolean) {
  const selected = new Set(selectedRevisionSectionIds.value);
  if (checked) selected.add(sectionId);
  else selected.delete(sectionId);
  selectedRevisionSectionIds.value = [...selected];
}

function toggleAllRevisionSections(checked: boolean) {
  selectedRevisionSectionIds.value = checked ? reviewableSections.value.map((section) => section.id) : [];
}

async function acceptSelectedRevisions() {
  if (!currentTaskId.value || selectedRevisionSectionIds.value.length === 0) return;
  batchRevisionSubmitting.value = true;
  try {
    const acceptedCount = selectedRevisionSectionIds.value.length;
    await updatePaperSectionRevisionStatusesApi(currentTaskId.value, selectedRevisionSectionIds.value, 'ACCEPTED');
    await loadClarificationsAndSections(currentTaskId.value);
    ui.message.success(isEnglish.value ? `Accepted ${acceptedCount} section revisions` : `已批量采纳 ${acceptedCount} 个章节`);
  } catch (error: unknown) {
    ui.message.error(apiErrorMessage(error, isEnglish.value ? 'Failed to accept selected revisions' : '批量采纳失败'));
  } finally {
    batchRevisionSubmitting.value = false;
  }
}

function prettyJson(raw: string) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function readableDiff(raw: string) {
  try {
    const parsed = JSON.parse(raw);
    if (typeof parsed.unifiedDiff === 'string' && parsed.unifiedDiff.trim()) {
      return parsed.unifiedDiff;
    }
    if (Array.isArray(parsed.hunks)) {
      return parsed.hunks
        .flatMap((hunk: any) => Array.isArray(hunk.lines) ? hunk.lines : [])
        .map((line: any) => `${line.type === 'ADD' ? '+' : line.type === 'DELETE' ? '-' : ' '}${line.text ?? ''}`)
        .join('\n');
    }
    return JSON.stringify(parsed, null, 2);
  } catch {
    return raw;
  }
}

function formatAuthors(authors: string | null) {
  if (!authors?.trim()) return 'Unknown authors';
  try {
    const parsed = JSON.parse(authors);
    if (Array.isArray(parsed)) {
      const names = parsed.map((author) => String(author).trim()).filter(Boolean);
      return names.length > 0 ? names.join(', ') : 'Unknown authors';
    }
  } catch {
    // Some legacy cards store authors as display-ready plain text.
  }
  return authors.trim();
}

function doiUrl(doi: string | null) {
  if (!doi) return '';
  const normalized = doi.replace(/^https?:\/\/(dx\.)?doi\.org\//i, '').trim();
  return normalized ? `https://doi.org/${normalized}` : '';
}

function clarificationQuestion(item: PaperClarificationResponse) {
  return parseJson<{ message?: string; blocking?: boolean; relatedSectionOrderIndex?: number }>(item.questionJson, {});
}

function clarificationOptions(item: PaperClarificationResponse) {
  return parseJson<{ options: string[]; defaultOption: string }>(item.optionsJson || '', { options: ['保持原样'], defaultOption: '保持原样' });
}

function answeredOption(item: PaperClarificationResponse) {
  const answer = parseJson<{ option?: string; action?: string }>(item.userAnswerJson || '', {});
  if (answer.option) return answer.option;
  if (answer.action === 'skip') return '跳过';
  return item.userAnswerJson || '-';
}

function parseJson<T>(raw: string, fallback: T): T {
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function formatConfidence(value: number | null) {
  if (value == null) return '-';
  return `${Math.round(value * 100)}%`;
}

function hasProgressMeta(event: PaperTimelineEvent) {
  return event.progressPercent != null || event.currentSection != null || event.attempt != null || Boolean(event.stage);
}

function normalizeStage(stage: string | null) {
  if (!stage) return null;
  return legacyStageAlias[stage] || stage;
}

function stageLabel(stage: string | null) {
  const normalized = normalizeStage(stage);
  if (normalized === 'GLOBAL_REVIEW') return isEnglish.value ? 'Consistency review' : '全文一致性审查';
  const labels: Record<string, string> = isEnglish.value ? {
    UPLOAD: 'Upload',
    PARSE: 'Parse LaTeX',
    STRUCTURE_CHECK: 'Structure review',
    RESEARCH_PROFILE: 'Research profile',
    RETRIEVE: 'Literature search',
    GAP_ANALYSIS: 'Gap analysis',
    POLISH: 'Section polish',
    ASSEMBLE: 'Assemble package',
    COMPLETE: 'Complete',
    CANCEL_REQUESTED: 'Stopping',
    CANCELLING: 'Stopping',
    CANCELLED: 'Cancelled',
    STOPPED: 'Stopped',
    FAILED: 'Failed',
    PENDING: 'Pending',
    RUNNING: 'Running',
    PAUSED: 'Paused',
    WAITING_INPUT: 'Awaiting review',
    TIMED_OUT: 'Timed out',
  } : {
    UPLOAD: '上传',
    PARSE: '解析 LaTeX',
    STRUCTURE_CHECK: '结构确认',
    RESEARCH_PROFILE: '研究画像',
    RETRIEVE: '文献检索',
    GAP_ANALYSIS: 'Gap 分析',
    POLISH: '分章润色',
    ASSEMBLE: '三件套组装',
    COMPLETE: '完成',
    CANCEL_REQUESTED: '等待取消',
    CANCELLING: '正在取消',
    CANCELLED: '已取消',
    STOPPED: '已停止',
    FAILED: '失败',
    PENDING: '等待中',
    RUNNING: '运行中',
    PAUSED: '已暂停',
    WAITING_INPUT: '等待确认',
    TIMED_OUT: '已超时',
  };
  return normalized ? labels[normalized] || normalized : '-';
}

function eventDisplayType(type: string) {
  const labels: Record<string, string> = {
    TASK_CREATED: '任务已创建',
    STAGE_CHANGED: '阶段变更',
    TASK_PAUSED: '已暂停',
    TASK_RESUMED: '继续执行',
    TASK_CANCEL_REQUESTED: '已提交停止',
    TASK_CANCELLING: '正在安全停止',
    TASK_CANCELLED: '已取消',
    TASK_COMPLETED: '已完成',
    TASK_FAILED: '执行失败',
    ARTIFACT_MARKED_PARTIAL: '产物标记为部分结果',
  };
  return labels[type] || type;
}

function eventProgressHint(event: PaperTimelineEvent) {
  const parts: string[] = [];
  if (event.currentSection && event.totalSections) {
    parts.push(`章节 ${event.currentSection}/${event.totalSections}`);
  }
  if (event.sectionTitle) {
    parts.push(event.sectionTitle);
  }
  if (event.attempt && event.maxAttempts) {
    parts.push(`尝试 ${event.attempt}/${event.maxAttempts}`);
  }
  if (event.progressPercent != null) {
    parts.push(`进度 ${clampProgress(event.progressPercent)}%`);
  }
  return parts.join(' · ');
}

function stageChipClass(stage: string) {
  const activeIndex = workflowSteps.findIndex((step) => step.stage === normalizedCurrentStage.value);
  const index = workflowSteps.findIndex((step) => step.stage === stage);
  return {
    'paper-stage-chip--done': activeIndex >= 0 && index < activeIndex,
    'paper-stage-chip--active': activeIndex >= 0 && index === activeIndex,
  };
}

function workflowStepClass(stage: string) {
  if (stage === 'UPLOAD') {
    return {
      'paper-workflow-step-v2--done': Boolean(currentTask.value || selectedTexFile.value),
      'paper-stage-chip--active': !currentTask.value && Boolean(selectedTexFile.value),
    };
  }
  if (stage === 'ASSEMBLE' && currentTask.value?.status === 'COMPLETED') {
    return { 'paper-stage-chip--done': true };
  }
  return stageChipClass(stage);
}

function workflowStepDetail(stage: string) {
  const localize = (english: string, chinese: string) => isEnglish.value ? english : chinese;
  const activeIndex = workflowSteps.findIndex((step) => step.stage === normalizedCurrentStage.value);
  const index = workflowSteps.findIndex((step) => step.stage === stage);
  const done = currentTask.value?.status === 'COMPLETED' || (activeIndex >= 0 && index < activeIndex);
  if (stage === 'UPLOAD') return currentTask.value || selectedTexFile.value ? localize('Ready', '就绪') : localize('Pending', '等待');
  if (stage === 'STRUCTURE_CHECK') {
    if (needsStructureConfirmation.value) return localize(`${pendingClarifications.value.length || 1} pending`, `${pendingClarifications.value.length || 1} 项待确认`);
    return done ? localize('Confirmed', '已确认') : activeIndex === index ? localize('Checking', '检查中') : localize('Pending', '等待');
  }
  if (stage === 'RETRIEVE') {
    const event = latestProgressEvent.value;
    if (activeIndex === index && event?.currentSection != null && event.totalSections) return localize(`${event.currentSection}/${event.totalSections} cards`, `${event.currentSection}/${event.totalSections} 条文献`);
    return done ? localize(`${selectedLiteratureCount.value} selected`, `选中 ${selectedLiteratureCount.value} 篇`) : activeIndex === index ? localize('Searching', '检索中') : localize('Pending', '等待');
  }
  if (stage === 'GAP_ANALYSIS') return done ? localize(`${suggestions.value.length} suggestions`, `${suggestions.value.length} 项建议`) : activeIndex === index ? localize('Reviewing', '审查中') : localize('Pending', '等待');
  if (stage === 'POLISH') {
    const polished = sections.value.filter((section) => Boolean(section.polishStatus)).length;
    const event = latestProgressEvent.value;
    if (activeIndex === index && event?.currentSection != null && event.totalSections) {
      return `${event.currentSection}/${event.totalSections}`;
    }
    return done && sections.value.length > 0 ? `${polished}/${sections.value.length}` : localize('Pending', '等待');
  }
  if (stage === 'GLOBAL_REVIEW') return done ? localize('Reviewed', '已审查') : activeIndex === index ? localize('Reviewing', '审查中') : localize('Pending', '等待');
  if (stage === 'ASSEMBLE') return canDownload.value ? localize('Ready', '就绪') : activeIndex === index ? localize('Generating files', '生成文件中') : localize('Pending', '等待');
  return done ? localize('Completed', '已完成') : activeIndex === index ? localize('In progress', '进行中') : localize('Pending', '等待');
}

function clampProgress(value: number) {
  return Math.max(0, Math.min(100, Math.round(value)));
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString(locale.value);
}

function formatTaskListDate(value: string) {
  return new Intl.DateTimeFormat(locale.value, {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

type PaperTimelineEvent = {
  id: number;
  taskType: string;
  taskId: number;
  userId: number;
  type: string;
  stage: string | null;
  status: string;
  message: string;
  payloadJson: string | null;
  timestamp: string;
  progressPercent: number | null;
  currentSection: number | null;
  totalSections: number | null;
  sectionTitle: string | null;
  attempt: number | null;
  maxAttempts: number | null;
};

type PaperTaskListItem = {
  id: number;
  title: string;
  sourceFilename: string | null;
  status: string;
  currentStage: string | null;
  updatedAt: string;
};

async function loadTaskEventHistory(taskId: number) {
  try {
    const { data } = await listTaskEvents(taskId, PAPER_TASK_TYPE);
    appendTaskEvents(data.map(normalizeTaskEvent));
  } catch {
    // Keep the page usable even if the unified event history endpoint is temporarily unavailable.
  }
}

function resetTaskEvents() {
  events.value = [];
  lastTaskEventId.value = null;
}

function appendTaskEvents(incoming: PaperTimelineEvent[]) {
  if (incoming.length === 0) {
    return;
  }
  const merged = new Map<number, PaperTimelineEvent>();
  for (const event of events.value) {
    merged.set(event.id, event);
  }
  for (const event of incoming) {
    const existing = merged.get(event.id);
    if (!existing || event.timestamp >= existing.timestamp) {
      merged.set(event.id, event);
    }
  }
  events.value = Array.from(merged.values()).sort((left, right) => left.id - right.id);
  lastTaskEventId.value = events.value.length > 0 ? events.value[events.value.length - 1].id : null;
}

function applyTaskEventToTaskState(event: PaperTimelineEvent) {
  if (currentTask.value && currentTask.value.id === event.taskId) {
    currentTask.value = {
      ...currentTask.value,
      status: event.status || currentTask.value.status,
      currentStage: event.stage || currentTask.value.currentStage,
      errorMessage: event.status === 'FAILED' ? event.message : currentTask.value.errorMessage,
    };
  }
  if (taskStatus.value && taskStatus.value.taskId === event.taskId) {
    taskStatus.value = {
      ...taskStatus.value,
      status: event.status || taskStatus.value.status,
      currentStage: event.stage || taskStatus.value.currentStage,
      terminal: TERMINAL_TASK_STATUSES.has(event.status),
      cancellable: !TERMINAL_TASK_STATUSES.has(event.status) && !['CANCEL_REQUESTED', 'CANCELLING'].includes(event.status),
    };
  }
}

function normalizeTaskEvent(event: TaskEventResponse): PaperTimelineEvent {
  const payload = parseJson<Record<string, unknown>>(event.payloadJson || '', {});
  return {
    id: event.id,
    taskType: event.taskType,
    taskId: event.taskId,
    userId: event.userId,
    type: event.eventType,
    stage: event.stage,
    status: event.status,
    message: event.message || event.eventType,
    payloadJson: event.payloadJson,
    timestamp: event.createdAt,
    progressPercent: numericPayloadValue(payload.progressPercent),
    currentSection: numericPayloadValue(payload.currentSection),
    totalSections: numericPayloadValue(payload.totalSections),
    sectionTitle: stringPayloadValue(payload.sectionTitle),
    attempt: numericPayloadValue(payload.attempt),
    maxAttempts: numericPayloadValue(payload.maxAttempts),
  };
}

function isTerminalTaskEvent(event: PaperTimelineEvent) {
  return TERMINAL_TASK_STATUSES.has(event.status);
}

function numericPayloadValue(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function stringPayloadValue(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : null;
}
</script>
