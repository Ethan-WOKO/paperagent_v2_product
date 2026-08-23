<template>
  <AppLayout>
    <main class="project-workspace project-workspace--console">
      <div class="project-workspace__header-shell" :class="{ 'project-workspace__header-shell--collapsed': projectHeaderCollapsed }">
        <header class="project-workspace__header" :class="{ 'project-workspace__header--collapsed': projectHeaderCollapsed }">
          <h1>{{ activeProject?.name || t('project.page.projects') }}</h1>
          <NSpace :size="8" wrap>
            <NTag v-if="activeProject" size="small" type="success">{{ t('project.page.readOnly') }}</NTag>
            <NButton size="small" secondary :loading="loading.projects" @click="loadProjects">{{ t('project.page.refresh') }}</NButton>
            <NButton v-if="activeProject" size="small" secondary type="error" :disabled="reactPlanBusy" @click="deleteModalOpen = true">{{ t('project.page.deleteProject') }}</NButton>
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

      <section v-else class="project-workspace__grid" :class="{ 'project-workspace__grid--context-collapsed': contextRailCollapsed }" :style="projectLayoutStyle">
        <aside ref="projectContextRailRef" class="project-panel project-panel--files project-context-rail" :style="projectRailStyle">
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
              <div
                v-for="project in projects"
                :key="project.id"
                role="button"
                tabindex="0"
                class="project-list__item"
                :class="{ active: project.id === activeProjectId }"
                :aria-current="project.id === activeProjectId ? 'page' : undefined"
                :title="project.name"
                @click="selectProject(project.id)"
                @keydown.enter.prevent="selectProject(project.id)"
              >
                <strong>{{ project.name }}</strong>
                <NDropdown trigger="click" :options="projectMenuOptions" @select="(key) => handleProjectMenuSelect(key, project)">
                  <button type="button" class="project-conversation-item__more" aria-label="项目操作" @click.stop>...</button>
                </NDropdown>
              </div>
            </div>
          </section>

          <button
            type="button"
            class="project-rail-resizer project-rail-resizer--row"
            aria-label="调整项目列表高度"
            title="拖动调整高度，双击恢复默认"
            :disabled="sidebarSections.projects"
            @pointerdown="startProjectLayoutResize('projects', $event)"
            @keydown="handleProjectLayoutResizeKey('projects', $event)"
            @dblclick="resetProjectLayout"
          />

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
                :aria-current="session.id === activeSessionId ? 'page' : undefined"
                :title="session.title"
                @click="selectConversation(session.id)"
                @keydown.enter.prevent="selectConversation(session.id)"
              >
                <span class="project-conversation-item__copy">
                  <span>{{ session.title || `Conversation #${session.id}` }}</span>
                  <small v-if="reactPlanSessionState(session.id)" :data-state="reactPlanSessionState(session.id)">
                    {{ reactPlanSessionStateLabel(session.id) }}
                  </small>
                </span>
                <NDropdown trigger="click" :options="sessionMenuOptions" @select="(key) => handleSessionMenuSelect(key, session)">
                  <button type="button" class="project-conversation-item__more" :aria-label="t('project.page.conversationActions')" @click.stop>...</button>
                </NDropdown>
              </div>
              <small v-if="loading.sessions">{{ t('project.page.loading') }}</small>
            </div>
          </section>

          <button
            type="button"
            class="project-rail-resizer project-rail-resizer--row"
            aria-label="调整会话列表高度"
            title="拖动调整高度，双击恢复默认"
            :disabled="sidebarSections.conversations"
            @pointerdown="startProjectLayoutResize('conversations', $event)"
            @keydown="handleProjectLayoutResizeKey('conversations', $event)"
            @dblclick="resetProjectLayout"
          />

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
                  <NButton size="tiny" quaternary title="搜索当前项目的文件内容" @click="showInspector('search')">搜索内容</NButton>
                  <NButton
                    size="tiny"
                    quaternary
                    :disabled="directoryPaths.length === 0"
                    :title="allDirectoriesExpanded ? t('project.page.collapseAllFolders') : t('project.page.expandAllFolders')"
                    @click="toggleAllDirectories"
                  >
                    {{ allDirectoriesExpanded ? t('project.page.collapse') : t('project.page.expand') }}
                  </NButton>
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
                :aria-current="!node.directory && selectedFile?.path === node.path ? 'page' : undefined"
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

          </section>
        </aside>

        <button
          type="button"
          class="project-rail-resizer project-rail-resizer--column"
          aria-label="调整项目资料栏宽度"
          title="拖动调整宽度，双击恢复默认"
          @pointerdown="startProjectLayoutResize('width', $event)"
          @keydown="handleProjectLayoutResizeKey('width', $event)"
          @dblclick="resetProjectLayout"
        />

        <section class="project-panel project-panel--main project-panel--v2">
          <div class="project-tabs project-command-bar">
            <div class="project-tabs__actions" role="group" aria-label="Project utilities">
              <button type="button" class="project-utility-chip project-context-toggle" :aria-expanded="!contextRailCollapsed" @click="setContextRailCollapsed(!contextRailCollapsed)">
                {{ contextRailCollapsed ? '展开项目资料' : '收起项目资料' }}
              </button>
              <button type="button" class="project-utility-chip project-utility-chip--secondary" :class="{ active: inspectorOpen && inspectorTab === 'preview' }" :aria-pressed="inspectorOpen && inspectorTab === 'preview'" aria-controls="project-inspector" @click="toggleInspector('preview')">文件预览</button>
              <button type="button" class="project-utility-chip project-utility-chip--secondary" :class="{ active: inspectorOpen && inspectorTab === 'evidence' }" :aria-pressed="inspectorOpen && inspectorTab === 'evidence'" aria-controls="project-inspector" @click="toggleInspector('evidence')">证据 <span>{{ evidence.length }}</span></button>
              <button type="button" class="project-utility-chip" :class="{ active: inspectorOpen && inspectorTab === 'changes' }" :aria-pressed="inspectorOpen && inspectorTab === 'changes'" aria-controls="project-inspector" @click="toggleInspector('changes')">修改与验证 <span>{{ candidates.length }}</span></button>
              <button type="button" class="project-utility-chip project-utility-chip--secondary" :class="{ active: inspectorOpen && inspectorTab === 'versions' }" :aria-pressed="inspectorOpen && inspectorTab === 'versions'" aria-controls="project-inspector" @click="toggleInspector('versions')">项目版本 <span>{{ revisions.length }}</span></button>
              <NButton class="project-new-conversation" size="tiny" quaternary @click="startNewConversation">新建会话</NButton>
              <NDropdown trigger="click" :options="projectUtilityMenuOptions" @select="handleProjectUtilityMenuSelect">
                <button type="button" class="project-utility-chip project-utility-more" :aria-label="isEnglish ? 'More project tools' : '更多项目工具'">
                  {{ isEnglish ? 'More tools' : '更多工具' }}
                </button>
              </NDropdown>
            </div>
          </div>

          <section v-if="inspectorOpen" id="project-inspector" class="project-inspector">
            <div class="project-inspector__tabs">
              <strong>{{ inspectorTitle }}</strong>
              <button type="button" class="project-inspector__close" @click="inspectorOpen = false">收起</button>
            </div>

            <div class="project-inspector__body">
              <template v-if="inspectorTab === 'search'">
                <div class="project-search-workspace">
                  <div class="project-search-workspace__form">
                    <NInput v-model:value="searchQuery" clearable placeholder="搜索当前项目的文件内容" @keyup.enter="runSearch" />
                    <NButton type="primary" :loading="loading.search" :disabled="!activeProject || !searchQuery.trim()" @click="runSearch">搜索</NButton>
                  </div>
                  <div v-if="searchResults.length" class="project-search-results project-search-results--wide">
                    <button v-for="hit in searchResults" :key="`${hit.path}:${hit.lineNumber}`" type="button" @click="openFile(hit.path)">
                      <strong>{{ hit.path }}:{{ hit.lineNumber }}</strong>
                      <span>{{ hit.line }}</span>
                    </button>
                  </div>
                  <NEmpty v-else-if="searchQuery.trim() && !loading.search" size="small" description="没有找到匹配内容。" />
                  <NEmpty v-else-if="!loading.search" size="small" description="输入关键词，搜索当前项目中的文件内容。" />
                </div>
              </template>

              <template v-else-if="inspectorTab === 'preview'">
                <div class="project-preview project-preview--inline">
                  <div class="project-panel__title"><strong>{{ selectedFile?.path || '文件预览' }}</strong><span v-if="selectedFile">{{ shortHash(selectedFile.sha256) }}</span></div>
                  <NSpin v-if="loading.file" size="small" />
                  <iframe v-else-if="selectedFileType === 'pdf' && pdfPreviewUrl" class="project-preview__pdf" :src="pdfPreviewUrl" :title="selectedFile?.path" />
                  <div v-else-if="selectedFileType === 'docx' && documentPreviewLocations.length" class="project-preview__document">
                    <article v-for="(location, index) in documentPreviewLocations" :key="`${location.kind}-${index}`">
                      <strong>{{ documentLocationLabel(location) }}</strong>
                      <p>{{ location.text }}</p>
                    </article>
                  </div>
                  <div v-else-if="selectedFileType === 'xlsx' && spreadsheetPreviewSheets.length" class="project-preview__spreadsheet">
                    <section v-for="sheet in spreadsheetPreviewSheets" :key="sheet.name">
                      <strong>{{ sheet.name }}</strong>
                      <div class="project-preview__cells">
                        <span v-for="cell in sheet.samples || []" :key="cell.reference"><b>{{ cell.reference }}</b>{{ spreadsheetCellValue(cell) }}</span>
                      </div>
                    </section>
                  </div>
                  <pre v-else-if="selectedFile">{{ selectedFile.content }}</pre>
                  <NEmpty v-else size="small" description="请从左侧选择一个可读取文件。" />
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
                  <p class="project-panel__hint">修改内容通过最后一次沙箱运行并核对一致后，会自动创建新版本；旧版本仍可回退。</p>
                  <NButton size="tiny" secondary :loading="loading.candidates" :disabled="!activeProject || candidates.length === 0" title="重新核对候选修改与当前项目版本" @click="refreshCandidates">重新核对</NButton>
                </div>

                <div class="project-candidate-list">
                  <button v-for="candidate in candidates" :key="candidate.artifact.id" :class="{ active: selectedCandidate?.artifact.id === candidate.artifact.id }" @click="selectCandidate(candidate)">
                    <strong :title="candidateTitle(candidate)">{{ candidateTitle(candidate) }}</strong>
                    <span>
                      <NTag size="tiny" :type="candidateApplied(candidate.artifact.id) ? 'success' : 'info'">
                        {{ candidateApplied(candidate.artifact.id) ? '已应用' : '尚未应用' }}
                      </NTag>
                      <NTag size="tiny" :type="candidateStateType(candidate.state)">{{ candidateStateLabel(candidate.state) }}</NTag>
                      <small v-if="candidate.candidate">{{ candidate.candidate.changes.length }} 个文件</small>
                    </span>
                  </button>
                  <NEmpty v-if="!loading.candidates && candidates.length === 0" size="small" description="目前还没有候选修改。" />
                </div>

                <div v-if="selectedCandidate" class="project-diff">
                  <div class="project-panel__title"><strong>候选修改</strong><span>编号 {{ selectedCandidate.artifact.id }}</span></div>
                  <NAlert v-if="selectedCandidate.error" :type="selectedCandidate.state === 'STALE' ? 'warning' : 'error'" :show-icon="false">
                    {{ selectedCandidate.error }}
                  </NAlert>

                  <template v-if="selectedCandidate.candidate">
                    <dl class="project-candidate-meta">
                      <dt>格式版本</dt><dd>{{ selectedCandidate.candidate.schemaVersion }}</dd>
                      <dt>项目版本</dt><dd :title="selectedCandidate.candidate.projectVersion">{{ selectedCandidate.candidate.projectVersion }}</dd>
                      <dt>候选指纹</dt><dd :title="selectedCandidate.candidate.fingerprint">{{ selectedCandidate.candidate.fingerprint }}</dd>
                      <dt>当前状态</dt><dd>{{ selectedCandidateApplied ? selectedCandidateApplicationLabel : `${selectedCandidate.candidate.governanceStatus} / ${selectedCandidate.candidate.applicationStatus}` }}</dd>
                      <dt>差异格式</dt><dd>{{ selectedCandidate.candidate.reviewDiff.format }}</dd>
                    </dl>

                    <section class="project-candidate-validation">
                      <div class="project-panel__title"><strong>候选内容检查</strong><span>{{ candidateValidationLabel(selectedCandidate.candidate) }}</span></div>
                      <div class="project-validation-checks">
                        <NTag v-for="check in selectedCandidate.candidate.validation.checks" :key="check.area" size="tiny" :type="check.status === 'PASSED' ? 'success' : check.status === 'FAILED' ? 'error' : 'warning'">
                          {{ candidateCheckAreaLabel(check.area) }} {{ technicalStatusLabel(check.status) }}
                        </NTag>
                      </div>
                      <ul v-if="selectedCandidate.candidate.validation.issues.length" class="project-validation-issues">
                        <li v-for="issue in selectedCandidate.candidate.validation.issues" :key="`${issue.area}:${issue.code}:${issue.relativePath || ''}`">
                          <strong>{{ issue.code }}</strong><span v-if="issue.relativePath">{{ issue.relativePath }}</span>
                        </li>
                      </ul>
                      <dl class="project-candidate-usage">
                        <dt>修改数量</dt><dd>{{ selectedCandidate.candidate.validation.usage.inspectedChanges }} / {{ selectedCandidate.candidate.validation.usage.requestedChanges }}</dd>
                        <dt>证据数量</dt><dd>{{ selectedCandidate.candidate.validation.usage.inspectedEvidenceRefs }} / {{ selectedCandidate.candidate.validation.usage.requestedEvidenceRefs }}</dd>
                        <dt>候选内容大小</dt><dd>{{ formatBytes(selectedCandidate.candidate.validation.usage.inspectedCandidateUtf8Bytes) }} / {{ formatBytes(selectedCandidate.candidate.validation.usage.requestedCandidateUtf8Bytes) }}</dd>
                      </dl>
                    </section>

                    <section class="project-candidate-sandbox">
                      <div class="project-panel__title">
                        <strong>验证状态</strong>
                        <span>{{ selectedCandidateApplied ? '最终运行已绑定到当前项目版本' : documentOnlyProject ? '文档不会作为代码执行' : '等待最终运行结果' }}</span>
                      </div>
                      <div class="project-candidate-validation-summary">
                        <NAlert :type="selectedCandidateAutomaticValidation ? 'success' : 'default'" :show-icon="false">
                          Agent 自动验证：{{ selectedCandidateAutomaticValidation
                            ? `已通过（${selectedCandidateAutomaticValidation.provider}，退出码 ${selectedCandidateAutomaticValidation.exitCode}）`
                            : '尚无通过记录' }}
                        </NAlert>
                        <NAlert :type="candidateConfirmationAlertType(selectedCandidateConfirmationValidation)" :show-icon="false">
                          项目版本状态：{{ selectedCandidateConfirmationValidation
                            ? candidateConfirmationLabel(selectedCandidateConfirmationValidation)
                            : '尚未执行' }}
                        </NAlert>
                      </div>
                      <NAlert v-if="selectedCandidateApplied" type="success" :show-icon="false">
                        修改已经自动保存为新版本，无需再次选择环境、运行或确认。不满意可在“项目版本”中回退。
                      </NAlert>
                      <NAlert v-if="documentOnlyProject" type="info" :show-icon="false">
                        这个项目只包含文档。系统会核对项目版本、路径、哈希、权限和候选绑定，不会把文档放进 E2B 执行。
                      </NAlert>
                      <div v-if="!selectedCandidateApplied" class="project-candidate-sandbox__controls">
                        <NSelect v-model:value="validationProfile" size="small" :options="validationProfileOptions" :disabled="loading.candidateValidation" />
                        <NButton size="small" secondary :loading="loading.candidateValidation"
                          :disabled="!candidateCanSelect(selectedCandidate) || selectedChangeIndexes.size === 0"
                          @click="validationModalOpen = true">{{ documentOnlyProject ? '检查所选文档修改' : '在沙箱运行所选修改' }}</NButton>
                      </div>
                      <NAlert v-if="validationMessage" :type="validationMessageType" :show-icon="false">{{ validationMessage }}</NAlert>
                      <div v-if="!selectedCandidateApplied && candidateValidations.length" class="project-candidate-validation-history">
                        <button v-for="validation in candidateValidations" :key="validation.validationId"
                          :class="{ active: selectedValidation?.validationId === validation.validationId }"
                          @click="selectedValidation = validation">
                          <span>{{ candidateValidationProfileLabel(validation.profile) }}</span>
                          <NTag size="tiny" :type="candidateValidationStatusType(validation.status)">{{ technicalStatusLabel(validation.status) }}</NTag>
                          <small>{{ formatDateTime(validation.createdAt) }}</small>
                        </button>
                      </div>
                      <article v-if="!selectedCandidateApplied && selectedValidation" class="project-candidate-validation-receipt">
                        <dl>
                           <dt>验证编号</dt><dd :title="selectedValidation.validationId">{{ selectedValidation.validationId }}</dd>
                           <dt>绑定信息</dt><dd>{{ shortHash(selectedValidation.candidateFingerprint) }} / {{ shortHash(selectedValidation.projectVersion) }}</dd>
                           <dt>运行方式</dt><dd>{{ candidateValidationProfileLabel(selectedValidation.profile) }}</dd>
                           <dt>运行状态</dt><dd>{{ technicalStatusLabel(selectedValidation.status) }}</dd>
                           <dt>退出码</dt><dd>{{ selectedValidation.exitCode ?? '-' }}</dd>
                           <dt>是否超时</dt><dd>{{ selectedValidation.timedOut ? '是' : '否' }}</dd>
                           <dt>输出是否截断</dt><dd>{{ selectedValidation.outputTruncated ? '是' : '否' }}</dd>
                           <dt>沙箱提供方</dt><dd>{{ selectedValidation.provider || '-' }}</dd>
                           <dt>请求摘要</dt><dd :title="selectedValidation.requestDigest">{{ selectedValidation.requestDigest }}</dd>
                           <dt>执行凭证摘要</dt><dd :title="selectedValidation.receiptDigest || '-'">{{ selectedValidation.receiptDigest || '-' }}</dd>
                           <dt>确认状态</dt><dd>{{ technicalStatusLabel(selectedValidation.decisionStatus) }}</dd>
                        </dl>
                        <NAlert v-if="selectedValidation.errorCode" type="warning" :show-icon="false">{{ selectedValidation.errorCode }}</NAlert>
                        <NAlert v-if="selectedValidation.outputTruncated" type="warning" :show-icon="false">
                          输出超过限制，下面只显示截断后的标准输出和错误输出。
                        </NAlert>
                        <details open><summary>程序标准输出</summary><pre>{{ selectedValidation.stdout || '（空）' }}</pre></details>
                        <details open><summary>程序错误输出</summary><pre>{{ selectedValidation.stderr || '（空）' }}</pre></details>
                        <div class="project-candidate-output-analysis">
                          <strong>运行结果分析</strong>
                          <p>{{ selectedValidation.analysisDisclaimer || '基于输出、未独立验证。' }}</p>
                          <pre>{{ selectedValidation.analysisSummary || '没有生成分析摘要，请直接查看上面的程序输出。' }}</pre>
                        </div>
                        <NSpace justify="end">
                          <NButton v-if="!candidateValidationTerminal(selectedValidation.status)" size="tiny" secondary
                            :loading="loading.cancelCandidateValidation" @click="cancelSelectedValidation">取消运行</NButton>
                          <NButton v-if="selectedValidation.decisionStatus === 'PENDING'" size="tiny" type="warning" secondary
                            :loading="loading.rejectCandidateValidation" @click="rejectSelectedValidation">拒绝候选修改</NButton>
                        </NSpace>
                      </article>
                      <NEmpty v-else-if="!selectedCandidateApplied" size="small" description="尚未产生验证记录" />
                    </section>

                    <section class="project-candidate-files">
                      <article v-for="(entry, changeIndex) in selectedCandidate.candidate.reviewDiff.entries" :key="`${entry.type}:${entry.relativePath}`">
                        <header>
                          <NCheckbox
                            :checked="selectedChangeIndexes.has(changeIndex)"
                            :disabled="selectedCandidateApplied || !candidateCanSelect(selectedCandidate) || loading.applyCandidate || loading.candidateValidation"
                            :aria-label="`选择 ${entry.relativePath}`"
                            @update:checked="(checked) => setChangeSelected(changeIndex, checked)"
                          />
                           <NTag size="tiny" :type="candidateChangeType(entry.type)">{{ candidateChangeTypeLabel(entry.type) }}</NTag>
                          <strong :title="entry.relativePath">{{ entry.relativePath }}</strong>
                        </header>
                        <dl>
                          <dt>原文件哈希</dt><dd :title="entry.baseFileHash || '-'">{{ entry.baseFileHash || '-' }}</dd>
                          <dt>修改后哈希</dt><dd :title="entry.resultFileHash || '-'">{{ entry.resultFileHash || '-' }}</dd>
                        </dl>
                        <details open>
                          <summary>查看修改后的完整内容</summary>
                          <pre v-if="entry.replacementText !== null">{{ entry.replacementText }}</pre>
                          <p v-else class="project-delete-marker">这是删除文件操作，没有替换内容。</p>
                        </details>
                        <details>
                          <summary>修改依据（{{ candidateEvidence(selectedCandidate.candidate, entry.relativePath).length }}）</summary>
                          <div class="project-candidate-evidence">
                            <dl v-for="(ref, index) in candidateEvidence(selectedCandidate.candidate, entry.relativePath)" :key="`${ref.relativePath}:${ref.range.startLine}:${ref.range.endLine}:${index}`">
                              <dt>路径</dt><dd :title="ref.relativePath">{{ ref.relativePath }}</dd>
                              <dt>行号</dt><dd>{{ ref.range.startLine }}-{{ ref.range.endLine }}</dd>
                              <dt>文件哈希</dt><dd :title="ref.fileHash">{{ ref.fileHash }}</dd>
                              <dt>解析器</dt><dd>{{ ref.parserVersion }}</dd>
                              <dt>可信状态</dt><dd>{{ ref.trustLabel }}</dd>
                            </dl>
                          </div>
                        </details>
                      </article>
                    </section>
                    <NAlert v-if="applicationMessage" :type="applicationMessageType" :show-icon="false">
                      {{ applicationMessage }}
                    </NAlert>
                    <div v-if="!selectedCandidateApplied" class="project-candidate-apply">
                      <span>已选择 {{ selectedChangeIndexes.size }} / {{ selectedCandidate.candidate.changes.length }} 项修改</span>
                      <NButton
                        type="primary"
                        size="small"
                        :loading="loading.applyCandidate"
                        :disabled="!candidateCanApply(selectedCandidate) || selectedChangeIndexes.size === 0"
                        @click="openApplyConfirmation"
                      >确认并创建新版本</NButton>
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

          <section class="v2-conversation">
            <div class="project-conversation-shell">
            <div ref="reactPlanTasksRef" class="v2-conversation__tasks" aria-live="polite" :aria-busy="reactPlanBusy" @scroll="syncReactPlanNavigation">
              <div v-if="reactPlanNextCursor" class="reactplan-history-more">
                <NButton size="tiny" secondary :loading="reactPlanLoadingOlder" @click="loadEarlierReactPlanTasks">
                  加载更早任务
                </NButton>
              </div>
              <article
                v-for="item in reactPlanTimeline"
                :key="item.record.taskId"
                :ref="(element) => setReactPlanTaskRef(element, item.record.taskId)"
                class="v2-task-card reactplan-task-card"
                :data-task-state="item.record.view.state"
              >
                <header class="v2-task-card__question">
                  <div class="v2-task-card__question-copy">
                    <span class="v2-task-card__avatar" aria-hidden="true">你</span>
                    <p>{{ item.record.instruction }}</p>
                  </div>
                  <div class="v2-task-card__status">
                    <NTag size="small" :type="reactPlanStateTagType(item.record.view.state)">
                      {{ reactPlanStateLabel(item.record.view.state) }}
                    </NTag>
                    <small>{{ item.durationLabel }}</small>
                  </div>
                </header>

                <details
                  v-if="item.tools.length"
                  class="v2-conversation__process"
                  :open="item.record.view.state === 'running' || item.record.view.state === 'waiting_user'"
                >
                  <summary>
                    <span>执行过程</span>
                    <small>{{ item.tools.length }} 条工具记录</small>
                  </summary>
                  <ol>
                    <li v-for="tool in item.tools" :key="`${tool.callId}:${tool.sequence}`" :data-status="tool.state">
                      <span>{{ tool.sequence }}</span>
                      <div>
                        <strong>{{ reactPlanToolLabel(tool) }}</strong>
                        <small>{{ tool.outputSummary || tool.inputSummary }}</small>
                        <code v-if="tool.receiptRef" class="reactplan-receipt">{{ tool.receiptRef }}</code>
                      </div>
                      <NTag size="tiny" :type="tool.state === 'succeeded' ? 'success' : tool.state === 'failed' ? 'error' : 'default'">
                        {{ reactPlanToolStateLabel(tool.state) }}
                      </NTag>
                    </li>
                  </ol>
                </details>

                <section class="v2-task-card__result">
                  <span class="v2-task-card__avatar v2-task-card__avatar--assistant" aria-hidden="true">R</span>
                  <div class="v2-task-card__result-copy">
                    <MarkdownMessage v-if="item.delivery" :content="item.delivery.conclusion" variant="project" />
                    <NAlert v-else-if="item.question" type="warning" :show-icon="false">{{ item.question.text }}</NAlert>
                    <NAlert v-else-if="item.record.view.state === 'failed'" type="error" :show-icon="false">
                      {{ item.record.view.error?.message || '任务执行失败。' }}
                    </NAlert>
                    <NAlert v-else-if="item.record.view.state === 'cancelled'" type="warning" :show-icon="false">任务已取消。</NAlert>
                    <p v-else>{{ reactPlanStateLabel(item.record.view.state) }}</p>
                  </div>
                </section>

                <div v-if="item.delivery?.receiptRefs.length" class="v2-task-card__delivery">
                  <dl class="v2-task-card__validation">
                    <dt>正式回执</dt>
                    <dd><code v-for="receipt in item.delivery.receiptRefs" :key="receipt">{{ receipt }}</code></dd>
                  </dl>
                </div>

              </article>
              <NEmpty v-if="reactPlanTimeline.length === 0" description="尚无任务记录。输入任务后，这里会显示执行进度和最终结果。" />
            </div>
            <ConversationQuestionRail
              :items="reactPlanNavigationItems"
              :active-id="activeReactPlanNavigationId"
              aria-label="当前项目会话问题导航"
              @select="scrollToReactPlanTask"
            />
            </div>

            <NAlert v-if="reactPlanError" type="error" :show-icon="false">{{ reactPlanError }}</NAlert>
            <div
              v-if="reactPlanActivityLabel"
              class="reactplan-activity"
              :data-state="reactPlanCancelling ? 'cancelling' : 'running'"
              role="status"
              aria-live="polite"
            >
              <span class="reactplan-activity__dot" aria-hidden="true"></span>
              <span>{{ reactPlanActivityLabel }}</span>
            </div>
            <div class="v2-conversation__composer">
              <NInput
                v-model:value="reactPlanInput"
                aria-label="ReAct project task"
                type="textarea"
                :maxlength="20000"
                :autosize="{ minRows: 2, maxRows: 8 }"
                :placeholder="reactPlanQuestion ? '输入对模型追问的回复' : '让我们一起来做些什么？'"
                :disabled="reactPlanBusy"
                @keydown="handleReactPlanKeydown"
              />
              <div class="reactplan-composer-actions">
                <NSelect
                  v-if="!reactPlanQuestion"
                  v-model:value="selectedReactPlanSkillId"
                  aria-label="ReAct task skill"
                  class="reactplan-skill-select"
                  clearable
                  :options="reactPlanSkillOptions"
                  :disabled="reactPlanBusy"
                  placeholder="可选 Skill"
                />
                <NButton
                  v-if="reactPlanSubmitting"
                  class="project-send-button project-stop-button"
                  secondary
                  disabled
                >正在发送…</NButton>
                <NButton
                  v-else-if="reactPlanExecutionActive || reactPlanCancelling"
                  class="project-send-button project-stop-button"
                  secondary
                  type="error"
                  :disabled="reactPlanCancelling"
                  @click="cancelCurrentReactPlanTask"
                >{{ reactPlanCancelling ? '正在停止…' : '停止任务' }}</NButton>
                <template v-else>
                  <NButton
                    class="project-send-button"
                    type="primary"
                    :disabled="!activeProject || !reactPlanInput.trim() || reactPlanBusy"
                    @click="sendReactPlanTask"
                  >
                    {{ reactPlanQuestion ? '回复' : '发送' }}
                  </NButton>
                  <NButton
                    v-if="reactPlanQuestion && reactPlanCanCancel"
                    class="project-cancel-button"
                    size="small"
                    secondary
                    type="error"
                    @click="cancelCurrentReactPlanTask"
                  >取消任务</NButton>
                </template>
              </div>
            </div>
          </section>
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

    <NModal v-model:show="renameProjectModalOpen" preset="card" title="重命名项目" :style="{ width: 'min(420px, calc(100vw - 32px))' }">
      <NSpace vertical :size="14">
        <NInput
          v-model:value="renameProjectDraft"
          maxlength="255"
          show-count
          placeholder="项目名称"
          @keydown.enter.prevent="confirmRenameProject"
        />
        <NSpace justify="end">
          <NButton secondary @click="renameProjectModalOpen = false">取消</NButton>
          <NButton type="primary" :loading="loading.renameProject" :disabled="!renameProjectDraft.trim()" @click="confirmRenameProject">保存</NButton>
        </NSpace>
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

    <NModal v-model:show="applyModalOpen" preset="card" title="确认创建新的项目版本" :mask-closable="!loading.applyCandidate" :closable="!loading.applyCandidate" :style="{ width: 'min(520px, calc(100vw - 32px))' }">
      <p class="project-delete-copy">
        系统会使用选中的 {{ selectedChangeIndexes.size }} 项修改创建一个新的不可变项目版本。
        当前版本和历史版本都会保留，可以随时回退。
      </p>
      <NSpace justify="end">
        <NButton :disabled="loading.applyCandidate" @click="applyModalOpen = false">取消</NButton>
        <NButton type="primary" :loading="loading.applyCandidate" @click="confirmApplyCandidate">创建新版本</NButton>
      </NSpace>
    </NModal>

    <NModal v-model:show="validationModalOpen" preset="card" :title="documentOnlyProject ? '检查文档候选修改' : '在沙箱中运行候选修改'" :mask-closable="!loading.candidateValidation" :closable="!loading.candidateValidation" :style="{ width: 'min(560px, calc(100vw - 32px))' }">
      <p class="project-delete-copy">
        <template v-if="documentOnlyProject">
          系统会核对选中的 {{ selectedChangeIndexes.size }} 项文档修改与可信项目版本、候选元数据是否一致。
          文档不会作为代码执行，也不会启动 E2B。
        </template>
        <template v-else>
          系统会把可信项目版本和选中的 {{ selectedChangeIndexes.size }} 项修改放入隔离工作区，并使用 {{ validationProfile }} 运行。
          如需 Maven 依赖，只在工作区仅包含依赖清单时临时联网下载；完整代码上传前会恢复并确认断网。
          敏感环境变量不会传入沙箱，这一步也不会修改原项目。
        </template>
      </p>
      <NSpace justify="end">
        <NButton :disabled="loading.candidateValidation" @click="validationModalOpen = false">取消</NButton>
        <NButton type="primary" :loading="loading.candidateValidation" @click="confirmCandidateValidation">
          {{ documentOnlyProject ? '确认检查' : '确认并运行' }}
        </NButton>
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
import { NAlert, NButton, NCheckbox, NDropdown, NEmpty, NForm, NFormItem, NIcon, NInput, NModal, NSelect, NSpace, NSpin, NTag } from 'naive-ui';
import { ChevronRightIcon } from 'naive-ui/es/_internal/icons';
import AppLayout from '@/components/AppLayout.vue';
import ConversationQuestionRail from '@/components/ConversationQuestionRail.vue';
import MarkdownMessage from '@/components/MarkdownMessage.vue';
import { deleteSession as deleteAgentSession, getV2NaturalLanguageTurn, getV2ProductAvailability, listV2NaturalLanguageTurns, startV2NaturalLanguageTurn, updateSession as updateAgentSession, type AgentSessionResponse, type V2NaturalLanguageStepStatus, type V2NaturalLanguageTurnHistoryItem, type V2NaturalLanguageTurnResponse } from '@/api/agent';
import { answerReactPlanQuestion, cancelReactPlanTask, getReactPlanTask, listReactPlanSessionTasks, startReactPlanTask, streamReactPlanEvents, type ReactPlanSessionTask, type ReactPlanTaskState } from '@/api/reactPlan';
import { listSkills, type SkillListItemResponse } from '@/api/skills';
import { candidateReviewFailure, getCandidateChange, isCandidateArtifactV1, listArtifacts, type ArtifactResponse, type CandidateArtifactResponse, type CandidateChangeType, type CandidateEvidenceRef, type CandidateReviewState } from '@/api/artifact';
import { applyProjectCandidate, cancelCandidateValidation, createCandidateValidation, createProjectSession, deleteProject, exportProjectRevision, filterProjectUploadFiles, getProjectManifest, listCandidateValidations, listProjectRevisions, listProjectSessions, listProjects, previewProjectFile, readProjectFile, readProjectRawFile, rejectCandidateValidation, renameProject, rollbackProjectRevision, searchProject, uploadProject, type CandidateValidationProfile, type CandidateValidationResponse, type ProjectEvidenceResponse, type ProjectFileResponse, type ProjectManifestResponse, type ProjectRevisionResponse, type ProjectSearchHit, type ProjectSummaryResponse } from '@/api/project';
import { useI18n } from '@/composables/useI18n';
import { candidateValidationCanApply } from '@/utils/candidateValidationCanApply';
import {
  V2NaturalLanguageTurnNotCreatedError,
  isV2CandidateApplied,
  isCurrentV2NaturalLanguageRequest,
  isDefinitiveV2NaturalLanguageStartRejection,
  newV2NaturalLanguageClientRequestId,
  normalizeV2NaturalLanguageRequest,
  pollV2NaturalLanguageTurn,
  startThenPollV2NaturalLanguageTurn,
  v2NaturalLanguageStatusLabel,
  v2NaturalLanguageStepStatusLabel,
  type V2NaturalLanguageRequestIdentity,
} from '@/utils/v2NaturalLanguageTurn';
import {
  appendReactPlanEvent,
  formatReactPlanDuration,
  isReactPlanTerminal,
  latestReactPlanQuestion,
  mergeReactPlanSessionTasks,
  newReactPlanCancelId,
  newReactPlanRequestId,
  parseReactPlanHistory,
  reactPlanDelivery,
  reactPlanElapsedMillis,
  reactPlanStateLabel,
  reactPlanStateTagType,
  reactPlanToolEvents,
  reactPlanToolLabel,
  reactPlanToolStateLabel,
  serializeReactPlanHistory,
  upsertReactPlanRecord,
  type ReactPlanTaskEvent,
  type ReactPlanTaskRecord,
} from '@/utils/reactPlanTask';

type ProjectInspectorTab = 'search' | 'preview' | 'evidence' | 'changes' | 'versions';

interface CandidateReviewItem {
  artifact: ArtifactResponse;
  candidate: CandidateArtifactResponse | null;
  state: CandidateReviewState;
  error: string | null;
}

interface DocumentPreviewLocation {
  kind: 'PAGE' | 'PARAGRAPH' | 'TABLE_CELL' | string;
  text: string;
  page?: number;
  paragraph?: number;
  table?: number;
  row?: number;
  column?: number;
}

interface SpreadsheetPreviewCell {
  reference: string;
  value?: string;
  valueType?: string;
  formulaPresent?: boolean;
}

interface SpreadsheetPreviewSheet {
  name: string;
  samples?: SpreadsheetPreviewCell[];
}

const { isEnglish, t } = useI18n();
const route = useRoute();
const router = useRouter();
const projects = ref<ProjectSummaryResponse[]>([]);
const activeProjectId = ref<number | null>(null);
const projectSessions = ref<AgentSessionResponse[]>([]);
const activeSessionId = ref<number | null>(null);
const manifest = ref<ProjectManifestResponse | null>(null);
const selectedFile = ref<ProjectFileResponse | null>(null);
const pdfPreviewUrl = ref('');
const searchQuery = ref('');
const searchResults = ref<ProjectSearchHit[]>([]);
const evidence = ref<ProjectEvidenceResponse[]>([]);
const candidates = ref<CandidateReviewItem[]>([]);
const selectedCandidate = ref<CandidateReviewItem | null>(null);
const selectedChangeIndexes = ref<Set<number>>(new Set());
const candidateValidations = ref<CandidateValidationResponse[]>([]);
const selectedValidation = ref<CandidateValidationResponse | null>(null);
const validationProfile = ref<CandidateValidationProfile>('MAVEN_TEST');
const codeValidationProfileOptions: Array<{ label: string; value: CandidateValidationProfile }> = [
  { label: '编译并运行 Java 源文件', value: 'JAVA_SOURCE_RUN' },
  { label: '运行 Python 源文件', value: 'PYTHON_SOURCE_RUN' },
  { label: '编译并运行 C 源文件', value: 'C_SOURCE_RUN' },
  { label: '编译并运行 C++ 源文件', value: 'CPP_SOURCE_RUN' },
];

const selectedFileType = computed(() => {
  const path = selectedFile.value?.path.toLowerCase() || '';
  if (path.endsWith('.pdf')) return 'pdf';
  if (path.endsWith('.docx')) return 'docx';
  if (path.endsWith('.xlsx')) return 'xlsx';
  return 'text';
});

const structuredFilePreview = computed<Record<string, unknown> | null>(() => {
  if (!selectedFile.value || selectedFileType.value === 'text') return null;
  try {
    const value = JSON.parse(selectedFile.value.content) as unknown;
    return value && typeof value === 'object' && !Array.isArray(value)
      ? value as Record<string, unknown> : null;
  } catch {
    return null;
  }
});

const documentPreviewLocations = computed<DocumentPreviewLocation[]>(() => {
  const locations = structuredFilePreview.value?.locations;
  return Array.isArray(locations) ? locations as DocumentPreviewLocation[] : [];
});

const spreadsheetPreviewSheets = computed<SpreadsheetPreviewSheet[]>(() => {
  const sheets = structuredFilePreview.value?.sheets;
  return Array.isArray(sheets) ? sheets as SpreadsheetPreviewSheet[] : [];
});
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
const inspectorOpen = ref(false);
const inspectorTitle = computed(() => ({
  search: '搜索项目内容',
  preview: '文件预览',
  evidence: '证据',
  changes: '修改与验证',
  versions: '项目版本',
}[inspectorTab.value]));
const error = ref('');
const createModalOpen = ref(false);
const deleteModalOpen = ref(false);
const renameProjectModalOpen = ref(false);
const renameProjectId = ref<number | null>(null);
const renameProjectDraft = ref('');
const renameSessionModalOpen = ref(false);
const renameSessionId = ref<number | null>(null);
const renameSessionDraft = ref('');
let projectEpoch = 0;
let sessionFlight: Promise<number | null> | null = null;
let candidateValidationPoll: number | null = null;
const V2_NATURAL_LANGUAGE_STORAGE_KEY = 'yanban.v2NaturalLanguage.activeRequest.';
const REACT_PLAN_STORAGE_KEY = 'yanban.reactPlan.activeTask.';
const REACT_PLAN_RECONNECT_DELAY_MS = 1_500;
const v2NaturalTurnAvailable = ref(false);
const v2TurnInput = ref('');
const v2TurnStarting = ref(false);
const v2TurnPolling = ref(false);
const v2TurnError = ref('');
const v2TurnOutcome = ref<V2NaturalLanguageTurnResponse | null>(null);
const v2TurnHistory = ref<V2NaturalLanguageTurnHistoryItem[]>([]);
let v2TurnAbortController: AbortController | null = null;
let v2TurnSequence = 0;
let v2TurnClientRequestId: string | null = null;
const v2NaturalTurnBusy = computed(() => v2TurnStarting.value || v2TurnPolling.value);
const reactPlanInput = ref('');
const reactPlanSkills = ref<SkillListItemResponse[]>([]);
const selectedReactPlanSkillId = ref<string | null>(null);
const reactPlanSkillOptions = computed(() => reactPlanSkills.value
  .filter((skill) => skill.enabled)
  .map((skill) => ({ label: skill.name, value: skill.id })));
const reactPlanRecord = ref<ReactPlanTaskRecord | null>(null);
const reactPlanRecords = ref<ReactPlanTaskRecord[]>([]);
const reactPlanSessionStates = ref<Record<number, ReactPlanTaskState | undefined>>({});
const reactPlanNextCursor = ref<string | null>(null);
const reactPlanLoadingOlder = ref(false);
const reactPlanError = ref('');
const reactPlanSubmitting = ref(false);
const reactPlanStreaming = ref(false);
const reactPlanCancelling = ref(false);
const reactPlanAnswering = ref(false);
const reactPlanClock = ref(Date.now());
const reactPlanSubmissionStartedAt = ref<string | null>(null);
const reactPlanTasksRef = ref<HTMLElement | null>(null);
const reactPlanTaskRefs: Record<string, HTMLElement | null> = {};
const activeReactPlanNavigationId = ref<string | null>(null);
let reactPlanAbortController: AbortController | null = null;
let reactPlanReconnectTimer: number | null = null;
let reactPlanClockTimer: number | null = null;
let reactPlanSessionPollTimer: number | null = null;
let reactPlanSessionRefreshProjectId: number | null = null;
const reactPlanQuestion = computed(() => {
  const record = reactPlanRecord.value;
  if (!record || record.view.state !== 'waiting_user') return null;
  return latestReactPlanQuestion(record.events, record.view.pendingQuestionId);
});
const reactPlanTimeline = computed(() => reactPlanRecords.value.map((record) => ({
  record,
  durationLabel: `${isReactPlanTerminal(record.view.state) ? '用时' : '已用时'} ${formatReactPlanDuration(
    reactPlanElapsedMillis(record, reactPlanClock.value),
  )}`,
  tools: reactPlanToolEvents(record.events),
  delivery: reactPlanDelivery(record.events),
  question: latestReactPlanQuestion(record.events, record.view.pendingQuestionId),
})));
const reactPlanNavigationItems = computed(() => reactPlanTimeline.value.map((item) => ({
  id: item.record.taskId,
  user: navigationPreviewText(item.record.instruction),
  assistant: navigationPreviewText(
    item.delivery?.conclusion
      || item.question?.text
      || (item.record.view.state === 'failed'
        ? item.record.view.error?.message || '任务执行失败'
        : reactPlanStateLabel(item.record.view.state)),
  ),
  state: item.record.view.state,
})));
const reactPlanExecutionActive = computed(() => (
  reactPlanRecord.value?.view.state === 'queued'
    || reactPlanRecord.value?.view.state === 'running'
));
const reactPlanCanCancel = computed(() => Boolean(
  reactPlanRecord.value && !isReactPlanTerminal(reactPlanRecord.value.view.state),
));
const reactPlanActivityLabel = computed(() => {
  if (reactPlanCancelling.value) return '正在停止任务…';
  if (reactPlanSubmitting.value) {
    const startedAt = Date.parse(reactPlanSubmissionStartedAt.value || '');
    const elapsed = Number.isFinite(startedAt)
      ? formatReactPlanDuration(Math.max(0, reactPlanClock.value - startedAt))
      : '0 秒';
    return `正在发送任务… · 已用时 ${elapsed}`;
  }
  if (reactPlanRecord.value?.view.state === 'queued') return '任务已发送，正在准备执行…';
  if (reactPlanRecord.value?.view.state !== 'running') return '';
  const activeTool = [...reactPlanToolEvents(reactPlanRecord.value.events)]
    .reverse()
    .find((tool) => tool.state === 'requested' || tool.state === 'running');
  return activeTool ? `正在执行：${reactPlanToolLabel(activeTool)}` : '正在执行任务…';
});
const reactPlanBusy = computed(() => (
  reactPlanSubmitting.value || reactPlanCancelling.value || reactPlanAnswering.value
    || reactPlanExecutionActive.value
));

watch(reactPlanNavigationItems, () => {
  void nextTick(syncReactPlanNavigation);
}, { flush: 'post' });

function reactPlanSessionState(sessionId: number) {
  return reactPlanSessionStates.value[sessionId];
}

function reactPlanSessionStateLabel(sessionId: number) {
  const state = reactPlanSessionState(sessionId);
  if (!state) return '';
  return {
    queued: '排队中',
    running: '执行中',
    waiting_user: '等待回复',
    succeeded: '已完成',
    failed: '失败',
    cancelled: '已取消',
  }[state];
}

const loading = reactive({
  projects: false,
  sessions: false,
  manifest: false,
  file: false,
  search: false,
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
  renameProject: false,
  renameSession: false,
  v2History: false,
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
const PROJECT_CONTEXT_COLLAPSED_KEY = 'yanban.project.contextCollapsed';
const PROJECT_LAYOUT_KEY = 'yanban.project.contextLayout.v1';
const DEFAULT_PROJECT_LAYOUT = Object.freeze({ width: 270, projects: 170, conversations: 190 });
type ProjectLayoutAxis = 'width' | 'projects' | 'conversations';
const projectContextRailRef = ref<HTMLElement | null>(null);
const projectLayout = reactive(readStoredProjectLayout());
let activeProjectLayoutDrag: {
  axis: ProjectLayoutAxis;
  startCoordinate: number;
  startValue: number;
} | null = null;
const DEFAULT_SESSION_TITLE = '\u65b0\u4f1a\u8bdd';
const projectHeaderCollapsed = ref(readStoredBoolean(PROJECT_HEADER_COLLAPSED_KEY, false));
const contextRailCollapsed = ref(readStoredBoolean(
  PROJECT_CONTEXT_COLLAPSED_KEY,
  typeof window !== 'undefined' && window.innerWidth <= 980,
));
const projectLayoutStyle = computed(() => ({
  '--project-context-width': `${projectLayout.width}px`,
}));
const projectRailStyle = computed(() => ({
  gridTemplateRows: [
    sidebarSections.projects ? '34px' : `${projectLayout.projects}px`,
    '7px',
    sidebarSections.conversations ? '34px' : `${projectLayout.conversations}px`,
    '7px',
    sidebarSections.files ? '34px' : 'minmax(140px, 1fr)',
  ].join(' '),
}));
const sessionMenuOptions = computed(() => [
  { label: isEnglish.value ? 'Rename' : '重命名', key: 'rename' },
  { label: isEnglish.value ? 'Delete' : '删除', key: 'delete' },
]);
const projectMenuOptions = computed(() => [
  { label: isEnglish.value ? 'Rename project' : '重命名项目', key: 'rename' },
]);
const projectUtilityMenuOptions = computed(() => [
  { label: isEnglish.value ? 'File preview' : '文件预览', key: 'preview' },
  { label: `${isEnglish.value ? 'Evidence' : '证据'} (${evidence.value.length})`, key: 'evidence' },
  { label: `${isEnglish.value ? 'Project versions' : '项目版本'} (${revisions.value.length})`, key: 'versions' },
]);
const activeProject = computed(() => projects.value.find((item) => item.id === activeProjectId.value) || null);
const selectedCandidateAutomaticValidation = computed(() => {
  const artifactId = selectedCandidate.value?.artifact.id;
  if (!artifactId) return null;
  return [...v2TurnHistory.value].reverse()
    .find((item) => item.candidateArtifactId === artifactId)
    ?.agentAutomaticValidation || null;
});
const selectedCandidateConfirmationValidation = computed(() => {
  const artifactId = selectedCandidate.value?.artifact.id;
  if (!artifactId) return candidateValidations.value[0] || null;
  const automatic = [...v2TurnHistory.value].reverse()
    .find((item) => item.candidateArtifactId === artifactId)
    ?.confirmationValidation || null;
  return automatic || candidateValidations.value[0] || null;
});
const selectedCandidateApplied = computed(() =>
  selectedCandidateConfirmationValidation.value?.decisionStatus === 'APPLIED'
  && Boolean(selectedCandidateConfirmationValidation.value.appliedRevisionId));
const selectedCandidateApplicationLabel = computed(() =>
  selectedCandidateConfirmationValidation.value
    ? candidateConfirmationLabel(selectedCandidateConfirmationValidation.value)
    : '尚未应用');
const documentOnlyProject = computed(() => {
  const files = manifest.value?.files || [];
  return files.length > 0 && files.every((file) =>
    /\.(?:md|markdown|txt|rst|adoc|tex|pdf|docx)$/i.test(file.path));
});
const validationProfileOptions = computed<Array<{ label: string; value: CandidateValidationProfile }>>(() => {
  if (!manifest.value) return [];
  if (documentOnlyProject.value) {
    return [{ label: '文档完整性检查（不启动 E2B）', value: 'DOCUMENT_INTEGRITY' }];
  }
  const options = [...codeValidationProfileOptions];
  if (manifest.value?.files.some((file) => file.path === 'pom.xml')) {
    options.unshift(
      { label: '下载 Maven 依赖后离线测试', value: 'MAVEN_TEST' },
      { label: '下载 Maven 依赖后离线验证', value: 'MAVEN_VERIFY' },
    );
  }
  return options;
});
watch(validationProfileOptions, (options) => {
  if (options.length > 0
      && !options.some((option) => option.value === validationProfile.value)) {
    validationProfile.value = options[0].value;
  }
}, { immediate: true });
const directoryPaths = computed(() => collectDirectoryPaths(manifest.value?.files || []));
const allDirectoriesExpanded = computed(() => directoryPaths.value.length > 0 && collapsedDirectories.value.size === 0);
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

function readStoredBoolean(key: string, fallback: boolean) {
  if (typeof window === 'undefined') return fallback;
  const value = window.localStorage.getItem(key);
  return value == null ? fallback : value === 'true';
}

function clampProjectLayout(value: number, minimum: number, maximum: number) {
  return Math.min(Math.max(Math.round(value), minimum), maximum);
}

function readStoredProjectLayout() {
  if (typeof window === 'undefined') return { ...DEFAULT_PROJECT_LAYOUT };
  try {
    const stored = JSON.parse(window.localStorage.getItem(PROJECT_LAYOUT_KEY) || '{}') as Partial<typeof DEFAULT_PROJECT_LAYOUT>;
    return {
      width: clampProjectLayout(Number(stored.width) || DEFAULT_PROJECT_LAYOUT.width, 220, 480),
      projects: clampProjectLayout(Number(stored.projects) || DEFAULT_PROJECT_LAYOUT.projects, 72, 360),
      conversations: clampProjectLayout(Number(stored.conversations) || DEFAULT_PROJECT_LAYOUT.conversations, 72, 420),
    };
  } catch {
    return { ...DEFAULT_PROJECT_LAYOUT };
  }
}

function persistProjectLayout() {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(PROJECT_LAYOUT_KEY, JSON.stringify(projectLayout));
  }
}

function projectLayoutMaximum(axis: ProjectLayoutAxis) {
  if (axis === 'width') return Math.min(480, Math.max(220, window.innerWidth * .42));
  const railHeight = projectContextRailRef.value?.clientHeight || window.innerHeight * .72;
  const other = axis === 'projects'
    ? (sidebarSections.conversations ? 34 : projectLayout.conversations)
    : (sidebarSections.projects ? 34 : projectLayout.projects);
  return Math.max(72, railHeight - other - 188);
}

function updateProjectLayout(axis: ProjectLayoutAxis, value: number) {
  const minimum = axis === 'width' ? 220 : 72;
  projectLayout[axis] = clampProjectLayout(value, minimum, projectLayoutMaximum(axis));
}

function startProjectLayoutResize(axis: ProjectLayoutAxis, event: PointerEvent) {
  if (window.innerWidth <= 980 || contextRailCollapsed.value) return;
  activeProjectLayoutDrag = {
    axis,
    startCoordinate: axis === 'width' ? event.clientX : event.clientY,
    startValue: projectLayout[axis],
  };
  event.currentTarget instanceof HTMLElement && event.currentTarget.setPointerCapture(event.pointerId);
  document.documentElement.classList.add('project-layout-resizing');
  window.addEventListener('pointermove', handleProjectLayoutPointerMove);
  window.addEventListener('pointerup', stopProjectLayoutResize, { once: true });
  event.preventDefault();
}

function handleProjectLayoutPointerMove(event: PointerEvent) {
  if (!activeProjectLayoutDrag) return;
  const coordinate = activeProjectLayoutDrag.axis === 'width' ? event.clientX : event.clientY;
  updateProjectLayout(
    activeProjectLayoutDrag.axis,
    activeProjectLayoutDrag.startValue + coordinate - activeProjectLayoutDrag.startCoordinate,
  );
}

function stopProjectLayoutResize() {
  if (!activeProjectLayoutDrag) return;
  activeProjectLayoutDrag = null;
  document.documentElement.classList.remove('project-layout-resizing');
  window.removeEventListener('pointermove', handleProjectLayoutPointerMove);
  persistProjectLayout();
}

function handleProjectLayoutResizeKey(axis: ProjectLayoutAxis, event: KeyboardEvent) {
  if (window.innerWidth <= 980 || contextRailCollapsed.value) return;
  const negative = axis === 'width' ? event.key === 'ArrowLeft' : event.key === 'ArrowUp';
  const positive = axis === 'width' ? event.key === 'ArrowRight' : event.key === 'ArrowDown';
  if (!negative && !positive) return;
  event.preventDefault();
  updateProjectLayout(axis, projectLayout[axis] + (positive ? 12 : -12));
  persistProjectLayout();
}

function resetProjectLayout() {
  Object.assign(projectLayout, DEFAULT_PROJECT_LAYOUT);
  persistProjectLayout();
}

function setProjectHeaderCollapsed(collapsed: boolean) {
  projectHeaderCollapsed.value = collapsed;
  if (typeof window !== 'undefined') window.localStorage.setItem(PROJECT_HEADER_COLLAPSED_KEY, String(collapsed));
}

function setContextRailCollapsed(collapsed: boolean) {
  contextRailCollapsed.value = collapsed;
  if (typeof window !== 'undefined') window.localStorage.setItem(PROJECT_CONTEXT_COLLAPSED_KEY, String(collapsed));
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

function toggleAllDirectories() {
  if (allDirectoriesExpanded.value) collapseAllDirectories();
  else expandAllDirectories();
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
  const item = value as { response?: { data?: { code?: string; message?: string; detail?: string }; headers?: Record<string, string> }; message?: string };
  const message = item.response?.data?.detail || item.response?.data?.message || item.message;
  if (message === 'Network Error') {
    return 'The upload connection was interrupted. Check the folder size and try again.';
  }
  const code = item.response?.data?.code;
  const traceId = item.response?.headers?.['x-trace-id'];
  const details = [code, traceId ? `traceId=${traceId}` : null].filter(Boolean).join(', ');
  return `${message || 'Request failed.'}${details ? ` (${details})` : ''}`;
}

function apiStatus(value: unknown) {
  return (value as { response?: { status?: number } }).response?.status;
}

function newClientRequestId() {
  return globalThis.crypto?.randomUUID?.()
    || `project-${Date.now()}-${Math.random().toString(16).slice(2)}`;
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

function toggleInspector(tab: ProjectInspectorTab) {
  if (inspectorOpen.value && inspectorTab.value === tab) {
    inspectorOpen.value = false;
    return;
  }
  inspectorTab.value = tab;
  inspectorOpen.value = true;
}

function handleProjectUtilityMenuSelect(key: string | number) {
  if (key === 'preview' || key === 'evidence' || key === 'versions') {
    toggleInspector(key);
  }
}

function handleProjectMenuSelect(key: string | number, project: ProjectSummaryResponse) {
  if (key !== 'rename') return;
  renameProjectId.value = project.id;
  renameProjectDraft.value = project.name;
  renameProjectModalOpen.value = true;
}

async function confirmRenameProject() {
  const projectId = renameProjectId.value;
  const name = renameProjectDraft.value.trim();
  if (!projectId || !name) return;
  loading.renameProject = true;
  error.value = '';
  try {
    const { data } = await renameProject(projectId, name);
    projects.value = projects.value.map((project) => project.id === data.id ? data : project);
    renameProjectModalOpen.value = false;
  } catch (cause) {
    error.value = apiError(cause);
  } finally {
    loading.renameProject = false;
  }
}

function showInspector(tab: ProjectInspectorTab) {
  inspectorTab.value = tab;
  inspectorOpen.value = true;
}

function v2TurnStatusType(status: V2NaturalLanguageTurnHistoryItem['status']) {
  if (status === 'FAILED') return 'error';
  if (status === 'WAITING_CONFIRMATION') return 'warning';
  if (status === 'SUCCEEDED') return 'success';
  return 'info';
}

function v2TaskApplied(task: V2NaturalLanguageTurnHistoryItem) {
  return isV2CandidateApplied(task);
}

function v2TaskStatusType(task: V2NaturalLanguageTurnHistoryItem): 'error' | 'warning' | 'success' | 'info' {
  return v2TaskApplied(task) ? 'success' : v2TurnStatusType(task.status);
}

function v2TaskStatusLabel(task: V2NaturalLanguageTurnHistoryItem) {
  return v2TaskApplied(task)
    ? '已创建新版本'
    : v2NaturalLanguageStatusLabel(task.status);
}

function candidateConfirmationLabel(validation: {
  status: string;
  decisionStatus: string;
  appliedRevisionId?: number | null;
}) {
  if (validation.decisionStatus === 'APPLIED') {
    return validation.appliedRevisionId
      ? `已应用（revision #${validation.appliedRevisionId}）`
      : '已应用';
  }
  if (validation.decisionStatus === 'REJECTED') return '已拒绝';
  return technicalStatusLabel(validation.status);
}

function candidateApplied(artifactId: number) {
  const projected = [...v2TurnHistory.value].reverse()
    .find((item) => item.candidateArtifactId === artifactId)
    ?.confirmationValidation;
  if (projected?.decisionStatus === 'APPLIED'
      && Boolean(projected.appliedRevisionId)) return true;
  return selectedCandidate.value?.artifact.id === artifactId
    && candidateValidations.value.some((validation) =>
      validation.decisionStatus === 'APPLIED'
      && Boolean(validation.appliedRevisionId));
}

function candidateConfirmationAlertType(validation: {
  status: string;
  decisionStatus: string;
} | null): 'success' | 'warning' | 'info' {
  if (validation?.decisionStatus === 'APPLIED'
      || validation?.status === 'SUCCEEDED') return 'success';
  if (validation?.decisionStatus === 'REJECTED') return 'warning';
  return 'info';
}

function openV2CandidateReview(artifactId?: number | null) {
  if (artifactId) {
    const candidate = candidates.value.find((item) => item.artifact.id === artifactId);
    if (candidate) selectCandidate(candidate);
  }
  showInspector('changes');
  nextTick(() => document.querySelector('.project-inspector')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
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
    applicationMessage.value = `已创建新的项目版本 ${shortHash(data.resultVersion)}。候选修改 ${item.artifact.id} 仍作为历史记录保留。`;
    selectedFile.value = null;
    searchResults.value = [];
    await Promise.all([loadManifest(epoch), loadRevisions()]);
    await Promise.all([
      activeSessionId.value ? loadCandidates(activeSessionId.value, epoch) : Promise.resolve(),
      activeSessionId.value ? loadV2TurnHistory(activeSessionId.value, epoch) : Promise.resolve(),
    ]);
    showInspector('versions');
  } catch (cause) {
    const status = apiStatus(cause);
    applicationMessageType.value = status === 409 ? 'warning' : 'error';
    applicationMessage.value = status === 409
      ? `创建新版本前项目已经发生变化，请重新核对候选修改。${apiError(cause)}`
      : status === 422
        ? `候选修改没有通过当前检查，因此没有创建新版本。${apiError(cause)}`
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
      ? `沙箱验证当前不可用，仍可查看候选修改。${apiError(cause)}`
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
    if (activeSessionId.value) await loadV2TurnHistory(activeSessionId.value, epoch);
    void pollCandidateValidation(item.artifact.id, data.validationId, epoch, 0);
  } catch (cause) {
    if (epoch !== projectEpoch) return;
    validationMessageType.value = apiStatus(cause) === 503 ? 'warning' : 'error';
    validationMessage.value = apiStatus(cause) === 503
      ? `沙箱验证当前不可用，候选修改没有被应用。${apiError(cause)}`
      : apiError(cause);
  } finally {
    if (epoch === projectEpoch) loading.candidateValidation = false;
  }
}

async function pollCandidateValidation(artifactId: number, validationId: string, epoch: number, attempt: number) {
  if (epoch !== projectEpoch || selectedCandidate.value?.artifact.id !== artifactId) return;
  await loadCandidateValidations(selectedCandidate.value, epoch);
  const current = candidateValidations.value.find((validation) => validation.validationId === validationId);
  if (!current || candidateValidationTerminal(current.status) || current.decisionStatus !== 'PENDING') {
    if (activeSessionId.value) await loadV2TurnHistory(activeSessionId.value, epoch);
    return;
  }
  if (attempt >= 450) {
    validationMessageType.value = 'warning';
    validationMessage.value = '沙箱验证仍在进行中，请稍后重新打开候选修改查看持久化结果。';
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
    if (activeSessionId.value) await loadV2TurnHistory(activeSessionId.value);
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
    validationMessage.value = '已拒绝候选修改，没有创建新的项目版本；验证记录会保留。';
    await loadCandidateValidations();
    if (activeSessionId.value) await loadV2TurnHistory(activeSessionId.value);
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

function candidateStateLabel(state: CandidateReviewState) {
  const labels: Partial<Record<CandidateReviewState, string>> = {
    VALIDATED: '内容检查通过',
    STALE: '项目版本已变化',
    DRAFT: '草稿',
    INVALID: '内容无效',
    ERROR: '读取失败',
  };
  return labels[state] || state;
}

function candidateChangeType(type: CandidateChangeType): 'success' | 'warning' | 'error' {
  if (type === 'ADD') return 'success';
  if (type === 'DELETE') return 'error';
  return 'warning';
}

function candidateChangeTypeLabel(type: CandidateChangeType) {
  if (type === 'ADD') return '新增';
  if (type === 'DELETE') return '删除';
  return '修改';
}

function candidateValidationLabel(candidate: CandidateArtifactResponse) {
  return candidate.validation.issues.length === 0
    && candidate.validation.checks.every((check) => check.status === 'PASSED') ? '通过' : '失败';
}

function candidateValidationProfileLabel(profile: CandidateValidationProfile) {
  const option = validationProfileOptions.value.find((value) => value.value === profile);
  return option?.label || profile;
}

function candidateCheckAreaLabel(area: string) {
  const labels: Record<string, string> = {
    SCHEMA: '格式',
    GOVERNANCE: '权限',
    CHANGES: '修改内容',
    EVIDENCE: '修改依据',
    BUDGET: '大小限制',
  };
  return labels[area] || area;
}

function technicalStatusLabel(status: string) {
  const labels: Record<string, string> = {
    PASSED: '通过',
    FAILED: '失败',
    SUCCEEDED: '成功',
    RUNNING: '运行中',
    PENDING: '等待确认',
    CANCELLED: '已取消',
    CANCEL_REQUESTED: '正在取消',
    TIMED_OUT: '已超时',
    CLEANUP_FAILED: '清理失败',
    REJECTED: '已拒绝',
  };
  return labels[status] || status;
}

function candidateEvidence(candidate: CandidateArtifactResponse, relativePath: string): CandidateEvidenceRef[] {
  return candidate.changes.find((change) => change.relativePath === relativePath)?.evidenceRefs || [];
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
    void refreshReactPlanSessionSummaries();
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

function naturalLanguageStorageKey(projectId: number, sessionId: number) {
  return `${V2_NATURAL_LANGUAGE_STORAGE_KEY}${projectId}.${sessionId}`;
}

function reactPlanStorageKey(projectId: number, sessionId: number) {
  return `${REACT_PLAN_STORAGE_KEY}${projectId}.${sessionId}`;
}

function storedReactPlanRecords(projectId: number, sessionId: number) {
  const key = reactPlanStorageKey(projectId, sessionId);
  const raw = window.localStorage.getItem(key);
  const records = parseReactPlanHistory(raw, projectId, sessionId);
  if (raw && records.length === 0) window.localStorage.removeItem(key);
  return records;
}

function storeReactPlanRecords(records: ReactPlanTaskRecord[]) {
  const value = serializeReactPlanHistory(records);
  const latest = records[records.length - 1];
  if (!value || !latest) return;
  window.localStorage.setItem(reactPlanStorageKey(latest.projectId, latest.sessionId), value);
}

function rememberReactPlanSessionTasks(sessionId: number, tasks: ReactPlanSessionTask[]) {
  reactPlanSessionStates.value = {
    ...reactPlanSessionStates.value,
    [sessionId]: tasks[tasks.length - 1]?.task.state,
  };
}

async function refreshReactPlanSessionSummaries(activeOnly = false) {
  const projectId = activeProjectId.value;
  const epoch = projectEpoch;
  if (!projectId) return;
  if (activeOnly && reactPlanSessionRefreshProjectId === projectId) return;
  const sessions = projectSessions.value.filter((session) => {
    if (!activeOnly) return true;
    const state = reactPlanSessionState(session.id);
    return state === 'queued' || state === 'running';
  });
  if (sessions.length === 0) return;
  if (activeOnly) reactPlanSessionRefreshProjectId = projectId;
  try {
    await Promise.all(sessions.map(async (session) => {
      try {
        const page = (await listReactPlanSessionTasks(session.id, false, undefined, 1)).data;
        if (epoch === projectEpoch && projectId === activeProjectId.value) {
          rememberReactPlanSessionTasks(session.id, page.items);
        }
      } catch {
        // A sidebar summary is advisory. Opening the session performs a visible full reconciliation.
      }
    }));
  } finally {
    if (activeOnly && reactPlanSessionRefreshProjectId === projectId) {
      reactPlanSessionRefreshProjectId = null;
    }
  }
}

function invalidateReactPlanStream() {
  reactPlanAbortController?.abort();
  reactPlanAbortController = null;
  if (reactPlanReconnectTimer != null) {
    window.clearTimeout(reactPlanReconnectTimer);
    reactPlanReconnectTimer = null;
  }
  reactPlanStreaming.value = false;
}

function resetReactPlanView() {
  invalidateReactPlanStream();
  reactPlanRecord.value = null;
  reactPlanRecords.value = [];
  reactPlanNextCursor.value = null;
  reactPlanLoadingOlder.value = false;
  reactPlanError.value = '';
  reactPlanInput.value = '';
  reactPlanSubmitting.value = false;
  reactPlanSubmissionStartedAt.value = null;
  reactPlanCancelling.value = false;
  reactPlanAnswering.value = false;
  activeReactPlanNavigationId.value = null;
  Object.keys(reactPlanTaskRefs).forEach((taskId) => delete reactPlanTaskRefs[taskId]);
}

function navigationPreviewText(value: string | null | undefined) {
  const normalized = (value || '').replace(/\s+/g, ' ').trim();
  if (!normalized) return '';
  return normalized.length > 100 ? `${normalized.slice(0, 100)}…` : normalized;
}

function setReactPlanTaskRef(element: unknown, taskId: string) {
  if (element instanceof HTMLElement) reactPlanTaskRefs[taskId] = element;
  else delete reactPlanTaskRefs[taskId];
}

function syncReactPlanNavigation() {
  const container = reactPlanTasksRef.value;
  if (!container || reactPlanTimeline.value.length === 0) {
    activeReactPlanNavigationId.value = null;
    return;
  }
  const threshold = container.getBoundingClientRect().top + 24;
  let activeId = reactPlanTimeline.value[0]?.record.taskId ?? null;
  for (const item of reactPlanTimeline.value) {
    const card = reactPlanTaskRefs[item.record.taskId];
    if (!card || card.getBoundingClientRect().top > threshold) break;
    activeId = item.record.taskId;
  }
  activeReactPlanNavigationId.value = activeId;
}

async function scrollToReactPlanTask(taskId: string) {
  await nextTick();
  const container = reactPlanTasksRef.value;
  const card = reactPlanTaskRefs[taskId];
  if (!container || !card) return;
  const containerTop = container.getBoundingClientRect().top;
  const cardTop = card.getBoundingClientRect().top;
  const targetTop = container.scrollTop + cardTop - containerTop - 8;
  container.scrollTo({ top: Math.max(targetTop, 0), behavior: 'smooth' });
  activeReactPlanNavigationId.value = taskId;
}

function isCurrentReactPlan(record: ReactPlanTaskRecord, epoch: number) {
  return epoch === projectEpoch
    && record.projectId === activeProjectId.value
    && record.sessionId === activeSessionId.value
    && record.taskId === reactPlanRecord.value?.taskId;
}

function updateReactPlanRecord(record: ReactPlanTaskRecord) {
  const normalized = {
    ...record,
    startedAt: record.startedAt || record.view.createdAt,
    finishedAt: isReactPlanTerminal(record.view.state)
      ? (record.finishedAt || record.view.updatedAt)
      : null,
  };
  reactPlanRecord.value = normalized;
  reactPlanRecords.value = upsertReactPlanRecord(reactPlanRecords.value, normalized);
  reactPlanSessionStates.value = {
    ...reactPlanSessionStates.value,
    [normalized.sessionId]: normalized.view.state,
  };
  storeReactPlanRecords(reactPlanRecords.value);
}

function acceptReactPlanEvent(source: ReactPlanTaskRecord, event: ReactPlanTaskEvent, epoch: number) {
  if (!isCurrentReactPlan(source, epoch)) return;
  const current = reactPlanRecord.value;
  if (!current) return;
  const events = appendReactPlanEvent(current.events, event, current.taskId);
  if (events === current.events) return;
  const view = { ...current.view, lastSequence: event.sequence, updatedAt: event.occurredAt };
  if (event.type === 'status') {
    view.state = event.state;
    view.error = event.error;
    view.pendingQuestionId = event.state === 'waiting_user' ? view.pendingQuestionId : null;
    if (isReactPlanTerminal(event.state)) view.terminalSequence = event.sequence;
  } else if (event.type === 'question') {
    view.pendingQuestionId = event.questionId;
  } else if (event.type === 'delivery') {
    view.deliverySequence = event.sequence;
  }
  updateReactPlanRecord({ ...current, view, events });
}

function scheduleReactPlanReconnect(record: ReactPlanTaskRecord, epoch: number) {
  if (!isCurrentReactPlan(record, epoch)
      || isReactPlanTerminal(reactPlanRecord.value!.view.state)
      || reactPlanRecord.value!.view.state === 'waiting_user') return;
  reactPlanReconnectTimer = window.setTimeout(() => {
    reactPlanReconnectTimer = null;
    connectReactPlanTask(reactPlanRecord.value!, epoch);
  }, REACT_PLAN_RECONNECT_DELAY_MS);
}

async function connectReactPlanTask(record: ReactPlanTaskRecord, epoch = projectEpoch) {
  invalidateReactPlanStream();
  if (!isCurrentReactPlan(record, epoch)) return;
  const controller = new AbortController();
  reactPlanAbortController = controller;
  reactPlanStreaming.value = true;
  try {
    const view = (await getReactPlanTask(record.turnId, record.taskId, controller.signal)).data;
    if (!isCurrentReactPlan(record, epoch)) return;
    updateReactPlanRecord({ ...reactPlanRecord.value!, view });
    const currentEvents = reactPlanRecord.value?.events ?? [];
    const afterSequence = currentEvents.length ? currentEvents[currentEvents.length - 1].sequence : 0;
    if (afterSequence < view.lastSequence || !isReactPlanTerminal(view.state)) {
      await streamReactPlanEvents(
        record.turnId,
        record.taskId,
        afterSequence,
        controller.signal,
        (event) => acceptReactPlanEvent(record, event, epoch),
      );
    }
    if (!isCurrentReactPlan(record, epoch)) return;
    const finalView = (await getReactPlanTask(record.turnId, record.taskId, controller.signal)).data;
    if (!isCurrentReactPlan(record, epoch)) return;
    updateReactPlanRecord({ ...reactPlanRecord.value!, view: finalView });
    reactPlanError.value = '';
  } catch (cause) {
    if (controller.signal.aborted || !isCurrentReactPlan(record, epoch)) return;
    const current = reactPlanRecord.value;
    if (current && !isReactPlanTerminal(current.view.state)) {
      // A dropped SSE connection is recoverable: durable events are replayed
      // from the last accepted sequence on the scheduled reconnect.
      reactPlanError.value = '';
      console.warn('[react-plan] event stream interrupted; reconnecting', {
        taskId: record.taskId,
        afterSequence: current.events.length ? current.events[current.events.length - 1].sequence : 0,
        error: apiError(cause),
      });
    } else {
      reactPlanError.value = apiError(cause);
    }
  } finally {
    if (reactPlanAbortController === controller) reactPlanAbortController = null;
    if (isCurrentReactPlan(record, epoch)) {
      reactPlanStreaming.value = false;
      scheduleReactPlanReconnect(record, epoch);
    }
  }
}

async function loadReactPlanRecord(projectId: number, sessionId: number, epoch = projectEpoch) {
  invalidateReactPlanStream();
  const localRecords = storedReactPlanRecords(projectId, sessionId);
  let records = localRecords;
  let reconciliationFailed = false;
  try {
    const page = (await listReactPlanSessionTasks(sessionId, true)).data;
    if (epoch !== projectEpoch || projectId !== activeProjectId.value
        || sessionId !== activeSessionId.value) return;
    rememberReactPlanSessionTasks(sessionId, page.items);
    reactPlanNextCursor.value = page.hasMore ? page.nextCursor : null;
    records = mergeReactPlanSessionTasks(localRecords, page.items, projectId, sessionId);
    if (records.length) storeReactPlanRecords(records);
    else window.localStorage.removeItem(reactPlanStorageKey(projectId, sessionId));
  } catch (cause) {
    if (epoch !== projectEpoch || projectId !== activeProjectId.value
        || sessionId !== activeSessionId.value) return;
    reconciliationFailed = true;
    if (localRecords.length === 0) reactPlanError.value = apiError(cause);
  }
  reactPlanRecords.value = records;
  const record = records[records.length - 1] ?? null;
  reactPlanRecord.value = record;
  if (!reconciliationFailed) reactPlanError.value = '';
  if (record) connectReactPlanTask(record, epoch);
}

async function loadEarlierReactPlanTasks() {
  const projectId = activeProjectId.value;
  const sessionId = activeSessionId.value;
  const cursor = reactPlanNextCursor.value;
  const epoch = projectEpoch;
  if (!projectId || !sessionId || !cursor || reactPlanLoadingOlder.value) return;
  reactPlanLoadingOlder.value = true;
  try {
    const page = (await listReactPlanSessionTasks(sessionId, true, cursor)).data;
    if (epoch !== projectEpoch || projectId !== activeProjectId.value
        || sessionId !== activeSessionId.value) return;
    reactPlanRecords.value = mergeReactPlanSessionTasks(
      reactPlanRecords.value, page.items, projectId, sessionId,
    );
    reactPlanRecord.value = reactPlanRecords.value[reactPlanRecords.value.length - 1] ?? null;
    reactPlanNextCursor.value = page.hasMore ? page.nextCursor : null;
    storeReactPlanRecords(reactPlanRecords.value);
  } catch (cause) {
    if (epoch === projectEpoch && sessionId === activeSessionId.value) {
      reactPlanError.value = apiError(cause);
    }
  } finally {
    if (epoch === projectEpoch && sessionId === activeSessionId.value) {
      reactPlanLoadingOlder.value = false;
    }
  }
}

async function submitReactPlanTask() {
  const projectId = activeProjectId.value;
  const instruction = reactPlanInput.value.trim();
  if (!projectId || !instruction || reactPlanBusy.value) return;
  const epoch = projectEpoch;
  const startedAt = new Date().toISOString();
  reactPlanSubmissionStartedAt.value = startedAt;
  reactPlanSubmitting.value = true;
  reactPlanError.value = '';
  invalidateReactPlanStream();
  const controller = new AbortController();
  try {
    const sessionId = await ensureSession();
    if (!sessionId || epoch !== projectEpoch || projectId !== activeProjectId.value) return;
    const clientRequestId = newReactPlanRequestId();
    const accepted = (await startReactPlanTask(sessionId, {
      clientRequestId,
      instruction,
      ...(selectedReactPlanSkillId.value ? { skillId: selectedReactPlanSkillId.value } : {}),
    }, controller.signal)).data;
    void listProjectSessions(projectId).then(({ data }) => {
      if (epoch === projectEpoch && projectId === activeProjectId.value) {
        projectSessions.value = data;
      }
    }).catch(() => undefined);
    reactPlanSessionStates.value = {
      ...reactPlanSessionStates.value,
      [sessionId]: accepted.task.state,
    };
    if (epoch !== projectEpoch || sessionId !== activeSessionId.value) return;
    const record: ReactPlanTaskRecord = {
      version: 1,
      projectId,
      sessionId,
      clientRequestId,
      instruction,
      turnId: accepted.turnId,
      taskId: accepted.taskId,
      startedAt,
      finishedAt: null,
      view: accepted.task,
      events: [],
    };
    reactPlanInput.value = '';
    updateReactPlanRecord(record);
    connectReactPlanTask(record, epoch);
  } catch (cause) {
    if (!controller.signal.aborted && epoch === projectEpoch) reactPlanError.value = apiError(cause);
  } finally {
    if (epoch === projectEpoch) {
      reactPlanSubmitting.value = false;
      reactPlanSubmissionStartedAt.value = null;
    }
  }
}

async function answerCurrentReactPlanQuestion() {
  const record = reactPlanRecord.value;
  const question = reactPlanQuestion.value;
  const answer = reactPlanInput.value.trim();
  if (!record || record.view.state !== 'waiting_user'
      || !question || !answer || reactPlanAnswering.value) return;
  const epoch = projectEpoch;
  reactPlanAnswering.value = true;
  reactPlanError.value = '';
  invalidateReactPlanStream();
  const controller = new AbortController();
  try {
    const view = (await answerReactPlanQuestion(
      record.turnId,
      record.taskId,
      { questionId: question.questionId, answer },
      controller.signal,
    )).data;
    if (!isCurrentReactPlan(record, epoch)) return;
    reactPlanInput.value = '';
    updateReactPlanRecord({ ...reactPlanRecord.value!, view });
    connectReactPlanTask(reactPlanRecord.value!, epoch);
  } catch (cause) {
    if (!controller.signal.aborted && isCurrentReactPlan(record, epoch)) reactPlanError.value = apiError(cause);
  } finally {
    if (isCurrentReactPlan(record, epoch)) reactPlanAnswering.value = false;
  }
}

async function cancelCurrentReactPlanTask() {
  const record = reactPlanRecord.value;
  if (!record || isReactPlanTerminal(record.view.state) || reactPlanCancelling.value) return;
  if (!window.confirm('取消这个 ReAct 任务？已经产生的正式事件会保留。')) return;
  const epoch = projectEpoch;
  reactPlanCancelling.value = true;
  reactPlanError.value = '';
  invalidateReactPlanStream();
  const controller = new AbortController();
  try {
    const view = (await cancelReactPlanTask(
      record.turnId,
      record.taskId,
      newReactPlanCancelId(),
      controller.signal,
    )).data;
    if (!isCurrentReactPlan(record, epoch)) return;
    updateReactPlanRecord({ ...reactPlanRecord.value!, view });
    connectReactPlanTask(reactPlanRecord.value!, epoch);
  } catch (cause) {
    if (!controller.signal.aborted && isCurrentReactPlan(record, epoch)) reactPlanError.value = apiError(cause);
  } finally {
    if (isCurrentReactPlan(record, epoch)) reactPlanCancelling.value = false;
  }
}

function sendReactPlanTask() {
  if (reactPlanRecord.value?.view.state === 'waiting_user' && reactPlanQuestion.value) {
    void answerCurrentReactPlanQuestion();
  }
  else void submitReactPlanTask();
}

function handleReactPlanKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
  event.preventDefault();
  if (!activeProject.value || !reactPlanInput.value.trim() || reactPlanBusy.value) return;
  sendReactPlanTask();
}

function storedV2NaturalLanguageRequest(projectId: number, sessionId: number) {
  try {
    const raw = window.localStorage.getItem(naturalLanguageStorageKey(projectId, sessionId));
    if (!raw) return null;
    const value = JSON.parse(raw) as { clientRequestId?: unknown; question?: unknown };
    if (typeof value.clientRequestId !== 'string' || !value.clientRequestId
        || typeof value.question !== 'string' || !value.question) {
      window.localStorage.removeItem(naturalLanguageStorageKey(projectId, sessionId));
      return null;
    }
    return { clientRequestId: value.clientRequestId, question: value.question };
  } catch {
    window.localStorage.removeItem(naturalLanguageStorageKey(projectId, sessionId));
    return null;
  }
}

function storeV2NaturalLanguageRequest(
  projectId: number,
  sessionId: number,
  clientRequestId: string,
  question: string,
) {
  window.localStorage.setItem(
    naturalLanguageStorageKey(projectId, sessionId),
    JSON.stringify({ clientRequestId, question }),
  );
}

function clearStoredV2NaturalLanguageRequest(projectId: number, sessionId: number) {
  window.localStorage.removeItem(naturalLanguageStorageKey(projectId, sessionId));
}

function currentV2NaturalLanguageIdentity(): V2NaturalLanguageRequestIdentity {
  return {
    projectId: activeProjectId.value ?? -1,
    sessionId: activeSessionId.value ?? -1,
    clientRequestId: v2TurnClientRequestId ?? '',
    sequence: v2TurnSequence,
  };
}

function stopV2NaturalLanguagePolling() {
  v2TurnSequence += 1;
  v2TurnAbortController?.abort();
  v2TurnAbortController = null;
  v2TurnPolling.value = false;
}

function resetV2NaturalLanguageView() {
  stopV2NaturalLanguagePolling();
  v2TurnStarting.value = false;
  v2TurnClientRequestId = null;
  v2TurnOutcome.value = null;
  v2TurnError.value = '';
  v2TurnHistory.value = [];
}

function sortV2TurnHistory(items: V2NaturalLanguageTurnHistoryItem[]) {
  return [...items].sort((left, right) => {
    const byTime = Date.parse(left.createdAt) - Date.parse(right.createdAt);
    return byTime || left.clientRequestId.localeCompare(right.clientRequestId);
  });
}

function upsertV2TurnOutcome(
  clientRequestId: string,
  question: string,
  outcome: V2NaturalLanguageTurnResponse,
) {
  const previous = v2TurnHistory.value.find(
    (item) => item.clientRequestId === clientRequestId,
  );
  const now = new Date().toISOString();
  const next: V2NaturalLanguageTurnHistoryItem = {
    ...outcome,
    clientRequestId,
    question,
    createdAt: previous?.createdAt || now,
    updatedAt: now,
    agentAutomaticValidation: previous?.agentAutomaticValidation || null,
    confirmationValidation: previous?.confirmationValidation || null,
  };
  v2TurnHistory.value = sortV2TurnHistory([
    ...v2TurnHistory.value.filter((item) => item.clientRequestId !== clientRequestId),
    next,
  ]);
}

function upsertV2PlanningTask(clientRequestId: string, question: string) {
  upsertV2TurnOutcome(clientRequestId, question, {
    status: 'PLANNING',
    route: 'PERSISTENT_PLAN_EXECUTE',
    planId: null,
    projectVersion: manifest.value?.version || null,
    steps: [],
    finalText: null,
    candidateArtifactId: null,
    outputPaths: [],
    errorCode: null,
  });
}

async function loadV2TurnHistory(sessionId: number, epoch = projectEpoch) {
  loading.v2History = true;
  try {
    const items = (await listV2NaturalLanguageTurns(sessionId, 50)).data;
    if (epoch !== projectEpoch || sessionId !== activeSessionId.value) return;
    const pending = v2TurnHistory.value.filter((item) => (
      (item.status === 'PLANNING' || item.status === 'RUNNING')
      && !items.some((stored) => stored.clientRequestId === item.clientRequestId)
    ));
    v2TurnHistory.value = sortV2TurnHistory([...items, ...pending]);
  } catch (cause) {
    if (epoch === projectEpoch && sessionId === activeSessionId.value) {
      v2TurnError.value = apiError(cause);
    }
  } finally {
    if (epoch === projectEpoch && sessionId === activeSessionId.value) {
      loading.v2History = false;
    }
  }
}

function v2StepTagType(status: V2NaturalLanguageStepStatus) {
  if (status === 'SUCCEEDED') return 'success';
  if (status === 'FAILED') return 'error';
  if (status === 'RUNNING') return 'info';
  if (status === 'SUPERSEDED_BY_REPLAN') return 'warning';
  return 'default';
}

function v2NaturalLanguageFailureText(cause: unknown) {
  if (cause instanceof V2NaturalLanguageTurnNotCreatedError) {
    return '没有找到这次请求，请重新发送。';
  }
  if (cause instanceof Error && cause.message === 'v2-direct-answer-required') {
    return '直接回答没有返回有效内容，请重新发送。';
  }
  if (cause instanceof Error && cause.message === 'v2-natural-language-poll-timeout') {
    return '任务执行时间较长，请稍后刷新页面查看最新结果。';
  }
  return 'V2 请求失败，请稍后重试。';
}

async function presentV2NaturalLanguageCandidate(
  projectId: number,
  sessionId: number,
  outcome: V2NaturalLanguageTurnResponse,
  epoch: number,
) {
  if (!outcome.candidateArtifactId || epoch !== projectEpoch
      || projectId !== activeProjectId.value || sessionId !== activeSessionId.value) return;
  await loadCandidates(sessionId, epoch);
  const candidate = candidates.value.find((item) => item.artifact.id === outcome.candidateArtifactId);
  if (candidate) selectCandidate(candidate);
}

async function refreshProjectAfterV2AutoApply(clientRequestId: string, epoch: number) {
  if (epoch !== projectEpoch) return;
  const appliedTask = v2TurnHistory.value.find(
    (item) => item.clientRequestId === clientRequestId,
  );
  if (!appliedTask || !isV2CandidateApplied(appliedTask)) return;
  selectedFile.value = null;
  searchResults.value = [];
  await Promise.all([loadManifest(epoch), loadRevisions()]);
}

async function recoverV2NaturalLanguageTurn(projectId: number, sessionId: number) {
  if (!v2NaturalTurnAvailable.value) return;
  const stored = storedV2NaturalLanguageRequest(projectId, sessionId);
  if (!stored) return;
  stopV2NaturalLanguagePolling();
  v2TurnClientRequestId = stored.clientRequestId;
  v2TurnError.value = '';
  if (!v2TurnHistory.value.some((item) => item.clientRequestId === stored.clientRequestId)) {
    upsertV2PlanningTask(stored.clientRequestId, stored.question);
  }
  const sequence = v2TurnSequence;
  const expected = { projectId, sessionId, clientRequestId: stored.clientRequestId, sequence };
  const controller = new AbortController();
  v2TurnAbortController = controller;
  v2TurnPolling.value = true;
  const epoch = projectEpoch;
  const request = normalizeV2NaturalLanguageRequest(
    stored.question,
    stored.clientRequestId,
  );
  try {
    const outcome = await pollV2NaturalLanguageTurn(
      async () => (
        await getV2NaturalLanguageTurn(sessionId, stored.clientRequestId, controller.signal)
      ).data,
      {
        signal: controller.signal,
        resume: async () => (
          await startV2NaturalLanguageTurn(
            sessionId, request, controller.signal,
          )
        ).data,
        onOutcome: (value) => {
          if (isCurrentV2NaturalLanguageRequest(expected, currentV2NaturalLanguageIdentity())) {
            v2TurnOutcome.value = value;
            upsertV2TurnOutcome(stored.clientRequestId, stored.question, value);
          }
        },
      },
    );
    if (!isCurrentV2NaturalLanguageRequest(expected, currentV2NaturalLanguageIdentity())) return;
    v2TurnOutcome.value = outcome;
    upsertV2TurnOutcome(stored.clientRequestId, stored.question, outcome);
    clearStoredV2NaturalLanguageRequest(projectId, sessionId);
    await loadV2TurnHistory(sessionId, epoch);
    await refreshProjectAfterV2AutoApply(stored.clientRequestId, epoch);
    await presentV2NaturalLanguageCandidate(projectId, sessionId, outcome, epoch);
  } catch (cause) {
    if (controller.signal.aborted) return;
    if (isCurrentV2NaturalLanguageRequest(expected, currentV2NaturalLanguageIdentity())) {
      if ((cause as { response?: { status?: number } })?.response?.status === 404) {
        clearStoredV2NaturalLanguageRequest(projectId, sessionId);
      }
      v2TurnError.value = v2NaturalLanguageFailureText(cause);
      await loadV2TurnHistory(sessionId, epoch);
      if (v2TurnHistory.value.some((item) => (
        item.clientRequestId === stored.clientRequestId && item.status === 'FAILED'
      ))) v2TurnError.value = '';
    }
  } finally {
    if (isCurrentV2NaturalLanguageRequest(expected, currentV2NaturalLanguageIdentity())) {
      v2TurnPolling.value = false;
      v2TurnAbortController = null;
    }
  }
}

async function sendV2NaturalLanguageTurn() {
  if (!v2NaturalTurnAvailable.value) {
    v2TurnError.value = 'V2 暂时不可用，项目文件、候选修改和版本功能仍可继续使用。';
    return;
  }
  const projectId = activeProjectId.value;
  if (!projectId || v2NaturalTurnBusy.value) return;
  const epoch = projectEpoch;
  const clientRequestId = newV2NaturalLanguageClientRequestId();
  const question = v2TurnInput.value.trim();
  v2TurnStarting.value = true;
  v2TurnError.value = '';
  v2TurnOutcome.value = null;
  try {
    const sessionId = await ensureSession();
    if (!sessionId || epoch !== projectEpoch || projectId !== activeProjectId.value) return;
    const pending = storedV2NaturalLanguageRequest(projectId, sessionId);
    if (pending) {
      await recoverV2NaturalLanguageTurn(projectId, sessionId);
      return;
    }
    const request = normalizeV2NaturalLanguageRequest(question, clientRequestId);
    stopV2NaturalLanguagePolling();
    v2TurnClientRequestId = clientRequestId;
    const sequence = v2TurnSequence;
    const expected = { projectId, sessionId, clientRequestId, sequence };
    storeV2NaturalLanguageRequest(projectId, sessionId, clientRequestId, question);
    upsertV2PlanningTask(clientRequestId, question);
    const controller = new AbortController();
    v2TurnAbortController = controller;
    v2TurnPolling.value = true;
    const outcome = await startThenPollV2NaturalLanguageTurn(
      async () => (await startV2NaturalLanguageTurn(sessionId, request, controller.signal)).data,
      async () => (
        await getV2NaturalLanguageTurn(sessionId, clientRequestId, controller.signal)
      ).data,
      {
        signal: controller.signal,
        resume: async () => (
          await startV2NaturalLanguageTurn(
            sessionId, request, controller.signal,
          )
        ).data,
        onOutcome: (value) => {
          if (isCurrentV2NaturalLanguageRequest(expected, currentV2NaturalLanguageIdentity())) {
            v2TurnOutcome.value = value;
            upsertV2TurnOutcome(clientRequestId, question, value);
          }
        },
      },
    );
    if (!isCurrentV2NaturalLanguageRequest(expected, currentV2NaturalLanguageIdentity())) return;
    v2TurnOutcome.value = outcome;
    upsertV2TurnOutcome(clientRequestId, question, outcome);
    v2TurnInput.value = '';
    clearStoredV2NaturalLanguageRequest(projectId, sessionId);
    await loadV2TurnHistory(sessionId, epoch);
    await refreshProjectAfterV2AutoApply(clientRequestId, epoch);
    await presentV2NaturalLanguageCandidate(projectId, sessionId, outcome, epoch);
  } catch (cause) {
    const sessionId = activeSessionId.value;
    if (epoch === projectEpoch && projectId === activeProjectId.value) {
      if (sessionId && (isDefinitiveV2NaturalLanguageStartRejection(cause)
          || cause instanceof V2NaturalLanguageTurnNotCreatedError
          || (cause instanceof Error && [
            'v2-direct-answer-required',
            'v2-intake-route-invalid',
          ].includes(cause.message)))) {
        clearStoredV2NaturalLanguageRequest(projectId, sessionId);
      }
      v2TurnError.value = v2NaturalLanguageFailureText(cause);
      if (sessionId) {
        await loadV2TurnHistory(sessionId, epoch);
        if (v2TurnHistory.value.some((item) => (
          item.clientRequestId === clientRequestId && item.status === 'FAILED'
        ))) v2TurnError.value = '';
      }
    }
  } finally {
    if (epoch === projectEpoch && projectId === activeProjectId.value) {
      v2TurnStarting.value = false;
      v2TurnPolling.value = false;
      v2TurnAbortController = null;
    }
  }
}

function handleV2TurnKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || (!event.ctrlKey && !event.metaKey)) return;
  event.preventDefault();
  void sendV2NaturalLanguageTurn();
}

async function selectProject(projectId: number) {
  resetV2NaturalLanguageView();
  resetReactPlanView();
  projectEpoch++;
  sessionFlight = null;
  if (candidateValidationPoll != null) {
    window.clearTimeout(candidateValidationPoll);
    candidateValidationPoll = null;
  }
  loading.file = false;
  loading.search = false;
  activeProjectId.value = projectId;
  activeSessionId.value = null;
  projectSessions.value = [];
  reactPlanSessionStates.value = {};
  collapsedDirectories.value = new Set(collapsedDirectoriesByProject.get(projectId) || []);
  manifest.value = null;
  selectedFile.value = null;
  searchResults.value = [];
  evidence.value = [];
  candidates.value = [];
  revisions.value = [];
  selectedCandidate.value = null;
  selectedChangeIndexes.value = new Set();
  candidateValidations.value = [];
  selectedValidation.value = null;
  applicationMessage.value = '';
  validationMessage.value = '';
  revisionMessage.value = '';
  inspectorTab.value = 'preview';
  inspectorOpen.value = false;
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
  clearPdfPreview();
  loading.file = true;
  try {
    let value: ProjectFileResponse;
    if (path.toLowerCase().endsWith('.pdf')) {
      const raw = (await readProjectRawFile(projectId, path)).data;
      const entry = manifest.value?.files.find((file) => file.path === path);
      if (!entry) throw new Error('The selected PDF is not in the current Project manifest.');
      let extracted = '';
      try {
        extracted = (await previewProjectFile(projectId, path)).data.content;
      } catch {
        // Visual PDF preview remains useful for encrypted or image-only documents.
      }
      value = { ...entry, content: extracted };
      pdfPreviewUrl.value = URL.createObjectURL(raw);
    } else if (/\.(?:docx|xlsx)$/i.test(path)) {
      value = (await previewProjectFile(projectId, path)).data;
    } else {
      value = (await readProjectFile(projectId, path)).data;
    }
    if (epoch === projectEpoch && projectId === activeProjectId.value) {
      selectedFile.value = value;
      showInspector('preview');
    } else {
      clearPdfPreview();
    }
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  } finally {
    if (epoch === projectEpoch) loading.file = false;
  }
}

function clearPdfPreview() {
  if (!pdfPreviewUrl.value) return;
  URL.revokeObjectURL(pdfPreviewUrl.value);
  pdfPreviewUrl.value = '';
}

watch(() => selectedFile.value?.path, (path) => {
  if (!path?.toLowerCase().endsWith('.pdf')) clearPdfPreview();
});

function documentLocationLabel(location: DocumentPreviewLocation) {
  if (location.kind === 'PAGE') return `第 ${location.page || '?'} 页`;
  if (location.kind === 'PARAGRAPH') return `第 ${location.paragraph || '?'} 段`;
  if (location.kind === 'TABLE_CELL') {
    return `表格 ${location.table || '?'} · 行 ${location.row || '?'} · 列 ${location.column || '?'}`;
  }
  return location.kind;
}

function spreadsheetCellValue(cell: SpreadsheetPreviewCell) {
  if (cell.formulaPresent) return '公式（未执行）';
  return cell.value ?? cell.valueType ?? '';
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
    await Promise.all([
      loadCandidates(sessionId, epoch),
      loadV2TurnHistory(sessionId, epoch),
    ]);
    if (epoch === projectEpoch && activeProjectId.value) {
      await loadReactPlanRecord(activeProjectId.value, sessionId, epoch);
      void recoverV2NaturalLanguageTurn(activeProjectId.value, sessionId);
    }
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
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

async function refreshCandidates() {
  const sessionId = currentSessionId();
  if (sessionId) await loadCandidates(sessionId);
}

async function selectConversation(sessionId: number) {
  if (sessionId === activeSessionId.value) return;
  resetV2NaturalLanguageView();
  resetReactPlanView();
  projectEpoch++;
  sessionFlight = null;
  if (candidateValidationPoll != null) {
    window.clearTimeout(candidateValidationPoll);
    candidateValidationPoll = null;
  }
  activeSessionId.value = sessionId;
  syncProjectLocation(activeProjectId.value, sessionId);
  evidence.value = [];
  candidates.value = [];
  selectedCandidate.value = null;
  candidateValidations.value = [];
  selectedValidation.value = null;
  const epoch = projectEpoch;
  try {
    await Promise.all([
      loadCandidates(sessionId, epoch),
      loadV2TurnHistory(sessionId, epoch),
    ]);
    if (epoch === projectEpoch && activeProjectId.value) {
      await loadReactPlanRecord(activeProjectId.value, sessionId, epoch);
      void recoverV2NaturalLanguageTurn(activeProjectId.value, sessionId);
    }
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  }
}

async function startNewConversation() {
  const project = activeProject.value;
  if (!project) return;
  resetV2NaturalLanguageView();
  resetReactPlanView();
  projectEpoch++;
  sessionFlight = null;
  evidence.value = [];
  candidates.value = [];
  selectedCandidate.value = null;
  const epoch = projectEpoch;
  loading.sessions = true;
  try {
    const created = (await createProjectSession(project.id, { title: DEFAULT_SESSION_TITLE, ragDisabled: true })).data;
    if (epoch !== projectEpoch) return;
    projectSessions.value = [created, ...projectSessions.value.filter((item) => item.id !== created.id)];
    reactPlanSessionStates.value = { ...reactPlanSessionStates.value, [created.id]: undefined };
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
  const taskState = reactPlanSessionState(session.id);
  if (taskState === 'queued' || taskState === 'running'
      || (session.id === activeSessionId.value && reactPlanSubmitting.value)) {
    error.value = 'This conversation still has an active task. Stop it before deleting the conversation.';
    return;
  }
  const sessionTitle = session.title || `Conversation #${session.id}`;
  if (!window.confirm(`Delete "${sessionTitle}"?`)) {
    return;
  }
  const wasActive = activeSessionId.value === session.id;
  error.value = '';
  try {
    if (activeProjectId.value) {
      window.localStorage.removeItem(reactPlanStorageKey(activeProjectId.value, session.id));
    }
    await deleteAgentSession(session.id);
    projectSessions.value = projectSessions.value.filter((item) => item.id !== session.id);
    const nextStates = { ...reactPlanSessionStates.value };
    delete nextStates[session.id];
    reactPlanSessionStates.value = nextStates;
    if (!wasActive) return;
    resetV2NaturalLanguageView();
    resetReactPlanView();
    projectEpoch++;
    sessionFlight = null;
    evidence.value = [];
    candidates.value = [];
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
    resetV2NaturalLanguageView();
    resetReactPlanView();
    projectEpoch++;
    sessionFlight = null;
    collapsedDirectoriesByProject.delete(projectId);
    projects.value = projects.value.filter((item) => item.id !== projectId);
    deleteModalOpen.value = false;
    activeProjectId.value = null;
    manifest.value = null;
    selectedFile.value = null;
    searchResults.value = [];
    projectSessions.value = [];
    reactPlanSessionStates.value = {};
    activeSessionId.value = null;
    syncProjectLocation(null, null);
    evidence.value = [];
    candidates.value = [];
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

async function loadProductV2Availability() {
  try {
    const document = (await getV2ProductAvailability()).data;
    const capabilities = Array.isArray(document.capabilities) ? document.capabilities : [];
    const validDocument = document.formatVersion === 1
      && typeof document.enabled === 'boolean'
      && capabilities.every((capability) => typeof capability === 'string')
      && new Set(capabilities).size === capabilities.length;
    v2NaturalTurnAvailable.value = validDocument
      && document.enabled
      && capabilities.includes('agent.turn');
  } catch {
    v2NaturalTurnAvailable.value = false;
  }
  if (!v2NaturalTurnAvailable.value) {
    stopV2NaturalLanguagePolling();
    v2TurnStarting.value = false;
  }
  const projectId = activeProjectId.value;
  const sessionId = activeSessionId.value;
  if (!projectId || !sessionId) return;
  if (v2NaturalTurnAvailable.value) void recoverV2NaturalLanguageTurn(projectId, sessionId);
}

onMounted(() => {
  inspectorOpen.value = false;
  reactPlanClockTimer = window.setInterval(() => {
    reactPlanClock.value = Date.now();
  }, 1_000);
  reactPlanSessionPollTimer = window.setInterval(() => {
    void refreshReactPlanSessionSummaries(true);
  }, 4_000);
  void loadProductV2Availability();
  void listSkills().then(({ data }) => {
    reactPlanSkills.value = data;
  }).catch(() => {
    reactPlanSkills.value = [];
  });
  void loadProjects();
});
onUnmounted(() => {
  clearPdfPreview();
  stopProjectLayoutResize();
  stopV2NaturalLanguagePolling();
  invalidateReactPlanStream();
  if (candidateValidationPoll != null) window.clearTimeout(candidateValidationPoll);
  if (reactPlanClockTimer != null) window.clearInterval(reactPlanClockTimer);
  if (reactPlanSessionPollTimer != null) window.clearInterval(reactPlanSessionPollTimer);
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
.project-rail-resizer--column + .project-panel { border-left: 1px solid var(--yb-border); }
.project-panel__title { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 12px; }
.project-panel__title > span, .project-panel__count { color: var(--yb-text-muted); font-family: ui-monospace, monospace; font-size: 10px; }
.project-panel__title-actions { min-width: 0; }
.project-panel__title-actions :deep(.n-button) { padding: 0 5px; font-size: 9px; }
.project-panel__title--section { padding-top: 10px; border-top: 1px solid var(--yb-border); }
.project-panel__hint { flex: 0 0 auto; margin: 0; color: var(--yb-text-muted); font-size: 10px; line-height: 1.4; }
.project-panel__loading { padding: 8px; }

.project-sidebar-section { min-height: 0; display: flex; flex-direction: column; gap: 8px; }
.project-sidebar-section + .project-sidebar-section { margin-top: 10px; }
.project-sidebar-section--projects,
.project-sidebar-section--chats { flex: 0 0 auto; min-height: 0; max-height: 180px; }
.project-sidebar-section--file-browser { flex: 1 1 auto; min-height: 0; }
.project-sidebar-section--collapsed { flex: 0 0 auto; min-height: 0; gap: 0; }
.project-sidebar-section--collapsed + .project-sidebar-section--collapsed { margin-top: 0; }
.project-sidebar-section__header { flex: 0 0 auto; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; }
.project-sidebar-section__toggle { box-sizing: border-box; flex: 0 0 32px; min-width: 0; height: 32px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 2px; border: 0; background: transparent; color: var(--yb-text); text-align: left; font: inherit; }
.project-sidebar-section + .project-sidebar-section .project-sidebar-section__toggle,
.project-sidebar-section + .project-sidebar-section .project-sidebar-section__header { border-top: 1px solid var(--yb-border); }
.project-sidebar-section__header .project-sidebar-section__toggle { width: 100%; min-width: 0; border-top: 0 !important; }
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
.project-sidebar-section--projects .project-list,
.project-sidebar-section--chats .project-conversation-history--sidebar { flex: 0 1 auto; max-height: 136px; }
.project-list__item, .project-file-list__item, .project-search-results button, .project-candidate-list button { width: 100%; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; border-radius: 7px; }
.project-list__item { min-height: 44px; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 7px; padding: 7px 8px 7px 10px; line-height: 1.3; }
.project-list__item.active, .project-file-list__item.active, .project-candidate-list button.active { background: var(--yb-sidebar-active); }
.project-list__item strong, .project-list__item small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-list__item strong { font-size: 12px; font-weight: 600; line-height: 1.35; }
.project-list__item small { margin: 0; color: var(--yb-text-muted); font-size: 10px; line-height: 1.3; }

.project-file-list { flex: 1 1 180px; min-height: 70px; }
.project-sidebar-section--file-browser .project-file-list { flex: 1 1 auto; min-height: 0; }
.project-file-list__item { padding: 5px 6px; display: flex; justify-content: space-between; gap: 6px; font-family: var(--pa-font-sans); font-size: 10px; }
.project-file-list__item:hover { background: var(--yb-bg-muted); }
.project-file-list__item span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-file-list__item small { flex: 0 0 auto; color: var(--yb-text-muted); font-family: var(--pa-font-mono); font-size: 9px; }
.project-file-list__directory { font-weight: 650; }
.project-file-list__chevron { display: inline-flex; width: 13px; height: 13px; margin-right: 2px; color: var(--yb-text-muted); font-size: 13px; vertical-align: -2px; transition: transform 160ms ease; }
.project-file-list__chevron--expanded { transform: rotate(90deg); }

.project-search { flex: 0 0 auto; display: grid; grid-template-columns: 1fr auto; gap: 6px; }
.project-search-results { flex: 0 1 90px; min-height: 0; display: flex; flex-direction: column; gap: 3px; }
.project-search-results button { padding: 5px 6px; }
.project-search-results strong, .project-search-results span { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 10px; }
.project-search-results span { color: var(--yb-text-muted); margin-top: 2px; }
.project-search-workspace { min-height: 220px; display: flex; flex-direction: column; gap: 12px; }
.project-search-workspace__form { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
.project-search-results--wide { flex: 1 1 auto; max-height: 34dvh; gap: 4px; }
.project-search-results--wide button { padding: 9px 10px; border-bottom: 1px solid var(--yb-border); border-radius: 4px; }
.project-search-results--wide strong { font-size: 11px; }
.project-search-results--wide span { margin-top: 4px; overflow: visible; white-space: normal; line-height: 1.5; }

.project-tabs { flex: 0 0 auto; min-width: 0; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding-bottom: 8px; border-bottom: 1px solid var(--yb-border); }
.project-tabs__title { flex: 0 0 auto; font-size: 12px; line-height: 26px; }
.project-tabs__actions { min-width: 0; display: flex; align-items: center; gap: 6px; overflow-x: auto; overscroll-behavior-x: contain; scrollbar-width: thin; }
.project-tabs__actions > * { flex: 0 0 auto; }
.project-agent-mode { min-width: 0; display: flex; align-items: center; gap: 12px; }
.project-agent-mode__caption { color: var(--yb-text-muted); font-size: 10px; }
.project-agent-mode__switch { display: inline-flex; gap: 3px; padding: 3px; border: 1px solid var(--yb-border); border-radius: 9px; background: var(--yb-bg-muted); }
.project-agent-mode__switch button { display: flex; align-items: baseline; gap: 5px; padding: 6px 10px; border: 0; border-radius: 6px; background: transparent; color: var(--yb-text-secondary); font-weight: 750; cursor: pointer; }
.project-agent-mode__switch button small { color: var(--yb-text-muted); font-size: 9px; font-weight: 500; }
.project-agent-mode__switch button.active { background: var(--yb-bg-elevated); color: var(--yb-primary); box-shadow: 0 1px 3px color-mix(in srgb, var(--yb-text) 10%, transparent); }
.project-agent-mode__switch button.active small { color: var(--yb-text-secondary); }
.project-panel--v2 { overflow: hidden; }

.v2-workbench__hero { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding: 16px; border: 1px solid color-mix(in srgb, var(--yb-primary) 28%, var(--yb-border)); border-radius: 12px; background: color-mix(in srgb, var(--yb-primary) 5%, var(--yb-bg-elevated)); }
.v2-workbench__hero h2 { margin: 3px 0 5px; font-size: 18px; }
.v2-workbench__hero p { max-width: 720px; margin: 0; color: var(--yb-text-secondary); font-size: 11px; line-height: 1.6; }
.v2-workbench__eyebrow { color: var(--yb-primary); font-size: 9px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }
.v2-workbench__kind { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.v2-workbench__kind button { min-width: 0; display: flex; flex-direction: column; gap: 4px; padding: 12px; border: 1px solid var(--yb-border); border-radius: 10px; background: var(--yb-bg-elevated); color: var(--yb-text); text-align: left; cursor: pointer; }
.v2-workbench__kind button span { color: var(--yb-text-muted); font-size: 10px; line-height: 1.5; }
.v2-workbench__kind button.active { border-color: var(--yb-primary); background: color-mix(in srgb, var(--yb-primary) 7%, var(--yb-bg-elevated)); box-shadow: inset 3px 0 0 var(--yb-primary); }
.v2-availability-indicator { margin: 0; padding: 7px 10px; border-radius: 7px; background: var(--yb-bg-muted); color: var(--yb-text-secondary); font-size: 10px; }
.v2-availability-indicator[data-status="available"] { color: color-mix(in srgb, #18a058 86%, var(--yb-text)); }
.v2-availability-indicator[data-status="unavailable"] { color: #d03050; }
.v2-project-analysis { border: 1px solid var(--yb-border); border-radius: 10px; background: var(--yb-bg-elevated); }
.v2-project-analysis > summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; list-style: none; cursor: pointer; }
.v2-project-analysis > summary::-webkit-details-marker { display: none; }
.v2-project-analysis > summary > span { display: flex; flex-direction: column; gap: 3px; }
.v2-project-analysis > summary small { color: var(--yb-text-muted); font-size: 9px; font-weight: 400; }
.v2-project-analysis__body { display: flex; flex-direction: column; gap: 10px; padding: 0 14px 14px; border-top: 1px solid var(--yb-border); }
.v2-project-analysis__body > p { margin: 10px 0 0; color: var(--yb-text-secondary); font-size: 10px; line-height: 1.55; }
.v2-project-analysis__search { display: grid; grid-template-columns: minmax(0, 1fr) 110px; gap: 8px; }
.v2-project-analysis__outcome { display: flex; flex-direction: column; gap: 9px; padding: 11px; border: 1px solid var(--yb-border); border-radius: 8px; background: var(--yb-bg-muted); }
.v2-project-analysis__outcome > header { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--yb-text-muted); font-size: 9px; }
.v2-project-analysis__actions { display: flex; justify-content: flex-end; }
.v2-workbench__progress { display: flex; flex-direction: column; gap: 6px; padding: 12px; border: 1px solid var(--yb-border); border-radius: 10px; background: var(--yb-bg-muted); }
.v2-workbench__progress > header { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 2px; }
.v2-workbench__progress > header small { color: var(--yb-text-muted); font-size: 9px; }
.v2-workbench__progress > article { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 9px; padding: 8px; border-radius: 7px; background: var(--yb-bg-elevated); }
.v2-workbench__progress > article > span { display: grid; width: 22px; height: 22px; place-items: center; border: 1px solid var(--yb-border); border-radius: 50%; color: var(--yb-text-muted); font-weight: 800; }
.v2-workbench__progress > article[data-state="done"] > span { border-color: #18a058; color: #18a058; }
.v2-workbench__progress > article[data-state="failed"] > span { border-color: #d03050; color: #d03050; }
.v2-workbench__progress > article > div { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.v2-workbench__progress > article > div small { overflow-wrap: anywhere; color: var(--yb-text-muted); font-size: 9px; }
.v2-conversation { flex: 1 1 auto; min-height: 0; overflow: hidden; display: flex; flex-direction: column; gap: 12px; padding: 2px; }
.project-conversation-shell { flex: 1 1 auto; min-height: 0; display: grid; grid-template-columns: minmax(0, 1fr) 34px; gap: 10px; overflow: hidden; }
.project-conversation-shell > :deep(.chat-minimap-rail) { right: 2px; opacity: .62; }
.v2-conversation__tasks { flex: 1 1 auto; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; padding-right: 3px; scrollbar-gutter: stable; }
.reactplan-history-more { display: flex; justify-content: center; padding: 2px 0 4px; }
.v2-task-card { display: flex; flex-direction: column; gap: 10px; padding: 12px; border: 1px solid var(--yb-border); border-radius: 12px; background: var(--yb-bg-elevated); }
.v2-task-card__question { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.v2-task-card__status { flex: 0 0 auto; display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.v2-task-card__status small { color: var(--yb-text-muted); font-size: 10px; white-space: nowrap; }
.v2-task-card__question-copy { min-width: 0; flex: 1 1 auto; display: grid; grid-template-columns: 24px minmax(0, 1fr); align-items: start; gap: 10px; }
.v2-task-card__question p { margin: 1px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.65; }
.v2-task-card__avatar { flex: 0 0 24px; width: 24px; height: 24px; display: grid; place-items: center; border: 1px solid var(--yb-border-strong); border-radius: 50%; color: var(--yb-primary); background: var(--yb-bg-elevated); font-size: 10px; font-weight: 700; line-height: 1; }
.v2-task-card__avatar--assistant { border-color: var(--yb-primary); border-radius: 5px; color: var(--yb-primary-contrast); background: var(--yb-primary); }
.v2-task-card__result { display: grid; grid-template-columns: 24px minmax(0, 1fr); align-items: start; gap: 10px; padding: 10px; border-radius: 8px; background: var(--yb-bg-muted); }
.v2-task-card__result-copy { min-width: 0; }
.v2-task-card__result-copy > p { margin: 0; color: var(--yb-text-secondary); }
.v2-task-card__delivery { display: flex; flex-direction: column; gap: 8px; }
.v2-task-card__validation { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 5px 9px; margin: 0; font-size: 10px; }
.v2-task-card__validation dt { color: var(--yb-text-muted); }
.v2-task-card__validation dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.reactplan-task-card code { overflow-wrap: anywhere; color: var(--yb-text-muted); font-size: 9px; }
.reactplan-receipt { display: block; margin-top: 3px; }
.reactplan-activity { width: min(1040px, 100%); display: flex; align-items: center; gap: 7px; margin: 0 auto -5px; padding: 0 9px; color: var(--yb-text-muted); font-size: 10px; line-height: 1.4; }
.reactplan-activity__dot { width: 6px; height: 6px; flex: 0 0 auto; border-radius: 50%; background: var(--yb-primary); animation: reactplan-activity-pulse 1.6s ease-in-out infinite; }
.reactplan-activity[data-state='cancelling'] .reactplan-activity__dot { background: var(--yb-danger, #d65b5b); }
@keyframes reactplan-activity-pulse { 0%, 100% { opacity: .38; transform: scale(.82); } 50% { opacity: 1; transform: scale(1); } }
.v2-conversation__question { align-self: flex-end; max-width: min(82%, 720px); padding: 10px 12px; border-radius: 12px 12px 2px 12px; background: var(--yb-bg-muted); }
.v2-conversation__process, .v2-conversation__result { padding: 12px 0; border-bottom: 1px solid var(--yb-border); }
.v2-conversation__question small { color: var(--yb-text-muted); font-size: 9px; }
.v2-conversation__question p { margin: 5px 0 0; white-space: pre-wrap; line-height: 1.65; }
.v2-conversation__process > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.v2-conversation__process > summary { cursor: pointer; color: var(--yb-text-secondary); font-size: 10px; font-weight: 700; }
.v2-conversation__process[open] > summary { margin-bottom: 8px; }
.v2-conversation__process ol { display: flex; flex-direction: column; gap: 6px; margin: 0; padding: 0; list-style: none; }
.v2-conversation__process li { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 9px; padding: 8px; border-radius: 7px; background: var(--yb-bg-muted); }
.v2-conversation__process li > span { display: grid; width: 22px; height: 22px; place-items: center; border: 1px solid var(--yb-border); border-radius: 50%; color: var(--yb-text-muted); font-size: 10px; font-weight: 800; }
.v2-conversation__process li > div { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.v2-conversation__process li small { overflow-wrap: anywhere; color: var(--yb-text-muted); font-size: 9px; }
.v2-conversation__empty-process { margin: 0; color: var(--yb-text-muted); font-size: 10px; }
.v2-conversation__result { display: flex; flex-direction: column; gap: 10px; }
.v2-conversation__result h3 { margin: 0; font-size: 14px; }
.v2-conversation__outputs { display: flex; flex-direction: column; gap: 5px; padding: 9px; border-radius: 7px; background: var(--yb-bg-muted); font-size: 10px; }
.v2-conversation__outputs code { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; user-select: all; }
.v2-conversation__candidate { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.v2-conversation__composer {
  width: min(1040px, 100%);
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 8px;
  margin: auto auto 0;
  padding: 8px;
  border: 1px solid var(--project-rule);
  border-radius: 10px;
  background: var(--project-surface);
  box-shadow: 0 5px 16px color-mix(in srgb, var(--project-ink) 5%, transparent);
}
.v2-conversation__composer :deep(.n-input) {
  min-width: 0;
  background: transparent !important;
}
.v2-conversation__composer :deep(.n-input__border),
.v2-conversation__composer :deep(.n-input__state-border) { display: none; }
.reactplan-composer-actions { display: flex; align-items: center; gap: 6px; }
.reactplan-skill-select { width: 180px; min-width: 140px; }
.v2-conversation__composer .project-send-button {
  width: 72px;
  min-width: 72px;
  height: 40px;
  padding: 0;
  border-radius: 8px !important;
}
.v2-conversation__composer .project-stop-button { width: 88px; min-width: 88px; }
.v2-conversation__composer .project-cancel-button { min-width: 68px; height: 40px; border-radius: 8px !important; }
.v2-conversation__composer .project-send-button :deep(.n-button__content) {
  width: 100%;
  justify-content: center;
  line-height: 1;
}

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
.project-conversation-item__copy { display: flex; min-width: 0; align-items: center; gap: 6px; }
.project-conversation-item__copy > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-conversation-item__copy > small { flex: 0 0 auto; color: var(--yb-text-muted); font-size: 9px; }
.project-conversation-item__copy > small[data-state="running"] { color: var(--yb-primary); }
.project-conversation-item__copy > small[data-state="queued"] { color: var(--project-warning, #b7791f); }
.project-conversation-item__copy > small[data-state="succeeded"] { color: var(--project-success, #17845a); }
.project-conversation-item__more { width: 36px; height: 36px; margin: -4px -4px -4px 0; display: inline-flex; align-items: center; justify-content: center; border: 0; border-radius: 6px; background: transparent; color: var(--yb-text-muted); cursor: pointer; font-size: 12px; line-height: 1; }
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
.project-preview__pdf { flex: 1 1 auto; width: 100%; min-height: 0; border: 0; border-radius: 6px; background: #fff; }
.project-preview__document, .project-preview__spreadsheet { flex: 1 1 auto; min-height: 0; overflow: auto; display: flex; flex-direction: column; gap: 8px; }
.project-preview__document article, .project-preview__spreadsheet section { padding: 8px; border: 1px solid var(--yb-border); border-radius: 6px; background: var(--yb-bg); }
.project-preview__document strong, .project-preview__spreadsheet strong { color: var(--yb-text-secondary); font-size: 9px; }
.project-preview__document p { margin: 4px 0 0; white-space: pre-wrap; color: var(--yb-text); line-height: 1.55; }
.project-preview__cells { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 4px; margin-top: 6px; }
.project-preview__cells span { display: flex; gap: 6px; padding: 4px 6px; border-radius: 4px; background: var(--yb-bg-elevated); color: var(--yb-text); }

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
.project-candidate-validation-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px; }
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
  .project-rail-resizer--column + .project-panel { border-left: 0; border-top: 1px solid var(--yb-border); }
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
  .project-conversation-shell { grid-template-columns: minmax(0, 1fr); }
  .project-conversation-shell > :deep(.chat-minimap-rail) { display: none; }
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

/* Project console refresh: a restrained evidence workspace, scoped to this page. */
.project-workspace--console {
  --project-canvas: var(--pa-canvas);
  --project-surface: var(--pa-surface);
  --project-surface-raised: var(--pa-surface-muted);
  --project-rule: var(--pa-line);
  --project-rule-strong: var(--pa-line-strong);
  --project-ink: var(--pa-text);
  --project-text: var(--pa-text-secondary);
  --project-muted: var(--pa-text-muted);
  --project-accent: var(--pa-accent);
  --project-accent-strong: var(--pa-accent-hover);
  --project-accent-soft: var(--pa-accent-soft);
  --project-active: var(--pa-accent-soft);
  --project-success: var(--pa-success);
  --project-warning: var(--pa-warning);
  --project-danger: var(--pa-danger);
  --project-radius-control: 4px;
  --project-radius-panel: 6px;
  --project-font-ui: var(--pa-font-sans);
  --yb-bg: var(--project-canvas);
  --yb-bg-elevated: var(--project-surface);
  --yb-bg-muted: var(--project-surface-raised);
  --yb-bg-subtle: var(--project-canvas);
  --yb-border: var(--project-rule);
  --yb-border-strong: var(--project-rule-strong);
  --yb-text: var(--project-ink);
  --yb-text-secondary: var(--project-text);
  --yb-text-muted: var(--project-muted);
  --yb-primary: var(--project-accent);
  --yb-primary-strong: var(--project-accent-strong);
  --yb-primary-soft: var(--project-accent-soft);
  --yb-primary-contrast: #061317;
  --yb-sidebar-active: var(--project-active);
  gap: 10px;
  color: var(--project-ink);
  background: var(--project-canvas);
  font-family: var(--project-font-ui);
}

.project-workspace--console .project-workspace__header {
  min-height: 54px;
  padding: 10px 12px;
  border-bottom-color: var(--project-rule);
  background: var(--project-surface);
}

.project-workspace--console .project-workspace__header h1 {
  min-width: 0;
  font-family: var(--project-font-ui);
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -.015em;
}

.project-workspace--console .project-workspace__grid {
  grid-template-columns: var(--project-context-width, 270px) 7px minmax(0, 1fr);
  border-color: var(--project-rule);
  border-radius: var(--project-radius-panel);
  background: var(--project-surface);
  box-shadow: none;
}

.project-workspace--console .project-workspace__grid--context-collapsed {
  grid-template-columns: 0 0 minmax(0, 1fr);
}

.project-workspace--console .project-workspace__grid--context-collapsed .project-context-rail {
  visibility: hidden;
  width: 0;
  min-width: 0;
  padding: 0;
  overflow: hidden;
  border: 0;
}

.project-workspace--console .project-panel {
  padding: 14px;
  gap: 12px;
}

.project-workspace--console .project-context-rail {
  display: grid;
  padding: 12px;
  gap: 0;
  background: var(--project-canvas);
}

.project-workspace--console .project-context-rail .project-sidebar-section {
  min-height: 0;
  max-height: none;
  margin: 0;
  overflow: hidden;
}

.project-workspace--console .project-context-rail .project-sidebar-section--file-browser {
  min-height: 0;
}

.project-rail-resizer {
  position: relative;
  z-index: 2;
  min-width: 0;
  min-height: 0;
  padding: 0;
  border: 0;
  background: transparent;
  touch-action: none;
}

.project-rail-resizer::after {
  content: '';
  position: absolute;
  border-radius: 999px;
  background: transparent;
  transition: background-color 120ms ease;
}

.project-rail-resizer:hover::after,
.project-rail-resizer:focus-visible::after {
  background: color-mix(in srgb, var(--project-accent) 62%, transparent);
}

.project-rail-resizer--column { cursor: col-resize; }
.project-rail-resizer--column::after { inset: 0 2px; }
.project-rail-resizer--row { cursor: row-resize; }
.project-rail-resizer--row::after { inset: 2px 12px; }
.project-rail-resizer:disabled { cursor: default; opacity: .28; }
.project-workspace__grid--context-collapsed > .project-rail-resizer--column { visibility: hidden; }
:global(.project-layout-resizing) { cursor: grabbing; user-select: none; }

.project-workspace--console .project-panel + .project-panel,
.project-workspace--console .project-sidebar-section + .project-sidebar-section .project-sidebar-section__toggle,
.project-workspace--console .project-sidebar-section + .project-sidebar-section .project-sidebar-section__header {
  border-color: var(--project-rule);
}

.project-workspace--console .project-sidebar-section__toggle {
  min-height: 34px;
  height: 34px;
  padding-inline: 2px;
}

.project-workspace--console .project-sidebar-section__toggle strong,
.project-workspace--console .project-panel__title,
.project-workspace--console .project-tabs__title {
  font-family: var(--project-font-ui);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: .01em;
}

.project-workspace--console small {
  font-size: 11px;
}

.project-workspace--console .project-panel__title > span,
.project-workspace--console .project-panel__count,
.project-workspace--console .project-panel__hint,
.project-workspace--console .project-agent-mode__caption,
.project-workspace--console .project-file-list__item,
.project-workspace--console .project-search-results strong,
.project-workspace--console .project-search-results span,
.project-workspace--console .project-inspector__tabs > strong,
.project-workspace--console .v2-conversation__process > summary,
.project-workspace--console .v2-conversation__empty-process {
  font-size: 11px;
}

.project-workspace--console .project-panel__title > span,
.project-workspace--console .project-panel__count,
.project-workspace--console .project-panel__hint,
.project-workspace--console .project-agent-mode__caption {
  color: var(--project-muted);
}

.project-workspace--console .project-chevron-button,
.project-workspace--console .project-conversation-item__more {
  border-radius: var(--project-radius-control);
}

.project-workspace--console .project-list__item,
.project-workspace--console .project-file-list__item,
.project-workspace--console .project-search-results button,
.project-workspace--console .project-candidate-list button,
.project-workspace--console .project-conversation-item {
  border-radius: var(--project-radius-control);
}

.project-workspace--console .project-list__item {
  min-height: 44px;
  padding: 8px 12px;
}

.project-workspace--console .project-list__item strong {
  font-size: 13px;
  font-weight: var(--pa-font-weight-semibold);
  line-height: 1.35;
}

.project-workspace--console .project-list__item small {
  margin: 0;
  font-size: 11px;
  font-weight: var(--pa-font-weight-regular);
  line-height: 1.3;
}

.project-workspace--console .project-conversation-item {
  min-height: 40px;
  padding: 0 8px 0 10px;
  font-size: 12px;
  line-height: 1.35;
}

.project-workspace--console .project-file-list__item {
  min-height: 30px;
  padding-block: 6px;
}

.project-workspace--console .project-list__item.active,
.project-workspace--console .project-file-list__item.active,
.project-workspace--console .project-candidate-list button.active,
.project-workspace--console .project-conversation-item.active,
.project-workspace--console [aria-current="page"] {
  box-shadow: inset 2px 0 0 var(--project-accent);
  background: var(--project-active);
  color: var(--project-ink);
}

.project-workspace--console .project-list__item:hover,
.project-workspace--console .project-file-list__item:hover,
.project-workspace--console .project-search-results button:hover,
.project-workspace--console .project-conversation-item:hover {
  background: color-mix(in srgb, var(--project-surface-raised) 78%, var(--project-accent-soft));
}

.project-workspace--console .project-command-bar {
  gap: 12px;
  padding: 2px 0 10px;
  border-bottom-color: var(--project-rule);
}

.project-workspace--console .project-command-bar .project-tabs__actions {
  margin-left: auto;
}

.project-workspace--console .project-agent-mode {
  gap: 9px;
}

.project-workspace--console .project-tabs__actions {
  gap: 5px;
}

.project-workspace--console .project-utility-more {
  display: none;
}

.project-workspace--console .project-utility-chip {
  min-height: 32px;
  padding: 6px 9px;
  border-color: var(--project-rule);
  border-radius: var(--project-radius-control);
  color: var(--project-text);
  background: transparent;
  font-size: 11px;
}

.project-workspace--console .project-utility-chip.active,
.project-workspace--console .project-utility-chip[aria-pressed="true"] {
  border-color: var(--project-accent);
  background: var(--project-active);
  color: var(--project-ink);
  box-shadow: inset 2px 0 0 var(--project-accent);
}

.project-workspace--console .project-inspector {
  gap: 10px;
  padding: 12px 0;
  border: 0;
  border-block: 1px solid var(--project-rule);
  border-radius: 0;
  background: transparent;
}

.project-workspace--console .project-inspector__body {
  gap: 12px;
}

.project-workspace--console .v2-conversation {
  gap: 14px;
  padding: 4px 0 2px;
}

.project-workspace--console .v2-task-card__question p,
.project-workspace--console .v2-task-card__result,
.project-workspace--console .v2-task-card__result :deep(.message-markdown) {
  font-size: 14px;
  line-height: 1.65;
}

.project-workspace--console .v2-conversation__tasks {
  gap: 0;
}

.project-workspace--console .v2-task-card {
  gap: 12px;
  padding: 14px 0;
  border: 0;
  border-top: 1px solid var(--project-rule);
  border-radius: 0;
  background: transparent;
}

.project-workspace--console .v2-task-card:first-child {
  border-top: 0;
  padding-top: 0;
}

.project-workspace--console .v2-task-card__question {
  padding: 12px 14px;
  border: 1px solid var(--pa-role-user-border);
  border-left: 3px solid var(--project-accent);
  border-radius: var(--project-radius-panel);
  background: var(--pa-role-user-surface);
}

.project-workspace--console .v2-task-card__result {
  gap: 8px;
  padding: 10px 0 2px;
  border-left: 0;
  border-radius: 0;
  background: transparent;
}

.project-workspace--console .v2-task-card__delivery,
.project-workspace--console .v2-conversation__outputs {
  gap: 7px;
}

.project-workspace--console .v2-conversation__outputs {
  padding: 8px 0 0 12px;
  border-left: 2px solid var(--project-rule-strong);
  border-radius: 0;
  background: transparent;
  font-size: 11px;
}

.project-workspace--console .v2-task-card__validation {
  gap: 5px 12px;
  font-size: 11px;
}

.project-workspace--console .v2-conversation__process {
  padding: 0;
  border-top: 1px solid var(--project-rule);
  border-bottom: 1px solid var(--project-rule);
}

.project-workspace--console .v2-conversation__process > summary {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 8px 2px;
  list-style: none;
  color: var(--project-text);
  font-size: 11px;
  font-weight: 600;
}

.project-workspace--console .v2-conversation__process > summary::-webkit-details-marker { display: none; }

.project-workspace--console .v2-conversation__process > summary::before {
  display: grid;
  width: 16px;
  height: 16px;
  place-items: center;
  border: 1px solid var(--project-rule-strong);
  border-radius: 50%;
  color: var(--project-success);
  content: "✓";
  font-size: 10px;
  line-height: 1;
}

.project-workspace--console .v2-conversation__process > summary::after {
  margin-left: auto;
  color: var(--project-muted);
  content: "›";
  font-size: 18px;
  transition: transform 150ms ease;
}

.project-workspace--console .v2-conversation__process[open] > summary::after { transform: rotate(90deg); }
.project-workspace--console .v2-conversation__process > summary small { color: var(--project-muted); font-size: 10px; font-weight: 400; }

.project-workspace--console .v2-conversation__process[open] > summary {
  margin-bottom: 0;
  border-bottom: 1px solid var(--project-rule);
}

.project-workspace--console .v2-conversation__process ol {
  position: relative;
  gap: 0;
  padding: 8px 2px;
}

.project-workspace--console .v2-conversation__process ol::before {
  position: absolute;
  top: 19px;
  bottom: 19px;
  left: 11px;
  width: 1px;
  background: var(--project-rule-strong);
  content: "";
}

.project-workspace--console .v2-conversation__process li {
  position: relative;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  gap: 9px;
  padding: 8px 0;
  border-radius: 0;
  background: transparent;
}

.project-workspace--console .v2-conversation__process li > span {
  z-index: 1;
  border-color: var(--project-rule-strong);
  border-radius: 50%;
  color: var(--project-muted);
  background: var(--project-canvas);
  font-size: 11px;
}

.project-workspace--console .v2-conversation__process li[data-status="SUCCEEDED"] > span { border-color: var(--project-success); color: var(--project-success); }
.project-workspace--console .v2-conversation__process li[data-status="FAILED"] > span { border-color: var(--project-danger); color: var(--project-danger); }
.project-workspace--console .v2-conversation__process li[data-status="RUNNING"] > span,
.project-workspace--console .v2-conversation__process li[data-status="ACTIVE"] > span { border-color: var(--project-accent); color: var(--project-accent); }

.project-workspace--console :deep(.n-button) {
  --n-border-radius: var(--project-radius-control);
}

.project-workspace--console :deep(.n-button--primary-type) {
  --n-color: var(--project-accent);
  --n-color-hover: color-mix(in srgb, var(--project-accent) 88%, white);
  --n-color-pressed: var(--project-accent-strong);
  --n-border: 1px solid var(--project-accent);
  --n-border-hover: 1px solid var(--project-accent);
  --n-border-pressed: 1px solid var(--project-accent-strong);
  --n-text-color: #061317;
  --n-text-color-hover: #061317;
  --n-text-color-pressed: #061317;
}

.project-workspace--console :deep(.n-button.n-button--primary-type) {
  background-color: var(--project-accent) !important;
  border-color: var(--project-accent) !important;
  color: #061317 !important;
}

.project-workspace--console :deep(.n-button.n-button--primary-type .n-button__border) {
  border-color: var(--project-accent) !important;
}

.project-workspace--console :deep(.n-button.n-button--primary-type:not(.n-button--disabled):hover) {
  background-color: color-mix(in srgb, var(--project-accent) 88%, white) !important;
  border-color: color-mix(in srgb, var(--project-accent) 88%, white) !important;
}

.project-workspace--console :deep(.n-button.n-button--primary-type:not(.n-button--disabled):active) {
  background-color: var(--project-accent-strong) !important;
  border-color: var(--project-accent-strong) !important;
}

.project-workspace--console :deep(.n-input-wrapper) {
  --n-border-radius: var(--project-radius-control);
}

.project-workspace--console button:focus-visible,
.project-workspace--console [role="button"]:focus-visible,
.project-workspace--console :deep(.n-input):focus-within {
  outline: 2px solid var(--project-accent);
  outline-offset: 2px;
}

.project-workspace--console button:disabled,
.project-workspace--console :deep(.n-button--disabled) {
  opacity: .48;
}

@media (max-width: 1200px) {
  .project-workspace--console .project-workspace__grid { grid-template-columns: var(--project-context-width, 248px) 7px minmax(0, 1fr); }
  .project-workspace--console .project-workspace__grid--context-collapsed { grid-template-columns: 0 0 minmax(0, 1fr); }
}

@media (max-width: 980px) {
  .project-workspace--console { height: auto; min-height: calc(100dvh - 28px); overflow: visible; gap: 8px; }
  .project-workspace--console .project-workspace__header { padding: 10px 12px; }
  .project-workspace--console .project-workspace__grid { grid-template-columns: 1fr; overflow: visible; }
  .project-workspace--console .project-workspace__grid--context-collapsed { grid-template-columns: 1fr; }
  .project-workspace--console .project-workspace__grid--context-collapsed .project-context-rail { display: none; }
  .project-workspace--console .project-panel { min-height: 0; padding: 12px; }
  .project-workspace--console .project-rail-resizer { display: none; }
  .project-workspace--console .project-context-rail {
    display: flex;
    flex-direction: row;
    gap: 0;
    padding: 0;
    overflow-x: auto;
    overflow-y: hidden;
    scroll-snap-type: x proximity;
    scrollbar-width: none;
  }
  .project-workspace--console .project-context-rail::-webkit-scrollbar { display: none; }
  .project-workspace--console .project-context-rail .project-sidebar-section {
    flex: 0 0 clamp(238px, 34vw, 300px);
    min-height: 224px;
    max-height: 254px;
    gap: 7px;
    margin: 0;
    padding: 12px;
    scroll-snap-align: start;
  }
  .project-workspace--console .project-context-rail .project-sidebar-section--file-browser { flex-basis: clamp(278px, 42vw, 340px); }
  .project-workspace--console .project-context-rail .project-sidebar-section + .project-sidebar-section { border-top: 0; border-left: 1px solid var(--project-rule); }
  .project-workspace--console .project-context-rail .project-sidebar-section + .project-sidebar-section .project-sidebar-section__toggle,
  .project-workspace--console .project-context-rail .project-sidebar-section + .project-sidebar-section .project-sidebar-section__header { border-top: 0; }
  .project-workspace--console .project-context-rail .project-list,
  .project-workspace--console .project-context-rail .project-conversation-history--sidebar,
  .project-workspace--console .project-context-rail .project-file-list,
  .project-workspace--console .project-context-rail .project-search-results { min-height: 0; max-height: none; }
  .project-workspace--console .project-panel + .project-panel { border-top-color: var(--project-rule); }
  .project-workspace--console .project-tabs { align-items: flex-start; gap: 9px; }
  .project-workspace--console .project-tabs__actions {
    width: 100%;
    flex-wrap: wrap;
    overflow: visible;
    padding-bottom: 2px;
  }
  .project-workspace--console .project-utility-chip--secondary { display: none; }
  .project-workspace--console .project-utility-more { display: inline-flex; }
  .project-workspace--console .project-panel--v2,
  .project-workspace--console .v2-conversation,
  .project-workspace--console .v2-conversation__tasks { overflow: visible; }
  .project-workspace--console .v2-conversation,
  .project-workspace--console .v2-conversation__tasks { flex: none; }
}

@media (min-width: 981px) {
  .project-workspace--console .project-inspector {
    min-height: 0;
    max-height: 42dvh;
    overflow: hidden;
  }

  .project-workspace--console .project-inspector__tabs {
    flex: 0 0 auto;
  }

  .project-workspace--console .project-inspector__body {
    flex: 1 1 auto;
    min-height: 0;
    overflow-y: auto;
    overscroll-behavior: contain;
    padding-right: 4px;
    scrollbar-gutter: stable;
  }

  .project-workspace--console .project-inspector__body .project-diff {
    min-height: 0;
    overflow: visible;
  }

  .project-workspace--console .project-candidate-apply {
    position: sticky;
    z-index: 2;
    bottom: 0;
    margin-inline: -4px;
    padding: 10px 4px 2px;
    background: var(--project-surface);
  }
}

@media (max-width: 620px) {
  .project-workspace--console .project-workspace__header { gap: 9px; padding: 10px; }
  .project-workspace--console .project-workspace__header h1 {
    width: 100%;
    max-width: 100%;
    padding-right: 32px;
    overflow-wrap: anywhere;
    word-break: break-word;
    font-size: 18px;
  }
  .project-workspace--console .project-workspace__header :deep(.n-space) { width: 100%; gap: 6px !important; flex-wrap: wrap !important; }
  .project-workspace--console .workspace-hero__collapse {
    position: absolute;
    top: auto;
    right: auto;
    bottom: -15px;
    left: 50%;
    transform: translateX(-50%);
  }
  .project-workspace--console .project-panel--v2 { padding: 12px 10px; }
  .project-workspace--console .project-utility-chip { min-height: 40px; }
  .project-workspace--console .project-context-rail .project-sidebar-section,
  .project-workspace--console .project-context-rail .project-sidebar-section--file-browser {
    flex-basis: calc(100vw - 52px);
    min-height: 196px;
    max-height: 214px;
  }
  .project-workspace--console .v2-task-card__question,
  .project-workspace--console .v2-conversation__candidate {
    flex-direction: column;
    align-items: flex-start;
  }
  .project-workspace--console .v2-task-card__validation { grid-template-columns: 1fr; gap: 3px; }
  .project-workspace--console .v2-task-card__validation dd + dt { margin-top: 6px; }
  .project-workspace--console .v2-conversation__process li { grid-template-columns: 24px minmax(0, 1fr); align-items: start; }
  .project-workspace--console .v2-conversation__process li :deep(.n-tag) { grid-column: 2; justify-self: start; }
  .project-workspace--console .project-inspector { max-height: 196px; overflow: hidden; }
  .project-workspace--console .project-inspector__body { min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding-right: 4px; }
  .project-workspace--console .project-preview--inline { min-height: 116px; max-height: 142px; }
  .project-workspace--console .v2-conversation__composer {
    position: sticky;
    bottom: 6px;
    grid-template-columns: 1fr;
    gap: 8px;
    padding: 10px 0 2px;
    background: var(--project-canvas);
    z-index: 2;
  }
  .project-workspace--console .v2-conversation__composer :deep(.n-button) { width: 100%; min-height: 44px; }
  .project-workspace--console .reactplan-composer-actions { width: 100%; }
  .project-workspace--console .reactplan-composer-actions :deep(.n-button) { width: auto; min-width: 0; flex: 1 1 0; }
}

@media (prefers-reduced-motion: reduce) {
  .project-workspace--console *,
  .project-workspace--console *::before,
  .project-workspace--console *::after {
    scroll-behavior: auto !important;
    animation: none !important;
    transition: none !important;
  }
}
</style>
