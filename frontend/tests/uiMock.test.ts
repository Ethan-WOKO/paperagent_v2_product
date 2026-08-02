import { describe, expect, it } from 'vitest';
import { resolveUiMockResponse } from '../src/mocks/httpAdapter';

describe('frontend UI mock boundary', () => {
  it('keeps administrator and ordinary-user navigation authority distinct', () => {
    const admin = resolveUiMockResponse(
      { method: 'GET', path: '/users/me' },
      { role: 'admin', projectState: 'complete' },
    );
    const user = resolveUiMockResponse(
      { method: 'GET', path: '/users/me' },
      { role: 'user', projectState: 'complete' },
    );

    expect(admin.data).toMatchObject({ username: 'yifeng', role: 'ADMIN' });
    expect(user.data).toMatchObject({ role: 'USER', demo: false });
  });

  it('provides deterministic project lifecycle states', () => {
    const completed = resolveUiMockResponse(
      { method: 'GET', path: '/agent/sessions/6401/v2/turns' },
      { role: 'admin', projectState: 'complete' },
    );
    const running = resolveUiMockResponse(
      { method: 'GET', path: '/agent/sessions/6401/v2/turns' },
      { role: 'user', projectState: 'running' },
    );
    const empty = resolveUiMockResponse(
      { method: 'GET', path: '/agent/sessions/6401/v2/turns' },
      { role: 'user', projectState: 'empty' },
    );

    expect(completed.data).toMatchObject([{ status: 'SUCCEEDED' }]);
    expect(running.data).toMatchObject([{ status: 'RUNNING' }]);
    expect(empty.data).toEqual([]);
  });

  it('provides ordinary workspace sessions and their message history separately', () => {
    const sessions = resolveUiMockResponse(
      { method: 'GET', path: '/agent/sessions' },
      { role: 'user', projectState: 'complete' },
    );
    const messages = resolveUiMockResponse(
      { method: 'GET', path: '/agent/sessions/6201/messages' },
      { role: 'user', projectState: 'complete' },
    );

    expect(sessions.data).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: 6201, scope: 'WORKSPACE', projectId: null }),
    ]));
    expect(messages.data).toEqual([
      expect.objectContaining({ role: 'user' }),
      expect.objectContaining({ role: 'process' }),
      expect.objectContaining({ role: 'assistant' }),
    ]);
  });

  it('advertises the same V2 capability document consumed by the production page', () => {
    const result = resolveUiMockResponse(
      { method: 'GET', path: '/agent/sessions/v2/capabilities' },
      { role: 'user', projectState: 'complete' },
    );

    expect(result.data).toMatchObject({
      formatVersion: 1,
      enabled: true,
      capabilities: expect.arrayContaining(['agent.turn', 'literature.search']),
    });
  });

  it('provides a completed paper task with real review surfaces', () => {
    const task = resolveUiMockResponse(
      { method: 'GET', path: '/paper/tasks/9001' },
      { role: 'admin', projectState: 'complete' },
    );
    const suggestions = resolveUiMockResponse(
      { method: 'GET', path: '/paper/tasks/9001/suggestions' },
      { role: 'admin', projectState: 'complete' },
    );
    const artifacts = resolveUiMockResponse(
      { method: 'GET', path: '/paper/tasks/9001/artifacts' },
      { role: 'admin', projectState: 'complete' },
    );

    expect(task.data).toMatchObject({ id: 9001, status: 'COMPLETED' });
    expect(suggestions.data).toHaveLength(3);
    expect(suggestions.data).toEqual(expect.arrayContaining([
      expect.objectContaining({ evidenceCount: 2 }),
    ]));
    expect(artifacts.data).toEqual(expect.arrayContaining([
      expect.objectContaining({ type: 'polished_tex' }),
      expect.objectContaining({ type: 'review_report' }),
    ]));
  });

  it('provides knowledge documents, preview text, and retrieval results', () => {
    const documents = resolveUiMockResponse(
      { method: 'GET', path: '/kb/documents' },
      { role: 'user', projectState: 'complete' },
    );
    const preview = resolveUiMockResponse(
      { method: 'GET', path: '/kb/documents/301/preview' },
      { role: 'user', projectState: 'complete' },
    );
    const results = resolveUiMockResponse(
      { method: 'POST', path: '/search' },
      { role: 'user', projectState: 'complete' },
    );

    expect(documents.data).toEqual(expect.arrayContaining([
      expect.objectContaining({ status: 'READY', isPublic: false }),
      expect.objectContaining({ status: 'PROCESSING' }),
      expect.objectContaining({ status: 'FAILED' }),
    ]));
    expect(preview.data).toMatchObject({ id: 301, truncated: true, totalChunks: 36 });
    expect(results.data).toHaveLength(3);
    expect(results.data).toEqual(expect.arrayContaining([
      expect.objectContaining({ score: 0.9142, isPublic: false }),
      expect.objectContaining({ isPublic: true }),
    ]));
  });

  it('fails closed for endpoints without an explicit fixture', () => {
    const result = resolveUiMockResponse(
      { method: 'GET', path: '/future-feature' },
      { role: 'admin', projectState: 'complete' },
    );
    expect(result).toMatchObject({
      status: 501,
      data: { code: 'UI_MOCK_NOT_IMPLEMENTED' },
    });
  });
});
