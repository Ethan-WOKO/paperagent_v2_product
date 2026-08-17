<template>
  <AppLayout>
    <main class="project-workspace project-workspace--console">
      <div class="project-workspace__header-shell" :class="{ 'project-workspace__header-shell--collapsed': projectHeaderCollapsed }">
        <header class="project-workspace__header" :class="{ 'project-workspace__header--collapsed': projectHeaderCollapsed }">
          <h1>{{ activeProject?.name || t('project.page.projects') }}</h1>
          <NSpace :size="8" wrap>
            <NTag v-if="activeProject" size="small" type="success">{{ t('project.page.readOnly') }}</NTag>
            <NButton size="small" secondary :loading="loading.projects" @click="loadProjects">{{ t('project.page.refresh') }}</NButton>
            <NButton v-if="activeProject" size="small" secondary type="error" :disabled="loading.deleteProject" @click="deleteModalOpen = true">{{ t('project.page.deleteProject') }}</NButton>
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

      <section v-else class="project-workspace__grid" :class="{ 'project-workspace__grid--context-collapsed': contextRailCollapsed }">
        <aside class="project-panel project-panel--files project-context-rail">
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
              <button v-for="project in projects" :key="project.id" type="button" class="project-list__item" :class="{ active: project.id === activeProjectId }" :aria-current="project.id === activeProjectId ? 'page' : undefined" @click="selectProject(project.id)">
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
                :aria-current="session.id === activeSessionId ? 'page' : undefined"
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

            <div v-show="!sidebarSections.files" class="project-search">
              <NInput v-model:value="searchQuery" size="small" :placeholder="t('project.page.searchProject')" @keyup.enter="runSearch" />
              <NButton size="small" secondary :loading="loading.search" :disabled="!activeProject" @click="runSearch">{{ t('project.page.search') }}</NButton>
            </div>

            <div v-if="!sidebarSections.files && searchResults.length" class="project-search-results">
              <button v-for="hit in searchResults" :key="`${hit.path}:${hit.lineNumber}`" type="button" @click="openFile(hit.path)">
                <strong>{{ hit.path }}:{{ hit.lineNumber }}</strong>
                <span>{{ hit.line }}</span>
              </button>
            </div>
          </section>
        </aside>

        <section class="project-panel project-panel--main project-panel--v2">
          <div class="project-tabs project-command-bar">
            <div class="project-tabs__actions" role="group" aria-label="Project utilities">
              <div class="project-agent-mode" aria-label="Agent 链路">
                <div class="project-agent-mode__switch">
                  <button type="button" :class="{ active: projectAgentRoute === 'v2' }" :aria-pressed="projectAgentRoute === 'v2'" :disabled="projectAgentBusy" @click="setProjectAgentRoute('v2')">
                    正式链路
                  </button>
                  <button type="button" :class="{ active: projectAgentRoute === 'react' }" :aria-pressed="projectAgentRoute === 'react'" :disabled="projectAgentBusy" @click="setProjectAgentRoute('react')">
                    ReAct <small>测试</small>
                  </button>
                </div>
              </div>
              <button type="button" class="project-utility-chip project-context-toggle" :aria-expanded="!contextRailCollapsed" @click="setContextRailCollapsed(!contextRailCollapsed)">
                {{ contextRailCollapsed ? '展开项目资料' : '收起项目资料' }}
              </button>
              <button type="button" class="project-utility-chip project-utility-chip--secondary" :class="{ active: inspectorOpen && inspectorTab === 'preview' }" :aria-pressed="inspectorOpen && inspectorTab === 'preview'" aria-controls="project-inspector" @click="toggleInspector('preview')">文件预览</button>
              <button type="button" class="project-utility-chip project-utility-chip--secondary" :class="{ active: inspectorOpen && inspectorTab === 'evidence' }" :aria-pressed="inspectorOpen && inspectorTab === 'evidence'" aria-controls="project-inspector" @click="toggleInspector('evidence')">证据 <span>{{ evidence.length }}</span></button>
              <button type="button" class="project-utility-chip" :class="{ active: inspectorOpen && inspectorTab === 'changes' }" :aria-pressed="inspectorOpen && inspectorTab === 'changes'" aria-controls="project-inspector" @click="toggleInspector('changes')">修改与验证 <span>{{ candidates.length }}</span></button>
              <button type="button" class="project-utility-chip project-utility-chip--secondary" :class="{ active: inspectorOpen && inspectorTab === 'versions' }" :aria-pressed="inspectorOpen && inspectorTab === 'versions'" aria-controls="project-inspector" @click="toggleInspector('versions')">项目版本 <span>{{ revisions.length }}</span></button>
              <NButton class="project-new-conversation" size="tiny" quaternary :disabled="projectAgentBusy" @click="startNewConversation">新建会话</NButton>
              <NDropdown trigger="click" :options="projectUtilityMenuOptions" @select="handleProjectUtilityMenuSelect">
                <button type="button" class="project-utility-chip project-utility-more" :aria-label="isEnglish ? 'More project tools' : '更多项目工具'">
                  {{ isEnglish ? 'More tools' : '更多工具' }}
                </button>
              </NDropdown>
            </div>
          </div>

          <section v-if="inspectorOpen" id="project-inspector" class="project-inspector">
            <div class="project-inspector__tabs">
              <strong>任务详情</strong>
              <button type="button" class="project-inspector__close" @click="inspectorOpen = false">收起</button>
            </div>

            <div class="project-inspector__body">
              <template v-if="inspectorTab === 'preview'">
                <div class="project-preview project-preview--inline">
                  <div class="project-panel__title"><strong>{{ selectedFile?.path || '文件预览' }}</strong><span v-if="selectedFile">{{ shortHash(selectedFile.sha256) }}</span></div>
                  <NSpin v-if="loading.file" size="small" />
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
                      <NTag v-if="!candidateApplied(candidate.artifact.id)" size="tiny" :type="candidateStateType(candidate.state)">{{ candidateStateLabel(candidate.state) }}</NTag>
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
                      <div class="project-panel__title"><strong>候选内容检查</strong><span>{{ selectedCandidateApplied ? '发布时已核验' : candidateValidationLabel(selectedCandidate.candidate) }}</span></div>
                      <NAlert v-if="selectedCandidateApplied" type="success" :show-icon="false">
                        当前项目版本来自这个候选修改，并已绑定正式验证与发布记录。
                      </NAlert>
                      <div v-else class="project-validation-checks">
                        <NTag v-for="check in selectedCandidate.candidate.validation.checks" :key="check.area" size="tiny" :type="check.status === 'PASSED' ? 'success' : check.status === 'FAILED' ? 'error' : 'warning'">
                          {{ candidateCheckAreaLabel(check.area) }} {{ technicalStatusLabel(check.status) }}
                        </NTag>
                      </div>
                      <ul v-if="!selectedCandidateApplied && selectedCandidate.candidate.validation.issues.length" class="project-validation-issues">
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
                        <span>{{ selectedCandidateApplied
                          ? '最终运行已绑定到当前项目版本'
                          : selectedCandidateValidatedTurn
                            ? 'Agent 正式验证已通过；尚未创建项目版本'
                            : documentOnlyProject
                              ? '文档不会作为代码执行'
                              : '等待手动版本验证' }}</span>
                      </div>
                      <div class="project-candidate-validation-summary">
                        <NAlert v-if="selectedCandidateValidatedTurn" type="success" :show-icon="false">
                          Agent 任务已通过正式验证（{{ selectedCandidateValidatedTurn.validation?.receipts.length || 0 }} 个回执）；下方记录仅用于手动创建项目版本。
                        </NAlert>
                        <NAlert :type="selectedCandidateSuccessfulValidation ? 'success' : 'default'" :show-icon="false">
                          手动版本验证：{{ selectedCandidateSuccessfulValidation
                            ? `已通过（${selectedCandidateSuccessfulValidation.provider}，退出码 ${selectedCandidateSuccessfulValidation.exitCode}）`
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
            <div v-if="projectAgentRoute === 'v2'" class="v2-conversation__tasks" aria-live="polite" :aria-busy="loading.v2History || v2TurnRefreshing">
              <article
                v-for="task in v2TurnHistory"
                :key="task.clientRequestId"
                class="v2-task-card"
                :data-work-state="task.workState"
                :data-delivery-status="task.deliveryStatus || undefined"
              >
                <header class="v2-task-card__question">
                  <div class="v2-task-card__question-copy">
                    <span class="v2-task-card__avatar" aria-hidden="true">你</span>
                    <p>{{ task.question }}</p>
                  </div>
                  <NTag size="small" :type="v2ProjectTurnTagType(task)">
                    {{ v2ProjectTurnLabel(task) }}
                  </NTag>
                </header>

                <details
                  v-if="task.steps.length"
                  class="v2-conversation__process"
                  :open="task.workState === 'EXECUTING' || task.workState === 'AWAITING_REVIEW'"
                >
                  <summary>
                    <span>{{ v2ProjectWorkStateLabel(task.workState) }}</span>
                    <small>{{ task.steps.length }} 个正式步骤</small>
                  </summary>
                  <ol>
                    <li v-for="step in task.steps" :key="step.stepId" :data-status="step.status">
                      <span>{{ step.index }}</span>
                      <div>
                        <strong>{{ step.title }}</strong>
                        <small v-if="step.detail">{{ step.detail }}</small>
                      </div>
                      <NTag size="tiny" :type="v2ProjectStepTagType(step.status)">
                        {{ v2ProjectStepLabel(step.status) }}
                      </NTag>
                    </li>
                  </ol>
                </details>

                <section class="v2-task-card__result">
                  <span class="v2-task-card__avatar v2-task-card__avatar--assistant" aria-hidden="true">P</span>
                  <div class="v2-task-card__result-copy">
                    <NAlert
                      v-if="task.publishedProjectVersion && task.revisionId && task.publishReceiptId"
                      type="success"
                      :show-icon="false"
                    >
                      已发布 Project 版本 {{ shortHash(task.publishedProjectVersion) }}
                      （revision #{{ task.revisionId }}）。旧版本仍可回滚。
                    </NAlert>
                    <MarkdownMessage
                      v-if="task.deliveryStatus === 'SUCCEEDED' && task.finalText"
                      :content="task.finalText"
                      variant="project"
                    />
                    <NAlert v-else-if="task.deliveryStatus === 'DELIVERY_FAILED'" type="error" :show-icon="false">
                      交付失败。<template v-if="task.deliveryErrorCode">错误代码：{{ task.deliveryErrorCode }}</template>
                    </NAlert>
                    <NAlert v-else-if="task.pendingItem?.status === 'PENDING'" type="warning" :show-icon="false">
                      <strong>{{ task.pendingItem.question }}</strong>
                      <template v-if="task.pendingItem.expectedFormat"><br>期望格式：{{ task.pendingItem.expectedFormat }}</template>
                    </NAlert>
                    <NAlert v-else-if="task.taskOutcomeStatus === 'FAILED'" type="error" :show-icon="false">
                      任务失败。<template v-if="task.failureCode">错误代码：{{ task.failureCode }}</template>
                    </NAlert>
                    <NAlert v-else-if="task.taskOutcomeStatus === 'CANCELLED'" type="warning" :show-icon="false">任务已取消，等待正式交付记录。</NAlert>
                    <NAlert v-else-if="task.taskOutcomeStatus === 'SUPERSEDED'" type="warning" :show-icon="false">该任务已被新任务替代。</NAlert>
                    <NAlert v-else-if="task.workState === 'BLOCKED'" type="error" :show-icon="false">
                      任务已停止：检测到确定性错误或连续无进展。你可以取消任务，或者直接删除会话/项目。
                    </NAlert>
                    <p v-else>{{ v2ProjectWorkStateLabel(task.workState) }}</p>
                  </div>
                </section>

                <div v-if="task.candidateArtifactId || task.outputPaths.length || task.validation" class="v2-task-card__delivery">
                  <div v-if="task.candidateArtifactId" class="v2-conversation__candidate">
                    <span>候选修改 #{{ task.candidateArtifactId }}</span>
                    <NButton type="primary" secondary @click="openV2CandidateReview(task.candidateArtifactId)">查看修改</NButton>
                  </div>
                  <div v-if="task.outputPaths.length" class="v2-conversation__outputs">
                    <strong>正式交付位置</strong>
                    <code v-for="path in task.outputPaths" :key="path" :title="path">{{ path }}</code>
                  </div>
                  <dl v-if="task.validation" class="v2-task-card__validation">
                    <dt>验证状态</dt><dd>{{ task.validation.status }}</dd>
                    <dt>验证回执</dt>
                    <dd>
                      <code v-for="receipt in task.validation.receipts" :key="`${receipt.requirementId}:${receipt.receiptId}`" :title="receipt.requirementId">
                        {{ receipt.receiptId }}
                      </code>
                    </dd>
                  </dl>
                </div>

                <div class="v2-task-card__actions">
                  <NButton
                    v-if="canReplyToV2ProjectGap(task)"
                    size="tiny"
                    type="warning"
                    @click="prepareV2GapReply(task)"
                  >回复缺口</NButton>
                  <NButton
                    v-if="canCancelV2ProjectTurn(task)"
                    size="tiny"
                    secondary
                    type="error"
                    :disabled="v2TurnCancelSubmitting"
                    @click="cancelV2NaturalLanguageTask(task)"
                  >取消任务</NButton>
                  <template v-if="canSendV2ProjectFollowUp(task)">
                    <NButton size="tiny" quaternary @click="prepareV2Instruction('SUPPLEMENT', task)">补充</NButton>
                    <NButton size="tiny" quaternary @click="prepareV2Instruction('CORRECTION', task)">纠正</NButton>
                    <NButton size="tiny" quaternary @click="prepareV2Instruction('REPLACEMENT', task)">替代</NButton>
                  </template>
                </div>
              </article>
              <NEmpty v-if="!loading.v2History && v2TurnHistory.length === 0" description="发送自然语言任务后，正式链路状态会显示在这里。" />
              <NSpin v-if="loading.v2History" size="small" />
            </div>

            <div v-else class="v2-conversation__tasks" aria-live="polite" :aria-busy="reactPlanBusy">
              <article
                v-for="item in reactPlanTimeline"
                :key="item.record.taskId"
                class="v2-task-card reactplan-task-card"
                :data-task-state="item.record.view.state"
              >
                <header class="v2-task-card__question">
                  <div class="v2-task-card__question-copy">
                    <span class="v2-task-card__avatar" aria-hidden="true">你</span>
                    <p>{{ item.record.instruction }}</p>
                  </div>
                  <NTag size="small" :type="reactPlanStateTagType(item.record.view.state)">
                    {{ reactPlanStateLabel(item.record.view.state) }}
                  </NTag>
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
                        <strong>{{ reactPlanToolLabel(tool.name) }}</strong>
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
                    <NAlert v-else-if="item.question" type="warning" :show-icon="false">
                      {{ item.question.text }}
                    </NAlert>
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

                <div class="v2-task-card__actions">
                  <NButton
                    v-if="item.record.taskId === reactPlanRecord?.taskId && !isReactPlanTerminal(item.record.view.state)"
                    size="tiny"
                    secondary
                    type="error"
                    :disabled="reactPlanCancelling"
                    @click="cancelCurrentReactPlanTask"
                  >取消任务</NButton>
                </div>
              </article>
              <NEmpty v-if="reactPlanTimeline.length === 0" description="发送任务后，ReAct 会自己查找文件、选择工具并展示正式执行结果。" />
              <NSpin v-if="reactPlanBusy" size="small" />
            </div>

            <NAlert v-if="projectAgentRoute === 'v2' && v2TurnError" type="error" :show-icon="false">{{ v2TurnError }}</NAlert>
            <NAlert v-if="projectAgentRoute === 'react' && reactPlanError" type="error" :show-icon="false">{{ reactPlanError }}</NAlert>
            <NAlert v-if="projectAgentRoute === 'v2' && v2ComposerMode.type !== 'INITIAL'" type="info" :show-icon="false" closable @close="resetV2ComposerMode">
              {{ v2ComposerModeLabel }}
            </NAlert>
            <div class="v2-conversation__composer">
              <NInput
                :value="projectAgentInput"
                :aria-label="projectAgentRoute === 'react' ? 'ReAct project task' : 'V2 project task'"
                type="textarea"
                :maxlength="20000"
                :autosize="{ minRows: 2, maxRows: 8 }"
                :placeholder="projectAgentPlaceholder"
                :disabled="projectAgentBusy"
                @update:value="setProjectAgentInput"
                @keydown="handleProjectAgentKeydown"
              />
              <NButton
                v-if="projectAgentRoute === 'v2'"
                class="project-send-button"
                type="primary"
                :loading="projectAgentBusy"
                :disabled="!activeProject || !projectAgentInput.trim() || projectAgentBusy"
                @click="sendV2NaturalLanguageTurn"
              >
                {{ projectAgentSubmitLabel }}
              </NButton>
              <NButton
                v-else
                class="project-send-button"
                type="primary"
                :loading="projectAgentBusy"
                :disabled="!activeProject || !projectAgentInput.trim() || projectAgentBusy"
                @click="sendProjectAgentTask"
              >
                {{ projectAgentSubmitLabel }}
              </NButton>
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
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NAlert, NButton, NCheckbox, NDropdown, NEmpty, NForm, NFormItem, NIcon, NInput, NModal, NSelect, NSpace, NSpin, NTag } from 'naive-ui';
import { ChevronRightIcon } from 'naive-ui/es/_internal/icons';
import AppLayout from '@/components/AppLayout.vue';
import MarkdownMessage from '@/components/MarkdownMessage.vue';
import {
  cancelV2NaturalLanguageTurn,
  deleteSession as deleteAgentSession,
  getV2NaturalLanguageTurn,
  listV2NaturalLanguageTurns,
  replyV2NaturalLanguagePendingItem,
  startV2NaturalLanguageTurn,
  updateSession as updateAgentSession,
  type AgentSessionResponse,
  type V2NaturalLanguageTurnHistoryItem,
  type V2NaturalLanguageTurnResponse,
  type V2ProjectInstructionKind,
} from '@/api/agent';
import {
  answerReactPlanQuestion,
  cancelReactPlanTask,
  getReactPlanTask,
  startReactPlanTask,
  streamReactPlanEvents,
} from '@/api/reactPlan';
import { candidateReviewFailure, getCandidateChange, isCandidateArtifactV1, listArtifacts, type ArtifactResponse, type CandidateArtifactResponse, type CandidateChangeType, type CandidateEvidenceRef, type CandidateReviewState } from '@/api/artifact';
import { applyProjectCandidate, cancelCandidateValidation, createCandidateValidation, createProjectSession, deleteProject, exportProjectRevision, filterProjectUploadFiles, getProjectManifest, listCandidateValidations, listProjectRevisions, listProjectSessions, listProjects, readProjectFile, rejectCandidateValidation, rollbackProjectRevision, searchProject, uploadProject, type CandidateValidationProfile, type CandidateValidationResponse, type ProjectEvidenceResponse, type ProjectFileResponse, type ProjectManifestResponse, type ProjectRevisionResponse, type ProjectSearchHit, type ProjectSummaryResponse } from '@/api/project';
import { useI18n } from '@/composables/useI18n';
import { candidateValidationCanApply } from '@/utils/candidateValidationCanApply';
import {
  isCurrentV2NaturalLanguageRequest,
  newV2NaturalLanguageClientRequestId,
  type V2NaturalLanguageRequestIdentity,
} from '@/utils/v2NaturalLanguageTurn';
import {
  V2_PROJECT_CHAIN_REFRESH_INTERVAL_MS,
  V2_PROJECT_CHAIN_TRANSIENT_READ_RETRY_LIMIT,
  canCancelV2ProjectTurn,
  canReplyToV2ProjectGap,
  canSendV2ProjectFollowUp,
  isCurrentV2ProjectHistoryRequest,
  isV2ProjectTurnInteractionBlocking,
  isV2ProjectTurnReadTransientFailure,
  normalizeV2ProjectInstructionRequest,
  parseV2ProjectChainRecoveryRecord,
  shouldClearV2ProjectChainRecovery,
  shouldRefreshV2ProjectChainTurn,
  sortV2ProjectChainHistory,
  v2ProjectInstructionKindLabel,
  v2ProjectCommandFailureRecoveryDecision,
  v2ProjectStepLabel,
  v2ProjectStepTagType,
  v2ProjectTurnLabel,
  v2ProjectTurnTagType,
  v2ProjectWorkStateLabel,
  type V2ProjectChainRecoveryRecord,
  type V2ProjectHistoryRequestIdentity,
} from '@/utils/v2ProjectAgentChain';
import {
  appendReactPlanEvent,
  isReactPlanTerminal,
  latestReactPlanQuestion,
  newReactPlanCancelId,
  newReactPlanRequestId,
  parseReactPlanHistory,
  reactPlanDelivery,
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

type ProjectInspectorTab = 'preview' | 'evidence' | 'changes' | 'versions';

interface CandidateReviewItem {
  artifact: ArtifactResponse;
  candidate: CandidateArtifactResponse | null;
  state: CandidateReviewState;
  error: string | null;
}

type V2ComposerMode =
  | { type: 'INITIAL' }
  | { type: 'INSTRUCTION'; kind: Exclude<V2ProjectInstructionKind, 'INITIAL'>; targetClientRequestId: string }
  | { type: 'GAP_REPLY'; targetClientRequestId: string; gapId: string; question: string };

type ProjectAgentRoute = 'v2' | 'react';

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
const error = ref('');
const createModalOpen = ref(false);
const deleteModalOpen = ref(false);
const renameSessionModalOpen = ref(false);
const renameSessionId = ref<number | null>(null);
const renameSessionDraft = ref('');
let projectEpoch = 0;
let sessionFlight: Promise<number | null> | null = null;
let candidateValidationPoll: number | null = null;
// Legacy candidate-view refresh budget only; it does not cancel validation or decide its result.
const CANDIDATE_VALIDATION_VIEW_MAX_REFRESHES = 450;
const CANDIDATE_VALIDATION_VIEW_REFRESH_INTERVAL_MS = 2_000;
const V2_NATURAL_LANGUAGE_STORAGE_KEY = 'yanban.v2NaturalLanguage.activeRequest.';
const REACT_PLAN_STORAGE_KEY = 'yanban.reactPlan.activeTask.';
const PROJECT_AGENT_ROUTE_STORAGE_KEY = 'yanban.projectAgent.route.';
const REACT_PLAN_RECONNECT_DELAY_MS = 1_500;
const projectAgentRoute = ref<ProjectAgentRoute>('v2');
const v2TurnInput = ref('');
const v2TurnSubmitting = ref(false);
const v2TurnRefreshing = ref(false);
const v2TurnInteractionBlocking = ref(false);
const v2TurnCancelSubmitting = ref(false);
const v2TurnError = ref('');
const v2TurnHistory = ref<V2NaturalLanguageTurnHistoryItem[]>([]);
const v2ComposerMode = ref<V2ComposerMode>({ type: 'INITIAL' });
let v2TurnAbortController: AbortController | null = null;
let v2TurnRefreshTimer: number | null = null;
let v2HistoryAbortController: AbortController | null = null;
let v2HistoryRequestSequence = 0;
let v2TurnSequence = 0;
let v2TurnClientRequestId: string | null = null;
const v2NaturalTurnBusy = computed(() => (
  v2TurnSubmitting.value || v2TurnInteractionBlocking.value
    || v2TurnCancelSubmitting.value
));
const reactPlanInput = ref('');
const reactPlanRecord = ref<ReactPlanTaskRecord | null>(null);
const reactPlanRecords = ref<ReactPlanTaskRecord[]>([]);
const reactPlanError = ref('');
const reactPlanSubmitting = ref(false);
const reactPlanStreaming = ref(false);
const reactPlanCancelling = ref(false);
const reactPlanAnswering = ref(false);
let reactPlanAbortController: AbortController | null = null;
let reactPlanReconnectTimer: number | null = null;
const reactPlanQuestion = computed(() => latestReactPlanQuestion(
  reactPlanRecord.value?.events ?? [],
  reactPlanRecord.value?.view.pendingQuestionId,
));
const reactPlanTimeline = computed(() => reactPlanRecords.value.map((record) => ({
  record,
  tools: reactPlanToolEvents(record.events),
  delivery: reactPlanDelivery(record.events),
  question: latestReactPlanQuestion(record.events, record.view.pendingQuestionId),
})));
const reactPlanBusy = computed(() => (
  reactPlanSubmitting.value || reactPlanCancelling.value || reactPlanAnswering.value
    || reactPlanRecord.value?.view.state === 'queued'
    || reactPlanRecord.value?.view.state === 'running'
));
const projectAgentBusy = computed(() => (
  projectAgentRoute.value === 'react' ? reactPlanBusy.value : v2NaturalTurnBusy.value
));
const projectAgentInput = computed(() => (
  projectAgentRoute.value === 'react' ? reactPlanInput.value : v2TurnInput.value
));
const projectAgentPlaceholder = computed(() => {
  if (projectAgentRoute.value === 'v2') return v2ComposerPlaceholder.value;
  return reactPlanQuestion.value ? '输入对模型追问的回复' : '描述任务，ReAct 会自己查找文件并选择工具';
});
const projectAgentSubmitLabel = computed(() => {
  if (projectAgentRoute.value === 'v2') return v2ComposerSubmitLabel.value;
  return reactPlanQuestion.value ? '回复' : '发送';
});

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
const DEFAULT_SESSION_TITLE = '\u65b0\u4f1a\u8bdd';
const projectHeaderCollapsed = ref(readStoredBoolean(PROJECT_HEADER_COLLAPSED_KEY, false));
const contextRailCollapsed = ref(readStoredBoolean(
  PROJECT_CONTEXT_COLLAPSED_KEY,
  typeof window !== 'undefined' && window.innerWidth <= 980,
));
const sessionMenuOptions = computed(() => [
  { label: isEnglish.value ? 'Rename' : '重命名', key: 'rename' },
  { label: isEnglish.value ? 'Delete' : '删除', key: 'delete' },
]);
const projectUtilityMenuOptions = computed(() => [
  { label: isEnglish.value ? 'File preview' : '文件预览', key: 'preview' },
  { label: `${isEnglish.value ? 'Evidence' : '证据'} (${evidence.value.length})`, key: 'evidence' },
  { label: `${isEnglish.value ? 'Project versions' : '项目版本'} (${revisions.value.length})`, key: 'versions' },
]);
const activeProject = computed(() => projects.value.find((item) => item.id === activeProjectId.value) || null);
const v2ComposerModeLabel = computed(() => {
  const mode = v2ComposerMode.value;
  if (mode.type === 'GAP_REPLY') return `回复正式待处理事项：${mode.question}`;
  if (mode.type === 'INSTRUCTION') {
    return `${v2ProjectInstructionKindLabel(mode.kind)}任务 ${mode.targetClientRequestId}`;
  }
  return '';
});
const v2ComposerPlaceholder = computed(() => {
  const mode = v2ComposerMode.value;
  if (mode.type === 'GAP_REPLY') return '输入待处理事项的回复';
  if (mode.type === 'INSTRUCTION') return `输入${v2ProjectInstructionKindLabel(mode.kind)}内容`;
  return '例如：读取项目中的 README，完成任务并给出正式交付结果';
});
const v2ComposerSubmitLabel = computed(() => {
  const mode = v2ComposerMode.value;
  if (mode.type === 'GAP_REPLY') return '回复';
  if (mode.type === 'INSTRUCTION') return v2ProjectInstructionKindLabel(mode.kind);
  return '发送';
});
const selectedCandidateSuccessfulValidation = computed(() =>
  candidateValidations.value.find((validation) => validation.status === 'SUCCEEDED') || null);
const selectedCandidateConfirmationValidation = computed(() => candidateValidations.value[0] || null);
const selectedCandidateValidatedTurn = computed(() => {
  const artifactId = selectedCandidate.value?.artifact.id;
  if (!artifactId) return null;
  return v2TurnHistory.value.find((turn) =>
    turn.candidateArtifactId === artifactId
    && turn.validation?.status === 'PASSED') || null;
});
const selectedCandidatePublishedTurn = computed(() => {
  const artifactId = selectedCandidate.value?.artifact.id;
  if (!artifactId) return null;
  return v2TurnHistory.value.find((turn) =>
    turn.candidateArtifactId === artifactId
    && Boolean(turn.publishedProjectVersion)
    && Boolean(turn.revisionId)
    && Boolean(turn.publishReceiptId)) || null;
});
const selectedCandidateApplied = computed(() =>
  selectedCandidatePublishedTurn.value !== null
  || (selectedCandidateConfirmationValidation.value?.decisionStatus === 'APPLIED'
    && Boolean(selectedCandidateConfirmationValidation.value.appliedRevisionId)));
const selectedCandidateApplicationLabel = computed(() =>
  selectedCandidatePublishedTurn.value
    ? `已由 Agent 链发布（revision #${selectedCandidatePublishedTurn.value.revisionId}）`
    : selectedCandidateConfirmationValidation.value
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
  const item = value as { response?: { data?: { code?: string; message?: string }; headers?: Record<string, string> }; message?: string };
  const message = item.response?.data?.message || item.message;
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

function showInspector(tab: ProjectInspectorTab) {
  inspectorTab.value = tab;
  inspectorOpen.value = true;
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
  return v2TurnHistory.value.some((turn) =>
    turn.candidateArtifactId === artifactId
    && Boolean(turn.publishedProjectVersion)
    && Boolean(turn.revisionId)
    && Boolean(turn.publishReceiptId))
    || (selectedCandidate.value?.artifact.id === artifactId
      && candidateValidations.value.some((validation) =>
      validation.decisionStatus === 'APPLIED'
      && Boolean(validation.appliedRevisionId)));
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
    if (activeSessionId.value) await loadCandidates(activeSessionId.value, epoch);
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
  if (!current || candidateValidationTerminal(current.status) || current.decisionStatus !== 'PENDING') return;
  if (attempt >= CANDIDATE_VALIDATION_VIEW_MAX_REFRESHES) {
    validationMessageType.value = 'warning';
    validationMessage.value = '沙箱验证仍在进行中，请稍后重新打开候选修改查看持久化结果。';
    return;
  }
  candidateValidationPoll = window.setTimeout(() => {
    void pollCandidateValidation(artifactId, validationId, epoch, attempt + 1);
  }, CANDIDATE_VALIDATION_VIEW_REFRESH_INTERVAL_MS);
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
    validationMessage.value = '已拒绝候选修改，没有创建新的项目版本；验证记录会保留。';
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

function projectAgentRouteStorageKey(projectId: number, sessionId: number) {
  return `${PROJECT_AGENT_ROUTE_STORAGE_KEY}${projectId}.${sessionId}`;
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
  reactPlanError.value = '';
  reactPlanInput.value = '';
  reactPlanSubmitting.value = false;
  reactPlanCancelling.value = false;
  reactPlanAnswering.value = false;
}

function isCurrentReactPlan(record: ReactPlanTaskRecord, epoch: number) {
  return epoch === projectEpoch
    && record.projectId === activeProjectId.value
    && record.sessionId === activeSessionId.value
    && record.taskId === reactPlanRecord.value?.taskId;
}

function setProjectAgentRoute(route: ProjectAgentRoute) {
  if (route === projectAgentRoute.value || projectAgentBusy.value) return;
  projectAgentRoute.value = route;
  if (activeProjectId.value && activeSessionId.value) {
    window.localStorage.setItem(
      projectAgentRouteStorageKey(activeProjectId.value, activeSessionId.value),
      route,
    );
  }
  if (route === 'v2') {
    invalidateReactPlanStream();
    return;
  }
  invalidateV2NaturalLanguageRequest();
  const projectId = activeProjectId.value;
  const sessionId = activeSessionId.value;
  if (!projectId || !sessionId) return;
  const stored = storedReactPlanRecords(projectId, sessionId);
  reactPlanRecords.value = stored;
  reactPlanRecord.value = stored[stored.length - 1] ?? null;
  if (reactPlanRecord.value) connectReactPlanTask(reactPlanRecord.value, projectEpoch);
}

function setProjectAgentInput(value: string) {
  if (projectAgentRoute.value === 'react') reactPlanInput.value = value;
  else v2TurnInput.value = value;
}

function updateReactPlanRecord(record: ReactPlanTaskRecord) {
  reactPlanRecord.value = record;
  reactPlanRecords.value = upsertReactPlanRecord(reactPlanRecords.value, record);
  storeReactPlanRecords(reactPlanRecords.value);
}

function acceptReactPlanEvent(
  source: ReactPlanTaskRecord,
  event: ReactPlanTaskEvent,
  epoch: number,
) {
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
    reactPlanError.value = apiError(cause);
  } finally {
    if (reactPlanAbortController === controller) reactPlanAbortController = null;
    if (isCurrentReactPlan(record, epoch)) {
      reactPlanStreaming.value = false;
      scheduleReactPlanReconnect(record, epoch);
    }
  }
}

function loadReactPlanRecord(projectId: number, sessionId: number, epoch = projectEpoch) {
  invalidateReactPlanStream();
  projectAgentRoute.value = window.localStorage.getItem(
    projectAgentRouteStorageKey(projectId, sessionId),
  ) === 'react' ? 'react' : 'v2';
  const records = storedReactPlanRecords(projectId, sessionId);
  reactPlanRecords.value = records;
  const record = records[records.length - 1] ?? null;
  reactPlanRecord.value = record;
  reactPlanError.value = '';
  if (record && projectAgentRoute.value === 'react') connectReactPlanTask(record, epoch);
}

async function submitReactPlanTask() {
  const projectId = activeProjectId.value;
  const instruction = reactPlanInput.value.trim();
  if (!projectId || !instruction || reactPlanBusy.value) return;
  const epoch = projectEpoch;
  reactPlanSubmitting.value = true;
  reactPlanError.value = '';
  invalidateReactPlanStream();
  const controller = new AbortController();
  reactPlanAbortController = controller;
  try {
    const sessionId = await ensureSession();
    if (!sessionId || epoch !== projectEpoch || projectId !== activeProjectId.value) return;
    const clientRequestId = newReactPlanRequestId();
    const accepted = (await startReactPlanTask(
      sessionId,
      { clientRequestId, instruction },
      controller.signal,
    )).data;
    if (epoch !== projectEpoch || sessionId !== activeSessionId.value) return;
    const record: ReactPlanTaskRecord = {
      version: 1,
      projectId,
      sessionId,
      clientRequestId,
      instruction,
      turnId: accepted.turnId,
      taskId: accepted.taskId,
      view: accepted.task,
      events: [],
    };
    reactPlanInput.value = '';
    updateReactPlanRecord(record);
    connectReactPlanTask(record, epoch);
  } catch (cause) {
    if (!controller.signal.aborted && epoch === projectEpoch) reactPlanError.value = apiError(cause);
  } finally {
    if (reactPlanAbortController === controller) reactPlanAbortController = null;
    if (epoch === projectEpoch) reactPlanSubmitting.value = false;
  }
}

async function answerCurrentReactPlanQuestion() {
  const record = reactPlanRecord.value;
  const question = reactPlanQuestion.value;
  const answer = reactPlanInput.value.trim();
  if (!record || !question || !answer || reactPlanAnswering.value) return;
  const epoch = projectEpoch;
  reactPlanAnswering.value = true;
  reactPlanError.value = '';
  invalidateReactPlanStream();
  const controller = new AbortController();
  reactPlanAbortController = controller;
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
    if (reactPlanAbortController === controller) reactPlanAbortController = null;
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
  reactPlanAbortController = controller;
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
    if (reactPlanAbortController === controller) reactPlanAbortController = null;
    if (isCurrentReactPlan(record, epoch)) reactPlanCancelling.value = false;
  }
}

function sendProjectAgentTask() {
  if (projectAgentRoute.value === 'v2') {
    void sendV2NaturalLanguageTurn();
  } else if (reactPlanQuestion.value) {
    void answerCurrentReactPlanQuestion();
  } else {
    void submitReactPlanTask();
  }
}

function handleProjectAgentKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || (!event.ctrlKey && !event.metaKey)) return;
  event.preventDefault();
  sendProjectAgentTask();
}

function storedV2NaturalLanguageRequest(projectId: number, sessionId: number) {
  const key = naturalLanguageStorageKey(projectId, sessionId);
  const raw = window.localStorage.getItem(key);
  const record = parseV2ProjectChainRecoveryRecord(raw, projectId, sessionId);
  if (raw && !record) {
    // Invalid local data is not a chain fact and cannot be used for recovery.
    window.localStorage.removeItem(key);
  }
  return record;
}

function storeV2NaturalLanguageRequest(record: V2ProjectChainRecoveryRecord) {
  window.localStorage.setItem(
    naturalLanguageStorageKey(record.projectId, record.sessionId),
    JSON.stringify(record),
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

function isCurrentV2NaturalLanguageResponse(expected: V2NaturalLanguageRequestIdentity) {
  return isCurrentV2NaturalLanguageRequest(expected, currentV2NaturalLanguageIdentity());
}

function currentV2HistoryRequestIdentity(): V2ProjectHistoryRequestIdentity {
  return {
    ...currentV2NaturalLanguageIdentity(),
    historySequence: v2HistoryRequestSequence,
  };
}

function invalidateV2HistoryRequest() {
  v2HistoryRequestSequence += 1;
  v2HistoryAbortController?.abort();
  v2HistoryAbortController = null;
  loading.v2History = false;
}

function invalidateV2NaturalLanguageRequest() {
  v2TurnSequence += 1;
  v2TurnClientRequestId = null;
  v2TurnAbortController?.abort();
  v2TurnAbortController = null;
  if (v2TurnRefreshTimer != null) {
    window.clearTimeout(v2TurnRefreshTimer);
    v2TurnRefreshTimer = null;
  }
  v2TurnRefreshing.value = false;
  v2TurnInteractionBlocking.value = false;
}

function resetV2ComposerMode() {
  v2ComposerMode.value = { type: 'INITIAL' };
}

function resetV2NaturalLanguageView() {
  invalidateV2NaturalLanguageRequest();
  invalidateV2HistoryRequest();
  v2TurnHistory.value = [];
  v2TurnError.value = '';
  v2TurnInput.value = '';
  resetV2ComposerMode();
}

function upsertV2NaturalLanguageOutcome(
  outcome: V2NaturalLanguageTurnResponse,
  question: string,
) {
  const previous = v2TurnHistory.value.find(
    (item) => item.clientRequestId === outcome.clientRequestId,
  );
  const now = new Date().toISOString();
  v2TurnHistory.value = sortV2ProjectChainHistory([
    ...v2TurnHistory.value.filter((item) => item.clientRequestId !== outcome.clientRequestId),
    {
      ...outcome,
      question: previous?.question || question,
      createdAt: previous?.createdAt || now,
      updatedAt: now,
    },
  ]);
}

async function refreshProjectAfterV2Turn(
  projectId: number,
  sessionId: number,
  outcome: V2NaturalLanguageTurnResponse,
  epoch: number,
) {
  if (epoch !== projectEpoch || projectId !== activeProjectId.value
      || sessionId !== activeSessionId.value) return;
  if (outcome.publishedProjectVersion && outcome.revisionId && outcome.publishReceiptId) {
    selectedFile.value = null;
    searchResults.value = [];
    await Promise.all([loadManifest(epoch), loadRevisions()]);
  }
  if (!outcome.candidateArtifactId) return;
  await loadCandidates(sessionId, epoch);
  if (epoch !== projectEpoch) return;
  const candidate = candidates.value.find((item) => item.artifact.id === outcome.candidateArtifactId);
  if (candidate) selectCandidate(candidate);
}

async function readV2NaturalLanguageTurn(
  recovery: V2ProjectChainRecoveryRecord,
  expected: V2NaturalLanguageRequestIdentity,
  controller: AbortController,
  epoch: number,
  preserveError = false,
  transientFailureCount = 0,
) {
  try {
    const outcome = (await getV2NaturalLanguageTurn(
      recovery.sessionId,
      recovery.rootClientRequestId,
      controller.signal,
    )).data;
    if (!isCurrentV2NaturalLanguageResponse(expected)) return;
    if (!preserveError) v2TurnError.value = '';
    upsertV2NaturalLanguageOutcome(outcome, recovery.question);
    v2TurnInteractionBlocking.value = isV2ProjectTurnInteractionBlocking(outcome);
    if (shouldClearV2ProjectChainRecovery(outcome)) {
      clearStoredV2NaturalLanguageRequest(recovery.projectId, recovery.sessionId);
      v2TurnRefreshing.value = false;
      v2TurnAbortController = null;
      await refreshProjectAfterV2Turn(
        recovery.projectId, recovery.sessionId, outcome, epoch,
      );
      return;
    }
    if (!shouldRefreshV2ProjectChainTurn(outcome)) {
      v2TurnRefreshing.value = false;
      v2TurnAbortController = null;
      return;
    }
    v2TurnRefreshTimer = window.setTimeout(() => {
      v2TurnRefreshTimer = null;
      void readV2NaturalLanguageTurn(
        recovery, expected, controller, epoch, preserveError,
      );
    }, V2_PROJECT_CHAIN_REFRESH_INTERVAL_MS);
  } catch (cause) {
    if (controller.signal.aborted || !isCurrentV2NaturalLanguageResponse(expected)) return;
    if (isV2ProjectTurnReadTransientFailure(cause)
        && transientFailureCount < V2_PROJECT_CHAIN_TRANSIENT_READ_RETRY_LIMIT) {
      v2TurnRefreshTimer = window.setTimeout(() => {
        v2TurnRefreshTimer = null;
        void readV2NaturalLanguageTurn(
          recovery, expected, controller, epoch, preserveError,
          transientFailureCount + 1,
        );
      }, V2_PROJECT_CHAIN_REFRESH_INTERVAL_MS);
      return;
    }
    v2TurnRefreshing.value = false;
    v2TurnInteractionBlocking.value = false;
    v2TurnAbortController = null;
    if (!preserveError) v2TurnError.value = apiError(cause);
  }
}

function recoverV2NaturalLanguageTurn(
  projectId: number,
  sessionId: number,
  recoveryOverride?: V2ProjectChainRecoveryRecord,
  options: { preserveError?: boolean } = {},
) {
  let recovery = recoveryOverride || storedV2NaturalLanguageRequest(projectId, sessionId);
  if (!recovery) {
    const latest = [...v2TurnHistory.value].reverse()
      .find((item) => !shouldClearV2ProjectChainRecovery(item));
    if (!latest) return;
    recovery = {
      version: 1,
      projectId,
      sessionId,
      rootClientRequestId: latest.clientRequestId,
      commandClientRequestId: latest.clientRequestId,
      question: latest.question,
    };
    storeV2NaturalLanguageRequest(recovery);
  }
  const storedTurn = v2TurnHistory.value.find(
    (item) => item.clientRequestId === recovery?.rootClientRequestId,
  );
  if (storedTurn && shouldClearV2ProjectChainRecovery(storedTurn)) {
    clearStoredV2NaturalLanguageRequest(projectId, sessionId);
    return;
  }
  invalidateV2NaturalLanguageRequest();
  v2TurnClientRequestId = recovery.commandClientRequestId;
  const expected = currentV2NaturalLanguageIdentity();
  const controller = new AbortController();
  v2TurnAbortController = controller;
  v2TurnRefreshing.value = true;
  v2TurnInteractionBlocking.value = true;
  if (!options.preserveError) v2TurnError.value = '';
  void readV2NaturalLanguageTurn(
    recovery, expected, controller, projectEpoch, options.preserveError === true,
  );
}

async function loadV2TurnHistory(
  sessionId: number,
  epoch = projectEpoch,
) {
  v2HistoryRequestSequence += 1;
  v2HistoryAbortController?.abort();
  const controller = new AbortController();
  v2HistoryAbortController = controller;
  const expected = currentV2HistoryRequestIdentity();
  loading.v2History = true;
  try {
    const items = (await listV2NaturalLanguageTurns(sessionId, 50, controller.signal)).data;
    if (epoch !== projectEpoch || controller.signal.aborted
        || !isCurrentV2ProjectHistoryRequest(expected, currentV2HistoryRequestIdentity())) return;
    v2TurnHistory.value = sortV2ProjectChainHistory(items);
  } catch (cause) {
    if (!controller.signal.aborted && epoch === projectEpoch
        && isCurrentV2ProjectHistoryRequest(expected, currentV2HistoryRequestIdentity())) {
      v2TurnError.value = apiError(cause);
    }
  } finally {
    if (epoch === projectEpoch
        && isCurrentV2ProjectHistoryRequest(expected, currentV2HistoryRequestIdentity())) {
      loading.v2History = false;
      v2HistoryAbortController = null;
    }
  }
}

function prepareV2GapReply(task: V2NaturalLanguageTurnHistoryItem) {
  const pendingItem = task.pendingItem;
  if (!pendingItem || !canReplyToV2ProjectGap(task)) return;
  v2ComposerMode.value = {
    type: 'GAP_REPLY',
    targetClientRequestId: task.clientRequestId,
    gapId: pendingItem.gapId,
    question: pendingItem.question,
  };
  v2TurnInput.value = '';
}

function prepareV2Instruction(
  kind: Exclude<V2ProjectInstructionKind, 'INITIAL'>,
  task: V2NaturalLanguageTurnHistoryItem,
) {
  if (!canSendV2ProjectFollowUp(task)) return;
  v2ComposerMode.value = {
    type: 'INSTRUCTION',
    kind,
    targetClientRequestId: task.clientRequestId,
  };
  v2TurnInput.value = '';
}

function v2NaturalLanguageFailureText(cause: unknown) {
  const code = (cause as { response?: { data?: { code?: unknown } } } | null)
    ?.response?.data?.code;
  if (typeof code === 'string' && code) return `Project Agent 请求失败：${code}`;
  return apiError(cause);
}

async function sendV2NaturalLanguageTurn() {
  const projectId = activeProjectId.value;
  const question = v2TurnInput.value.trim();
  if (!projectId || !question || v2NaturalTurnBusy.value) return;
  const epoch = projectEpoch;
  const mode = v2ComposerMode.value;
  const commandClientRequestId = newV2NaturalLanguageClientRequestId();
  v2TurnSubmitting.value = true;
  v2TurnError.value = '';
  let commandSessionId: number | null = null;
  let previousRecovery: V2ProjectChainRecoveryRecord | null = null;
  let commandRecovery: V2ProjectChainRecoveryRecord | null = null;
  let commandExpected: V2NaturalLanguageRequestIdentity | null = null;
  try {
    const sessionId = await ensureSession();
    if (!sessionId || epoch !== projectEpoch || projectId !== activeProjectId.value) return;
    commandSessionId = sessionId;
    previousRecovery = storedV2NaturalLanguageRequest(projectId, sessionId);
    const rootClientRequestId = mode.type === 'INITIAL'
      ? commandClientRequestId
      : mode.targetClientRequestId;
    const taskQuestion = v2TurnHistory.value.find(
      (item) => item.clientRequestId === rootClientRequestId,
    )?.question || question;
    commandRecovery = {
      version: 1,
      projectId,
      sessionId,
      rootClientRequestId,
      commandClientRequestId,
      question: taskQuestion,
    };
    storeV2NaturalLanguageRequest(commandRecovery);
    invalidateV2NaturalLanguageRequest();
    v2TurnClientRequestId = commandClientRequestId;
    commandExpected = currentV2NaturalLanguageIdentity();
    const controller = new AbortController();
    v2TurnAbortController = controller;
    if (mode.type === 'GAP_REPLY') {
      const acknowledgement = (await replyV2NaturalLanguagePendingItem(
        sessionId,
        mode.targetClientRequestId,
        mode.gapId,
        { content: question, clientRequestId: commandClientRequestId },
        controller.signal,
      )).data;
      if (!isCurrentV2NaturalLanguageResponse(commandExpected)) return;
      commandRecovery = {
        ...commandRecovery,
        rootClientRequestId: acknowledgement.rootClientRequestId,
      };
    } else {
      const request = normalizeV2ProjectInstructionRequest(
        question,
        commandClientRequestId,
        mode.type === 'INITIAL' ? 'INITIAL' : mode.kind,
        mode.type === 'INITIAL' ? null : mode.targetClientRequestId,
      );
      const acknowledgement = (await startV2NaturalLanguageTurn(
        sessionId, request, controller.signal,
      )).data;
      if (!isCurrentV2NaturalLanguageResponse(commandExpected)) return;
      commandRecovery = {
        ...commandRecovery,
        rootClientRequestId: acknowledgement.rootClientRequestId,
      };
    }
    storeV2NaturalLanguageRequest(commandRecovery);
    v2TurnInput.value = '';
    resetV2ComposerMode();
    if (epoch === projectEpoch && projectId === activeProjectId.value
        && commandExpected && isCurrentV2NaturalLanguageResponse(commandExpected)) {
      recoverV2NaturalLanguageTurn(projectId, sessionId, commandRecovery);
    }
  } catch (cause) {
    const expected = commandExpected;
    if (epoch === projectEpoch && projectId === activeProjectId.value
        && expected && isCurrentV2NaturalLanguageResponse(expected)) {
      const failureText = v2NaturalLanguageFailureText(cause);
      v2TurnError.value = failureText;
      if (commandSessionId && commandRecovery) {
        if (v2ProjectCommandFailureRecoveryDecision(cause) === 'DROP_UNBOUND_RECOVERY') {
          if (previousRecovery) {
            storeV2NaturalLanguageRequest(previousRecovery);
            recoverV2NaturalLanguageTurn(
              projectId,
              commandSessionId,
              previousRecovery,
              { preserveError: true },
            );
          } else clearStoredV2NaturalLanguageRequest(projectId, commandSessionId);
          v2TurnError.value = failureText;
        } else {
          await loadV2TurnHistory(commandSessionId, epoch);
          if (isCurrentV2NaturalLanguageResponse(expected)) {
            recoverV2NaturalLanguageTurn(
              projectId, commandSessionId, commandRecovery,
            );
          }
        }
      }
    }
  } finally {
    if (epoch === projectEpoch && projectId === activeProjectId.value) {
      v2TurnSubmitting.value = false;
    }
  }
}

async function cancelV2NaturalLanguageTask(task: V2NaturalLanguageTurnHistoryItem) {
  const projectId = activeProjectId.value;
  const sessionId = activeSessionId.value;
  if (!projectId || !sessionId || v2TurnSubmitting.value
      || v2TurnCancelSubmitting.value
      || !canCancelV2ProjectTurn(task)) return;
  if (!window.confirm('取消这个任务？已提交的正式事实会保留。')) return;
  const commandClientRequestId = newV2NaturalLanguageClientRequestId();
  const epoch = projectEpoch;
  const previousRecovery = storedV2NaturalLanguageRequest(projectId, sessionId);
  const recovery: V2ProjectChainRecoveryRecord = {
    version: 1,
    projectId,
    sessionId,
    rootClientRequestId: task.clientRequestId,
    commandClientRequestId,
    question: task.question,
  };
  storeV2NaturalLanguageRequest(recovery);
  v2TurnCancelSubmitting.value = true;
  v2TurnError.value = '';
  invalidateV2NaturalLanguageRequest();
  v2TurnClientRequestId = commandClientRequestId;
  const expected = currentV2NaturalLanguageIdentity();
  const controller = new AbortController();
  v2TurnAbortController = controller;
  try {
    const acknowledgement = (await cancelV2NaturalLanguageTurn(
      sessionId,
      task.clientRequestId,
      { clientRequestId: commandClientRequestId },
      controller.signal,
    )).data;
    if (!isCurrentV2NaturalLanguageResponse(expected)) return;
    const acceptedRecovery = {
      ...recovery,
      rootClientRequestId: acknowledgement.rootClientRequestId,
    };
    storeV2NaturalLanguageRequest(acceptedRecovery);
    if (isCurrentV2NaturalLanguageResponse(expected)) {
      recoverV2NaturalLanguageTurn(projectId, sessionId, acceptedRecovery);
    }
  } catch (cause) {
    if (isCurrentV2NaturalLanguageResponse(expected)) {
      const failureText = v2NaturalLanguageFailureText(cause);
      v2TurnError.value = failureText;
      if (v2ProjectCommandFailureRecoveryDecision(cause) === 'DROP_UNBOUND_RECOVERY') {
        if (previousRecovery) {
          storeV2NaturalLanguageRequest(previousRecovery);
          recoverV2NaturalLanguageTurn(
            projectId,
            sessionId,
            previousRecovery,
            { preserveError: true },
          );
        } else clearStoredV2NaturalLanguageRequest(projectId, sessionId);
        v2TurnError.value = failureText;
      } else {
        await loadV2TurnHistory(sessionId, epoch);
        if (isCurrentV2NaturalLanguageResponse(expected)) {
          recoverV2NaturalLanguageTurn(projectId, sessionId, recovery);
        }
      }
    }
  } finally {
    if (epoch === projectEpoch && projectId === activeProjectId.value) {
      v2TurnCancelSubmitting.value = false;
    }
  }
}

function handleV2TurnKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || (!event.ctrlKey && !event.metaKey)) return;
  event.preventDefault();
  void sendV2NaturalLanguageTurn();
}

function openV2CandidateReview(artifactId?: number | null) {
  if (!artifactId) return;
  const candidate = candidates.value.find((item) => item.artifact.id === artifactId);
  if (candidate) selectCandidate(candidate);
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
    if (activeProjectId.value) loadReactPlanRecord(activeProjectId.value, sessionId, epoch);
    await Promise.all([
      loadCandidates(sessionId, epoch),
      loadV2TurnHistory(sessionId, epoch),
    ]);
    if (epoch === projectEpoch && activeProjectId.value) {
      recoverV2NaturalLanguageTurn(activeProjectId.value, sessionId);
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
  if (sessionId === activeSessionId.value || projectAgentBusy.value) return;
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
      loadReactPlanRecord(activeProjectId.value, sessionId, epoch);
      recoverV2NaturalLanguageTurn(activeProjectId.value, sessionId);
    }
  } catch (cause) {
    if (epoch === projectEpoch) error.value = apiError(cause);
  }
}

async function startNewConversation() {
  const project = activeProject.value;
  if (!project || projectAgentBusy.value) return;
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
  const sessionTitle = session.title || `Conversation #${session.id}`;
  if (!window.confirm(`Delete "${sessionTitle}"?`)) {
    return;
  }
  const wasActive = activeSessionId.value === session.id;
  const recoveryToResume = wasActive && activeProjectId.value
    ? storedV2NaturalLanguageRequest(activeProjectId.value, session.id)
    : null;
  error.value = '';
  if (wasActive) {
    invalidateV2NaturalLanguageRequest();
    invalidateReactPlanStream();
  }
  try {
    await deleteAgentSession(session.id);
    if (activeProjectId.value) {
      window.localStorage.removeItem(reactPlanStorageKey(activeProjectId.value, session.id));
      window.localStorage.removeItem(projectAgentRouteStorageKey(activeProjectId.value, session.id));
    }
    projectSessions.value = projectSessions.value.filter((item) => item.id !== session.id);
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
    if (wasActive && recoveryToResume && activeProjectId.value) {
      recoverV2NaturalLanguageTurn(
        activeProjectId.value,
        session.id,
        recoveryToResume,
        { preserveError: true },
      );
    }
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
    projectSessions.value.forEach((session) => {
      window.localStorage.removeItem(reactPlanStorageKey(projectId, session.id));
      window.localStorage.removeItem(projectAgentRouteStorageKey(projectId, session.id));
    });
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

onMounted(() => {
  inspectorOpen.value = false;
  void loadProjects();
});
onUnmounted(() => {
  invalidateV2NaturalLanguageRequest();
  invalidateV2HistoryRequest();
  invalidateReactPlanStream();
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
.project-list__item { min-height: 50px; display: grid; align-content: center; gap: 2px; padding: 7px 10px; line-height: 1.3; }
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
.v2-conversation__tasks { flex: 1 1 auto; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; padding-right: 3px; scrollbar-gutter: stable; }
.v2-task-card { display: flex; flex-direction: column; gap: 10px; padding: 12px; border: 1px solid var(--yb-border); border-radius: 12px; background: var(--yb-bg-elevated); }
.v2-task-card__question { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.v2-task-card__question-copy { min-width: 0; flex: 1 1 auto; display: grid; grid-template-columns: 24px minmax(0, 1fr); align-items: start; gap: 10px; }
.v2-task-card__question p { margin: 1px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.65; }
.v2-task-card__avatar { width: 24px; height: 24px; display: grid; place-items: center; border: 1px solid var(--yb-border-strong); border-radius: 50%; color: var(--yb-primary); font-size: 10px; font-weight: 700; }
.v2-task-card__avatar--assistant { border-color: var(--yb-primary); border-radius: 5px; color: var(--yb-primary-contrast); background: var(--yb-primary); }
.v2-task-card__result { display: grid; grid-template-columns: 24px minmax(0, 1fr); align-items: start; gap: 10px; padding: 10px; border-radius: 8px; background: var(--yb-bg-muted); }
.v2-task-card__result-copy { min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.v2-task-card__result-copy > p { margin: 0; color: var(--yb-text-secondary); }
.v2-task-card__delivery { display: flex; flex-direction: column; gap: 8px; }
.v2-task-card__validation { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 5px 9px; margin: 0; font-size: 10px; }
.v2-task-card__validation dt { color: var(--yb-text-muted); }
.v2-task-card__validation dd { min-width: 0; margin: 0; overflow-wrap: anywhere; }
.v2-task-card__actions { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.reactplan-task-card code { overflow-wrap: anywhere; color: var(--yb-text-muted); font-size: 9px; }
.reactplan-receipt { display: block; margin-top: 3px; }
.project-agent-mode__switch button:disabled { cursor: not-allowed; opacity: .58; }
.v2-conversation__process { padding: 0; border-top: 1px solid var(--yb-border); border-bottom: 1px solid var(--yb-border); }
.v2-conversation__process > summary { display: flex; align-items: center; gap: 8px; min-height: 38px; cursor: pointer; color: var(--yb-text-secondary); font-size: 10px; font-weight: 700; }
.v2-conversation__process > summary small { margin-left: auto; color: var(--yb-text-muted); font-weight: 400; }
.v2-conversation__process ol { display: flex; flex-direction: column; gap: 6px; margin: 0; padding: 0 0 8px; list-style: none; }
.v2-conversation__process li { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 9px; padding: 8px; border-radius: 7px; background: var(--yb-bg-muted); }
.v2-conversation__process li > span { display: grid; width: 22px; height: 22px; place-items: center; border: 1px solid var(--yb-border); border-radius: 50%; color: var(--yb-text-muted); font-size: 10px; font-weight: 800; }
.v2-conversation__process li > div { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.v2-conversation__process li small { overflow-wrap: anywhere; color: var(--yb-text-muted); font-size: 9px; }
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
.v2-conversation__composer .project-send-button {
  width: 72px;
  min-width: 72px;
  height: 40px;
  padding: 0;
  border-radius: 8px !important;
}
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
  grid-template-columns: clamp(248px, 21vw, 290px) minmax(0, 1fr);
  border-color: var(--project-rule);
  border-radius: var(--project-radius-panel);
  background: var(--project-surface);
  box-shadow: none;
}

.project-workspace--console .project-workspace__grid--context-collapsed {
  grid-template-columns: 0 minmax(0, 1fr);
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
  padding: 12px;
  background: var(--project-canvas);
}

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
.project-workspace--console .project-inspector__tabs > strong {
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
  min-height: 54px;
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

.project-workspace--console .v2-conversation__tasks {
  gap: 0;
}

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
  .project-workspace--console .project-workspace__grid { grid-template-columns: 248px minmax(0, 1fr); }
  .project-workspace--console .project-workspace__grid--context-collapsed { grid-template-columns: 0 minmax(0, 1fr); }
}

@media (max-width: 980px) {
  .project-workspace--console { height: auto; min-height: calc(100dvh - 28px); overflow: visible; gap: 8px; }
  .project-workspace--console .project-workspace__header { padding: 10px 12px; }
  .project-workspace--console .project-workspace__grid { grid-template-columns: 1fr; overflow: visible; }
  .project-workspace--console .project-workspace__grid--context-collapsed { grid-template-columns: 1fr; }
  .project-workspace--console .project-workspace__grid--context-collapsed .project-context-rail { display: none; }
  .project-workspace--console .project-panel { min-height: 0; padding: 12px; }
  .project-workspace--console .project-context-rail {
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
