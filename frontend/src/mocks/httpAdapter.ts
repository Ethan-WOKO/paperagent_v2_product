import { AxiosError, AxiosHeaders, type AxiosAdapter, type AxiosResponse } from 'axios';
import type { V2NaturalLanguageTurnStartResponse } from '@/api/agent';
import {
  mockManifest,
  mockChatMessages,
  mockChatSessions,
  mockKnowledgeDocuments,
  mockKnowledgePreview,
  mockKnowledgeSearchResults,
  mockMemories,
  mockSettings,
  mockSkills,
  mockAdminUsers,
  mockAdminDetail,
  mockAdminInvites,
  mockPaperAnalysis,
  mockPaperArtifacts,
  mockPaperClarifications,
  mockPaperEvents,
  mockPaperHistory,
  mockPaperSections,
  mockPaperSuggestions,
  mockPaperTask,
  mockPaperTaskStatus,
  mockProjectFile,
  mockProjects,
  mockProjectSearch,
  mockRevisions,
  mockSessions,
  mockTurn,
  mockTurnHistory,
  mockUsers,
} from './fixtures';
import type { UiMockScenario } from './scenario';

interface MockRequest {
  method: string;
  path: string;
  params?: Record<string, unknown>;
}

interface MockResult {
  status: number;
  data: unknown;
}

function normalizePath(url = '') {
  return new URL(url, 'http://paperagent.local').pathname.replace(/^\/api\/v1/, '');
}

export function resolveUiMockResponse(request: MockRequest, scenario: UiMockScenario): MockResult {
  const method = request.method.toUpperCase();
  const path = normalizePath(request.path);

  if (method === 'GET' && path === '/users/me') return { status: 200, data: mockUsers[scenario.role] };
  if (method === 'POST' && path === '/auth/login') {
    return { status: 200, data: { tokenType: 'Bearer', accessToken: 'ui-mock', refreshToken: 'ui-mock' } };
  }
  if (method === 'GET' && path === '/agent/sessions/v2/capabilities') {
    return {
      status: 200,
      data: { formatVersion: 1, enabled: true, capabilities: ['agent.turn', 'literature.search', 'project.read-analysis', 'project.candidate'] },
    };
  }
  if (method === 'GET' && path === '/agent/sessions') return { status: 200, data: mockChatSessions };
  const chatMessagesMatch = /^\/agent\/sessions\/(\d+)\/messages$/.exec(path);
  if (method === 'GET' && chatMessagesMatch) {
    return { status: 200, data: mockChatMessages[Number(chatMessagesMatch[1])] || [] };
  }
  if (method === 'GET' && path === '/projects') return { status: 200, data: mockProjects };
  if (method === 'GET' && path === '/projects/64/manifest') return { status: 200, data: mockManifest };
  if (method === 'GET' && path === '/projects/64/agent/sessions') return { status: 200, data: mockSessions };
  if (method === 'GET' && path === '/projects/64/files/read') {
    return { status: 200, data: mockProjectFile(String(request.params?.path || 'README.md')) };
  }
  if (method === 'GET' && path === '/projects/64/search') {
    return { status: 200, data: mockProjectSearch(String(request.params?.query || '')) };
  }
  if (method === 'GET' && path === '/projects/64/revisions') return { status: 200, data: mockRevisions };
  if (method === 'GET' && path === '/artifacts') return { status: 200, data: [] };
  if (method === 'GET' && path === '/agent/sessions/6401/v2/turns') {
    return { status: 200, data: mockTurnHistory(scenario.projectState) };
  }
  if (method === 'GET' && path.startsWith('/agent/sessions/6401/v2/turns/')) {
    return { status: 200, data: mockTurn(scenario.projectState) };
  }
  if (method === 'POST' && path === '/agent/sessions/6401/v2/turns') {
    const response: V2NaturalLanguageTurnStartResponse = {
      sessionId: 6401,
      turnId: 7401,
      userMessageId: 8401,
      assistantMessageId: scenario.projectState === 'complete' ? 8402 : null,
      clientRequestId: 'ui-mock-start',
      route: 'PERSISTENT_PLAN_EXECUTE',
      answer: scenario.projectState === 'complete' ? mockTurn('complete').finalText : null,
      planId: 'product-plan.ui-mock',
      replayed: false,
    };
    return { status: 200, data: response };
  }
  if (method === 'GET' && path === '/paper/tasks') return { status: 200, data: mockPaperHistory };
  if (method === 'GET' && path === '/paper/tasks/9001') return { status: 200, data: mockPaperTask };
  if (method === 'GET' && path === '/paper/tasks/9001/clarifications') return { status: 200, data: mockPaperClarifications };
  if (method === 'GET' && path === '/paper/tasks/9001/sections') return { status: 200, data: mockPaperSections };
  if (method === 'GET' && path === '/paper/tasks/9001/suggestions') return { status: 200, data: mockPaperSuggestions };
  if (method === 'GET' && path === '/paper/tasks/9001/artifacts') return { status: 200, data: mockPaperArtifacts };
  if (method === 'GET' && path === '/paper/tasks/9001/analysis') return { status: 200, data: mockPaperAnalysis };
  if (method === 'GET' && path === '/tasks/9001/status') return { status: 200, data: mockPaperTaskStatus };
  if (method === 'GET' && path === '/agent/tasks/paper_polish/9001/events') return { status: 200, data: mockPaperEvents };
  if (method === 'GET' && path === '/kb/documents') return { status: 200, data: mockKnowledgeDocuments };
  if (method === 'GET' && path === '/kb/documents/301/preview') return { status: 200, data: mockKnowledgePreview };
  if (method === 'POST' && path === '/search') return { status: 200, data: mockKnowledgeSearchResults };
  if (method === 'GET' && path === '/settings/memory/distillation') {
    return {
      status: 200,
      data: {
        available: true,
        autoEnabled: false,
        intervalSeconds: 86400,
        lastProcessedMessageId: 8400,
        nextRunAt: null,
        lastSuccessAt: '2026-08-26T08:30:00Z',
        latestJob: {
          id: 91,
          triggerType: 'MANUAL',
          status: 'SUCCEEDED',
          fromMessageId: 8300,
          throughMessageId: 8400,
          messageCount: 18,
          candidateCount: 3,
          createdMemoryCount: 2,
          attemptCount: 1,
          errorCode: null,
          errorMessage: null,
          startedAt: '2026-08-26T08:29:00Z',
          finishedAt: '2026-08-26T08:30:00Z',
          createdAt: '2026-08-26T08:29:00Z',
          updatedAt: '2026-08-26T08:30:00Z',
        },
      },
    };
  }
  if (method === 'PUT' && path === '/settings/memory/distillation') {
    return {
      status: 200,
      data: {
        available: true, autoEnabled: true, intervalSeconds: 86400,
        lastProcessedMessageId: 8400, nextRunAt: '2026-08-27T08:30:00Z',
        lastSuccessAt: '2026-08-26T08:30:00Z', latestJob: null,
      },
    };
  }
  if (method === 'POST' && path === '/settings/memory/distillation/jobs') {
    return {
      status: 202,
      data: {
        id: 92, triggerType: 'MANUAL', status: 'PENDING', fromMessageId: 8400,
        throughMessageId: 8450, messageCount: 8, candidateCount: 0, createdMemoryCount: 0,
        attemptCount: 0, errorCode: null, errorMessage: null, startedAt: null, finishedAt: null,
        createdAt: '2026-08-27T08:30:00Z', updatedAt: '2026-08-27T08:30:00Z',
      },
    };
  }
  if (method === 'GET' && /^\/settings\/memory\/distillation\/jobs\/\d+$/.test(path)) {
    return {
      status: 200,
      data: {
        id: 92, triggerType: 'MANUAL', status: 'SUCCEEDED', fromMessageId: 8400,
        throughMessageId: 8450, messageCount: 8, candidateCount: 2, createdMemoryCount: 2,
        attemptCount: 1, errorCode: null, errorMessage: null,
        startedAt: '2026-08-27T08:30:01Z', finishedAt: '2026-08-27T08:30:03Z',
        createdAt: '2026-08-27T08:30:00Z', updatedAt: '2026-08-27T08:30:03Z',
      },
    };
  }
  if (method === 'GET' && path === '/settings/memory') return { status: 200, data: mockMemories };
  if (method === 'GET' && path === '/settings') return { status: 200, data: mockSettings };
  if (method === 'GET' && path === '/skills') return { status: 200, data: mockSkills };
  if (method === 'GET' && path === '/admin/users') return { status: 200, data: mockAdminUsers };
  if (method === 'GET' && path === '/admin/users/1') return { status: 200, data: mockAdminDetail };
  if (method === 'DELETE' && /^\/admin\/users\/\d+$/.test(path)) return { status: 204, data: null };
  if (method === 'GET' && path === '/admin/invite-codes') return { status: 200, data: mockAdminInvites };
  if (method === 'POST' && path === '/admin/invite-codes/generate') {
    return { status: 200, data: { code: 'YB-ABCD-EFGH-JKLM-NPQR' } };
  }
  if (method === 'POST' && path === '/admin/invite-codes') return { status: 201, data: mockAdminInvites[0] };
  if (method === 'DELETE' && /^\/admin\/invite-codes\/\d+$/.test(path)) return { status: 204, data: null };

  return {
    status: 501,
    data: { code: 'UI_MOCK_NOT_IMPLEMENTED', message: `${method} ${path} is not mocked yet.`, fieldErrors: {} },
  };
}

export function createUiMockAdapter(scenario: UiMockScenario): AxiosAdapter {
  return async (config) => {
    if (config.signal?.aborted) throw new AxiosError('Request canceled', AxiosError.ERR_CANCELED, config);
    await new Promise((resolve) => window.setTimeout(resolve, 40));
    const result = resolveUiMockResponse({
      method: config.method || 'GET',
      path: config.url || '',
      params: config.params as Record<string, unknown> | undefined,
    }, scenario);
    const response: AxiosResponse = {
      data: result.data,
      status: result.status,
      statusText: result.status < 400 ? 'OK' : 'Mock Error',
      headers: new AxiosHeaders(),
      config,
      request: { uiMock: true },
    };
    if (result.status >= 400) {
      throw new AxiosError(
        `UI mock request failed with status ${result.status}`,
        AxiosError.ERR_BAD_RESPONSE,
        config,
        response.request,
        response,
      );
    }
    return response;
  };
}
