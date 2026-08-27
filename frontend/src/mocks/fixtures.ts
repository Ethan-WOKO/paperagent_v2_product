import type { UserMeResponse } from '@/api/auth';
import type {
  AgentMessageResponse,
  AgentSessionResponse,
  V2NaturalLanguageTurnHistoryItem,
  V2NaturalLanguageTurnResponse,
} from '@/api/agent';
import type {
  ProjectFileResponse,
  ProjectManifestResponse,
  ProjectRevisionResponse,
  ProjectSearchHit,
  ProjectSummaryResponse,
} from '@/api/project';
import type {
  PaperAnalysisResponse,
  PaperArtifactResponse,
  PaperClarificationResponse,
  PaperSectionResponse,
  PaperSuggestionResponse,
  PaperTaskHistoryResponse,
  PaperTaskResponse,
} from '@/api/paper';
import type { TaskEventResponse, TaskStatusResponse } from '@/api/task';
import type { KbDocumentItem, KbDocumentPreviewResponse, KnowledgeSearchResult } from '@/api/knowledge';
import type { LongTermMemoryResponse } from '@/api/memory';
import type { UserSettingsResponse } from '@/api/settings';
import type { SkillListItemResponse } from '@/api/skills';
import type { AdminInviteCode, AdminUserDetail, AdminUserSummary } from '@/api/admin';
import type { UiMockProjectState, UiMockRole } from './scenario';

const CREATED_AT = '2026-08-01T02:18:00Z';
const UPDATED_AT = '2026-08-01T02:28:00Z';

export const mockUsers: Record<UiMockRole, UserMeResponse> = {
  admin: {
    id: 1,
    username: 'yifeng',
    accountType: 'NORMAL',
    demo: false,
    role: 'ADMIN',
    aiQuotaTotal: 2_000_000,
    aiQuotaUsed: 426_800,
    aiQuotaRemaining: 1_573_200,
  },
  user: {
    id: 2,
    username: 'researcher',
    accountType: 'NORMAL',
    demo: false,
    role: 'USER',
    aiQuotaTotal: 800_000,
    aiQuotaUsed: 128_400,
    aiQuotaRemaining: 671_600,
  },
  demo: {
    id: 3,
    username: 'demo',
    accountType: 'DEMO',
    demo: true,
    role: 'USER',
    aiQuotaTotal: 100_000,
    aiQuotaUsed: 18_500,
    aiQuotaRemaining: 81_500,
  },
};

export const mockProjects: ProjectSummaryResponse[] = [{
  id: 64,
  name: 'PaperAgent V2 产品重构',
  accessMode: 'READ_ONLY',
  createdAt: CREATED_AT,
}];

export const mockManifest: ProjectManifestResponse = {
  projectId: 64,
  version: 'V64',
  files: [
    { path: 'README.md', sizeBytes: 6_821, modifiedAt: UPDATED_AT, sha256: 'readme-v64' },
    { path: 'docs/ARCHITECTURE.md', sizeBytes: 18_304, modifiedAt: UPDATED_AT, sha256: 'architecture-v64' },
    { path: 'src/orchestrator.py', sizeBytes: 12_984, modifiedAt: UPDATED_AT, sha256: 'orchestrator-v64' },
    { path: 'tests/test_orchestrator.py', sizeBytes: 8_117, modifiedAt: UPDATED_AT, sha256: 'tests-v64' },
  ],
};

export const mockSessions: AgentSessionResponse[] = [{
  id: 6401,
  userId: 1,
  scope: 'PROJECT',
  projectId: 64,
  title: '评审 V2 执行架构',
  modelProvider: 'deepseek',
  model: 'deepseek-v4-flash',
  maxSteps: 20,
  ragDisabled: false,
  createdAt: CREATED_AT,
  updatedAt: UPDATED_AT,
}];

export const mockChatSessions: AgentSessionResponse[] = [
  {
    id: 6201,
    userId: 1,
    scope: 'WORKSPACE',
    projectId: null,
    title: '多智能体论文的证据约束',
    modelProvider: 'deepseek',
    model: 'deepseek-v4-flash',
    maxSteps: 20,
    ragDisabled: false,
    createdAt: '2026-08-01T01:42:00Z',
    updatedAt: UPDATED_AT,
  },
  {
    id: 6202,
    userId: 1,
    scope: 'WORKSPACE',
    projectId: null,
    title: '梳理实验对照组',
    modelProvider: 'deepseek',
    model: 'deepseek-v4-flash',
    maxSteps: 20,
    ragDisabled: false,
    createdAt: '2026-07-31T09:20:00Z',
    updatedAt: '2026-07-31T10:08:00Z',
  },
  {
    id: 6203,
    userId: 1,
    scope: 'WORKSPACE',
    projectId: null,
    title: '检索可复现性研究',
    modelProvider: 'deepseek',
    model: 'deepseek-v4-pro',
    maxSteps: 20,
    ragDisabled: false,
    createdAt: '2026-07-30T07:12:00Z',
    updatedAt: '2026-07-30T08:31:00Z',
  },
  {
    id: 6204,
    userId: 1,
    scope: 'WORKSPACE',
    projectId: null,
    title: '润色方法章节摘要',
    modelProvider: 'deepseek',
    model: 'deepseek-v4-flash',
    maxSteps: 20,
    ragDisabled: true,
    createdAt: '2026-07-28T04:16:00Z',
    updatedAt: '2026-07-28T05:02:00Z',
  },
];

export const mockChatMessages: Record<number, AgentMessageResponse[]> = {
  6201: [
    {
      id: 8201,
      sessionId: 6201,
      userId: 1,
      role: 'user',
      content: '请结合我上传的综述，解释多智能体学术协作系统为什么需要证据约束，并给出可以落地的设计原则。',
      toolCallsJson: null,
      paperTaskId: null,
      createdAt: '2026-08-01T02:18:00Z',
    },
    {
      id: 8202,
      sessionId: 6201,
      userId: 1,
      role: 'process',
      content: [
        '读取附件「多智能体学术协作综述.pdf」',
        '检索知识库：证据约束、执行回执、项目版本',
        '整理 3 条可实施的设计原则',
      ].join('\n'),
      toolCallsJson: null,
      paperTaskId: null,
      createdAt: '2026-08-01T02:18:28Z',
    },
    {
      id: 8203,
      sessionId: 6201,
      userId: 1,
      role: 'assistant',
      content: [
        '证据约束的价值，不是让系统展示更多过程，而是让每个结论都能回答三个问题：依据从哪里来、在哪个版本上得出、是否经过验证。',
        '',
        '### 可以落地的设计原则',
        '',
        '1. **结论与证据分离保存**：最终回答保持简洁，来源、执行回执与工具结果作为可展开的证据层。',
        '2. **版本进入任务边界**：任务开始时固定输入版本；计划可以修订，但已经确认的事实不能被后续步骤静默覆盖。',
        '3. **验证结果可追溯**：自动检查、人工确认和失败原因使用统一状态，并保留对应的时间与产物。',
        '',
        '这样既能降低用户阅读负担，也能在需要审计时快速还原推理与执行依据。',
      ].join('\n'),
      toolCallsJson: null,
      paperTaskId: null,
      createdAt: UPDATED_AT,
    },
  ],
  6202: [],
  6203: [],
  6204: [],
};

const stepSet = {
  complete: [
    { index: 1, title: '检查任务编排', status: 'SUCCEEDED' as const, detail: '完成边界核对' },
    { index: 2, title: '检查证据链', status: 'SUCCEEDED' as const, detail: '完成来源与版本核对' },
    { index: 3, title: '检查验证机制', status: 'SUCCEEDED' as const, detail: '完成验证覆盖核对' },
  ],
  running: [
    { index: 1, title: '检查任务编排', status: 'SUCCEEDED' as const, detail: '完成边界核对' },
    { index: 2, title: '检查证据链', status: 'RUNNING' as const, detail: '正在核对证据来源' },
    { index: 3, title: '检查验证机制', status: 'PENDING' as const, detail: null },
  ],
  waiting: [
    { index: 1, title: '检查任务编排', status: 'SUCCEEDED' as const, detail: '完成边界核对' },
    { index: 2, title: '确认修改范围', status: 'PENDING' as const, detail: '等待用户确认' },
  ],
  failed: [
    { index: 1, title: '检查任务编排', status: 'SUCCEEDED' as const, detail: '完成边界核对' },
    { index: 2, title: '读取验证输出', status: 'FAILED' as const, detail: '验证结果不可用' },
  ],
};

export function mockTurnHistory(state: UiMockProjectState): V2NaturalLanguageTurnHistoryItem[] {
  if (state === 'empty') return [];
  const status = state === 'complete' ? 'SUCCEEDED'
    : state === 'running' ? 'RUNNING'
      : state === 'waiting' ? 'WAITING_CONFIRMATION' : 'FAILED';
  return [{
    clientRequestId: `ui-mock-${state}`,
    question: '请评审 V2 执行架构，检查任务编排、证据链和验证机制。',
    status,
    route: 'PERSISTENT_PLAN_EXECUTE',
    planId: 'product-plan.ui-mock',
    projectVersion: 'V64',
    steps: stepSet[state],
    finalText: state === 'complete'
      ? 'V2 执行架构的模块边界清晰，证据链可以追溯，验证机制覆盖主要执行路径。仍需补强失败恢复与候选修改的自动化验证。'
      : null,
    candidateArtifactId: null,
    outputPaths: state === 'complete' ? ['docs/architecture-review.md'] : [],
    errorCode: state === 'failed' ? 'VALIDATION_UNAVAILABLE' : null,
    createdAt: CREATED_AT,
    updatedAt: UPDATED_AT,
    agentAutomaticValidation: null,
    confirmationValidation: null,
  }];
}

export function mockTurn(state: UiMockProjectState): V2NaturalLanguageTurnResponse {
  const item = mockTurnHistory(state)[0];
  if (item) {
    const { route: _route, clientRequestId: _request, question: _question,
      createdAt: _created, updatedAt: _updated, agentAutomaticValidation: _automatic,
      confirmationValidation: _confirmation, ...turn } = item;
    return { ...turn, route: item.route || 'PERSISTENT_PLAN_EXECUTE' };
  }
  return {
    status: 'PLANNING',
    route: 'PERSISTENT_PLAN_EXECUTE',
    planId: null,
    projectVersion: 'V64',
    steps: [],
    finalText: null,
    candidateArtifactId: null,
    outputPaths: [],
    errorCode: null,
  };
}

export const mockRevisions: ProjectRevisionResponse[] = [{
  id: 64,
  projectVersion: 'V64',
  current: true,
  fileCount: 4,
  totalBytes: 46_226,
  sourceType: 'UPLOAD',
  createdAt: CREATED_AT,
}];

const mockFileContent: Record<string, string> = {
  'README.md': '# PaperAgent V2\n\nV2 project workspace and persistent execution runtime.',
  'docs/ARCHITECTURE.md': '# V2 Architecture\n\nTaskFrame → Plan → Step Agent Loop → Workspace Diff → Final Synthesis',
  'src/orchestrator.py': 'def execute_plan(plan):\n    return plan.run()\n',
  'tests/test_orchestrator.py': 'def test_execute_plan():\n    assert True\n',
};

export function mockProjectFile(path = 'README.md'): ProjectFileResponse {
  const entry = mockManifest.files.find((file) => file.path === path) || mockManifest.files[0];
  return { ...entry, content: mockFileContent[entry.path] || '' };
}

export function mockProjectSearch(query: string): ProjectSearchHit[] {
  if (!query.trim()) return [];
  return [{
    path: 'docs/ARCHITECTURE.md',
    lineNumber: 3,
    line: 'TaskFrame → Plan → Step Agent Loop → Workspace Diff → Final Synthesis',
    sha256: 'architecture-v64',
  }];
}

export const mockPaperTask: PaperTaskResponse = {
  id: 9001,
  userId: 2,
  title: '多智能体学术协作方法研究',
  sourceFilename: 'multi-agent-research.tex',
  objectKey: 'ui-mock/papers/9001/source.tex',
  finalObjectKey: 'ui-mock/papers/9001/polished.zip',
  clientRequestId: 'ui-mock-paper-9001',
  idempotent: false,
  status: 'COMPLETED',
  targetLanguage: 'zh',
  currentStage: 'ASSEMBLE',
  errorMessage: null,
  scoreThreshold: 82,
  maxRounds: 3,
  innerMaxAttempts: 2,
  literatureMinCount: 8,
  literatureCount: 16,
  createdAt: '2026-07-31T09:10:00Z',
  updatedAt: '2026-07-31T09:28:00Z',
};

export const mockPaperArtifacts: PaperArtifactResponse[] = [
  { id: 9101, taskId: 9001, type: 'polished_tex', objectKey: 'ui-mock/papers/9001/polished.tex', version: 1, metadataJson: null, artifactStatus: 'COMPLETED', createdAt: mockPaperTask.updatedAt },
  { id: 9102, taskId: 9001, type: 'suggested_bib', objectKey: 'ui-mock/papers/9001/suggested.bib', version: 1, metadataJson: null, artifactStatus: 'COMPLETED', createdAt: mockPaperTask.updatedAt },
  { id: 9103, taskId: 9001, type: 'review_report', objectKey: 'ui-mock/papers/9001/review-report.md', version: 1, metadataJson: null, artifactStatus: 'COMPLETED', createdAt: mockPaperTask.updatedAt },
];

export const mockPaperHistory: PaperTaskHistoryResponse[] = [{
  id: mockPaperTask.id,
  title: mockPaperTask.title,
  sourceFilename: mockPaperTask.sourceFilename,
  clientRequestId: mockPaperTask.clientRequestId,
  status: mockPaperTask.status,
  currentStage: mockPaperTask.currentStage,
  errorMessage: null,
  targetLanguage: mockPaperTask.targetLanguage,
  finalObjectKey: mockPaperTask.finalObjectKey,
  literatureMinCount: mockPaperTask.literatureMinCount,
  literatureCount: mockPaperTask.literatureCount,
  createdAt: mockPaperTask.createdAt,
  updatedAt: mockPaperTask.updatedAt,
  artifacts: mockPaperArtifacts,
}];

export const mockPaperClarifications: PaperClarificationResponse[] = [{
  id: 9201,
  taskId: 9001,
  type: 'SECTION_ROLE_CONFIRMATION',
  questionJson: JSON.stringify({ message: '方法章节识别结果是否正确？', blocking: false }),
  optionsJson: JSON.stringify({ options: ['保持识别结果', '标记为实验章节'], defaultOption: '保持识别结果' }),
  status: 'ANSWERED',
  userAnswerJson: JSON.stringify('保持识别结果'),
  createdAt: '2026-07-31T09:13:00Z',
  answeredAt: '2026-07-31T09:14:00Z',
}];

export const mockPaperSections: PaperSectionResponse[] = [
  { id: 9301, taskId: 9001, sourcePath: 'multi-agent-research.tex', orderIndex: 0, level: 1, title: '引言', role: 'INTRODUCTION', roleConfidence: 0.97, roleSource: 'MODEL', charStart: 0, charEnd: 3280, polishStatus: 'POLISHED', revisionStatus: 'ACCEPTED', reviewJson: null, diffJson: JSON.stringify({ summary: '压缩背景描述并明确研究问题' }) },
  { id: 9302, taskId: 9001, sourcePath: 'multi-agent-research.tex', orderIndex: 1, level: 1, title: '相关工作', role: 'RELATED_WORK', roleConfidence: 0.95, roleSource: 'MODEL', charStart: 3281, charEnd: 7920, polishStatus: 'POLISHED', revisionStatus: 'ACCEPTED', reviewJson: null, diffJson: JSON.stringify({ summary: '统一术语并补充证据提示' }) },
  { id: 9303, taskId: 9001, sourcePath: 'multi-agent-research.tex', orderIndex: 2, level: 1, title: '方法', role: 'METHOD', roleConfidence: 0.98, roleSource: 'MODEL', charStart: 7921, charEnd: 14160, polishStatus: 'POLISHED', revisionStatus: 'REVIEW_REQUIRED', reviewJson: JSON.stringify({ note: '请核对实验变量定义' }), diffJson: JSON.stringify({ summary: '重组方法步骤并降低歧义' }) },
  { id: 9304, taskId: 9001, sourcePath: 'multi-agent-research.tex', orderIndex: 3, level: 1, title: '结论', role: 'CONCLUSION', roleConfidence: 0.96, roleSource: 'MODEL', charStart: 14161, charEnd: 15840, polishStatus: 'POLISHED', revisionStatus: 'ACCEPTED', reviewJson: null, diffJson: JSON.stringify({ summary: '收束结论并保留限制说明' }) },
];

const mockEvidence = [
  { id: 9401, title: 'Collaborative Agents for Scientific Workflows', authors: 'Lin Wei; Maya Patel', publicationYear: 2025, venue: 'JASIST', doi: '10.1000/ui-mock.001', arxivId: null, openAlexId: 'W-ui-mock-1', s2Id: null, url: 'https://example.com/paper-1', pdfUrl: null, citationCount: 28, relevanceScore: 0.91, narrativeRole: 'method support', sourceQuery: 'multi-agent scientific workflow' },
  { id: 9402, title: 'Evidence-grounded Academic Assistants', authors: 'A. Chen; R. Kumar', publicationYear: 2024, venue: 'ACL Findings', doi: '10.1000/ui-mock.002', arxivId: null, openAlexId: 'W-ui-mock-2', s2Id: null, url: 'https://example.com/paper-2', pdfUrl: null, citationCount: 43, relevanceScore: 0.88, narrativeRole: 'evidence support', sourceQuery: 'evidence grounded academic agent' },
];

export const mockPaperSuggestions: PaperSuggestionResponse[] = [
  { id: 9501, taskId: 9001, sectionId: 9301, track: 'REVIEW', category: '论点清晰度', severity: 'HIGH', statement: '引言中的核心研究问题出现较晚，建议前移并减少背景铺陈。', applicable: true, patchJson: null, status: 'PROPOSED', honestyGrade: 'A', honestyReason: '基于原文结构判断', evidenceCount: 0, evidenceCards: [], createdAt: mockPaperTask.createdAt, updatedAt: mockPaperTask.updatedAt },
  { id: 9502, taskId: 9001, sectionId: 9302, track: 'ADVOCACY', category: '证据支持', severity: 'MEDIUM', statement: '相关工作中的协作代理论断需要可核验文献支持。', applicable: true, patchJson: null, status: 'PROPOSED', honestyGrade: 'A', honestyReason: '存在可核验外部证据', evidenceCount: 2, evidenceCards: mockEvidence, createdAt: mockPaperTask.createdAt, updatedAt: mockPaperTask.updatedAt },
  { id: 9503, taskId: 9001, sectionId: 9303, track: 'REVIEW', category: '术语一致性', severity: 'LOW', statement: '方法章节中“任务代理”和“执行代理”需要统一定义。', applicable: true, patchJson: null, status: 'PROPOSED', honestyGrade: 'A', honestyReason: '基于全文术语对照', evidenceCount: 0, evidenceCards: [], createdAt: mockPaperTask.createdAt, updatedAt: mockPaperTask.updatedAt },
];

export const mockPaperAnalysis: PaperAnalysisResponse = {
  researchProfileJson: JSON.stringify({ topic: '多智能体学术协作', contribution: '持久化任务与证据约束' }),
  conceptLadderJson: JSON.stringify({ levels: ['任务编排', '证据链', '验证机制'] }),
  gapMatrixJson: JSON.stringify({ gaps: ['失败恢复说明不足', '变量定义需核对'] }),
};

export const mockPaperTaskStatus: TaskStatusResponse = {
  taskType: 'paper_polish', taskId: 9001, status: 'COMPLETED', currentStage: 'ASSEMBLE',
  createdAt: mockPaperTask.createdAt, updatedAt: mockPaperTask.updatedAt,
  startedAt: '2026-07-31T09:10:20Z', finishedAt: mockPaperTask.updatedAt,
  progressPercent: 100, errorCode: null, errorMessage: null, cancellationReason: null,
  partialResultAvailable: false, completedArtifactCount: 3, partialArtifactCount: 0,
  lastEventId: 9603, lastEventType: 'TASK_COMPLETED', lastEventMessage: '论文润色已完成',
  lastEventAt: mockPaperTask.updatedAt, terminal: true, cancellable: false,
};

export const mockPaperEvents: TaskEventResponse[] = [
  { id: 9601, taskType: 'paper_polish', taskId: 9001, userId: 2, eventType: 'TASK_PROGRESS', stage: 'STRUCTURE', status: 'RUNNING', message: '完成章节结构识别', payloadJson: JSON.stringify({ progressPercent: 24, currentSection: 1, totalSections: 4 }), createdAt: '2026-07-31T09:12:00Z' },
  { id: 9602, taskType: 'paper_polish', taskId: 9001, userId: 2, eventType: 'TASK_PROGRESS', stage: 'POLISH', status: 'RUNNING', message: '完成全部章节润色', payloadJson: JSON.stringify({ progressPercent: 82, currentSection: 4, totalSections: 4 }), createdAt: '2026-07-31T09:24:00Z' },
  { id: 9603, taskType: 'paper_polish', taskId: 9001, userId: 2, eventType: 'TASK_COMPLETED', stage: 'ASSEMBLE', status: 'COMPLETED', message: '论文润色已完成', payloadJson: JSON.stringify({ progressPercent: 100 }), createdAt: mockPaperTask.updatedAt },
];

export const mockKnowledgeDocuments: KbDocumentItem[] = [
  {
    id: 301, userId: 2, filename: '多智能体学术协作综述.pdf', status: 'READY', isPublic: false,
    sourceType: 'USER_UPLOAD', projectId: null, lineageId: 'kb-multi-agent-review', versionNo: 2,
    versionStatus: 'ACTIVE', canonicalKey: 'multi-agent-review', effectiveAt: '2026-07-29T08:30:00Z',
    supersededAt: null, deletedAt: null, mimeType: 'application/pdf', fileSize: 2_843_107,
    errorMessage: null, createdAt: '2026-07-26T08:30:00Z', updatedAt: '2026-07-29T08:30:00Z',
  },
  {
    id: 302, userId: 2, filename: '实验室研究规范.md', status: 'READY', isPublic: true,
    sourceType: 'DEMO_SEED', projectId: null, lineageId: 'kb-lab-guidelines', versionNo: 1,
    versionStatus: 'ACTIVE', canonicalKey: 'lab-guidelines', effectiveAt: '2026-07-21T03:10:00Z',
    supersededAt: null, deletedAt: null, mimeType: 'text/markdown', fileSize: 48_230,
    errorMessage: null, createdAt: '2026-07-21T03:10:00Z', updatedAt: '2026-07-21T03:10:00Z',
  },
  {
    id: 303, userId: 2, filename: '证据约束方法记录.docx', status: 'PROCESSING', isPublic: false,
    sourceType: 'USER_UPLOAD', projectId: null, lineageId: 'kb-evidence-methods', versionNo: 1,
    versionStatus: 'ACTIVE', canonicalKey: 'evidence-methods', effectiveAt: null,
    supersededAt: null, deletedAt: null, mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', fileSize: 684_712,
    errorMessage: null, createdAt: '2026-08-01T01:58:00Z', updatedAt: '2026-08-01T02:11:00Z',
  },
  {
    id: 304, userId: 2, filename: '旧版引用说明.txt', status: 'FAILED', isPublic: false,
    sourceType: 'USER_UPLOAD', projectId: null, lineageId: 'kb-citation-notes', versionNo: 1,
    versionStatus: 'ACTIVE', canonicalKey: 'citation-notes', effectiveAt: null,
    supersededAt: null, deletedAt: null, mimeType: 'text/plain', fileSize: 12_806,
    errorMessage: '文档编码无法识别，请转换为 UTF-8 后重新上传。', createdAt: '2026-07-18T06:20:00Z', updatedAt: '2026-07-18T06:21:00Z',
  },
];

export const mockKnowledgePreview: KbDocumentPreviewResponse = {
  id: 301,
  filename: '多智能体学术协作综述.pdf',
  status: 'READY',
  sourceType: 'USER_UPLOAD',
  projectId: null,
  lineageId: 'kb-multi-agent-review',
  versionNo: 2,
  versionStatus: 'ACTIVE',
  canonicalKey: 'multi-agent-review',
  mimeType: 'application/pdf',
  fileSize: 2_843_107,
  totalChunks: 36,
  previewChunks: 8,
  maxChars: 20000,
  truncated: true,
  content: '多智能体学术协作系统需要同时处理任务编排、证据追踪与结果验证。\n\n在持久化执行中，计划可以修订，但已经完成的权威事实必须保持可追溯。系统应记录任务版本、执行回执和证据来源，并在用户接受前保持项目原版本不变。\n\n检索模块应根据用户权限过滤私有文档与允许访问的公共文档，避免跨用户数据泄露。',
};

export const mockKnowledgeSearchResults: KnowledgeSearchResult[] = [
  {
    documentId: 301, filename: '多智能体学术协作综述.pdf', chunkIndex: 12,
    chunkText: '持久化计划允许修订，但完成步骤的权威事实、执行回执和证据来源必须保持可追溯。',
    score: 0.9142, isPublic: false, sourceType: 'USER_UPLOAD', versionStatus: 'ACTIVE',
    lineageId: 'kb-multi-agent-review', versionNo: 2, projectId: null, canonicalKey: 'multi-agent-review',
  },
  {
    documentId: 302, filename: '实验室研究规范.md', chunkIndex: 3,
    chunkText: '研究记录应注明数据来源、实验版本和结论边界；无法核验的材料不得作为最终结论的唯一依据。',
    score: 0.7825, isPublic: true, sourceType: 'DEMO_SEED', versionStatus: 'ACTIVE',
    lineageId: 'kb-lab-guidelines', versionNo: 1, projectId: null, canonicalKey: 'lab-guidelines',
  },
  {
    documentId: 301, filename: '多智能体学术协作综述.pdf', chunkIndex: 18,
    chunkText: '隔离工作区可以使修改、测试和失败恢复不影响原始项目版本，接受后再将差异应用回项目。',
    score: 0.6679, isPublic: false, sourceType: 'USER_UPLOAD', versionStatus: 'ACTIVE',
    lineageId: 'kb-multi-agent-review', versionNo: 2, projectId: null, canonicalKey: 'multi-agent-review',
  },
];

export const mockMemories: LongTermMemoryResponse[] = [
  {
    id: 501, userId: 2, projectId: null, scope: 'USER', memoryType: 'STYLE',
    content: '论文润色优先保持论证克制、术语一致，避免把不确定的推断改写成确定事实。',
    tags: ['学术写作', '风格'], sourceType: 'USER_SETTING', sourceRefId: null, confidence: 0.96,
    status: 'ACTIVE', confirmationStatus: 'CONFIRMED', confirmedAt: '2026-07-27T08:10:00Z',
    confirmedSource: 'USER', provenanceType: 'MANUAL', provenanceRef: null, projectVersion: null,
    expiresAt: null, invalidatedAt: null, invalidationReason: null, supersedesMemoryId: null,
    supersededByMemoryId: null, createdAt: '2026-07-27T08:08:00Z', updatedAt: '2026-07-27T08:10:00Z', deletedAt: null,
  },
  {
    id: 502, userId: 2, projectId: 64, scope: 'PROJECT', memoryType: 'DECISION',
    content: 'PaperAgent V2 顶层执行模式固定为 DIRECT 和 PERSISTENT_PLAN_EXECUTE；项目文件修改必须进入持久化计划并在隔离工作区完成。',
    tags: ['V2', '执行约束', '架构'], sourceType: 'AGENT_EXTRACTED', sourceRefId: 'session:6401', confidence: 0.91,
    status: 'ACTIVE', confirmationStatus: 'UNCONFIRMED', confirmedAt: null, confirmedSource: null,
    provenanceType: 'PROJECT_SESSION', provenanceRef: 'session:6401:turn:7401', projectVersion: 'V64',
    expiresAt: null, invalidatedAt: null, invalidationReason: null, supersedesMemoryId: null,
    supersededByMemoryId: null, createdAt: '2026-07-30T02:18:00Z', updatedAt: '2026-07-30T02:18:00Z', deletedAt: null,
  },
  {
    id: 503, userId: 2, projectId: null, scope: 'USER', memoryType: 'PREFERENCE',
    content: '默认使用中文界面，并保留深色和浅色主题切换。', tags: ['界面', '语言'],
    sourceType: 'USER_SETTING', sourceRefId: null, confidence: 0.88, status: 'ACTIVE',
    confirmationStatus: 'CONFIRMED', confirmedAt: '2026-07-20T04:00:00Z', confirmedSource: 'USER',
    provenanceType: 'MANUAL', provenanceRef: null, projectVersion: null, expiresAt: '2026-12-31T15:59:59Z',
    invalidatedAt: null, invalidationReason: null, supersedesMemoryId: null, supersededByMemoryId: null,
    createdAt: '2026-07-20T03:58:00Z', updatedAt: '2026-07-20T04:00:00Z', deletedAt: null,
  },
];

export const mockSettings: UserSettingsResponse = {
  defaultProvider: 'deepseek',
  deepseekApiKeyConfigured: true,
  glmApiKeyConfigured: false,
  githubPatConfigured: true,
  deepseekModel: 'deepseek-v4-flash',
  glmModel: 'glm-5.2',
  deepseekModels: ['deepseek-v4-flash', 'deepseek-v4-pro'],
  glmModels: ['glm-5.2', 'glm-5.1', 'glm-4.7-flash'],
  deepseekTemperature: 0.3,
  maxSteps: 20,
  ragDefaultEnabled: true,
  filesystemRoots: ['workspace'],
  disabledSkills: ['legacy-plan-agent'],
  customModels: [{
    id: 701, providerKey: 'local-reviewer', label: 'Local Reviewer', modelName: 'reviewer-v1',
    apiUrl: 'http://127.0.0.1:11434/v1/chat/completions', apiKeyConfigured: false, builtin: false,
    sortOrder: 10, createdAt: '2026-07-28T03:00:00Z', updatedAt: '2026-07-28T03:00:00Z',
  }],
  updatedAt: '2026-07-31T11:40:00Z',
};

export const mockSkills: SkillListItemResponse[] = [
  { id: 'frontend-design', name: 'frontend-design', source: 'USER', path: 'skills/frontend-design', enabled: true, description: '构建具有明确视觉方向的前端界面。' },
  { id: 'academic-review', name: 'academic-ai-style-reviewer', source: 'USER', path: 'skills/academic-ai-style-reviewer', enabled: true, description: '审查机械化或过度润色的学术表达。' },
  { id: 'pdf', name: 'pdf', source: 'SYSTEM', path: 'skills/pdf', enabled: true, description: '读取、生成与验证 PDF。' },
  { id: 'documents', name: 'documents', source: 'SYSTEM', path: 'skills/documents', enabled: true, description: '创建和编辑文档。' },
  { id: 'legacy-plan-agent', name: 'legacy-plan-agent', source: 'LEGACY', path: 'skills/legacy-plan-agent', enabled: false, description: '旧版计划能力，当前已禁用。' },
];

export const mockAdminUsers: AdminUserSummary[] = [
  { id: 1, username: 'yifeng', accountType: 'NORMAL', role: 'ADMIN', aiQuotaTotal: 2_000_000, aiQuotaUsed: 426_800, aiQuotaRemaining: 1_573_200, createdAt: '2026-06-01T02:00:00Z', lastLoginAt: '2026-08-01T02:16:00Z', chatSessionCount: 12, paperTaskCount: 8, projectCount: 4 },
  { id: 2, username: 'researcher', accountType: 'NORMAL', role: 'USER', aiQuotaTotal: 800_000, aiQuotaUsed: 128_400, aiQuotaRemaining: 671_600, createdAt: '2026-06-18T07:30:00Z', lastLoginAt: '2026-07-31T10:08:00Z', chatSessionCount: 7, paperTaskCount: 3, projectCount: 2 },
  { id: 3, username: 'demo', accountType: 'DEMO', role: 'USER', aiQuotaTotal: 100_000, aiQuotaUsed: 18_500, aiQuotaRemaining: 81_500, createdAt: '2026-07-01T00:00:00Z', lastLoginAt: '2026-08-01T01:42:00Z', chatSessionCount: 2, paperTaskCount: 1, projectCount: 1 },
];

export const mockAdminDetail: AdminUserDetail = {
  user: mockAdminUsers[0],
  chats: [{
    id: 6401, title: '评审 V2 执行架构', scope: 'PROJECT', projectId: 64, modelProvider: 'deepseek',
    model: 'deepseek-v4-flash', createdAt: CREATED_AT, updatedAt: UPDATED_AT, archived: false,
    messages: [
      { id: 8401, role: 'user', content: '检查任务编排、证据链和验证机制。', createdAt: CREATED_AT, deletable: false },
      { id: 8402, role: 'assistant', content: '模块边界清晰，仍需补强失败恢复验证。', createdAt: UPDATED_AT, deletable: false },
    ],
  }],
  papers: [{ id: 9001, title: '多智能体学术协作方法研究', sourceFilename: 'multi-agent-research.tex', status: 'COMPLETED', currentStage: 'ASSEMBLE', errorMessage: null, createdAt: mockPaperTask.createdAt, updatedAt: mockPaperTask.updatedAt }],
  projects: [{ id: 64, name: 'PaperAgent V2 产品重构', rootType: 'UPLOAD', indexVersion: 'V64', createdAt: CREATED_AT, updatedAt: UPDATED_AT }],
  usage: [
    { id: 801, feature: 'PROJECT_AGENT', promptTokens: 82_400, completionTokens: 31_800, totalTokens: 114_200, createdAt: '2026-07-31T10:10:00Z' },
    { id: 802, feature: 'PAPER_POLISH', promptTokens: 186_500, completionTokens: 72_100, totalTokens: 258_600, createdAt: '2026-07-31T09:28:00Z' },
  ],
};

export const mockAdminInvites: AdminInviteCode[] = [
  { id: 901, code: 'YB-RSCH-ABCD-EFGH-JKLM', maxUses: 20, usedCount: 8, remainingUses: 12, enabled: true, status: 'AVAILABLE', createdAt: '2026-06-01T02:00:00Z' },
  { id: 902, code: 'YB-PAPR-DEMN-JKLM-NPQR', maxUses: 50, usedCount: 50, remainingUses: 0, enabled: true, status: 'EXHAUSTED', createdAt: '2026-06-15T02:00:00Z' },
];
