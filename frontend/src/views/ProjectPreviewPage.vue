<template>
  <AppLayout>
    <main class="project-workspace">
      <div class="project-workspace__header-shell" :class="{ 'project-workspace__header-shell--collapsed': projectHeaderCollapsed }">
        <header class="project-workspace__header" :class="{ 'project-workspace__header--collapsed': projectHeaderCollapsed }">
          <h1>{{ activeProject?.name || t('project.page.projects') }}</h1>
          <NSpace :size="8" wrap>
            <NTag v-if="activeProject" size="small" type="success">{{ t('project.page.readOnly') }}</NTag>
            <NButton size="small" secondary :loading="loading.projects" @click="loadProjects">{{ t('project.page.refresh') }}</NButton>
            <NButton v-if="activeProject" size="small" secondary type="error" :disabled="loading.send" @click="deleteModalOpen = true">{{ t('project.page.deleteProject') }}</NButton>
            <NButton size="small" type="primary" @click="openCreateProjectModal">{{ t('project.page.newProject') }}</NButton>
          </NSpace>
          <button type="button" class="workspace-hero__collapse" :title="t('project.page.collapseHeader')" :aria-label="t('project.page.collapseHeader')" @click="setProjectHeaderCollapsed(true)">
            <NIcon aria-hidden="true"><ChevronRightIcon /></NIcon>
          </button>
        </header>
        <button v-if="projectHeaderCollapsed" type="button" class="workspace-hero__restore" :title="t('project.page.expandHeader')" :aria-label="t('project.page.expandHeader')" @click="setProjectHeaderCollapsed(false)">
          <NIcon aria-hidden="true"><ChevronRightIcon /></NIcon>
        </button>
      </div>

      <NAlert v-if="error" type="error" closable class="project-workspace__alert" @close="error = ''">{{ error }}</NAlert>

      <section v-if="loading.projects" class="project-workspace__state">
        <NSpin size="small" />
        {{ t('project.page.loadingProjects') }}
      </section>
      <section v-else-if="projects.length === 0" class="project-workspace__state">
        <NEmpty :description="t('project.page.noProjects')" />
      </section>

      <section v-else class="project-workspace__grid">
        <aside class="project-panel project-panel--files">
          <section class="project-sidebar-section project-sidebar-section--projects" :class="{ 'project-sidebar-section--collapsed': sidebarSections.projects }">
            <div class="project-sidebar-section__toggle">
              <span>
                <button type="button" class="project-chevron-button" :class="{ 'project-chevron-button--expanded': !sidebarSections.projects }" :aria-expanded="!sidebarSections.projects" :aria-label="sidebarSections.projects ? t('project.page.expandProjects') : t('project.page.collapseProjects')" :title="sidebarSections.projects ? t('project.page.expandProjects') : t('project.page.collapseProjects')" @click="toggleSidebarSection('projects')">
                  <NIcon aria-hidden="true"><ChevronRightIcon /></NIcon>
                </button>
                <strong>{{ t('project.page.projects') }}</strong>
              </span>
              <span class="project-panel__count">{{ projects.length }}</span>
            </div>
            <div v-show="!sidebarSections.projects" class="project-list">
              <button v-for="project in projects" :key="project.id" class="project-list__item" :class="{ active: project.id === activeProjectId }" @click="selectProject(project.id)">
                <strong>{{ project.name }}</strong>
                <small>#{{ project.id }} - {{ project.accessMode }}</small>
              </button>
            </div>
          </section>

          <section class="project-sidebar-section project-sidebar-section--chats" :class="{ 'project-sidebar-section--collapsed': sidebarSections.conversations }">
            <div class="project-sidebar-section__toggle">
              <span>
                <button type="button" class="project-chevron-button" :class="{ 'project-chevron-button--expanded': !sidebarSections.conversations }" :aria-expanded="!sidebarSections.conversations" :aria-label="sidebarSections.conversations ? t('project.page.expandConversations') : t('project.page.collapseConversations')" :title="sidebarSections.conversations ? t('project.page.expandConversations') : t('project.page.collapseConversations')" @click="toggleSidebarSection('conversations')">
                  <NIcon aria-hidden="true"><ChevronRightIcon /></NIcon>
                </button>
                <strong>{{ t('project.page.conversations') }}</strong>
              </span>
              <span class="project-panel__count">{{ projectSessions.length }}</span>
            </div>
            <div v-show="!sidebarSections.conversations" class="project-conversation-history project-conversation-history--sidebar" :aria-label="t('project.page.conversationHistory')">
              <div
                v-for="session in projectSessions"
                :key="session.id"
                role="button"
                tabindex="0"
                class="project-conversation-item"
                :class="{ active: session.id === activeSessionId }"
                :title="session.title"
                @click="selectConversation(session.id)"
                @keydown.enter.prevent="selectConversation(session.id)"
              >
                <span>{{ session.title || `Conversation #${session.id}` }}</span>
                <NDropdown trigger="click" :options="sessionMenuOptions" @select="(key) => handleSessionMenuSelect(key, session)">
                  <button type="button" class="project-conversation-item__more" :aria-label="t('project.page.conversationActions')" @click.stop>...</button>
                </NDropdown>
              </div>
              <small v-if="loading.sessions">{{ t('project.page.loading') }}</small>
            </div>
          </section>

          <section class="project-sidebar-section project-sidebar-section--file-browser" :class="{ 'project-sidebar-section--collapsed': sidebarSections.files }">
            <div class="project-sidebar-section__header">
              <div class="project-sidebar-section__toggle">
                <span>
                  <button type="button" class="project-chevron-button" :class="{ 'project-chevron-button--expanded': !sidebarSections.files }" :aria-expanded="!sidebarSections.files" :aria-label="sidebarSections.files ? t('project.page.expandFiles') : t('project.page.collapseFiles')" :title="sidebarSections.files ? t('project.page.expandFiles') : t('project.page.collapseFiles')" @click="toggleSidebarSection('files')">
                    <NIcon aria-hidden="true"><ChevronRightIcon /></NIcon>
                  </button>
                  <strong>{{ t('project.page.files') }}</strong>
                </span>
              </div>
              <NSpace class="project-panel__title-actions" :size="4" align="center">
                <span class="project-panel__count">{{ manifest?.files.length || 0 }}</span>
                <template v-if="!sidebarSections.files">
                  <NButton size="tiny" quaternary :disabled="directoryPaths.length === 0" :title="t('project.page.expandAllFolders')" @click="expandAllDirectories">{{ t('project.page.expand') }}</NButton>
                  <NButton size="tiny" quaternary :disabled="directoryPaths.length === 0" :title="t('project.page.collapseAllFolders')" @click="collapseAllDirectories">{{ t('project.page.collapse') }}</NButton>
                </template>
              </NSpace>
            </div>

            <div v-if="!sidebarSections.files && loading.manifest" class="project-panel__loading"><NSpin size="small" /></div>
            <div v-else-if="!sidebarSections.files" class="project-file-list">
              <button
                v-for="node in fileTree"
                :key="node.key"
                type="button"
                class="project-file-list__item"
                :class="{ 'project-file-list__directory': node.directory, active: !node.directory && selectedFile?.path === node.path }"
                :style="{ paddingLeft: `${6 + node.depth * 12}px` }"
                :title="node.path"
                :aria-expanded="node.directory ? !collapsedDirectories.has(node.path) : undefined"
                @click="node.directory ? toggleDirectory(node.path) : openFile(node.path)"
              >
                <span>
                  <NIcon v-if="node.directory" class="project-file-list__chevron" :class="{ 'project-file-list__chevron--expanded': !collapsedDirectories.has(node.path) }" aria-hidden="true"><ChevronRightIcon /></NIcon>
                  {{ node.name }}
                </span>
                <small v-if="!node.directory">{{ shortHash(node.sha256) }}</small>
              </button>
              <NEmpty v-if="manifest && manifest.files.length === 0" size="small" :description="t('project.page.noReadableFiles')" />
            </div>

            <div v-show="!sidebarSections.files" class="project-search">
              <NInput v-model:value="searchQuery" size="small" :placeholder="t('project.page.searchProject')" @keyup.enter="runSearch" />
              <NButton size="small" secondary :loading="loading.search" :disabled="!activeProject" @click="runSearch">{{ t('project.page.search') }}</NButton>
            </div>

            <div v-if="!sidebarSections.files && searchResults.length" class="project-search-results">
              <button v-for="hit in searchResults" :key="`${hit.path}:${hit.lineNumber}`" @click="openFile(hit.path)">
                <strong>{{ hit.path }}:{{ hit.lineNumber }}</strong>
                <span>{{ hit.line }}</span>
              </button>
            </div>
          </section>
        </aside>

        <section class="project-panel project-panel--main">
          <div class="project-tabs">
            <strong class="project-tabs__title">{{ t('project.page.conversation') }}</strong>
            <div class="project-tabs__actions">
              <button class="project-utility-chip" :class="{ active: inspectorOpen && inspectorTab === 'preview' }" @click="toggleInspector('preview')">{{ t('project.page.preview') }}</button>
              <button class="project-utility-chip" :class="{ active: inspectorOpen && inspectorTab === 'evidence' }" @click="toggleInspector('evidence')">{{ t('project.page.evidence') }} <span>{{ evidence.length }}</span></button>
              <button class="project-utility-chip" :class="{ active: inspectorOpen && inspectorTab === 'changes' }" @click="toggleInspector('changes')">{{ t('project.page.changes') }} <span>{{ candidates.length }}</span></button>
              <button class="project-utility-chip" :class="{ active: inspectorOpen && inspectorTab === 'versions' }" @click="toggleInspector('versions')">{{ t('project.page.versions') }} <span>{{ revisions.length }}</span></button>
              <NButton size="tiny" quaternary :disabled="loading.send" @click="startNewConversation">{{ t('project.page.newConversation') }}</NButton>
            </div>
          </div>

          <section v-if="inspectorOpen" class="project-inspector">
            <div class="project-inspector__tabs">
              <strong>{{ t('project.page.inspector') }}</strong>
              <button type="button" class="project-inspector__close" @click="inspectorOpen = false">{{ t('project.page.hideInspector') }}</button>
            </div>

            <div class="project-inspector__body">
              <template v-if="inspectorTab === 'preview'">
                <div class="project-preview project-preview--inline">
                  <div class="project-panel__title"><strong>{{ selectedFile?.path || 'Preview' }}</strong><span v-if="selectedFile">{{ shortHash(selectedFile.sha256) }}</span></div>
                  <NSpin v-if="loading.file" size="small" />
                  <pre v-else-if="selectedFile">{{ selectedFile.content }}</pre>
                  <NEmpty v-else size="small" description="Select a readable file to preview it here." />
                </div>
              </template>

              <template v-else-if="inspectorTab === 'evidence'">
                <p class="project-panel__hint">Files actually read by the Agent. CURRENT means their hashes still match.</p>
                <div class="project-evidence-list">
                  <article v-for="item in evidence" :key="item.id">
                    <div>
                      <strong :title="item.relativePath">{{ item.relativePath }}</strong>
                      <NTag size="tiny" :type="item.current ? 'success' : 'warning'">{{ item.current ? 'CURRENT' : 'STALE' }}</NTag>
                    </div>
                    <dl>
                      <dt>hash</dt>
                      <dd>{{ shortHash(item.hash) }}</dd>
                      <dt>version</dt>
                      <dd>{{ shortHash(item.version) }}</dd>
                      <dt>trust</dt>
                      <dd>{{ item.trusted ? 'TRUSTED' : 'UNTRUSTED' }}</dd>
                    </dl>
                  </article>
                  <NEmpty v-if="!loading.evidence && evidence.length === 0" size="small" description="Evidence appears after the Agent reads Project files or a Plan is selected." />
                </div>
              </template>

              <template v-else-if="inspectorTab === 'changes'">
                <div class="project-inspector__changes-head">
                  <p class="project-panel__hint">Read-only suggestions. Original Project files are never changed.</p>
                  <NButton size="tiny" secondary :loading="loading.candidates" :disabled="!activeProject || candidates.length === 0" title="Compare each proposal's base hash with the current Project file" @click="refreshCandidates">Revalidate</NButton>
                </div>

                <div class="project-candidate-list">
                  <button v-for="candidate in candidates" :key="candidate.artifact.id" :class="{ active: selectedCandidate?.artifact.id === candidate.artifact.id }" @click="selectCandidate(candidate)">
                    <strong :title="candidateTitle(candidate)">{{ candidateTitle(candidate) }}</strong>
                    <span>
                      <NTag size="tiny" type="info">NOT_APPLIED</NTag>
                      <NTag size="tiny" :type="candidateStateType(candidate.state)">{{ candidate.state }}</NTag>
                      <small v-if="candidate.candidate">{{ candidate.candidate.changes.length }} file{{ candidate.candidate.changes.length === 1 ? '' : 's' }}</small>
                    </span>
                  </button>
                  <NEmpty v-if="!loading.candidates && candidates.length === 0" size="small" description="No read-only Candidate proposals yet." />
                </div>

                <div v-if="selectedCandidate" class="project-diff">
                  <div class="project-panel__title"><strong>Read-only Candidate</strong><span>artifact {{ selectedCandidate.artifact.id }}</span></div>
                  <NAlert v-if="selectedCandidate.error" :type="selectedCandidate.state === 'STALE' ? 'warning' : 'error'" :show-icon="false">
                    {{ selectedCandidate.error }}
                  </NAlert>

                  <template v-if="selectedCandidate.candidate">
                    <dl class="project-candidate-meta">
                      <dt>schema</dt><dd>{{ selectedCandidate.candidate.schemaVersion }}</dd>
                      <dt>Project version</dt><dd :title="selectedCandidate.candidate.projectVersion">{{ selectedCandidate.candidate.projectVersion }}</dd>
                      <dt>fingerprint</dt><dd :title="selectedCandidate.candidate.fingerprint">{{ selectedCandidate.candidate.fingerprint }}</dd>
                      <dt>state</dt><dd>{{ selectedCandidate.candidate.governanceStatus }} / {{ selectedCandidate.candidate.applicationStatus }}</dd>
                      <dt>review format</dt><dd>{{ selectedCandidate.candidate.reviewDiff.format }}</dd>
                    </dl>

                    <section class="project-candidate-validation">
                      <div class="project-panel__title"><strong>Validation</strong><span>{{ candidateValidationLabel(selectedCandidate.candidate) }}</span></div>
                      <div class="project-validation-checks">
                        <NTag v-for="check in selectedCandidate.candidate.validation.checks" :key="check.area" size="tiny" :type="check.status === 'PASSED' ? 'success' : check.status === 'FAILED' ? 'error' : 'warning'">
                          {{ check.area }} {{ check.status }}
                        </NTag>
                      </div>
                      <ul v-if="selectedCandidate.candidate.validation.issues.length" class="project-validation-issues">
                        <li v-for="issue in selectedCandidate.candidate.validation.issues" :key="`${issue.area}:${issue.code}:${issue.relativePath || ''}`">
                          <strong>{{ issue.code }}</strong><span v-if="issue.relativePath">{{ issue.relativePath }}</span>
                        </li>
                      </ul>
                      <dl class="project-candidate-usage">
                        <dt>changes</dt><dd>{{ selectedCandidate.candidate.validation.usage.inspectedChanges }} / {{ selectedCandidate.candidate.validation.usage.requestedChanges }}</dd>
                        <dt>Evidence</dt><dd>{{ selectedCandidate.candidate.validation.usage.inspectedEvidenceRefs }} / {{ selectedCandidate.candidate.validation.usage.requestedEvidenceRefs }}</dd>
                        <dt>candidate UTF-8</dt><dd>{{ formatBytes(selectedCandidate.candidate.validation.usage.inspectedCandidateUtf8Bytes) }} / {{ formatBytes(selectedCandidate.candidate.validation.usage.requestedCandidateUtf8Bytes) }}</dd>
                      </dl>
                    </section>

                    <section class="project-candidate-sandbox">
                      <div class="project-panel__title">
                        <strong>Sandbox verification</strong>
                        <span>Candidate remains NOT_APPLIED</span>
                      </div>
                      <div class="project-candidate-sandbox__controls">
                        <NSelect v-model:value="validationProfile" size="small" :options="validationProfileOptions" :disabled="loading.candidateValidation" />
                        <NButton size="small" secondary :loading="loading.candidateValidation"
                          :disabled="!candidateCanSelect(selectedCandidate) || selectedChangeIndexes.size === 0"
                          @click="validationModalOpen = true">Validate selected changes</NButton>
                      </div>
                      <NAlert v-if="validationMessage" :type="validationMessageType" :show-icon="false">{{ validationMessage }}</NAlert>
                      <div v-if="candidateValidations.length" class="project-candidate-validation-history">
                        <button v-for="validation in candidateValidations" :key="validation.validationId"
                          :class="{ active: selectedValidation?.validationId === validation.validationId }"
                          @click="selectedValidation = validation">
                          <span>{{ validation.profile }}</span>
                          <NTag size="tiny" :type="candidateValidationStatusType(validation.status)">{{ validation.status }}</NTag>
                          <small>{{ formatDateTime(validation.createdAt) }}</small>
                        </button>
                      </div>
                      <article v-if="selectedValidation" class="project-candidate-validation-receipt">
                        <dl>
                          <dt>validation</dt><dd :title="selectedValidation.validationId">{{ selectedValidation.validationId }}</dd>
                          <dt>binding</dt><dd>{{ shortHash(selectedValidation.candidateFingerprint) }} / {{ shortHash(selectedValidation.projectVersion) }}</dd>
                          <dt>profile</dt><dd>{{ selectedValidation.profile }}</dd>
                          <dt>status</dt><dd>{{ selectedValidation.status }}</dd>
                          <dt>exit code</dt><dd>{{ selectedValidation.exitCode ?? '-' }}</dd>
                          <dt>timed out</dt><dd>{{ selectedValidation.timedOut ? 'true' : 'false' }}</dd>
                          <dt>output truncated</dt><dd>{{ selectedValidation.outputTruncated ? 'true' : 'false' }}</dd>
                          <dt>provider</dt><dd>{{ selectedValidation.provider || '-' }}</dd>
                          <dt>request digest</dt><dd :title="selectedValidation.requestDigest">{{ selectedValidation.requestDigest }}</dd>
                          <dt>receipt digest</dt><dd :title="selectedValidation.receiptDigest || '-'">{{ selectedValidation.receiptDigest || '-' }}</dd>
                          <dt>decision</dt><dd>{{ selectedValidation.decisionStatus }}</dd>
                        </dl>
                        <NAlert v-if="selectedValidation.errorCode" type="warning" :show-icon="false">{{ selectedValidation.errorCode }}</NAlert>
                        <NAlert v-if="selectedValidation.outputTruncated" type="warning" :show-icon="false">
                          Output reached the configured limit and was truncated. Review the bounded stdout/stderr below.
                        </NAlert>
                        <details open><summary>Raw stdout</summary><pre>{{ selectedValidation.stdout || '(empty)' }}</pre></details>
                        <details open><summary>Raw stderr</summary><pre>{{ selectedValidation.stderr || '(empty)' }}</pre></details>
                        <div class="project-candidate-output-analysis">
                          <strong>Read-only analysis summary</strong>
                          <p>{{ selectedValidation.analysisDisclaimer || '基于输出、未独立验证。' }}</p>
                          <pre>{{ selectedValidation.analysisSummary || 'No analysis summary was generated; review the raw Broker output above.' }}</pre>
                        </div>
                        <NSpace justify="end">
                          <NButton v-if="!candidateValidationTerminal(selectedValidation.status)" size="tiny" secondary
                            :loading="loading.cancelCandidateValidation" @click="cancelSelectedValidation">Cancel run</NButton>
                          <NButton v-if="selectedValidation.decisionStatus === 'PENDING'" size="tiny" type="warning" secondary
                            :loading="loading.rejectCandidateValidation" @click="rejectSelectedValidation">Reject Candidate</NButton>
                        </NSpace>
                      </article>
                      <NEmpty v-else size="small" description="No sandbox verification receipt for this Candidate yet." />
                    </section>

                    <section class="project-candidate-files">
                      <article v-for="(entry, changeIndex) in selectedCandidate.candidate.reviewDiff.entries" :key="`${entry.type}:${entry.relativePath}`">
                        <header>
                          <NCheckbox
                            :checked="selectedChangeIndexes.has(changeIndex)"
                            :disabled="!candidateCanSelect(selectedCandidate) || loading.applyCandidate || loading.candidateValidation"
                            :aria-label="`Accept ${entry.relativePath}`"
                            @update:checked="(checked) => setChangeSelected(changeIndex, checked)"
                          />
                          <NTag size="tiny" :type="candidateChangeType(entry.type)">{{ entry.type }}</NTag>
                          <strong :title="entry.relativePath">{{ entry.relativePath }}</strong>
                        </header>
                        <dl>
                          <dt>base hash</dt><dd :title="entry.baseFileHash || '-'">{{ entry.baseFileHash || '-' }}</dd>
                          <dt>result hash</dt><dd :title="entry.resultFileHash || '-'">{{ entry.resultFileHash || '-' }}</dd>
                        </dl>
                        <details open>
                          <summary>Review replacement</summary>
                          <pre v-if="entry.replacementText !== null">{{ entry.replacementText }}</pre>
                          <p v-else class="project-delete-marker">File deletion only. No replacement content.</p>
                        </details>
                        <details>
                          <summary>Evidence provenance ({{ candidateEvidence(selectedCandidate.candidate, entry.relativePath).length }})</summary>
                          <div class="project-candidate-evidence">
                            <dl v-for="(ref, index) in candidateEvidence(selectedCandidate.candidate, entry.relativePath)" :key="`${ref.relativePath}:${ref.range.startLine}:${ref.range.endLine}:${index}`">
                              <dt>path</dt><dd :title="ref.relativePath">{{ ref.relativePath }}</dd>
                              <dt>lines</dt><dd>{{ ref.range.startLine }}-{{ ref.range.endLine }}</dd>
                              <dt>file hash</dt><dd :title="ref.fileHash">{{ ref.fileHash }}</dd>
                              <dt>parser</dt><dd>{{ ref.parserVersion }}</dd>
                              <dt>trust</dt><dd>{{ ref.trustLabel }}</dd>
                            </dl>
                          </div>
                        </details>
                      </article>
                    </section>
                    <NAlert v-if="applicationMessage" :type="applicationMessageType" :show-icon="false">
                      {{ applicationMessage }}
                    </NAlert>
                    <div class="project-candidate-apply">
                      <span>{{ selectedChangeIndexes.size }} of {{ selectedCandidate.candidate.changes.length }} changes selected</span>
                      <NButton
                        type="primary"
                        size="small"
                        :loading="loading.applyCandidate"
                        :disabled="!candidateCanApply(selectedCandidate) || selectedChangeIndexes.size === 0"
                        @click="openApplyConfirmation"
                      >Apply selected changes</NButton>
                    </div>
                  </template>
                </div>
              </template>

              <template v-else>
                <div class="project-inspector__changes-head">
                  <p class="project-panel__hint">Immutable server-managed Project revisions.</p>
                  <NButton size="tiny" secondary :loading="loading.revisions" :disabled="!activeProject" @click="loadRevisions">Refresh</NButton>
                </div>
                <NAlert v-if="revisionMessage" :type="revisionMessageType" :show-icon="false">{{ revisionMessage }}</NAlert>
                <div class="project-revision-list">
                  <article v-for="revision in revisions" :key="revision.id">
                    <header>
                      <strong :title="revision.projectVersion">{{ shortHash(revision.projectVersion) }}</strong>
                      <NTag v-if="revision.current" size="tiny" type="success">CURRENT</NTag>
                      <NTag size="tiny" type="info">{{ revision.sourceType }}</NTag>
                    </header>
                    <dl>
                      <dt>revision</dt><dd>{{ revision.id }}</dd>
                      <dt>files</dt><dd>{{ revision.fileCount }}</dd>
                      <dt>size</dt><dd>{{ formatBytes(revision.totalBytes) }}</dd>
                      <dt>created</dt><dd>{{ formatDateTime(revision.createdAt) }}</dd>
                    </dl>
                    <div class="project-revision-actions">
                      <NButton size="tiny" secondary :loading="exportingRevisionId === revision.id" @click="downloadRevision(revision)">Export ZIP</NButton>
                      <NButton size="tiny" secondary :disabled="revision.current || loading.rollback" @click="openRollbackConfirmation(revision)">Rollback</NButton>
                    </div>
                  </article>
                  <NEmpty v-if="!loading.revisions && revisions.length === 0" size="small" description="Version history begins when a managed Project is imported or its first Candidate is applied." />
                </div>
              </template>
            </div>
          </section>

          <div class="project-scroll-shell">
            <div ref="messagesContainer" class="project-messages" aria-label="Project conversation" @scroll="handleProjectContentScroll">
              <template v-for="item in projectTimelineItems" :key="item.key">
                <div
                  v-if="item.type === 'message'"
                  :ref="(el) => setProjectContentRef(el, item.message.localId)"
                  class="project-message-row"
                  :class="`project-message-row--${item.message.role}`"
                >
                  <details v-if="item.message.role === 'process'" class="project-process-card" :open="item.message.processOpen" @toggle="syncProcessOpen(item.message, $event)">
                    <summary title="Toggle process details">
                      <span>{{ processSummary(item.message) }}</span>
                      <NIcon class="project-process-card__chevron" aria-hidden="true"><ChevronRightIcon /></NIcon>
                    </summary>
                    <pre>{{ item.message.content }}</pre>
                  </details>
                  <div v-else class="project-message" :class="`project-message--${item.message.role}`">
                    <small>{{ item.message.role === 'user' ? 'You' : 'Project Agent' }}</small>
                    <MarkdownMessage :content="item.message.content || (item.message.pending ? 'Thinking...' : '')" :variant="item.message.role === 'assistant' ? 'project' : 'default'" />
                    <details v-if="item.message.technicalContent" class="project-message-technical">
                      <summary>{{ t('project.result.technicalDetails') }}</summary>
                      <pre>{{ item.message.technicalContent }}</pre>
                    </details>
                  </div>
                </div>

                <div
                  v-else
                  :ref="(el) => setProjectContentRef(el, projectPlanItemId(item.plan.id, 'plan'))"
                  class="project-message-row project-message-row--process"
                >
                  <article class="project-execution-card" :class="{ 'project-execution-card--selected': selectedPlan?.id === item.plan.id }">
                    <details class="project-execution-card__details" :open="requiresSandboxConfirmation(item.plan) || undefined">
                      <summary :title="t('project.result.details')" @click="selectPlan(item.plan)">
                        <NIcon class="project-execution-card__chevron" aria-hidden="true"><ChevronRightIcon /></NIcon>
                        <span class="project-execution-card__heading">
                          <strong>{{ t('project.result.plan') }}</strong>
                          <span>{{ item.plan.summary || abbreviateText(item.plan.goal, 100) }}</span>
                        </span>
                        <span class="project-execution-card__meta">
                          <NTag size="tiny" :type="planUserStatus(item.plan).tone">{{ t(planUserStatus(item.plan).key) }}</NTag>
                          <span>{{ planProgressLabel(item.plan) }}</span>
                          <span>{{ t('project.result.duration', { duration: formatPlanElapsed(planElapsedMs(item.plan)) }) }}</span>
                        </span>
                      </summary>

                      <div class="project-execution-card__body">
                        <div class="project-execution-card__details-title">{{ t('project.result.details') }}</div>
                        <p v-if="item.plan.summary" class="project-execution-card__summary-copy">{{ item.plan.summary }}</p>

                        <dl class="project-result-layers">
                          <div>
                            <dt>{{ t('project.result.execution') }}</dt>
                            <dd><NTag size="tiny" :type="planExecutionResult(item.plan).tone">{{ t(planExecutionResult(item.plan).key) }}</NTag></dd>
                          </div>
                          <div>
                            <dt>{{ t('project.result.task') }}</dt>
                            <dd><NTag size="tiny" :type="planTaskResult(item.plan).tone">{{ t(planTaskResult(item.plan).key) }}</NTag></dd>
                          </div>
                          <div>
                            <dt>{{ t('project.result.answerBasis') }}</dt>
                            <dd><NTag size="tiny" :type="planAnswerResult(item.plan).tone">{{ t(planAnswerResult(item.plan).key) }}</NTag></dd>
                          </div>
                        </dl>

                        <details v-if="item.plan.finalSynthesisInput" class="project-result-evidence">
                          <summary>
                            <NIcon class="project-result-evidence__chevron" aria-hidden="true"><ChevronRightIcon /></NIcon>
                            <strong>{{ t('project.result.evidenceTitle') }}</strong>
                            <span>{{ t('project.result.evidenceCount', { count: item.plan.finalSynthesisInput.evidence.length }) }}</span>
                          </summary>
                          <div class="project-result-evidence__body">
                            <p v-if="planEvidenceGroups(item.plan).length === 0" class="project-panel__hint">{{ t('project.result.evidence.empty') }}</p>
                            <details v-for="group in planEvidenceGroups(item.plan)" :key="group.group" class="project-result-evidence-group">
                              <summary>
                                <NIcon class="project-result-evidence__chevron" aria-hidden="true"><ChevronRightIcon /></NIcon>
                                <span>{{ t(group.key) }}</span>
                                <NTag size="tiny" :type="group.tone">{{ group.evidence.length }}</NTag>
                              </summary>
                              <div class="project-result-evidence-group__body">
                                <section v-for="entry in group.evidence" :key="entry.id" class="project-result-evidence-entry">
                                  <header>
                                    <span>{{ entry.statement || t('project.result.statementMissing') }}</span>
                                    <NTag size="tiny" :type="answerStatusResult(entry.status).tone">{{ t(answerStatusResult(entry.status).key) }}</NTag>
                                  </header>

                                  <details v-if="hasTechnicalEvidenceFields(entry)" class="project-result-technical">
                                    <summary>{{ t('project.result.technicalDetails') }}</summary>
                                    <dl>
                                      <template v-if="entry.id"><dt>{{ t('project.result.field.evidenceId') }}</dt><dd>{{ entry.id }}</dd></template>
                                      <template v-if="entry.sourceType"><dt>{{ t('project.result.field.source') }}</dt><dd>{{ entry.sourceType }}</dd></template>
                                      <template v-if="entry.path"><dt>{{ t('project.result.field.path') }}</dt><dd>{{ entry.path }}</dd></template>
                                      <template v-if="entry.projectVersion"><dt>{{ t('project.result.field.projectVersion') }}</dt><dd>{{ entry.projectVersion }}</dd></template>
                                      <template v-if="entry.hash"><dt>{{ t('project.result.field.hash') }}</dt><dd>{{ entry.hash }}</dd></template>
                                      <template v-if="entry.basisRefs.length"><dt>{{ t('project.result.field.basisRefs') }}</dt><dd>{{ entry.basisRefs.join(', ') }}</dd></template>
                                      <template v-if="entry.executionFact?.provider"><dt>{{ t('project.result.field.provider') }}</dt><dd>{{ entry.executionFact.provider }}</dd></template>
                                      <template v-if="entry.executionFact?.status"><dt>{{ t('project.result.field.status') }}</dt><dd>{{ entry.executionFact.status }}</dd></template>
                                      <template v-if="entry.executionFact?.exitCode != null"><dt>{{ t('project.result.field.exitCode') }}</dt><dd>{{ entry.executionFact.exitCode }}</dd></template>
                                      <template v-if="entry.executionFact?.command.length"><dt>{{ t('project.result.field.command') }}</dt><dd>{{ entry.executionFact.command.join(' ') }}</dd></template>
                                      <template v-if="entry.executionFact?.failurePhase"><dt>{{ t('project.result.field.failurePhase') }}</dt><dd>{{ entry.executionFact.failurePhase }}</dd></template>
                                      <template v-if="entry.executionFact?.failureType"><dt>{{ t('project.result.field.failureType') }}</dt><dd>{{ entry.executionFact.failureType }}</dd></template>
                                      <template v-if="entry.executionFact?.providerErrorType"><dt>{{ t('project.result.field.providerErrorType') }}</dt><dd>{{ entry.executionFact.providerErrorType }}</dd></template>
                                      <template v-if="entry.executionFact?.providerCommandExitCode != null"><dt>{{ t('project.result.field.providerCommandExitCode') }}</dt><dd>{{ entry.executionFact.providerCommandExitCode }}</dd></template>
                                    </dl>
                                  </details>

                                  <details v-if="entry.executionFact" class="project-result-raw-output">
                                    <summary>{{ t('project.result.rawStdout') }}</summary>
                                    <pre>{{ entry.executionFact.stdout ?? t('project.result.emptyOutput') }}</pre>
                                  </details>
                                  <details v-if="entry.executionFact" class="project-result-raw-output">
                                    <summary>{{ t('project.result.rawStderr') }}</summary>
                                    <pre>{{ entry.executionFact.stderr ?? t('project.result.emptyOutput') }}</pre>
                                  </details>
                                </section>
                              </div>
                            </details>

                            <details class="project-result-verification">
                              <summary>{{ t('project.result.verificationScope') }}</summary>
                              <dl>
                                <dt>{{ t('project.result.verifiedItems') }}</dt>
                                <dd>{{ item.plan.finalSynthesisInput.verificationScope.verifies.join('；') || t('project.result.noItems') }}</dd>
                                <dt>{{ t('project.result.limitations') }}</dt>
                                <dd>{{ item.plan.finalSynthesisInput.verificationScope.limitations.join('；') || t('project.result.noItems') }}</dd>
                              </dl>
                            </details>
                          </div>
                        </details>

                        <details v-for="step in item.plan.steps" :key="`${item.plan.id}-${step.id}`" class="project-plan-step-details">
                          <summary @click="selectPlan(item.plan)">
                            <NIcon class="project-plan-step-details__chevron" aria-hidden="true"><ChevronRightIcon /></NIcon>
                            <span class="project-plan-step-message__copy">
                              <small>
                                {{ t('project.result.step', { number: step.sortOrder }) }}
                                <NTag size="tiny" :type="planTagType(step.status)">{{ planStepStatusLabel(step.status) }}</NTag>
                              </small>
                              <strong class="project-plan-step-message__title">{{ step.title || step.stepKey }}</strong>
                              <span class="project-plan-step-message__preview">{{ planStepPreviewLine(step) }}</span>
                            </span>
                          </summary>
                          <div class="project-plan-step-details__body">
                            <MarkdownMessage :content="planStepMessageContent(step)" variant="project" />
                            <details v-if="step.result || step.errorMessage" class="project-plan-step-record">
                              <summary>{{ t('project.result.stepRecord') }}</summary>
                              <pre v-if="step.result">{{ step.result }}</pre>
                              <pre v-if="step.errorMessage">{{ step.errorMessage }}</pre>
                            </details>
                          </div>
                        </details>

                        <NButton
                          v-if="!requiresSandboxConfirmation(item.plan) && !planTerminal(item.plan.status)"
                          size="small"
                          type="error"
                          secondary
                          :loading="cancellingPlanId === item.plan.id"
                          :disabled="cancellingPlanId !== null"
                          @click.stop="cancelProjectPlan(item.plan)"
                        >
                          {{ t('project.result.cancelRunning') }}
                        </NButton>
                      </div>
                    </details>

                    <NAlert
                      v-if="requiresSandboxConfirmation(item.plan)"
                      class="project-sandbox-confirmation"
                      type="warning"
                      :title="t('project.result.confirmTitle')"
                    >
                      <p>
                        {{ t('project.result.confirmCopy', { count: sandboxConfirmationStepCount(item.plan) }) }}
                      </p>
                      <NButton
                        type="warning"
                        size="small"
                        :loading="executingSandboxPlanId === item.plan.id"
                        :disabled="executingSandboxPlanId !== null"
                        @click.stop="confirmSandboxExecution(item.plan)"
                      >
                        {{ t('project.result.confirm') }}
                      </NButton>
                      <NButton
                        class="project-sandbox-cancel"
                        size="small"
                        :loading="cancellingPlanId === item.plan.id"
                        :disabled="cancellingPlanId !== null || executingSandboxPlanId !== null"
                        @click.stop="cancelProjectPlan(item.plan)"
                      >
                        {{ t('project.result.reject') }}
                      </NButton>
                    </NAlert>
                  </article>
                </div>
              </template>

              <NEmpty v-if="!loading.messages && !loading.plans && projectTimelineItems.length === 0" description="Ask the Project Agent to inspect the selected Project." />
              <NSpin v-if="loading.messages || loading.plans" size="small" />
            </div>
            <nav v-if="projectNavItems.length" class="project-content-nav" aria-label="Project conversation navigation">
              <button
                v-for="item in projectNavItems"
                :key="item.id"
                type="button"
                class="project-content-nav__item"
                :class="[`project-content-nav__item--${item.kind}`, { active: activeProjectNavId === item.id }]"
                :title="item.title"
                @click="scrollToProjectNavItem(item)"
              >
                <span>{{ item.label }}</span>
              </button>
            </nav>
          </div>
          <ProjectContextDebugPanel
            :snapshot="contextSnapshot"
            :loading="loading.context"
            :error="contextError"
            :title="t('project.context.title')"
            :refresh-label="t('project.context.refresh')"
            :loading-label="t('project.context.loading')"
            :empty-label="t('project.context.empty')"
            :current-label="t('project.context.current')"
            :recent-label="t('project.context.recent')"
            :summary-label="t('project.context.summary')"
            :project-label="t('project.context.project')"
            :evidence-label="t('project.context.evidence')"
            :memory-label="t('project.context.memory')"
            :sections-label="t('project.context.sections')"
            :dropped-label="t('project.context.dropped')"
            @refresh="loadContextDebug()"
          />
          <details class="v2-project-analysis" :open="projectAnalysisOpen || undefined" @toggle="syncProjectAnalysisOpen">
            <summary>
              <span>
                <strong>{{ t('project.v2Analysis.title') }}</strong>
                <small>{{ t('project.v2Analysis.readOnly') }}</small>
              </span>
              <NTag size="tiny" type="info">V2</NTag>
            </summary>
            <div class="v2-project-analysis__body">
              <p>{{ t('project.v2Analysis.description') }}</p>
              <NInput
                v-model:value="projectAnalysisForm.objective"
                type="textarea"
                :maxlength="2000"
                :autosize="{ minRows: 2, maxRows: 4 }"
                :placeholder="t('project.v2Analysis.objective')"
              />
              <NInput
                v-model:value="projectAnalysisForm.pathsText"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 5 }"
                :placeholder="t('project.v2Analysis.paths')"
              />
              <div class="v2-project-analysis__search">
                <NInput
                  v-model:value="projectAnalysisForm.searchQuery"
                  :maxlength="256"
                  clearable
                  :placeholder="t('project.v2Analysis.search')"
                />
                <NInputNumber
                  v-model:value="projectAnalysisForm.maxSearchResults"
                  :min="1"
                  :max="20"
                  :disabled="!projectAnalysisForm.searchQuery.trim()"
                />
              </div>
              <NAlert v-if="projectAnalysisError" type="error" :show-icon="false">
                {{ projectAnalysisError }}
              </NAlert>
              <section v-if="projectAnalysisOutcome" class="v2-project-analysis__outcome">
                <header>
                  <NTag size="tiny" :type="projectAnalysisOutcome.status === 'SUCCEEDED' ? 'success' : projectAnalysisOutcome.status === 'FAILED' ? 'error' : 'info'">
                    {{ projectAnalysisOutcome.status }}
                  </NTag>
                  <span>{{ t('project.v2Analysis.version') }} {{ shortHash(projectAnalysisOutcome.projectVersion) }}</span>
                </header>
                <MarkdownMessage v-if="projectAnalysisOutcome.finalText" :content="projectAnalysisOutcome.finalText" variant="project" />
                <p v-else-if="projectAnalysisOutcome.status === 'RUNNING'">{{ t('project.v2Analysis.running') }}</p>
              </section>
              <div class="v2-project-analysis__actions">
                <NButton
                  type="primary"
                  :loading="projectAnalysisStarting || projectAnalysisPolling"
                  :disabled="!activeProject || !projectAnalysisForm.objective.trim() || !projectAnalysisForm.pathsText.trim()"
                  @click="startProjectAnalysis"
                >
                  {{ t('project.v2Analysis.start') }}
                </NButton>
              </div>
            </div>
          </details>
          <div class="project-composer">
            <NInput v-model:value="chatInput" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" placeholder="Ask about this read-only Project..." @keydown="handleComposerKeydown" />
            <NButton type="primary" :loading="loading.send" :disabled="!chatInput.trim() || !activeProject" @click="sendChat">Send</NButton>
          </div>
        </section>
      </section>
    </main>

    <NModal v-model:show="createModalOpen" preset="card" class="project-create-modal" :mask-closable="!loading.create" :closable="!loading.create" :style="{ width: 'min(620px, calc(100vw - 28px))' }">
      <template #header>
        <div class="project-create-header">
          <strong>Create Project</strong>
          <span>Import an isolated copy of a folder into secure object storage.</span>
        </div>
      </template>
      <NForm class="project-create-form" label-placement="top" @submit.prevent="submitProject">
        <NAlert v-if="createError" type="error" closable @close="createError = ''">{{ createError }}</NAlert>
        <NFormItem label="Project name"><NInput v-model:value="newProject.name" placeholder="Name this project" /></NFormItem>
        <NFormItem label="Project folder">
          <div class="project-folder-field">
            <input ref="directoryInput" class="project-folder-input" type="file" webkitdirectory directory multiple @change="handleProjectFolderChange" />
            <div class="project-folder-picker" :class="{ 'project-folder-picker--selected': projectFolderFiles.length }">
              <span class="project-folder-picker__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24"><path d="M3 6.75A1.75 1.75 0 0 1 4.75 5h5l2 2h7.5A1.75 1.75 0 0 1 21 8.75v8.5A1.75 1.75 0 0 1 19.25 19H4.75A1.75 1.75 0 0 1 3 17.25V6.75Z" /></svg>
              </span>
              <div class="project-folder-picker__copy">
                <strong>{{ selectedFolderName || 'Choose a project folder' }}</strong>
                <small v-if="projectFolderFiles.length">
                  {{ uploadableProjectFiles.length }} files · {{ formattedProjectUploadSize }}
                  <template v-if="excludedProjectFileCount"> · {{ excludedProjectFileCount }} excluded</template>
                </small>
                <small v-else>Source code, notes, and research files are supported.</small>
              </div>
              <NButton secondary @click="pickProjectFolder">{{ projectFolderFiles.length ? 'Change' : 'Browse' }}</NButton>
            </div>
            <div class="project-folder-safety">
              <span aria-hidden="true">✓</span>
              <p><strong>Your original folder stays untouched.</strong> Yanban works only with the imported copy.</p>
            </div>
          </div>
        </NFormItem>
        <details class="project-create-advanced">
          <summary><span>Advanced filters</span><small>Optional include and ignore rules</small></summary>
          <div class="project-create-advanced__body">
            <NFormItem label="Include rules"><NInput v-model:value="newProject.includeRules" placeholder="**" /></NFormItem>
            <NFormItem label="Ignore rules"><NInput v-model:value="newProject.ignoreRules" placeholder=".git/**, target/**" /></NFormItem>
          </div>
        </details>
        <div class="project-create-actions">
          <NButton :disabled="loading.create" @click="closeCreateProjectModal">Cancel</NButton>
          <NButton type="primary" attr-type="submit" :loading="loading.create" :disabled="!newProject.name.trim() || uploadableProjectFiles.length === 0">Import Project</NButton>
        </div>
      </NForm>
    </NModal>

    <NModal v-model:show="deleteModalOpen" preset="card" title="Delete Project" :mask-closable="!loading.deleteProject" :closable="!loading.deleteProject" :style="{ width: 'min(480px, calc(100vw - 32px))' }">
      <p class="project-delete-copy">This removes <strong>{{ activeProject?.name }}</strong> from Project Workspace. The local source folder and every file inside it remain unchanged.</p>
      <NSpace justify="end">
        <NButton :disabled="loading.deleteProject" @click="deleteModalOpen = false">Cancel</NButton>
        <NButton type="error" :loading="loading.deleteProject" @click="removeActiveProject">Delete Project</NButton>
      </NSpace>
    </NModal>

    <NModal v-model:show="renameSessionModalOpen" preset="card" title="Rename conversation" :style="{ width: 'min(420px, calc(100vw - 32px))' }">
      <NSpace vertical :size="14">
        <NInput
          v-model:value="renameSessionDraft"
          maxlength="40"
          show-count
          placeholder="Conversation name"
          @keydown.enter.prevent="confirmRenameSession"
        />
        <NSpace justify="end">
          <NButton secondary @click="renameSessionModalOpen = false">Cancel</NButton>
          <NButton type="primary" :loading="loading.renameSession" @click="confirmRenameSession">Save</NButton>
        </NSpace>
      </NSpace>
    </NModal>

    <NModal v-model:show="applyModalOpen" preset="card" title="Apply selected Candidate changes" :mask-closable="!loading.applyCandidate" :closable="!loading.applyCandidate" :style="{ width: 'min(520px, calc(100vw - 32px))' }">
      <p class="project-delete-copy">
        This creates a new immutable Project version from {{ selectedChangeIndexes.size }} selected change{{ selectedChangeIndexes.size === 1 ? '' : 's' }}.
        The current and earlier versions remain available for rollback.
      </p>
      <NSpace justify="end">
        <NButton :disabled="loading.applyCandidate" @click="applyModalOpen = false">Cancel</NButton>
        <NButton type="primary" :loading="loading.applyCandidate" @click="confirmApplyCandidate">Create new version</NButton>
      </NSpace>
    </NModal>

    <NModal v-model:show="validationModalOpen" preset="card" title="Run Candidate verification in sandbox" :mask-closable="!loading.candidateValidation" :closable="!loading.candidateValidation" :style="{ width: 'min(560px, calc(100vw - 32px))' }">
      <p class="project-delete-copy">
        This materializes the trusted ProjectVersion plus {{ selectedChangeIndexes.size }} selected Candidate change{{ selectedChangeIndexes.size === 1 ? '' : 's' }} into an isolated work copy and runs {{ validationProfile }}.
        Networking and sensitive environment injection remain disabled. This does not apply or modify the Project.
      </p>
      <NSpace justify="end">
        <NButton :disabled="loading.candidateValidation" @click="validationModalOpen = false">Cancel</NButton>
        <NButton type="primary" :loading="loading.candidateValidation" @click="confirmCandidateValidation">Confirm and run</NButton>
      </NSpace>
    </NModal>

    <NModal v-model:show="rollbackModalOpen" preset="card" title="Rollback Project version" :mask-closable="!loading.rollback" :closable="!loading.rollback" :style="{ width: 'min(520px, calc(100vw - 32px))' }">
      <p class="project-delete-copy">
        Switch the current Project to revision {{ rollbackTarget?.id }} ({{ rollbackTarget ? shortHash(rollbackTarget.projectVersion) : '' }}).
        No revision or Candidate will be deleted or modified.
      </p>
      <NSpace justify="end">
        <NButton :disabled="loading.rollback" @click="rollbackModalOpen = false">Cancel</NButton>
        <NButton type="warning" :loading="loading.rollback" @click="confirmRollback">Rollback</NButton>
      </NSpace>
    </NModal>
  </AppLayout>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NAlert, NButton, NCheckbox, NDropdown, NEmpty, NForm, NFormItem, NIcon, NInput, NInputNumber, NModal, NSelect, NSpace, NSpin, NTag } from 'naive-ui';
import { ChevronRightIcon } from 'naive-ui/es/_internal/icons';
import AppLayout from '@/components/AppLayout.vue';
import MarkdownMessage from '@/components/MarkdownMessage.vue';
import ProjectContextDebugPanel from '@/components/ProjectContextDebugPanel.vue';
import { cancelPlan, confirmAndQueueSandboxPlan, deleteSession as deleteAgentSession, listMessages, listPlans, updateSession as updateAgentSession, type AgentContextSnapshotResponse, type AgentMessageResponse, type AgentPlanResponse, type AgentSessionResponse } from '@/api/agent';
import { candidateReviewFailure, getCandidateChange, isCandidateArtifactV1, listArtifacts, type ArtifactResponse, type CandidateArtifactResponse, type CandidateChangeType, type CandidateEvidenceRef, type CandidateReviewState } from '@/api/artifact';
import { applyProjectCandidate, cancelCandidateValidation, createCandidateValidation, createProjectSession, deleteProject, exportProjectRevision, filterProjectUploadFiles, getProjectManifest, listCandidateValidations, listProjectContextSnapshots, listProjectEvidence, listProjectRevisions, listProjectSessions, listProjects, readProjectFile, readV2ProjectReadAnalysisTurn, rejectCandidateValidation, rollbackProjectRevision, searchProject, sendProjectMessage, startV2ProjectReadAnalysisTurn, uploadProject, type CandidateValidationProfile, type CandidateValidationResponse, type ProjectEvidenceResponse, type ProjectFileResponse, type ProjectManifestResponse, type ProjectRevisionResponse, type ProjectSearchHit, type ProjectSummaryResponse, type V2ProjectReadAnalysisTurnResponse } from '@/api/project';
import { useAuthStore } from '@/stores/auth';
import { useI18n } from '@/composables/useI18n';
import {
  isControlledProjectPartial,
  isInternalRuntimeFailureText,
  isSandboxConfirmationRequiredText,
  projectAssistantPresentation,
  withoutInternalRuntimeCodes,
  withoutInternalProjectEvidenceRefs,
} from '@/utils/projectCompletion';
import { requiresSandboxConfirmation, sandboxConfirmationStepCount } from '@/utils/projectSandboxConfirmation';
import {
  answerStatusPresentation,
  effectivePlanResult,
  executionOutcomePresentation,
  groupSynthesisEvidence,
  hasTechnicalEvidenceFields,
  planUserStatusPresentation,
  taskOutcomePresentation,
} from '@/utils/projectResultPresentation';
import { candidateValidationCanApply } from '@/utils/candidateValidationCanApply';
import {
  isCurrentV2ProjectAnalysisRequest,
  isV2ProjectAnalysisTerminal,
  newV2ProjectAnalysisClientRequestId,
  normalizeV2ProjectAnalysisForm,
  pollV2ProjectAnalysis,
  type V2ProjectAnalysisRequestIdentity,
} from '@/utils/v2ProjectAnalysis';

type ProjectChatRole = 'user' | 'assistant' | 'process';
type ProjectInspectorTab = 'preview' | 'evidence' | 'changes' | 'versions';

interface ProjectChatMessage {
  localId: string;
  role: ProjectChatRole;
  content: string;
  pending?: boolean;
  processOpen?: boolean;
  processDone?: boolean;
  processStartedAt?: number;
  processElapsedMs?: number;
  technicalContent?: string;
  createdAt?: string;
}

interface ProjectWsChatEvent {
  type: 'ack' | 'process' | 'chunk' | 'reset' | 'replace' | 'done' | 'error' | 'debug';
  content?: string | null;
  assistantContent?: string | null;
  sessionId?: number | null;
  error?: string | null;
  clientRequestId?: string | null;
  projectEvidence?: ProjectEvidenceResponse[] | null;
  evidence?: ProjectEvidenceResponse[] | null;
  completionStatus?: 'VERIFIED' | 'PARTIAL' | 'INSUFFICIENT_EVIDENCE' | 'FAILED' | null;
  stopReason?: string | null;
  outcome?: string | null;
}

interface ProjectContentNavItem {
  id: string;
  label: string;
  title: string;
  kind: 'user' | 'assistant' | 'process' | 'step' | 'final';
  planId?: number;
}

interface CandidateReviewItem {
  artifact: ArtifactResponse;
  candidate: CandidateArtifactResponse | null;
  state: CandidateReviewState;
  error: string | null;
}

const authStore = useAuthStore();
const { isEnglish, t } = useI18n();
const route = useRoute();
const router = useRouter();
const projects = ref<ProjectSummaryResponse[]>([]);
const activeProjectId = ref<number | null>(null);
const projectSessions = ref<AgentSessionResponse[]>([]);
const activeSessionId = ref<number | null>(null);
const manifest = ref<ProjectManifestResponse | null>(null);
const selectedFile = ref<ProjectFileResponse | null>(null);
const searchQuery = ref('');
const searchResults = ref<ProjectSearchHit[]>([]);
const messages = ref<ProjectChatMessage[]>([]);
const contextSnapshot = ref<AgentContextSnapshotResponse | null>(null);
const contextError = ref('');
const plans = ref<AgentPlanResponse[]>([]);
const selectedPlan = ref<AgentPlanResponse | null>(null);
const executingSandboxPlanId = ref<number | null>(null);
const cancellingPlanId = ref<number | null>(null);
const evidence = ref<ProjectEvidenceResponse[]>([]);
const candidates = ref<CandidateReviewItem[]>([]);
const selectedCandidate = ref<CandidateReviewItem | null>(null);
const selectedChangeIndexes = ref<Set<number>>(new Set());
const candidateValidations = ref<CandidateValidationResponse[]>([]);
const selectedValidation = ref<CandidateValidationResponse | null>(null);
const validationProfile = ref<CandidateValidationProfile>('MAVEN_TEST');
const validationProfileOptions = [
  { label: 'Maven offline test', value: 'MAVEN_TEST' },
  { label: 'Maven offline verify', value: 'MAVEN_VERIFY' },
  { label: 'Java source compile and run', value: 'JAVA_SOURCE_RUN' },
  { label: 'Python source run', value: 'PYTHON_SOURCE_RUN' },
  { label: 'C source compile and run', value: 'C_SOURCE_RUN' },
  { label: 'C++ source compile and run', value: 'CPP_SOURCE_RUN' },
];
const revisions = ref<ProjectRevisionResponse[]>([]);
const applyModalOpen = ref(false);
const validationModalOpen = ref(false);
const rollbackModalOpen = ref(false);
const rollbackTarget = ref<ProjectRevisionResponse | null>(null);
const exportingRevisionId = ref<number | null>(null);
const applicationMessage = ref('');
const applicationMessageType = ref<'success' | 'warning' | 'error'>('success');
const validationMessage = ref('');
const validationMessageType = ref<'success' | 'warning' | 'error'>('success');
const revisionMessage = ref('');
const revisionMessageType = ref<'success' | 'warning' | 'error'>('success');
const inspectorTab = ref<ProjectInspectorTab>('preview');
const inspectorOpen = ref(true);
const chatInput = ref('');
const error = ref('');
const createModalOpen = ref(false);
const deleteModalOpen = ref(false);
const renameSessionModalOpen = ref(false);
const renameSessionId = ref<number | null>(null);
const renameSessionDraft = ref('');
const messagesContainer = ref<HTMLElement | null>(null);
const activeProjectNavId = ref<string | null>(null);
const projectContentRefs: Record<string, HTMLElement | null> = {};
let projectEpoch = 0;
let sessionFlight: Promise<number | null> | null = null;
let planPoll: number | null = null;
let candidateValidationPoll: number | null = null;
let currentSocket: WebSocket | null = null;
let activeClientRequestId: string | null = null;
let currentAssistantMessageId: string | null = null;
let currentProcessMessageId: string | null = null;
const V2_PROJECT_ANALYSIS_STORAGE_KEY = 'yanban.v2ProjectAnalysis.activeRequest.';
const projectAnalysisOpen = ref(false);
const projectAnalysisStarting = ref(false);
const projectAnalysisPolling = ref(false);
const projectAnalysisError = ref('');
const projectAnalysisOutcome = ref<V2ProjectReadAnalysisTurnResponse | null>(null);
const projectAnalysisForm = reactive({
  objective: '',
  pathsText: '',
  searchQuery: '',
  maxSearchResults: 10 as number | null,
});
let projectAnalysisAbortController: AbortController | null = null;
let projectAnalysisSequence = 0;
let projectAnalysisClientRequestId: string | null = null;

const loading = reactive({
  projects: false,
  sessions: false,
  manifest: false,
  file: false,
  search: false,
  messages: false,
  context: false,
  send: false,
  plans: false,
  evidence: false,
  candidates: false,
  revisions: false,
  applyCandidate: false,
  candidateValidation: false,
  cancelCandidateValidation: false,
  rejectCandidateValidation: false,
  rollback: false,
  create: false,
  deleteProject: false,
  renameSession: false,
});

const newProject = reactive({
  name: '',
  includeRules: '**',
  ignoreRules: '.git/**, target/**, node_modules/**',
});

type SidebarSection = 'projects' | 'conversations' | 'files';
const sidebarSections = reactive<Record<SidebarSection, boolean>>({
  projects: false,
  conversations: false,
  files: false,
});
const directoryInput = ref<HTMLInputElement | null>(null);
const projectFolderFiles = ref<File[]>([]);
const createError = ref('');
const selectedFolderName = computed(() => {
  const first = projectFolderFiles.value[0];
  if (!first) return '';
  const relativePath = first.webkitRelativePath || first.name;
  return relativePath.split(/[\\/]/)[0] || first.name;
});
const uploadableProjectFiles = computed(() => filterProjectUploadFiles(
  projectFolderFiles.value,
  splitRules(newProject.includeRules),
  splitRules(newProject.ignoreRules),
));
const excludedProjectFileCount = computed(() => projectFolderFiles.value.length - uploadableProjectFiles.value.length);
const projectUploadSize = computed(() => uploadableProjectFiles.value.reduce((total, file) => total + file.size, 0));
const formattedProjectUploadSize = computed(() => formatBytes(projectUploadSize.value));

const collapsedDirectories = ref<Set<string>>(new Set());
const collapsedDirectoriesByProject = new Map<number, Set<string>>();
const PROJECT_HEADER_COLLAPSED_KEY = 'yanban.project.headerCollapsed';
const DEFAULT_SESSION_TITLE = '\u65b0\u4f1a\u8bdd';
const projectHeaderCollapsed = ref(readStoredBoolean(PROJECT_HEADER_COLLAPSED_KEY, false));
const sessionMenuOptions = computed(() => [
  { label: isEnglish.value ? 'Rename' : '重命名', key: 'rename' },
  { label: isEnglish.value ? 'Delete' : '删除', key: 'delete' },
]);
const activeProject = computed(() => projects.value.find((item) => item.id === activeProjectId.value) || null);
const timelinePlans = computed(() => [...plans.value].sort((left, right) => new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime()));
const projectTimelineItems = computed(() => [
  ...messages.value.map((message, index) => ({
    type: 'message' as const,
    key: message.localId,
    message,
    sortAt: parseTimestamp(message.createdAt) ?? index,
    order: index,
  })),
  ...timelinePlans.value.map((plan, index) => ({
    type: 'plan' as const,
    key: `plan-${plan.id}`,
    plan,
    sortAt: parseTimestamp(plan.createdAt) ?? Number.MAX_SAFE_INTEGER - timelinePlans.value.length + index,
    order: messages.value.length + index,
  })),
].sort((left, right) => left.sortAt - right.sortAt || left.order - right.order));
const projectNavItems = computed<ProjectContentNavItem[]>(() => {
  return messages.value
    .filter((message) => message.role === 'user')
    .map((message, index) => ({
      id: message.localId,
      label: String(index + 1),
      title: abbreviateText(message.content || 'User message', 140),
      kind: 'user' as const,
    }));
});
const directoryPaths = computed(() => collectDirectoryPaths(manifest.value?.files || []));
const fileTree = computed(() => {
  const directories = new Set(directoryPaths.value);
  const rows: Array<{ key: string; name: string; path: string; sha256?: string; depth: number; directory: boolean }> = [];
  const walk = (prefix: string, depth: number) => {
    [...directories]
      .filter((item) => item.split('/').length === depth + 1 && item.startsWith(prefix))
      .sort()
      .forEach((dir) => {
        const parts = dir.split('/');
        rows.push({ key: `dir:${dir}`, name: parts[parts.length - 1] || dir, path: dir, depth, directory: true });
        if (!collapsedDirectories.value.has(dir)) walk(`${dir}/`, depth + 1);
      });

    (manifest.value?.files || [])
      .filter((file) => file.path.split('/').length === depth + 1 && file.path.startsWith(prefix))
      .forEach((file) => {
        const parts = file.path.split('/');
        rows.push({ key: file.path, name: parts[parts.length - 1] || file.path, path: file.path, sha256: file.sha256, depth, directory: false });
      });
  };

  walk('', 0);
  return rows;
});

watch(projectNavItems, async (items) => {
  await nextTick();
  const validIds = new Set(items.map((item) => item.id));
  Object.keys(projectContentRefs).forEach((id) => {
    if (!validIds.has(id)) delete projectContentRefs[id];
  });
  if (!items.some((item) => item.id === activeProjectNavId.value)) {
    activeProjectNavId.value = items[0]?.id || null;
  }
  handleProjectContentScroll();
}, { flush: 'post' });

function readStoredBoolean(key: string, fallback: boolean) {
  if (typeof window === 'undefined') return fallback;
  const value = window.localStorage.getItem(key);
  return value == null ? fallback : value === 'true';
}

function setProjectHeaderCollapsed(collapsed: boolean) {
  projectHeaderCollapsed.value = collapsed;
  if (typeof window !== 'undefined') window.localStorage.setItem(PROJECT_HEADER_COLLAPSED_KEY, String(collapsed));
}

function collectDirectoryPaths(files: ProjectManifestResponse['files']) {
  const directories = new Set<string>();
  files.forEach((file) => file.path.split('/').slice(0, -1).forEach((_, index, parts) => directories.add(parts.slice(0, index + 1).join('/'))));
  return [...directories].sort();
}

function storeCollapsedDirectories() {
  if (activeProjectId.value) collapsedDirectoriesByProject.set(activeProjectId.value, new Set(collapsedDirectories.value));
}

function toggleDirectory(path: string) {
  const next = new Set(collapsedDirectories.value);
  if (next.has(path)) next.delete(path);
  else next.add(path);
  collapsedDirectories.value = next;
  storeCollapsedDirectories();
}

function expandAllDirectories() {
  collapsedDirectories.value = new Set();
  storeCollapsedDirectories();
}

function collapseAllDirectories() {
  collapsedDirectories.value = new Set(directoryPaths.value);
  storeCollapsedDirectories();
}

function initializeDirectoryState(projectId: number, files: ProjectManifestResponse['files']) {
  const available = new Set(collectDirectoryPaths(files));
  const stored = collapsedDirectoriesByProject.get(projectId);
  collapsedDirectories.value = stored ? new Set([...stored].filter((path) => available.has(path))) : new Set(available);
  storeCollapsedDirectories();
}

function revealFileInTree(path: string) {
  const parts = path.split('/').slice(0, -1);
  if (parts.length === 0) return;
  const next = new Set(collapsedDirectories.value);
  parts.forEach((_, index) => next.delete(parts.slice(0, index + 1).join('/')));
  collapsedDirectories.value = next;
  storeCollapsedDirectories();
}

function apiError(value: unknown) {
  const item = value as { response?: { data?: { code?: string; message?: string }; headers?: Record<string, string> }; message?: string };
  const message = item.response?.data?.message || item.message;
  if (message === 'Network Error') {
    return 'The upload connection was interrupted. Check the folder size and try again.';
  }
  const code = item.response?.data?.code;
  const traceId = item.response?.headers?.['x-trace-id'];
  if (isInternalRuntimeFailureText([message, code, traceId ? `traceId=${traceId}` : ''].filter(Boolean).join(' '))) {
    return t('project.result.requestFailed');
  }
  const details = [code, traceId ? `traceId=${traceId}` : null].filter(Boolean).join(', ');
  return `${message || 'Request failed.'}${details ? ` (${details})` : ''}`;
}

function apiStatus(value: unknown) {
  return (value as { response?: { status?: number } }).response?.status;
}

function shortHash(value?: string) {
  return value ? `${value.slice(0, 10)}...` : '-';
}

function splitRules(value: string) {
  return value.split(/[\n,]/).map((item) => item.trim()).filter(Boolean);
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function toggleSidebarSection(section: SidebarSection) {
  sidebarSections[section] = !sidebarSections[section];
}

function pickProjectFolder() {
  directoryInput.value?.click();
}

function openCreateProjectModal() {
  createError.value = '';
  createModalOpen.value = true;
}

function closeCreateProjectModal() {
  if (!loading.create) createModalOpen.value = false;
}

function handleProjectFolderChange(event: Event) {
  const input = event.target as HTMLInputElement;
  projectFolderFiles.value = Array.from(input.files || []);
  createError.value = '';
  if (!newProject.name.trim() && selectedFolderName.value) {
    newProject.name = selectedFolderName.value;
  }
}

function resetProjectFolderSelection() {
  projectFolderFiles.value = [];
  if (directoryInput.value) directoryInput.value.value = '';
}

function planTagType(status: string): 'default' | 'success' | 'warning' | 'error' | 'info' {
  const value = status.toUpperCase();
  if (value.includes('COMPLETED') || value.includes('VERIFIED')) return 'success';
  if (value.includes('FAILED')) return 'error';
  if (value.includes('TIMED_OUT')) return 'error';
  if (value.includes('CANCELLED')) return 'warning';
  if (value.includes('REVIEWING')) return 'warning';
  if (value.includes('PENDING') || value.includes('RUNNING')) return 'info';
  if (value.includes('PARTIAL') || value.includes('DEGRADED') || value.includes('SKIPPED')) return 'warning';
  return 'default';
}

function abbreviateText(value: string, max = 220) {
  const compact = value.replace(/\s+/g, ' ').trim();
  return compact.length > max ? `${compact.slice(0, max - 3)}...` : compact;
}

function projectPlanItemId(planId: number, part: 'plan') {
  return `plan-${planId}-${part}`;
}

function setProjectContentRef(el: any, id: string) {
  if (el) {
    projectContentRefs[id] = el as HTMLElement;
  } else {
    delete projectContentRefs[id];
  }
}

function getProjectScrollContainer() {
  return messagesContainer.value;
}

function handleProjectContentScroll() {
  const container = getProjectScrollContainer();
  const items = projectNavItems.value;
  if (!container || items.length === 0) {
    activeProjectNavId.value = items[0]?.id || null;
    return;
  }

  const containerRect = container.getBoundingClientRect();
  const threshold = container.scrollTop + container.clientHeight * 0.22;
  let activeId = items[0].id;
  for (const item of items) {
    const element = projectContentRefs[item.id];
    if (!element) continue;
    const top = element.getBoundingClientRect().top - containerRect.top + container.scrollTop;
    if (top <= threshold) activeId = item.id;
    else break;
  }
  activeProjectNavId.value = activeId;
}

async function scrollToProjectNavItem(item: ProjectContentNavItem) {
  if (item.planId) {
    const plan = plans.value.find((candidate) => candidate.id === item.planId);
    if (plan) void selectPlan(plan);
  }
  await nextTick();
  const container = getProjectScrollContainer();
  const element = projectContentRefs[item.id];
  if (!container || !element) return;

  const containerRect = container.getBoundingClientRect();
  const top = element.getBoundingClientRect().top - containerRect.top + container.scrollTop - 10;
  activeProjectNavId.value = item.id;
  container.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
}

function planElapsedMs(plan: AgentPlanResponse) {
  const start = parseTimestamp(plan.startedAt || plan.createdAt);
  const end = parseTimestamp(plan.finishedAt || plan.updatedAt);
  if (start != null && !planTerminal(plan.status)) {
    return Math.max(0, Date.now() - start);
  }
  if (start != null && end != null && end >= start) {
    return end - start;
  }

  const stepStarts = plan.steps.map((step) => parseTimestamp(step.startedAt)).filter((value): value is number => value != null);
  const stepEnds = plan.steps.map((step) => parseTimestamp(step.finishedAt)).filter((value): value is number => value != null);
  if (stepStarts.length && stepEnds.length) {
    const firstStart = Math.min(...stepStarts);
    const lastEnd = Math.max(...stepEnds);
    return lastEnd >= firstStart ? lastEnd - firstStart : null;
  }
  return null;
}

function parseTimestamp(value?: string | null) {
  if (!value) return null;
  const timestamp = new Date(value).getTime();
  return Number.isFinite(timestamp) ? timestamp : null;
}

function formatPlanElapsed(value: number | null) {
  if (value == null) return t('project.result.duration.seconds', { seconds: 0 });
  const totalSeconds = Math.max(0, Math.round(value / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes <= 0) return t('project.result.duration.seconds', { seconds });
  return t('project.result.duration.minutes', { minutes, seconds });
}

function planProgressLabel(plan: AgentPlanResponse) {
  const completed = plan.steps.filter((step) => ['COMPLETED', 'SKIPPED'].includes(step.status.toUpperCase())).length;
  return t('project.result.progress', { completed, total: plan.steps.length });
}

function planUserStatus(plan: AgentPlanResponse) {
  return planUserStatusPresentation(plan, requiresSandboxConfirmation(plan));
}

function planExecutionResult(plan: AgentPlanResponse) {
  return executionOutcomePresentation(effectivePlanResult(plan).executionOutcome);
}

function planTaskResult(plan: AgentPlanResponse) {
  return taskOutcomePresentation(effectivePlanResult(plan).taskOutcome);
}

function planAnswerResult(plan: AgentPlanResponse) {
  return answerStatusPresentation(effectivePlanResult(plan).answerStatus);
}

function answerStatusResult(status: AgentPlanResponse['answerStatus']) {
  return answerStatusPresentation(status);
}

function planEvidenceGroups(plan: AgentPlanResponse) {
  return groupSynthesisEvidence(plan.finalSynthesisInput?.evidence || []);
}

function planStepStatusLabel(value: string) {
  const status = value.toUpperCase();
  if (status === 'PENDING') return t('project.result.step.queued');
  if (status === 'RUNNING' || status === 'REVIEWING') return t('project.result.step.running');
  if (status === 'COMPLETED') return t('project.result.step.completed');
  if (status === 'PARTIAL' || status === 'DEGRADED') return t('project.result.step.partial');
  if (status === 'FAILED' || status === 'TIMED_OUT') return t('project.result.step.failed');
  if (status === 'SKIPPED') return t('project.result.step.skipped');
  if (status === 'CANCELLED') return t('project.result.step.cancelled');
  return t('project.result.step.unknown');
}

function planStepPreviewLine(step: AgentPlanResponse['steps'][number]) {
  const source = withoutInternalRuntimeCodes(step.description || step.title || '');
  if (source.trim()) return abbreviateText(source, 140);
  return planStepStatusLabel(step.status);
}

function planStepMessageContent(step: AgentPlanResponse['steps'][number]) {
  const lines: string[] = [];
  if (step.description && step.description !== step.title) {
    lines.push(withoutInternalProjectEvidenceRefs(step.description));
  }
  lines.push(planStepStatusLabel(step.status));
  return lines.join('\n\n');
}

function toggleInspector(tab: ProjectInspectorTab) {
  if (inspectorOpen.value && inspectorTab.value === tab) {
    inspectorOpen.value = false;
    return;
  }
  inspectorTab.value = tab;
  inspectorOpen.value = true;
}

function showInspector(tab: ProjectInspectorTab) {
  inspectorTab.value = tab;
  inspectorOpen.value = true;
}

function selectCandidate(candidate: CandidateReviewItem) {
  selectedCandidate.value = candidate;
  selectedChangeIndexes.value = candidateCanSelect(candidate) && candidate.candidate
    ? new Set(candidate.candidate.changes.map((_, index) => index))
    : new Set();
  applicationMessage.value = '';
  validationMessage.value = '';
  candidateValidations.value = [];
  selectedValidation.value = null;
  showInspector('changes');
  void loadCandidateValidations(candidate);
}

function candidateCanSelect(item: CandidateReviewItem | null) {
  return !!item?.candidate
    && item.state === 'VALIDATED'
    && item.candidate.governanceStatus === 'VALIDATED'
    && item.candidate.applicationStatus === 'NOT_APPLIED'
    && item.candidate.validation.issues.length === 0
    && item.candidate.validation.checks.every((check) => check.status === 'PASSED');
}

function selectedIndexes() {
  return [...selectedChangeIndexes.value].sort((left, right) => left - right);
}

function applicableValidation(item: CandidateReviewItem | null) {
  if (!candidateCanSelect(item) || !item?.candidate) return null;
  const accepted = selectedIndexes();
  return candidateValidations.value.find((validation) => candidateValidationCanApply(validation, {
    projectVersion: item.candidate!.projectVersion,
    candidateFingerprint: item.candidate!.fingerprint,
    acceptedChangeIndexes: accepted,
  })) || null;
}

function candidateCanApply(item: CandidateReviewItem | null) {
  return applicableValidation(item) !== null;
}

function setChangeSelected(index: number, checked: boolean) {
  const next = new Set(selectedChangeIndexes.value);
  if (checked) next.add(index);
  else next.delete(index);
  selectedChangeIndexes.value = next;
}

function openApplyConfirmation() {
  if (!candidateCanApply(selectedCandidate.value) || selectedChangeIndexes.value.size === 0) return;
  applyModalOpen.value = true;
}

async function confirmApplyCandidate() {
  const projectId = activeProjectId.value;
  const item = selectedCandidate.value;
  const epoch = projectEpoch;
  const validation = applicableValidation(item);
  if (!projectId || !item?.candidate || !validation || selectedChangeIndexes.value.size === 0) return;
  loading.applyCandidate = true;
  applicationMessage.value = '';
  try {
    const accepted = selectedIndexes();
    const { data } = await applyProjectCandidate(projectId, item.artifact.id,
      item.candidate.projectVersion, accepted, validation.validationId, newClientRequestId());
    applyModalOpen.value = false;
    applicationMessageType.value = 'success';
    applicationMessage.value = `New Project version ${shortHash(data.resultVersion)} was published. Candidate ${item.artifact.id} remains NOT_APPLIED.`;
    selectedFile.value = null;
    searchResults.value = [];
    await Promise.all([loadManifest(epoch), loadRevisions()]);
    await Promise.all([
      activeSessionId.value ? loadCandidates(activeSessionId.value, epoch) : Promise.resolve(),
      selectedPlan.value ? selectPlan(selectedPlan.value, epoch) : Promise.resolve(),
    ]);
    showInspector('versions');
  } catch (cause) {
    const status = apiStatus(cause);
    applicationMessageType.value = status === 409 ? 'warning' : 'error';
    applicationMessage.value = status === 409
      ? `The Project changed before publication. Revalidate the Candidate and review it again. ${apiError(cause)}`
      : status === 422
        ? `The Candidate failed current validation and was not applied. ${apiError(cause)}`
        : apiError(cause);
  } finally {
    loading.applyCandidate = false;
  }
}

function candidateValidationTerminal(status: string) {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'CLEANUP_FAILED'].includes(status.toUpperCase());
}

function candidateValidationStatusType(status: string): 'success' | 'warning' | 'error' | 'info' {
  if (status === 'SUCCEEDED') return 'success';
  if (['FAILED', 'TIMED_OUT', 'CLEANUP_FAILED'].includes(status)) return 'error';
  if (['CANCELLED', 'CANCEL_REQUESTED', 'RETRY'].includes(status)) return 'warning';
  return 'info';
}

async function loadCandidateValidations(item = selectedCandidate.value, epoch = projectEpoch) {
  const projectId = activeProjectId.value;
  if (!projectId || !item?.candidate) return;
  try {
    const { data } = await listCandidateValidations(projectId, item.artifact.id);
    if (epoch !== projectEpoch || selectedCandidate.value?.artifact.id !== item.artifact.id) return;
    candidateValidations.value = data;
    const selectedId = selectedValidation.value?.validationId;
    selectedValidation.value = data.find((validation) => validation.validationId === selectedId) || data[0] || null;
    validationMessage.value = '';
  } catch (cause) {
    if (epoch !== projectEpoch) return;
    candidateValidations.value = [];
    selectedValidation.value = null;
    validationMessageType.value = apiStatus(cause) === 503 ? 'warning' : 'error';
    validationMessage.value = apiStatus(cause) === 503
      ? `Sandbox verification is unavailable. Candidate review and ordinary Project chat remain available. ${apiError(cause)}`
      : apiError(cause);
  }
}

async function confirmCandidateValidation() {
  const projectId = activeProjectId.value;
  const item = selectedCandidate.value;
  const epoch = projectEpoch;
  if (!projectId || !item?.candidate || !candidateCanSelect(item) || selectedChangeIndexes.value.size === 0) return;
  loading.candidateValidation = true;
  validationMessage.value = '';
  try {
    const { data } = await createCandidateValidation(projectId, item.artifact.id,
      item.candidate.projectVersion, validationProfile.value, selectedIndexes(), newClientRequestId());
    if (epoch !== projectEpoch) return;
    validationModalOpen.value = false;
    selectedValidation.value = data;
    await loadCandidateValidations(item, epoch);
    void pollCandidateValidation(item.artifact.id, data.validationId, epoch, 0);
  } catch (cause) {
    if (epoch !== projectEpoch) return;
    validationMessageType.value = apiStatus(cause) === 503 ? 'warning' : 'error';
    validationMessage.value = apiStatus(cause) === 503
      ? `Sandbox verification is unavailable. The Candidate was not applied. ${apiError(cause)}`
      : apiError(cause);
  } finally {
    if (epoch === projectEpoch) loading.candidateValidation = false;
  }
}

async function pollCandidateValidation(artifactId: number, validationId: string, epoch: number, attempt: number) {
  if (epoch !== projectEpoch || selectedCandidate.value?.artifact.id !== artifactId) return;
  await loadCandidateValidations(selectedCandidate.value, epoch);
  const current = candidateValidations.value.find((validation) => validation.validationId === validationId);
  if (!current || candidateValidationTerminal(current.status) || current.decisionStatus !== 'PENDING') return;
  if (attempt >= 450) {
    validationMessageType.value = 'warning';
    validationMessage.value = 'Sandbox verification is still pending. Refresh the Candidate to read its durable result.';
    return;
  }
  candidateValidationPoll = window.setTimeout(() => {
    void pollCandidateValidation(artifactId, validationId, epoch, attempt + 1);
  }, 2000);
}

async function cancelSelectedValidation() {
  const projectId = activeProjectId.value;
  const validation = selectedValidation.value;
  if (!projectId || !validation || candidateValidationTerminal(validation.status)) return;
  loading.cancelCandidateValidation = true;
  try {
    selectedValidation.value = (await cancelCandidateValidation(projectId, validation.validationId)).data;
    await loadCandidateValidations();
    void pollCandidateValidation(validation.artifactId, validation.validationId, projectEpoch, 0);
  } catch (cause) {
    validationMessageType.value = 'error'; validationMessage.value = apiError(cause);
  } finally { loading.cancelCandidateValidation = false; }
}

async function rejectSelectedValidation() {
  const projectId = activeProjectId.value;
  const validation = selectedValidation.value;
  if (!projectId || !validation || validation.decisionStatus !== 'PENDING') return;
  loading.rejectCandidateValidation = true;
  try {
    selectedValidation.value = (await rejectCandidateValidation(projectId, validation.validationId)).data;
    validationMessageType.value = 'warning';
    validationMessage.value = 'Candidate rejected. No Project version was created; the validation remains in history.';
    await loadCandidateValidations();
  } catch (cause) {
    validationMessageType.value = 'error'; validationMessage.value = apiError(cause);
  } finally { loading.rejectCandidateValidation = false; }
}

async function loadRevisions() {
  const projectId = activeProjectId.value;
  const epoch = projectEpoch;
  if (!projectId) return;
  loading.revisions = true;
  try {
    const { data } = await listProjectRevisions(projectId);
    if (epoch === projectEpoch && projectId === activeProjectId.value) revisions.value = data;
  } catch (cause) {
    if (epoch === projectEpoch) {
      revisionMessageType.value = 'error';
      revisionMessage.value = apiError(cause);
    }
  } finally {
    if (epoch === projectEpoch) loading.revisions = false;
  }
}

function openRollbackConfirmation(revision: ProjectRevisionResponse) {
  if (revision.current) return;
  rollbackTarget.value = revision;
  rollbackModalOpen.value = true;
}

async function confirmRollback() {
  const projectId = activeProjectId.value;
  const target = rollbackTarget.value;
  const currentVersion = manifest.value?.version;
  const epoch = projectEpoch;
  if (!projectId || !target || !currentVersion) return;
  loading.rollback = true;
  revisionMessage.value = '';
  try {
    const { data } = await rollbackProjectRevision(projectId, target.id, currentVersion, newClientRequestId());
    rollbackModalOpen.value = false;
    rollbackTarget.value = null;
    revisionMessageType.value = 'success';
    revisionMessage.value = `Current Project rolled back to ${shortHash(data.resultVersion)}. No history was deleted.`;
    selectedFile.value = null;
    searchResults.value = [];
    await Promise.all([loadManifest(epoch), loadRevisions()]);
    await Promise.all([
      activeSessionId.value ? loadCandidates(activeSessionId.value, epoch) : Promise.resolve(),
      selectedPlan.value ? selectPlan(selectedPlan.value, epoch) : Promise.resolve(),
    ]);
  } catch (cause) {
    const status = apiStatus(cause);
    revisionMessageType.value = status === 409 ? 'warning' : 'error';
    revisionMessage.value = status === 409
      ? `The current Project changed before rollback. Refresh versions and try again. ${apiError(cause)}`
      : apiError(cause);
  } finally {
    loading.rollback = false;
  }
}

async function downloadRevision(revision: ProjectRevisionResponse) {
  const project = activeProject.value;
  if (!project || exportingRevisionId.value != null) return;
  exportingRevisionId.value = revision.id;
  revisionMessage.value = '';
  try {
    const { data } = await exportProjectRevision(project.id, revision.id);
    const url = URL.createObjectURL(data);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${project.name.replace(/[^A-Za-z0-9._-]+/g, '-') || 'project'}-revision-${revision.id}.zip`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  } catch (cause) {
    revisionMessageType.value = 'error';
    revisionMessage.value = apiError(cause);
  } finally {
    exportingRevisionId.value = null;
  }
}

function formatDateTime(value: string) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}

function candidateTitle(item: CandidateReviewItem) {
  const firstPath = item.candidate?.changes[0]?.relativePath;
  if (!firstPath) return item.artifact.title || `Candidate ${item.artifact.id}`;
  const remaining = (item.candidate?.changes.length || 1) - 1;
  return remaining > 0 ? `${firstPath} +${remaining}` : firstPath;
}

function candidateStateType(state: CandidateReviewState): 'success' | 'warning' | 'error' | 'info' {
  if (state === 'VALIDATED') return 'success';
  if (state === 'STALE' || state === 'DRAFT') return 'warning';
  if (state === 'INVALID' || state === 'ERROR') return 'error';
  return 'info';
}

function candidateChangeType(type: CandidateChangeType): 'success' | 'warning' | 'error' {
  if (type === 'ADD') return 'success';
  if (type === 'DELETE') return 'error';
  return 'warning';
}

function candidateValidationLabel(candidate: CandidateArtifactResponse) {
  return candidate.validation.issues.length === 0
    && candidate.validation.checks.every((check) => check.status === 'PASSED') ? 'PASSED' : 'FAILED';
}

function candidateEvidence(candidate: CandidateArtifactResponse, relativePath: string): CandidateEvidenceRef[] {
  return candidate.changes.find((change) => change.relativePath === relativePath)?.evidenceRefs || [];
}

function syncProcessOpen(message: ProjectChatMessage, event: Event) {
  message.processOpen = (event.currentTarget as HTMLDetailsElement).open;
}

function processSummary(message: ProjectChatMessage) {
  if (!message.processDone) return 'Project Agent is working...';
  if (message.processElapsedMs != null) return `Process completed - ${(message.processElapsedMs / 1000).toFixed(1)}s`;
  return 'Process details';
}

function newClientRequestId() {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `project-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function appendChatMessage(message: ProjectChatMessage) {
  messages.value = [...messages.value, message];
}

function updateChatMessage(localId: string | null, update: (message: ProjectChatMessage) => void) {
  if (!localId) return;
  const message = messages.value.find((item) => item.localId === localId);
  if (message) update(message);
}

async function scrollMessagesToBottom() {
  await Promise.resolve();
  window.requestAnimationFrame(() => {
    if (messagesContainer.value) messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
  });
}

function appendAssistantChunk(content: string) {
  updateChatMessage(currentAssistantMessageId, (message) => {
    message.content += content;
    message.pending = false;
  });
  void scrollMessagesToBottom();
}

function replaceAssistantContent(content: string) {
  updateChatMessage(currentAssistantMessageId, (message) => {
    const presentation = projectAssistantPresentation(content, t('project.result.requestFailed'));
    message.content = presentation.content;
    message.technicalContent = presentation.technicalContent;
    message.pending = false;
  });
  void scrollMessagesToBottom();
}

function appendProcessLine(content: string) {
  const line = content.trim();
  if (!line) return;
  updateChatMessage(currentProcessMessageId, (message) => {
    const lines = message.content.split('\n').filter(Boolean);
    if (lines[lines.length - 1] !== line) message.content = [...lines, line].join('\n');
    message.processOpen = true;
    message.processDone = false;
  });
  void scrollMessagesToBottom();
}

function finishProcess() {
  updateChatMessage(currentProcessMessageId, (message) => {
    message.processDone = true;
    message.processOpen = false;
    message.processElapsedMs = message.processStartedAt ? Date.now() - message.processStartedAt : undefined;
  });
}

function closeProjectSocket() {
  const socket = currentSocket;
  currentSocket = null;
  activeClientRequestId = null;
  if (socket && socket.readyState < WebSocket.CLOSING) socket.close();
}

function projectToolLabel(name: string) {
  if (name === 'project_manifest') return 'Inspecting the authorized Project directory manifest.';
  if (name === 'project_search') return 'Searching authorized Project-relative files.';
  if (name === 'project_read_file') return 'Reading an authorized Project-relative file.';
  return 'Calling an authorized read-only Project tool.';
}

function parseToolNames(value: string | null) {
  if (!value) return [] as string[];
  try {
    const parsed = JSON.parse(value);
    if (!Array.isArray(parsed)) return [];
    return parsed.map((item) => String(item?.function?.name || item?.name || '')).filter(Boolean);
  } catch {
    return [];
  }
}

function toolResultLabel(content: string | null) {
  if (!content) return 'Project tool completed.';
  try {
    const payload = JSON.parse(content);
    if (payload?.success === false) return 'Project tool failed; the Agent may retry with another authorized read operation.';
    const path = payload?.relativePath;
    return path && path !== 'manifest' ? `Observed Project-relative path: ${path}` : 'Project tool completed.';
  } catch {
    return 'Project tool completed.';
  }
}

function buildProjectMessages(serverMessages: AgentMessageResponse[]) {
  const result: ProjectChatMessage[] = [];
  const hasProcessSummary = serverMessages.some((item) => item.role?.toLowerCase() === 'process');
  let pendingProcess: string[] = [];
  let pendingIds: number[] = [];
  let pendingCreatedAt: string | undefined;
  const flushProcess = () => {
    if (!pendingProcess.length) return;
    result.push({
      localId: `process-server-${pendingIds.join('-') || result.length}`,
      role: 'process',
      content: pendingProcess.join('\n'),
      processOpen: false,
      processDone: true,
      createdAt: pendingCreatedAt,
    });
    pendingProcess = [];
    pendingIds = [];
    pendingCreatedAt = undefined;
  };

  for (const item of serverMessages) {
    const role = item.role?.toLowerCase();
    if (role === 'assistant' && item.toolCallsJson) {
      if (!hasProcessSummary) {
        pendingCreatedAt ||= item.createdAt;
        pendingIds.push(item.id);
        pendingProcess.push(...(parseToolNames(item.toolCallsJson).map(projectToolLabel).length ? parseToolNames(item.toolCallsJson).map(projectToolLabel) : ['Selecting an authorized read-only Project tool.']));
      }
      continue;
    }
    if (role === 'tool') {
      if (!hasProcessSummary) {
        pendingCreatedAt ||= item.createdAt;
        pendingIds.push(item.id);
        pendingProcess.push(toolResultLabel(item.content));
      }
      continue;
    }
    if (role === 'system') continue;
    if (role === 'process') {
      pendingCreatedAt ||= item.createdAt;
      pendingIds.push(item.id);
      if (item.content?.trim()) pendingProcess.push(item.content.trim());
      continue;
    }
    if (role === 'user' || role === 'assistant') {
      flushProcess();
      if (role === 'assistant' && isSandboxConfirmationRequiredText(item.content)) continue;
      const rawContent = item.content || '';
      const presentation = role === 'assistant'
        ? projectAssistantPresentation(rawContent, t('project.result.requestFailed'))
        : { content: rawContent, technicalContent: undefined };
      result.push({
        localId: `server-${item.id}`,
        role,
        content: presentation.content,
        technicalContent: presentation.technicalContent,
        createdAt: item.createdAt,
      });
    }
  }

  flushProcess();
  return result;
}

function currentSessionId() {
  return activeSessionId.value;
}

function positiveQueryId(value: unknown): number | null {
  const raw = Array.isArray(value) ? value[0] : value;
  const parsed = typeof raw === 'string' ? Number(raw) : NaN;
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

function requestedSessionId(projectId: number): number | null {
  return positiveQueryId(route.query.projectId) === projectId
    ? positiveQueryId(route.query.sessionId)
    : null;
}

function syncProjectLocation(projectId: number | null, sessionId: number | null) {
  const query = { ...route.query };
  if (projectId) query.projectId = String(projectId);
  else delete query.projectId;
  if (sessionId) query.sessionId = String(sessionId);
  else delete query.sessionId;
  void router.replace({ query });
}

async function ensureSession() {
  if (sessionFlight) return sessionFlight;
  sessionFlight = ensureSessionOnce(false).finally(() => {
    sessionFlight = null;
  });
  return sessionFlight;
}

async function ensureSessionOnce(_recovered: boolean): Promise<number | null> {
  const project = activeProject.value;
  if (!project) return null;
  if (activeSessionId.value && projectSessions.value.some((item) => item.id === activeSessionId.value)) return activeSessionId.value;
  loading.sessions = true;
  try {
    projectSessions.value = (await listProjectSessions(project.id)).data;
    if (activeProjectId.value !== project.id) return null;
    if (projectSessions.value.length) {
      const requested = requestedSessionId(project.id);
      activeSessionId.value = projectSessions.value.find((item) => item.id === requested)?.id || projectSessions.value[0].id;
      syncProjectLocation(project.id, activeSessionId.value);
      return activeSessionId.value;
    }
    const created = (await createProjectSession(project.id, { title: DEFAULT_SESSION_TITLE, ragDisabled: true })).data;
    if (activeProjectId.value !== project.id) return null;
    projectSessions.value = [created];
    activeSessionId.value = created.id;
    syncProjectLocation(project.id, created.id);
    return created.id;
  } finally {
    if (activeProjectId.value === project.id) loading.sessions = false;
  }
}

async function loadProjects() {
  loading.projects = true;
  error.value = '';
  try {
    projects.value = (await listProjects()).data;
    const requested = positiveQueryId(route.query.projectId);
    const wanted = requested && projects.value.some((item) => item.id === requested)
      ? requested
      : activeProjectId.value && projects.value.some((item) => item.id === activeProjectId.value)
        ? activeProjectId.value
        : projects.value[0]?.id;
    if (wanted) await selectProject(wanted);
  } catch (cause) {
    error.value = apiError(cause);
  } finally {
    loading.projects = false;
  }
}

function currentProjectAnalysisIdentity(): V2ProjectAnalysisRequestIdentity {
  return {
    projectId: activeProjectId.value,
    sessionId: activeSessionId.value,
    clientRequestId: projectAnalysisClientRequestId,
    sequence: projectAnalysisSequence,
  };
}

function syncProjectAnalysisOpen(event: Event) {
  projectAnalysisOpen.value = (event.currentTarget as HTMLDetailsElement).open;
}

function stopProjectAnalysisPolling() {
  projectAnalysisSequence += 1;
  projectAnalysisAbortController?.abort();
  projectAnalysisAbortController = null;
  projectAnalysisPolling.value = false;
}

function storeProjectAnalysisRequest(projectId: number, sessionId: number, clientRequestId: string) {
  window.localStorage.setItem(`${V2_PROJECT_ANALYSIS_STORAGE_KEY}${projectId}.${sessionId}`, clientRequestId);
}

function clearStoredProjectAnalysisRequest(projectId: number, sessionId: number) {
  window.localStorage.removeItem(`${V2_PROJECT_ANALYSIS_STORAGE_KEY}${projectId}.${sessionId}`);
}

function storedProjectAnalysisRequest(projectId: number, sessionId: number) {
  const value = window.localStorage.getItem(`${V2_PROJECT_ANALYSIS_STORAGE_KEY}${projectId}.${sessionId}`);
  return value?.trim() || null;
}

async function runProjectAnalysisPolling(projectId: number, sessionId: number, clientRequestId: string) {
  stopProjectAnalysisPolling();
  projectAnalysisClientRequestId = clientRequestId;
  const sequence = projectAnalysisSequence;
  const expected = { projectId, sessionId, clientRequestId, sequence };
  const controller = new AbortController();
  projectAnalysisAbortController = controller;
  projectAnalysisPolling.value = true;
  try {
    const terminal = await pollV2ProjectAnalysis(
      async () => (await readV2ProjectReadAnalysisTurn(projectId, sessionId, clientRequestId)).data,
      {
        signal: controller.signal,
        onOutcome: (outcome) => {
          if (isCurrentV2ProjectAnalysisRequest(expected, currentProjectAnalysisIdentity())) {
            projectAnalysisOutcome.value = outcome;
          }
        },
      },
    );
    if (!isCurrentV2ProjectAnalysisRequest(expected, currentProjectAnalysisIdentity())) return;
    projectAnalysisOutcome.value = terminal;
    clearStoredProjectAnalysisRequest(projectId, sessionId);
    if (terminal.status === 'SUCCEEDED') {
      await loadMessages(sessionId, projectEpoch).catch(() => undefined);
    }
  } catch (cause) {
    if (controller.signal.aborted) return;
    if (isCurrentV2ProjectAnalysisRequest(expected, currentProjectAnalysisIdentity())) {
      projectAnalysisError.value = apiError(cause);
    }
  } finally {
    if (isCurrentV2ProjectAnalysisRequest(expected, currentProjectAnalysisIdentity())) {
      projectAnalysisPolling.value = false;
      projectAnalysisAbortController = null;
    }
  }
}

async function recoverProjectAnalysis(projectId: number, sessionId: number) {
  const clientRequestId = storedProjectAnalysisRequest(projectId, sessionId);
  if (!clientRequestId) return;
  projectAnalysisOpen.value = true;
  projectAnalysisError.value = '';
  await runProjectAnalysisPolling(projectId, sessionId, clientRequestId);
}

async function startProjectAnalysis() {
  const projectId = activeProjectId.value;
  if (!projectId || projectAnalysisStarting.value || projectAnalysisPolling.value) return;
  const epoch = projectEpoch;
  const clientRequestId = newV2ProjectAnalysisClientRequestId();
  projectAnalysisStarting.value = true;
  projectAnalysisError.value = '';
  projectAnalysisOutcome.value = null;
  try {
    const sessionId = await ensureSession();
    if (!sessionId || epoch !== projectEpoch || projectId !== activeProjectId.value) return;
    const request = normalizeV2ProjectAnalysisForm(projectAnalysisForm, clientRequestId);
    stopProjectAnalysisPolling();
    projectAnalysisClientRequestId = clientRequestId;
    const sequence = projectAnalysisSequence;
    const expected = { projectId, sessionId, clientRequestId, sequence };
    storeProjectAnalysisRequest(projectId, sessionId, clientRequestId);
    const response = (await startV2ProjectReadAnalysisTurn(projectId, sessionId, request)).data;
    if (!isCurrentV2ProjectAnalysisRequest(expected, currentProjectAnalysisIdentity())) return;
    projectAnalysisOutcome.value = response;
    if (isV2ProjectAnalysisTerminal(response)) {
      clearStoredProjectAnalysisRequest(projectId, sessionId);
      if (response.status === 'SUCCEEDED') {
        await loadMessages(sessionId, epoch).catch(() => undefined);
      }
      return;
    }
    await runProjectAnalysisPolling(projectId, sessionId, clientRequestId);
  } catch (cause) {
    if (epoch === projectEpoch && projectId === activeProjectId.value) {
      projectAnalysisError.value = apiError(cause);
    }
  } finally {
    if (epoch === projectEpoch && projectId === activeProjectId.value) {
      projectAnalysisStarting.value = false;
    }
  }
}

async function selectProject(projectId: number) {
  stopProjectAnalysisPolling();
  projectAnalysisStarting.value = false;
  projectAnalysisClientRequestId = null;
  projectAnalysisOutcome.value = null;
  projectAnalysisError.value = '';
  closeProjectSocket();
  currentAssistantMessageId = null;
  currentProcessMessageId = null;
  projectEpoch++;
  sessionFlight = null;
  if (planPoll != null) {
    window.clearTimeout(planPoll);
    planPoll = null;
  }
  if (candidateValidationPoll != null) {
    window.clearTimeout(candidateValidationPoll);
    candidateValidationPoll = null;
  }
  loading.file = false;
  loading.search = false;
  loading.send = false;
  activeProjectId.value = projectId;
  activeSessionId.value = null;
  projectSessions.value = [];
  collapsedDirectories.value = new Set(collapsedDirectoriesByProject.get(projectId) || []);
  manifest.value = null;
  selectedFile.value = null;
  searchResults.value = [];
  messages.value = [];
  resetContextDebug();
  plans.value = [];
  evidence.value = [];
  candidates.value = [];
  revisions.value = [];
  selectedPlan.value = null;
  selectedCandidate.value = null;
  selectedChangeIndexes.value = new Set();
  candidateValidations.value = [];
  selectedValidation.value = null;
  applicationMessage.value = '';
  validationMessage.value = '';
  revisionMessage.value = '';
  inspectorTab.value = 'preview';
  inspectorOpen.value = true;
  const epoch = projectEpoch;
  await Promise.all([loadManifest(epoch), loadConversation(epoch), loadRevisions()]);
}

async function loadManifest(epoch = projectEpoch) {
  const projectId = activeProjectId.value;
  if (!projectId) return;
  loading.manifest = true;
  try {
    const value = (await getProjectManifest(projectId)).data;
    if (epoch === projectEpoch && projectId === activeProjectId.value) {
      manifest.value = value;
      initializeDirectoryState(projectId, value.files);
    }
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  } finally {
    if (epoch === projectEpoch) loading.manifest = false;
  }
}

async function openFile(path: string) {
  const projectId = activeProjectId.value;
  const epoch = projectEpoch;
  if (!projectId) return;
  revealFileInTree(path);
  loading.file = true;
  try {
    const value = (await readProjectFile(projectId, path)).data;
    if (epoch === projectEpoch && projectId === activeProjectId.value) {
      selectedFile.value = value;
      showInspector('preview');
    }
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  } finally {
    if (epoch === projectEpoch) loading.file = false;
  }
}

async function runSearch() {
  const projectId = activeProjectId.value;
  const epoch = projectEpoch;
  if (!projectId || !searchQuery.value.trim()) {
    searchResults.value = [];
    return;
  }
  loading.search = true;
  try {
    const value = (await searchProject(projectId, searchQuery.value.trim())).data;
    if (epoch === projectEpoch && projectId === activeProjectId.value) searchResults.value = value;
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  } finally {
    if (epoch === projectEpoch) loading.search = false;
  }
}

async function loadConversation(epoch = projectEpoch) {
  try {
    const sessionId = await ensureSession();
    if (!sessionId || epoch !== projectEpoch) return;
    loading.messages = true;
    loading.plans = true;
    await Promise.all([loadMessages(sessionId, epoch), loadPlans(sessionId, epoch), loadCandidates(sessionId, epoch)]);
    if (epoch === projectEpoch && activeProjectId.value) {
      void recoverProjectAnalysis(activeProjectId.value, sessionId);
    }
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  } finally {
    if (epoch === projectEpoch) {
      loading.messages = false;
      loading.plans = false;
    }
  }
}

async function loadMessages(sessionId = currentSessionId(), epoch = projectEpoch) {
  if (!sessionId) return;
  const value = (await listMessages(sessionId, { limit: 100, view: 'all' })).data;
  if (epoch === projectEpoch) {
    messages.value = buildProjectMessages(value);
    await scrollMessagesToBottom();
  }
  await loadContextDebug(sessionId, epoch);
}

async function loadContextDebug(sessionId = currentSessionId(), epoch = projectEpoch) {
  const projectId = activeProjectId.value;
  if (!projectId || !sessionId) {
    resetContextDebug();
    return;
  }
  loading.context = true;
  contextError.value = '';
  try {
    const snapshots = (await listProjectContextSnapshots(projectId, sessionId, 1)).data;
    if (epoch === projectEpoch && projectId === activeProjectId.value && sessionId === activeSessionId.value) {
      contextSnapshot.value = snapshots[0] || null;
    }
  } catch (cause) {
    if (epoch === projectEpoch && projectId === activeProjectId.value && sessionId === activeSessionId.value) {
      contextSnapshot.value = null;
      contextError.value = apiError(cause);
    }
  } finally {
    if (epoch === projectEpoch && projectId === activeProjectId.value) loading.context = false;
  }
}

function resetContextDebug() {
  contextSnapshot.value = null;
  contextError.value = '';
  loading.context = false;
}

async function loadPlans(sessionId = currentSessionId(), epoch = projectEpoch) {
  if (!sessionId) return;
  const value = (await listPlans(sessionId)).data;
  if (epoch === projectEpoch) {
    plans.value = value;
    const preserved = selectedPlan.value
      ? value.find((item) => item.id === selectedPlan.value?.id) || null
      : null;
    const restored = preserved || value[0] || null;
    if (restored && selectedPlan.value?.id !== restored.id) {
      await selectPlan(restored, epoch);
    } else {
      selectedPlan.value = restored;
    }
  }
}

function buildProjectWebSocketUrl(projectId: number, token: string) {
  const origin = window.location.origin.replace(/^http/, 'ws');
  return `${origin}/api/v1/ws/projects/${projectId}/chat?token=${encodeURIComponent(token)}`;
}

async function sendProjectWebSocket(projectId: number, sessionId: number, content: string, clientRequestId: string) {
  const token = authStore.token || localStorage.getItem('yanban_access_token');
  if (!token) throw new Error('Not authenticated.');
  closeProjectSocket();
  activeClientRequestId = clientRequestId;
  await new Promise<void>((resolve, reject) => {
    let settled = false;
    let acknowledged = false;
    const socket = new WebSocket(buildProjectWebSocketUrl(projectId, token));
    currentSocket = socket;
    const timeout = window.setTimeout(() => {
      if (!acknowledged && !settled) {
        settled = true;
        socket.close();
        reject(new Error('Project streaming connection timed out.'));
      }
    }, 8000);
    const cleanup = () => {
      window.clearTimeout(timeout);
      if (currentSocket === socket) currentSocket = null;
    };
    const fail = (message: string) => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(new Error(message));
    };
    socket.onopen = () => socket.send(JSON.stringify({ sessionId, content, ragDisabled: true, clientRequestId }));
    socket.onmessage = (event) => {
      let payload: ProjectWsChatEvent;
      try {
        payload = JSON.parse(event.data) as ProjectWsChatEvent;
      } catch {
        fail('Project streaming returned an invalid event.');
        socket.close();
        return;
      }
      if (payload.clientRequestId && payload.clientRequestId !== clientRequestId) return;
      if (payload.type === 'ack') {
        acknowledged = true;
        window.clearTimeout(timeout);
        return;
      }
      if (payload.type === 'process' && payload.content) {
        appendProcessLine(payload.content);
        return;
      }
      if (payload.type === 'reset') {
        replaceAssistantContent('');
        return;
      }
      if (payload.type === 'replace') {
        replaceAssistantContent(payload.assistantContent || payload.content || '');
        return;
      }
      if (payload.type === 'chunk' && payload.content) {
        appendAssistantChunk(payload.content);
        return;
      }
      if (payload.type === 'error') {
        if (isSandboxConfirmationRequiredText(payload.error)) {
          replaceAssistantContent('');
          finishProcess();
          if (!settled) {
            settled = true;
            cleanup();
            resolve();
          }
          socket.close();
          return;
        }
        fail(payload.error || 'Project Agent request failed.');
        socket.close();
        return;
      }
      if (payload.type === 'done') {
        if (payload.assistantContent != null) replaceAssistantContent(payload.assistantContent);
        const projectedEvidence = payload.projectEvidence || payload.evidence;
        if (projectedEvidence) evidence.value = projectedEvidence;
        finishProcess();
        if (!settled) {
          settled = true;
          cleanup();
          resolve();
        }
        socket.close();
      }
    };
    socket.onerror = () => fail(acknowledged ? 'Project streaming connection failed.' : 'Project streaming is unavailable.');
    socket.onclose = () => {
      cleanup();
      if (!settled) fail('Project streaming connection closed before completion.');
    };
  });
}

async function sendProjectHttp(projectId: number, sessionId: number, content: string, clientRequestId: string) {
  const response = (await sendProjectMessage(projectId, sessionId, { content, ragDisabled: true, clientRequestId })).data;
  evidence.value = response.projectEvidence || [];
  if (isSandboxConfirmationRequiredText(response.errorMessage)
      || isSandboxConfirmationRequiredText(response.assistantContent)) {
    replaceAssistantContent('');
    return;
  }
  if (response.assistantContent != null) replaceAssistantContent(response.assistantContent);
  if (!response.success && !isControlledProjectPartial(response)) {
    throw new Error(response.errorMessage || 'Project Agent request failed.');
  }
}

async function sendProjectWithFallback(projectId: number, sessionId: number, content: string, clientRequestId: string) {
  try {
    await sendProjectWebSocket(projectId, sessionId, content, clientRequestId);
  } catch {
    appendProcessLine('Streaming connection unavailable; reconciling through the HTTP fallback.');
    await sendProjectHttp(projectId, sessionId, content, clientRequestId);
  }
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return;
  event.preventDefault();
  void sendChat();
}

async function sendChat() {
  const projectId = activeProjectId.value;
  const content = chatInput.value.trim();
  if (!projectId || !content || loading.send) return;
  const epoch = projectEpoch;
  let requestId: string | null = null;
  loading.send = true;
  error.value = '';
  try {
    const sessionId = await ensureSession();
    if (!sessionId || epoch !== projectEpoch) return;
    chatInput.value = '';
    requestId = newClientRequestId();
    activeClientRequestId = requestId;
    currentProcessMessageId = `process-${requestId}`;
    currentAssistantMessageId = `assistant-${requestId}`;
    const createdAt = new Date().toISOString();
    appendChatMessage({ localId: `user-${requestId}`, role: 'user', content, createdAt });
    appendChatMessage({ localId: currentProcessMessageId, role: 'process', content: 'Starting authenticated read-only Project request.', processOpen: false, processDone: false, processStartedAt: Date.now(), createdAt });
    appendChatMessage({ localId: currentAssistantMessageId, role: 'assistant', content: '', pending: true, createdAt });
    await scrollMessagesToBottom();
    await sendProjectWithFallback(projectId, sessionId, content, requestId);
    finishProcess();
    if (epoch !== projectEpoch) return;
    await Promise.all([
      loadMessages(sessionId, epoch).catch(() => undefined),
      loadPlans(sessionId, epoch).catch(() => undefined),
      loadCandidates(sessionId, epoch).catch(() => undefined),
    ]);
  } catch (cause) {
    if (requestId && activeClientRequestId === requestId) finishProcess();
    if (epoch === projectEpoch) {
      await Promise.all([
        loadMessages(currentSessionId(), epoch).catch(() => undefined),
        loadPlans(currentSessionId(), epoch).catch(() => undefined),
      ]);
      if (!messages.value.some((item) => item.role === 'assistant' && item.content)) chatInput.value = content;
      error.value = apiError(cause);
    }
  } finally {
    if (epoch === projectEpoch) loading.send = false;
    if (requestId && activeClientRequestId === requestId) {
      currentAssistantMessageId = null;
      currentProcessMessageId = null;
      closeProjectSocket();
    }
  }
}

async function selectPlan(plan: AgentPlanResponse, epoch = projectEpoch) {
  const projectId = activeProjectId.value;
  selectedPlan.value = plan;
  if (!projectId) return;
  loading.evidence = true;
  try {
    const value = (await listProjectEvidence(projectId, plan.id)).data;
    if (epoch === projectEpoch && projectId === activeProjectId.value && selectedPlan.value?.id === plan.id) {
      evidence.value = value;
    }
  } catch (cause) {
    if (epoch === projectEpoch && projectId === activeProjectId.value && selectedPlan.value?.id === plan.id) {
      error.value = apiError(cause);
      evidence.value = [];
    }
  } finally {
    if (epoch === projectEpoch && projectId === activeProjectId.value) loading.evidence = false;
  }
}

async function confirmSandboxExecution(plan: AgentPlanResponse) {
  if (!requiresSandboxConfirmation(plan) || executingSandboxPlanId.value !== null) return;
  const sessionId = currentSessionId();
  if (!sessionId || plan.sessionId !== sessionId) {
    error.value = 'This plan does not belong to the active Project conversation.';
    return;
  }
  const epoch = projectEpoch;
  executingSandboxPlanId.value = plan.id;
  error.value = '';
  try {
    const response = await confirmAndQueueSandboxPlan(plan.id, newClientRequestId());
    if (epoch !== projectEpoch) return;
    selectedPlan.value = response.data;
    await Promise.all([loadMessages(sessionId, epoch), loadPlans(sessionId, epoch)]);
    const refreshed = plans.value.find((item) => item.id === plan.id);
    if (refreshed) await selectPlan(refreshed, epoch);
    void pollPlanUntilTerminal(sessionId, plan.id, epoch, 0);
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  } finally {
    if (epoch === projectEpoch) executingSandboxPlanId.value = null;
  }
}

async function cancelProjectPlan(plan: AgentPlanResponse) {
  if (planTerminal(plan.status) || cancellingPlanId.value !== null) return;
  const sessionId = currentSessionId();
  if (!sessionId || plan.sessionId !== sessionId) {
    error.value = 'This plan does not belong to the active Project conversation.';
    return;
  }
  const epoch = projectEpoch;
  cancellingPlanId.value = plan.id;
  error.value = '';
  try {
    const response = await cancelPlan(plan.id);
    if (epoch !== projectEpoch) return;
    selectedPlan.value = response.data;
    await Promise.all([loadMessages(sessionId, epoch), loadPlans(sessionId, epoch)]);
    const refreshed = plans.value.find((item) => item.id === plan.id);
    if (refreshed) await selectPlan(refreshed, epoch);
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  } finally {
    if (epoch === projectEpoch) cancellingPlanId.value = null;
  }
}

async function loadCandidates(sessionId: number, epoch = projectEpoch) {
  loading.candidates = true;
  try {
    const artifacts = (await listArtifacts(sessionId)).data.filter((item) => item.sourceType === 'CANDIDATE_CHANGESET');
    const details = await Promise.all(artifacts.map(async (artifact): Promise<CandidateReviewItem> => {
      try {
        const response = (await getCandidateChange(artifact.id)).data;
        if (!isCandidateArtifactV1(response)) {
          return { artifact, candidate: null, state: 'INVALID', error: 'Unsupported or incomplete Candidate schema. This artifact is not presented as validated.' };
        }
        if (response.projectId !== activeProjectId.value) {
          return { artifact, candidate: null, state: 'ERROR', error: 'Candidate belongs to a different Project and was rejected.' };
        }
        return { artifact, candidate: response, state: response.governanceStatus, error: null };
      } catch (cause) {
        return { artifact, candidate: null, state: candidateReviewFailure(apiStatus(cause)), error: apiError(cause) };
      }
    }));
    if (epoch !== projectEpoch) return;
    candidates.value = details;
    if (selectedCandidate.value) {
      selectedCandidate.value = candidates.value.find((item) => item.artifact.id === selectedCandidate.value?.artifact.id) || null;
      if (!candidateCanSelect(selectedCandidate.value)) selectedChangeIndexes.value = new Set();
      else if (selectedCandidate.value?.candidate) {
        selectedChangeIndexes.value = new Set([...selectedChangeIndexes.value]
          .filter((index) => index < selectedCandidate.value!.candidate!.changes.length));
      }
      if (selectedCandidate.value) await loadCandidateValidations(selectedCandidate.value, epoch);
    }
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  } finally {
    if (epoch === projectEpoch) loading.candidates = false;
  }
}

function planTerminal(status: string) {
  return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status.toUpperCase());
}

async function pollPlanUntilTerminal(sessionId: number, planId: number, epoch: number, attempt: number) {
  if (epoch !== projectEpoch) return;
  await loadPlans(sessionId, epoch);
  const plan = plans.value.find((item) => item.id === planId);
  if (!plan) return;
  await selectPlan(plan);
  if (requiresSandboxConfirmation(plan)) return;
  if (planTerminal(plan.status)) {
    await Promise.all([loadMessages(sessionId, epoch), loadCandidates(sessionId, epoch)]);
    return;
  }
  if (attempt >= 150) {
    error.value = 'Plan is still running beyond the expected five-minute window; use Refresh to check its latest status.';
    return;
  }
  planPoll = window.setTimeout(() => {
    void pollPlanUntilTerminal(sessionId, planId, epoch, attempt + 1);
  }, 2000);
}

async function refreshCandidates() {
  const sessionId = currentSessionId();
  if (sessionId) await loadCandidates(sessionId);
}

async function selectConversation(sessionId: number) {
  if (sessionId === activeSessionId.value || loading.send) return;
  stopProjectAnalysisPolling();
  projectAnalysisStarting.value = false;
  projectAnalysisClientRequestId = null;
  projectAnalysisOutcome.value = null;
  projectAnalysisError.value = '';
  closeProjectSocket();
  currentAssistantMessageId = null;
  currentProcessMessageId = null;
  projectEpoch++;
  sessionFlight = null;
  if (candidateValidationPoll != null) {
    window.clearTimeout(candidateValidationPoll);
    candidateValidationPoll = null;
  }
  activeSessionId.value = sessionId;
  syncProjectLocation(activeProjectId.value, sessionId);
  messages.value = [];
  resetContextDebug();
  plans.value = [];
  evidence.value = [];
  candidates.value = [];
  selectedPlan.value = null;
  selectedCandidate.value = null;
  candidateValidations.value = [];
  selectedValidation.value = null;
  const epoch = projectEpoch;
  loading.messages = true;
  loading.plans = true;
  try {
    await Promise.all([loadMessages(sessionId, epoch), loadPlans(sessionId, epoch), loadCandidates(sessionId, epoch)]);
    if (epoch === projectEpoch && activeProjectId.value) {
      void recoverProjectAnalysis(activeProjectId.value, sessionId);
    }
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  } finally {
    if (epoch === projectEpoch) {
      loading.messages = false;
      loading.plans = false;
    }
  }
}

async function startNewConversation() {
  const project = activeProject.value;
  if (!project || loading.send) return;
  stopProjectAnalysisPolling();
  projectAnalysisStarting.value = false;
  projectAnalysisClientRequestId = null;
  projectAnalysisOutcome.value = null;
  projectAnalysisError.value = '';
  closeProjectSocket();
  currentAssistantMessageId = null;
  currentProcessMessageId = null;
  projectEpoch++;
  sessionFlight = null;
  messages.value = [];
  resetContextDebug();
  plans.value = [];
  evidence.value = [];
  candidates.value = [];
  selectedPlan.value = null;
  selectedCandidate.value = null;
  const epoch = projectEpoch;
  loading.sessions = true;
  try {
    const created = (await createProjectSession(project.id, { title: DEFAULT_SESSION_TITLE, ragDisabled: true })).data;
    if (epoch !== projectEpoch) return;
    projectSessions.value = [created, ...projectSessions.value.filter((item) => item.id !== created.id)];
    activeSessionId.value = created.id;
    syncProjectLocation(project.id, created.id);
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  } finally {
    if (epoch === projectEpoch) loading.sessions = false;
  }
}

async function handleSessionMenuSelect(key: string | number, session: AgentSessionResponse) {
  if (key === 'rename') {
    openRenameSession(session);
    return;
  }
  if (key === 'delete') {
    await deleteConversation(session);
  }
}

function openRenameSession(session: AgentSessionResponse) {
  renameSessionId.value = session.id;
  renameSessionDraft.value = session.title || '';
  renameSessionModalOpen.value = true;
}

async function confirmRenameSession() {
  const title = renameSessionDraft.value.trim();
  if (!renameSessionId.value || !title) {
    error.value = 'Conversation name is required.';
    return;
  }
  loading.renameSession = true;
  error.value = '';
  try {
    const { data } = await updateAgentSession(renameSessionId.value, { title });
    replaceProjectSession(data);
    renameSessionModalOpen.value = false;
  } catch (cause) {
    error.value = apiError(cause);
  } finally {
    loading.renameSession = false;
  }
}

async function deleteConversation(session: AgentSessionResponse) {
  if (loading.send) {
    error.value = 'Current Project Agent request is still running. Please wait before deleting a conversation.';
    return;
  }
  const sessionTitle = session.title || `Conversation #${session.id}`;
  if (!window.confirm(`Delete "${sessionTitle}"?`)) {
    return;
  }
  const wasActive = activeSessionId.value === session.id;
  error.value = '';
  try {
    await deleteAgentSession(session.id);
    projectSessions.value = projectSessions.value.filter((item) => item.id !== session.id);
    if (!wasActive) return;
    stopProjectAnalysisPolling();
    projectAnalysisStarting.value = false;
    projectAnalysisClientRequestId = null;
    projectAnalysisOutcome.value = null;
    closeProjectSocket();
    currentAssistantMessageId = null;
    currentProcessMessageId = null;
    projectEpoch++;
    sessionFlight = null;
    messages.value = [];
    resetContextDebug();
    plans.value = [];
    evidence.value = [];
    candidates.value = [];
    selectedPlan.value = null;
    selectedCandidate.value = null;
    activeSessionId.value = null;
    const next = projectSessions.value[0];
    if (next) await selectConversation(next.id);
    else syncProjectLocation(activeProjectId.value, null);
  } catch (cause) {
    error.value = apiError(cause);
  }
}

function replaceProjectSession(session: AgentSessionResponse) {
  const index = projectSessions.value.findIndex((item) => item.id === session.id);
  if (index >= 0) {
    projectSessions.value.splice(index, 1, session);
  } else {
    projectSessions.value = [session, ...projectSessions.value];
  }
}

async function removeActiveProject() {
  const projectId = activeProjectId.value;
  if (!projectId || loading.deleteProject) return;
  loading.deleteProject = true;
  error.value = '';
  try {
    await deleteProject(projectId);
    stopProjectAnalysisPolling();
    projectAnalysisStarting.value = false;
    projectAnalysisClientRequestId = null;
    projectAnalysisOutcome.value = null;
    closeProjectSocket();
    currentAssistantMessageId = null;
    currentProcessMessageId = null;
    projectEpoch++;
    sessionFlight = null;
    if (planPoll != null) {
      window.clearTimeout(planPoll);
      planPoll = null;
    }
    collapsedDirectoriesByProject.delete(projectId);
    projects.value = projects.value.filter((item) => item.id !== projectId);
    deleteModalOpen.value = false;
    activeProjectId.value = null;
    manifest.value = null;
    selectedFile.value = null;
    searchResults.value = [];
    projectSessions.value = [];
    activeSessionId.value = null;
    syncProjectLocation(null, null);
    messages.value = [];
    resetContextDebug();
    plans.value = [];
    evidence.value = [];
    candidates.value = [];
    selectedPlan.value = null;
    selectedCandidate.value = null;
    collapsedDirectories.value = new Set();
    const nextProject = projects.value[0];
    if (nextProject) await selectProject(nextProject.id);
  } catch (cause) {
    error.value = apiError(cause);
  } finally {
    loading.deleteProject = false;
  }
}

async function submitProject() {
  const includeRules = splitRules(newProject.includeRules);
  if (!newProject.name.trim() || projectFolderFiles.value.length === 0) {
    createError.value = 'Project name and a selected Project folder are required.';
    return;
  }
  if (includeRules.length === 0) {
    createError.value = 'At least one include rule is required.';
    return;
  }
  if (uploadableProjectFiles.value.length === 0) {
    createError.value = 'No files remain after applying the Project filters.';
    return;
  }
  createError.value = '';
  loading.create = true;
  try {
    const created = (await uploadProject({
      name: newProject.name.trim(),
      files: uploadableProjectFiles.value,
      includeRules,
      ignoreRules: splitRules(newProject.ignoreRules),
    })).data;
    createModalOpen.value = false;
    newProject.name = '';
    resetProjectFolderSelection();
    await loadProjects();
    await selectProject(created.id);
  } catch (cause) {
    createError.value = apiError(cause);
  } finally {
    loading.create = false;
  }
}

onMounted(loadProjects);
onUnmounted(() => {
  stopProjectAnalysisPolling();
  closeProjectSocket();
  if (planPoll != null) window.clearTimeout(planPoll);
  if (candidateValidationPoll != null) window.clearTimeout(candidateValidationPoll);
  projectEpoch++;
});
</script>

<style scoped>
.project-workspace { height: calc(100dvh - 28px); min-height: 0; overflow: hidden; display: flex; flex-direction: column; gap: 12px; color: var(--yb-text); }
.project-workspace__header-shell { position: relative; flex: 0 0 auto; min-height: 0; overflow: visible; }
.project-workspace__header-shell--collapsed { height: 0; }
.project-workspace__header { position: relative; min-height: 48px; overflow: visible; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 4px 2px 12px; border-bottom: 1px solid var(--yb-border); transition: min-height 280ms cubic-bezier(.2,.8,.2,1), height 280ms cubic-bezier(.2,.8,.2,1), padding 280ms cubic-bezier(.2,.8,.2,1), opacity 220ms ease, transform 280ms cubic-bezier(.2,.8,.2,1), border-color 220ms ease; }
.project-workspace__header--collapsed { min-height: 0; height: 0; padding-top: 0; padding-bottom: 0; opacity: 0; overflow: hidden; pointer-events: none; transform: translateY(-12px); border-color: transparent; }
.project-workspace__header h1 { margin: 0; font-size: 20px; letter-spacing: 0; }
.workspace-hero__collapse :deep(.n-icon), .workspace-hero__restore :deep(.n-icon) { width: 16px; height: 16px; font-size: 16px; }
.workspace-hero__collapse :deep(.n-icon) { transform: rotate(-90deg); }
.workspace-hero__restore :deep(.n-icon) { transform: rotate(90deg); }
.project-workspace__alert { margin: 0; }
.project-workspace__state { min-height: 420px; display: grid; place-items: center; color: var(--yb-text-muted); }

.project-workspace__grid { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(220px, .76fr) minmax(0, 1.64fr); border: 1px solid var(--yb-border); border-radius: 12px; background: var(--yb-bg-elevated); overflow: hidden; overscroll-behavior: none; }
.project-panel { min-width: 0; min-height: 0; padding: 13px; display: flex; flex-direction: column; gap: 10px; }
.project-panel--files, .project-panel--main { overflow: hidden; }
.project-panel--files { gap: 0; }
.project-panel + .project-panel { border-left: 1px solid var(--yb-border); }
.project-panel__title { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 12px; }
.project-panel__title > span, .project-panel__count { color: var(--yb-text-muted); font-family: ui-monospace, monospace; font-size: 10px; }
.project-panel__title-actions { min-width: 0; }
.project-panel__title-actions :deep(.n-button) { padding: 0 5px; font-size: 9px; }
.project-panel__title--section { padding-top: 10px; border-top: 1px solid var(--yb-border); }
.project-panel__hint { flex: 0 0 auto; margin: 0; color: var(--yb-text-muted); font-size: 10px; line-height: 1.4; }
.project-panel__loading { padding: 8px; }

.project-sidebar-section { min-height: 0; display: flex; flex-direction: column; gap: 8px; }
.project-sidebar-section + .project-sidebar-section { margin-top: 10px; }
.project-sidebar-section--projects { flex: 0 1 25%; min-height: 86px; }
.project-sidebar-section--chats { flex: 0 1 25%; min-height: 86px; }
.project-sidebar-section--file-browser { flex: 1 1 50%; min-height: 0; }
.project-sidebar-section--collapsed { flex: 0 0 auto; min-height: 0; gap: 0; }
.project-sidebar-section--collapsed + .project-sidebar-section--collapsed { margin-top: 0; }
.project-sidebar-section__header { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.project-sidebar-section__toggle { box-sizing: border-box; flex: 0 0 32px; min-width: 0; height: 32px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 2px; border: 0; background: transparent; color: var(--yb-text); text-align: left; font: inherit; }
.project-sidebar-section + .project-sidebar-section .project-sidebar-section__toggle,
.project-sidebar-section + .project-sidebar-section .project-sidebar-section__header { border-top: 1px solid var(--yb-border); }
.project-sidebar-section__header .project-sidebar-section__toggle { border-top: 0 !important; }
.project-sidebar-section__toggle > span { min-width: 0; display: inline-flex; align-items: center; gap: 5px; }
.project-sidebar-section__toggle strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.project-chevron-button { flex: 0 0 22px; width: 22px; height: 22px; display: inline-grid; place-items: center; padding: 0; border: 0; border-radius: 6px; background: transparent; color: var(--yb-text-muted); cursor: pointer; }
.project-chevron-button:hover { background: var(--yb-bg-muted); color: var(--yb-text); }
.project-chevron-button:focus-visible { outline: 2px solid var(--yb-primary); outline-offset: 1px; }
.project-chevron-button :deep(.n-icon) { width: 14px; height: 14px; font-size: 14px; transition: transform 160ms ease; }
.project-chevron-button--expanded :deep(.n-icon) { transform: rotate(90deg); }

.project-list, .project-file-list, .project-search-results, .project-evidence-list, .project-candidate-list, .project-messages, .project-preview pre, .project-diff pre { overflow: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
.project-list { flex: 0 1 112px; min-height: 36px; display: flex; flex-direction: column; gap: 3px; }
.project-sidebar-section .project-list { flex: 1 1 auto; min-height: 0; }
.project-list__item, .project-file-list__item, .project-search-results button, .project-candidate-list button { width: 100%; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; border-radius: 7px; }
.project-list__item { padding: 7px; }
.project-list__item.active, .project-file-list__item.active, .project-candidate-list button.active { background: var(--yb-sidebar-active); }
.project-list__item strong, .project-list__item small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-list__item strong { font-size: 12px; }
.project-list__item small { margin-top: 2px; font-size: 10px; color: var(--yb-text-muted); }

.project-file-list { flex: 1 1 180px; min-height: 70px; }
.project-sidebar-section--file-browser .project-file-list { flex: 1 1 auto; min-height: 0; }
.project-file-list__item { padding: 5px 6px; display: flex; justify-content: space-between; gap: 6px; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; }
.project-file-list__item:hover { background: var(--yb-bg-muted); }
.project-file-list__item span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-file-list__item small { flex: 0 0 auto; color: var(--yb-text-muted); font-size: 9px; }
.project-file-list__directory { font-weight: 650; }
.project-file-list__chevron { display: inline-flex; width: 13px; height: 13px; margin-right: 2px; color: var(--yb-text-muted); font-size: 13px; vertical-align: -2px; transition: transform 160ms ease; }
.project-file-list__chevron--expanded { transform: rotate(90deg); }

.project-search { flex: 0 0 auto; display: grid; grid-template-columns: 1fr auto; gap: 6px; }
.project-search-results { flex: 0 1 90px; min-height: 0; display: flex; flex-direction: column; gap: 3px; }
.project-search-results button { padding: 5px 6px; }
.project-search-results strong, .project-search-results span { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 10px; }
.project-search-results span { color: var(--yb-text-muted); margin-top: 2px; }

.project-tabs { flex: 0 0 auto; min-width: 0; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding-bottom: 8px; border-bottom: 1px solid var(--yb-border); }
.project-tabs__title { flex: 0 0 auto; font-size: 12px; line-height: 26px; }
.project-tabs__actions { min-width: 0; display: flex; align-items: center; gap: 6px; overflow-x: auto; overscroll-behavior-x: contain; scrollbar-width: thin; }
.project-tabs__actions > * { flex: 0 0 auto; }

.project-utility-chip { min-height: 28px; display: inline-flex; align-items: center; gap: 6px; padding: 5px 10px; border: 1px solid var(--yb-border); border-radius: 999px; background: transparent; color: var(--yb-text-secondary); font-size: 10px; line-height: 1; white-space: nowrap; cursor: pointer; }
.project-utility-chip span { color: var(--yb-text-muted); }
.project-utility-chip.active { border-color: var(--yb-primary); background: var(--yb-sidebar-active); color: var(--yb-text); }
.project-utility-chip:focus-visible { outline: 2px solid var(--yb-primary); outline-offset: 1px; }

.project-conversation-history { flex: 0 0 auto; display: flex; align-items: center; gap: 6px; min-width: 0; overflow-x: auto; padding-bottom: 3px; }
.project-conversation-history > span, .project-conversation-history > small { flex: 0 0 auto; color: var(--yb-text-muted); font-size: 10px; }
.project-conversation-history > button { flex: 0 0 auto; max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 4px 8px; border: 1px solid var(--yb-border); border-radius: 999px; background: transparent; color: var(--yb-text-secondary); font-size: 10px; cursor: pointer; }
.project-conversation-history > button.active { border-color: var(--yb-primary); background: var(--yb-sidebar-active); color: var(--yb-text); }
.project-conversation-history--sidebar { flex: 1 1 auto; min-height: 0; flex-direction: column; align-items: stretch; overflow-x: hidden; overflow-y: auto; padding: 0; scrollbar-gutter: stable; }
.project-conversation-history--sidebar > button { width: 100%; max-width: none; padding: 6px 8px; border-radius: 7px; text-align: left; }
.project-conversation-history--sidebar > small { padding: 4px 2px; }
.project-conversation-item { width: 100%; min-height: 30px; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 6px; padding: 6px 7px; border-radius: 7px; color: var(--yb-text-secondary); font-size: 10px; cursor: pointer; }
.project-conversation-item:hover { background: var(--yb-bg-muted); }
.project-conversation-item.active { background: var(--yb-sidebar-active); color: var(--yb-text); }
.project-conversation-item > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-conversation-item__more { width: 22px; height: 22px; display: inline-flex; align-items: center; justify-content: center; border: 0; border-radius: 6px; background: transparent; color: var(--yb-text-muted); cursor: pointer; font-size: 12px; line-height: 1; }
.project-conversation-item__more:hover { background: var(--yb-bg-elevated); color: var(--yb-text); }

.project-inspector { flex: 0 0 auto; display: flex; flex-direction: column; gap: 10px; padding: 12px; border: 1px solid var(--yb-border); border-radius: 12px; background: color-mix(in srgb, var(--yb-bg-muted) 58%, transparent); }
.project-inspector__tabs { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.project-inspector__tabs > strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }
.project-inspector__tabs button { padding: 0; border: 0; background: transparent; color: var(--yb-text-muted); font: 600 11px inherit; cursor: pointer; }
.project-inspector__close { color: var(--yb-text-secondary) !important; }
.project-inspector__body { display: flex; flex-direction: column; gap: 10px; min-height: 0; }
.project-inspector__changes-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }

.project-preview { min-height: 120px; overflow: hidden; display: flex; flex-direction: column; gap: 7px; font-size: 10px; color: var(--yb-text-muted); }
.project-preview--inline { min-height: 220px; max-height: 320px; }
.project-preview pre, .project-diff pre { flex: 1 1 auto; min-height: 0; margin: 0; max-height: none; white-space: pre-wrap; word-break: break-word; color: var(--yb-text); font: 10px/1.5 ui-monospace, SFMono-Regular, Consolas, monospace; }

.project-scroll-shell { flex: 1 1 auto; min-height: 0; display: grid; grid-template-columns: minmax(0, 1fr) 20px; gap: 8px; align-items: stretch; overflow: hidden; }
.project-scroll-shell > .project-messages { min-height: 0; height: 100%; }
.project-content-nav { min-height: 0; display: flex; flex-direction: column; gap: 5px; align-items: center; overflow-y: auto; overscroll-behavior: contain; padding: 4px 0; scrollbar-width: none; }
.project-content-nav::-webkit-scrollbar { display: none; }
.project-content-nav__item { width: 13px; min-height: 13px; display: grid; place-items: center; padding: 0; border: 1px solid var(--yb-border); border-radius: 999px; background: var(--yb-bg-muted); color: transparent; cursor: pointer; transition: transform 140ms ease, background 140ms ease, border-color 140ms ease; }
.project-content-nav__item span { width: 1px; height: 1px; overflow: hidden; opacity: 0; }
.project-content-nav__item:hover,
.project-content-nav__item.active { transform: scale(1.22); border-color: var(--yb-primary); background: var(--yb-primary); }
.project-content-nav__item--user { background: color-mix(in srgb, var(--yb-primary) 12%, var(--yb-bg-muted)); }
.project-content-nav__item--step { border-radius: 4px; }
.project-content-nav__item--final { width: 15px; min-height: 15px; border-style: dashed; }

.project-messages { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; gap: 10px; padding-right: 4px; }
.project-message-row { display: flex; width: 100%; }
.project-message-row--user { justify-content: flex-end; }
.project-message-row--assistant, .project-message-row--process { justify-content: flex-start; }
.project-message { max-width: min(88%, 820px); padding: 10px 12px; border: 1px solid var(--yb-border); border-radius: 12px; font-size: 12px; line-height: 1.55; }
.project-message--user { background: var(--yb-bg-muted); }
.project-message--assistant { background: var(--yb-bg-elevated); font-size: 14px; line-height: 1.62; }
.project-message--assistant :deep(.message-markdown) { line-height: 1.64; }
.project-message--assistant :deep(.message-markdown h1), .project-message--assistant :deep(.message-markdown h2), .project-message--assistant :deep(.message-markdown h3) { margin: 14px 0 7px; line-height: 1.32; letter-spacing: 0; }
.project-message--assistant :deep(.message-markdown h1) { font-size: 18px; }
.project-message--assistant :deep(.message-markdown h2) { padding-bottom: 0; border-bottom: 0; font-size: 16px; }
.project-message--assistant :deep(.message-markdown h3) { font-size: 15px; }
.project-message--assistant :deep(.message-markdown p) { margin-bottom: 9px; }
.project-message small { display: block; margin-bottom: 4px; text-transform: uppercase; font-size: 9px; letter-spacing: .08em; color: var(--yb-text-muted); }
.project-message-technical { margin-top: 8px; border-top: 1px solid var(--yb-border); }
.project-message-technical summary { padding: 6px 0 2px; color: var(--yb-text-muted); font-size: 9px; font-weight: 650; cursor: pointer; }
.project-message-technical pre { box-sizing: border-box; max-height: 220px; margin: 5px 0 0; padding: 8px; overflow: auto; border-radius: 6px; background: var(--yb-bg-muted); white-space: pre-wrap; overflow-wrap: anywhere; font: 10px/1.5 ui-monospace, SFMono-Regular, Consolas, monospace; }

.project-process-card { width: min(92%, 620px); border: 1px solid var(--yb-border); border-radius: 10px; background: var(--yb-bg-muted); font-size: 11px; color: var(--yb-text-secondary); }
.project-process-card summary { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 10px; cursor: pointer; list-style: none; font-weight: 600; }
.project-process-card summary::-webkit-details-marker { display: none; }
.project-process-card__chevron { flex: 0 0 auto; width: 14px; height: 14px; font-size: 14px; transition: transform 160ms ease; }
.project-process-card[open] .project-process-card__chevron { transform: rotate(90deg); }
.project-process-card pre { margin: 0; padding: 0 10px 10px; white-space: pre-wrap; word-break: break-word; font: 10px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace; color: var(--yb-text-secondary); }

.project-execution-card { box-sizing: border-box; width: min(96%, 860px); overflow: hidden; border: 1px solid var(--yb-border); border-radius: 12px; background: color-mix(in srgb, var(--yb-bg-muted) 58%, var(--yb-bg-elevated)); color: var(--yb-text-secondary); }
.project-execution-card--selected { border-color: color-mix(in srgb, var(--yb-primary) 42%, var(--yb-border)); }
.project-execution-card__details > summary { display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; align-items: center; gap: 9px; padding: 10px 12px; list-style: none; cursor: pointer; }
.project-execution-card__details > summary::-webkit-details-marker { display: none; }
.project-execution-card__chevron { width: 15px; height: 15px; font-size: 15px; color: var(--yb-text-muted); transition: transform 160ms ease; }
.project-execution-card__details[open] > summary .project-execution-card__chevron { transform: rotate(90deg); }
.project-execution-card__heading { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.project-execution-card__heading strong { color: var(--yb-text); font-size: 12px; line-height: 1.35; }
.project-execution-card__heading > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--yb-text-muted); font-size: 10px; line-height: 1.35; }
.project-execution-card__meta { min-width: 0; display: flex; align-items: center; justify-content: flex-end; gap: 8px; color: var(--yb-text-muted); font-size: 10px; white-space: nowrap; }
.project-execution-card__body { display: flex; flex-direction: column; gap: 8px; padding: 10px 12px 12px; border-top: 1px solid var(--yb-border); background: color-mix(in srgb, var(--yb-bg-elevated) 72%, transparent); }
.project-execution-card__details-title { color: var(--yb-text); font-size: 11px; font-weight: 700; }
.project-execution-card__summary-copy { margin: 0; overflow-wrap: anywhere; color: var(--yb-text-secondary); font-size: 11px; line-height: 1.5; }
.project-result-layers { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; margin: 0; }
.project-result-layers > div { min-width: 0; padding: 7px 8px; border-left: 2px solid var(--yb-border); background: color-mix(in srgb, var(--yb-bg-muted) 68%, transparent); }
.project-result-layers dt { margin-bottom: 5px; color: var(--yb-text-muted); font-size: 9px; }
.project-result-layers dd { min-width: 0; margin: 0; }
.project-result-layers :deep(.n-tag) { max-width: 100%; height: auto; min-height: 20px; white-space: normal; }
.project-result-layers :deep(.n-tag__content) { min-width: 0; overflow-wrap: anywhere; line-height: 1.35; }

.project-result-evidence { border-block: 1px solid var(--yb-border); }
.project-result-evidence > summary, .project-result-evidence-group > summary { min-width: 0; display: grid; grid-template-columns: 14px minmax(0, 1fr) auto; align-items: center; gap: 7px; padding: 8px 0; list-style: none; cursor: pointer; }
.project-result-evidence summary::-webkit-details-marker { display: none; }
.project-result-evidence > summary strong, .project-result-evidence-group > summary span { min-width: 0; overflow-wrap: anywhere; color: var(--yb-text-secondary); font-size: 10px; }
.project-result-evidence > summary > span { color: var(--yb-text-muted); font-size: 9px; white-space: nowrap; }
.project-result-evidence__chevron { width: 13px; height: 13px; color: var(--yb-text-muted); font-size: 13px; transition: transform 160ms ease; }
.project-result-evidence[open] > summary .project-result-evidence__chevron, .project-result-evidence-group[open] > summary .project-result-evidence__chevron { transform: rotate(90deg); }
.project-result-evidence__body { min-width: 0; padding: 0 0 8px 21px; }
.project-result-evidence-group { border-top: 1px dashed var(--yb-border); }
.project-result-evidence-group__body { min-width: 0; padding: 0 0 5px 21px; }
.project-result-evidence-entry { min-width: 0; padding: 7px 0; }
.project-result-evidence-entry + .project-result-evidence-entry { border-top: 1px solid var(--yb-border); }
.project-result-evidence-entry > header { min-width: 0; display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.project-result-evidence-entry > header > span { min-width: 0; overflow-wrap: anywhere; color: var(--yb-text-secondary); font-size: 10px; line-height: 1.5; }
.project-result-evidence-entry > header :deep(.n-tag) { flex: 0 0 auto; }
.project-result-technical, .project-result-raw-output, .project-result-verification, .project-plan-step-record { margin-top: 6px; }
.project-result-technical > summary, .project-result-raw-output > summary, .project-result-verification > summary, .project-plan-step-record > summary { padding: 4px 0; color: var(--yb-text-muted); font-size: 9px; font-weight: 650; cursor: pointer; }
.project-result-technical dl, .project-result-verification dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 4px 8px; margin: 5px 0; font: 9px/1.45 ui-monospace, SFMono-Regular, Consolas, monospace; }
.project-result-technical dt, .project-result-verification dt { color: var(--yb-text-muted); }
.project-result-technical dd, .project-result-verification dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.project-result-raw-output pre, .project-plan-step-record pre { box-sizing: border-box; width: 100%; max-height: 260px; margin: 4px 0 0; padding: 8px; overflow: auto; border-radius: 6px; background: var(--yb-bg-muted); white-space: pre-wrap; overflow-wrap: anywhere; font: 10px/1.5 ui-monospace, SFMono-Regular, Consolas, monospace; }
.project-sandbox-confirmation { width: auto; margin: 0 10px 10px; }
.project-sandbox-confirmation p { margin: 0 0 8px; line-height: 1.55; }
.project-sandbox-confirmation ul { margin: 0 0 12px; padding-left: 20px; line-height: 1.6; }

.project-composer { flex: 0 0 auto; display: grid; grid-template-columns: 1fr auto; gap: 8px; align-items: end; padding-top: 10px; border-top: 1px solid var(--yb-border); background: var(--yb-bg-elevated); position: relative; z-index: 1; }

.project-plan-step-details { overflow: hidden; border: 1px solid var(--yb-border); border-radius: 8px; background: var(--yb-bg-elevated); }
.project-plan-step-message__copy { min-width: 0; display: block; }
.project-plan-step-message__copy small { display: flex; align-items: center; gap: 6px; margin-bottom: 3px; color: var(--yb-text-muted); font-size: 9px; }
.project-plan-step-message__title { display: block; margin: 0 0 3px; overflow-wrap: anywhere; color: var(--yb-text); font-size: 11px; line-height: 1.4; }
.project-plan-step-message__preview { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--yb-text-secondary); font-size: 10px; line-height: 1.45; }
.project-plan-step-details summary { display: grid; grid-template-columns: 14px minmax(0, 1fr); align-items: start; gap: 7px; padding: 8px 9px; list-style: none; cursor: pointer; }
.project-plan-step-details summary::-webkit-details-marker { display: none; }
.project-plan-step-details__chevron { width: 13px; height: 13px; margin-top: 2px; color: var(--yb-text-muted); font-size: 13px; transition: transform 160ms ease; }
.project-plan-step-details[open] .project-plan-step-details__chevron { transform: rotate(90deg); }
.project-plan-step-details__body { padding: 9px; border-top: 1px solid var(--yb-border); }
.project-plan-step-details__body :deep(.message-markdown) { overflow-wrap: anywhere; font-size: 10px; line-height: 1.55; }
.project-plan-step-details__body :deep(.message-markdown pre) { max-width: 100%; overflow: auto; }
.project-evidence-list { display: flex; flex-direction: column; gap: 7px; min-height: 0; max-height: 260px; }
.project-evidence-list article { padding: 8px; border: 1px solid var(--yb-border); border-radius: 7px; }
.project-evidence-list article > div { display: flex; justify-content: space-between; gap: 6px; }
.project-evidence-list strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }
.project-evidence-list dl { display: grid; grid-template-columns: auto 1fr; gap: 3px 7px; margin: 7px 0 0; font: 9px ui-monospace, SFMono-Regular, monospace; }
.project-evidence-list dt { color: var(--yb-text-muted); }
.project-evidence-list dd { margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.project-candidate-list { display: flex; flex-direction: column; gap: 4px; min-height: 0; max-height: 180px; }
.project-candidate-list button { padding: 7px; }
.project-candidate-list strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }
.project-candidate-list span { display: flex; align-items: center; gap: 4px; margin-top: 4px; }
.project-candidate-list small { margin-left: auto; color: var(--yb-text-muted); font-size: 9px; }

.project-diff { min-height: 180px; overflow: auto; display: flex; flex-direction: column; gap: 10px; padding-top: 10px; border-top: 1px solid var(--yb-border); font-size: 11px; scrollbar-gutter: stable; }
.project-candidate-meta, .project-candidate-usage, .project-candidate-files article > dl, .project-candidate-evidence dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 4px 8px; margin: 0; font: 9px ui-monospace, SFMono-Regular, monospace; }
.project-candidate-meta dt, .project-candidate-usage dt, .project-candidate-files dt, .project-candidate-evidence dt { color: var(--yb-text-muted); }
.project-candidate-meta dd, .project-candidate-usage dd, .project-candidate-files dd, .project-candidate-evidence dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.project-candidate-validation { display: flex; flex-direction: column; gap: 8px; padding-block: 9px; border-block: 1px solid var(--yb-border); }
.project-candidate-sandbox { display: flex; flex-direction: column; gap: 8px; padding-block: 9px; border-bottom: 1px solid var(--yb-border); }
.project-candidate-sandbox__controls { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 7px; }
.project-candidate-validation-history { display: flex; gap: 5px; overflow-x: auto; padding-bottom: 2px; }
.project-candidate-validation-history button { flex: 0 0 auto; display: flex; align-items: center; gap: 5px; padding: 5px 7px; border: 1px solid var(--yb-border); border-radius: 7px; background: var(--yb-bg-elevated); color: var(--yb-text-secondary); cursor: pointer; }
.project-candidate-validation-history button.active { border-color: var(--yb-primary); box-shadow: inset 2px 0 0 var(--yb-primary); }
.project-candidate-validation-history small { color: var(--yb-text-muted); font-size: 8px; }
.project-candidate-validation-receipt { display: flex; flex-direction: column; gap: 8px; padding: 9px; border: 1px solid var(--yb-border); border-radius: 7px; background: var(--yb-bg-elevated); }
.project-candidate-validation-receipt > dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 4px 8px; margin: 0; font: 9px ui-monospace, SFMono-Regular, Consolas, monospace; }
.project-candidate-validation-receipt dt { color: var(--yb-text-muted); }
.project-candidate-validation-receipt dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.project-candidate-validation-receipt details { border-top: 1px solid var(--yb-border); }
.project-candidate-validation-receipt summary { padding: 7px 0; cursor: pointer; color: var(--yb-text-secondary); font-size: 10px; font-weight: 650; }
.project-candidate-validation-receipt pre { box-sizing: border-box; max-height: 240px; margin: 0; padding: 8px; overflow: auto; border-radius: 6px; background: var(--yb-bg-muted); white-space: pre-wrap; overflow-wrap: anywhere; font: 10px/1.5 ui-monospace, SFMono-Regular, Consolas, monospace; }
.project-candidate-output-analysis { display: flex; flex-direction: column; gap: 5px; padding: 8px; border: 1px dashed var(--yb-border); border-radius: 7px; }
.project-candidate-output-analysis p { margin: 0; color: var(--yb-text-muted); font-size: 9px; }
.project-validation-checks { display: flex; flex-wrap: wrap; gap: 4px; }
.project-validation-issues { display: flex; flex-direction: column; gap: 4px; margin: 0; padding-left: 17px; }
.project-validation-issues li { color: var(--yb-text-secondary); overflow-wrap: anywhere; }
.project-validation-issues span { display: block; color: var(--yb-text-muted); }
.project-candidate-files { display: flex; flex-direction: column; gap: 8px; }
.project-candidate-files article { min-width: 0; padding: 9px; border: 1px solid var(--yb-border); border-radius: 7px; background: var(--yb-bg-elevated); }
.project-candidate-files article > header { display: flex; align-items: flex-start; gap: 7px; margin-bottom: 8px; }
.project-candidate-files article > header strong { min-width: 0; overflow-wrap: anywhere; font: 10px ui-monospace, SFMono-Regular, monospace; line-height: 1.45; }
.project-candidate-files article > dl { margin-bottom: 8px; }
.project-candidate-files details { border-top: 1px solid var(--yb-border); }
.project-candidate-files summary { padding: 7px 0; cursor: pointer; color: var(--yb-text-secondary); font-size: 10px; font-weight: 650; }
.project-candidate-files pre { box-sizing: border-box; max-height: 300px; margin: 0; padding: 8px; overflow: auto; border-radius: 6px; background: var(--yb-bg-muted); white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-word; font-size: 10px; line-height: 1.5; }
.project-delete-marker { margin: 0; padding: 7px 0; color: var(--yb-text-muted); }
.project-candidate-evidence { display: flex; flex-direction: column; gap: 7px; padding-bottom: 4px; }
.project-candidate-evidence dl + dl { padding-top: 7px; border-top: 1px dashed var(--yb-border); }
.project-candidate-apply { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 10px; border-top: 1px solid var(--yb-border); }
.project-candidate-apply span { color: var(--yb-text-secondary); font-size: 10px; }
.project-revision-list { display: flex; flex-direction: column; gap: 8px; max-height: 340px; overflow: auto; scrollbar-gutter: stable; }
.project-revision-list article { padding: 9px; border: 1px solid var(--yb-border); border-radius: 7px; background: var(--yb-bg-elevated); }
.project-revision-list article > header { display: flex; align-items: center; gap: 6px; }
.project-revision-list article > header strong { min-width: 0; margin-right: auto; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font: 10px ui-monospace, SFMono-Regular, Consolas, monospace; }
.project-revision-list dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 4px 8px; margin: 8px 0; font: 9px ui-monospace, SFMono-Regular, Consolas, monospace; }
.project-revision-list dt { color: var(--yb-text-muted); }
.project-revision-list dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.project-revision-actions { display: flex; justify-content: flex-end; gap: 6px; }

.project-delete-copy { margin: 0 0 20px; color: var(--yb-text-secondary); line-height: 1.6; }
.project-create-header { display: flex; flex-direction: column; gap: 4px; }
.project-create-header strong { font-size: 19px; line-height: 1.3; }
.project-create-header span { color: var(--yb-text-muted); font-size: 11px; font-weight: 400; }
.project-create-form { display: flex; flex-direction: column; gap: 2px; padding-top: 2px; }
.project-create-form :deep(.n-form-item) { --n-label-height: 28px !important; }
.project-create-form :deep(.n-form-item-label) { font-size: 12px; font-weight: 650; }
.project-folder-field { width: 100%; min-width: 0; display: flex; flex-direction: column; gap: 10px; }
.project-folder-input { display: none; }
.project-folder-picker { box-sizing: border-box; width: 100%; min-width: 0; display: grid; grid-template-columns: 38px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 14px; border: 1px dashed color-mix(in srgb, var(--yb-primary) 35%, var(--yb-border)); border-radius: 12px; background: color-mix(in srgb, var(--yb-primary-soft) 46%, var(--yb-bg-muted)); }
.project-folder-picker--selected { border-style: solid; }
.project-folder-picker__icon { width: 38px; height: 38px; display: grid; place-items: center; border-radius: 10px; background: var(--yb-bg-elevated); color: var(--yb-primary); box-shadow: 0 1px 3px rgba(15, 23, 42, .08); }
.project-folder-picker__icon svg { width: 21px; height: 21px; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linejoin: round; }
.project-folder-picker__copy { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.project-folder-picker__copy strong, .project-folder-picker__copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-folder-picker__copy strong { font-size: 12px; }
.project-folder-picker__copy small { color: var(--yb-text-muted); font-size: 10px; }
.project-folder-safety { display: flex; align-items: flex-start; gap: 8px; color: var(--yb-text-muted); }
.project-folder-safety > span { flex: 0 0 auto; width: 17px; height: 17px; display: grid; place-items: center; border-radius: 50%; background: color-mix(in srgb, #16a34a 13%, transparent); color: #15803d; font-size: 10px; font-weight: 800; }
.project-folder-safety p { margin: 0; font-size: 10px; line-height: 1.5; }
.project-folder-safety strong { color: var(--yb-text-secondary); }
.project-create-advanced { margin: 0 0 14px; border: 1px solid var(--yb-border); border-radius: 10px; background: color-mix(in srgb, var(--yb-bg-muted) 42%, transparent); }
.project-create-advanced summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; cursor: pointer; list-style: none; }
.project-create-advanced summary::-webkit-details-marker { display: none; }
.project-create-advanced summary span { font-size: 11px; font-weight: 650; }
.project-create-advanced summary small { color: var(--yb-text-muted); font-size: 10px; }
.project-create-advanced__body { display: grid; grid-template-columns: 1fr 1.45fr; gap: 12px; padding: 0 12px 4px; border-top: 1px solid var(--yb-border); }
.project-create-advanced__body :deep(.n-form-item) { margin-top: 8px; }
.project-create-actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 4px; }

@media (max-width: 1200px) {
  .project-workspace__grid { grid-template-columns: 220px minmax(0, 1fr); }
  .project-panel { padding: 10px; }
}

@media (max-width: 980px) {
  .project-workspace { height: auto; min-height: calc(100dvh - 28px); overflow: visible; }
  .project-workspace__grid { grid-template-columns: 1fr; overflow: visible; }
  .project-panel { min-height: 320px; }
  .project-panel--files, .project-panel--main { overflow: visible; }
  .project-panel + .project-panel { border-left: 0; border-top: 1px solid var(--yb-border); }
  .project-workspace__header { align-items: flex-start; flex-direction: column; }
  .project-sidebar-section { flex: none; }
  .project-sidebar-section--projects, .project-sidebar-section--chats, .project-sidebar-section--file-browser { min-height: 0; }
  .project-list { flex: none; max-height: 140px; }
  .project-conversation-history--sidebar { flex: none; max-height: 140px; }
  .project-file-list { flex: none; max-height: 260px; }
  .project-search-results { flex: none; max-height: 120px; }
  .project-preview--inline { min-height: 180px; max-height: 260px; }
  .project-messages { min-height: 320px; max-height: 520px; }
  .project-evidence-list, .project-candidate-list { max-height: 220px; }
  .project-tabs, .project-inspector__tabs, .project-inspector__changes-head { flex-direction: column; align-items: stretch; }
  .project-tabs__actions { width: 100%; flex-wrap: nowrap; }
}

@media (max-width: 620px) {
  .project-execution-card__details > summary { grid-template-columns: 16px minmax(0, 1fr); align-items: start; }
  .project-execution-card__meta { grid-column: 2; justify-content: flex-start; flex-wrap: wrap; white-space: normal; }
  .project-result-layers { grid-template-columns: 1fr; }
  .project-result-evidence__body, .project-result-evidence-group__body { padding-left: 14px; }
  .project-result-technical dl, .project-result-verification dl { grid-template-columns: 1fr; gap: 2px; }
  .project-result-technical dd + dt, .project-result-verification dd + dt { margin-top: 4px; }
  .project-folder-picker { grid-template-columns: 34px minmax(0, 1fr); }
  .project-folder-picker > :deep(.n-button) { grid-column: 1 / -1; width: 100%; }
  .project-create-advanced__body { grid-template-columns: 1fr; gap: 0; }
  .project-create-advanced summary { align-items: flex-start; flex-direction: column; gap: 2px; }
}
</style>
