import { readFileSync } from 'node:fs';
import { describe, expect, it, vi } from 'vitest';

import type {
  V2NaturalLanguageTurnHistoryItem,
  V2NaturalLanguageTurnResponse,
} from '@/api/agent';
import {
  isCurrentV2NaturalLanguageRequest,
  isV2CandidateApplied,
  isV2NaturalLanguageTerminal,
  newV2NaturalLanguageClientRequestId,
  normalizeV2NaturalLanguageRequest,
  pollV2NaturalLanguageTurn,
  startThenPollV2NaturalLanguageTurn,
  v2NaturalLanguageStatusLabel,
  v2NaturalLanguageStepStatusLabel,
  V2NaturalLanguageTurnNotCreatedError,
} from '../v2NaturalLanguageTurn';

function outcome(
  status: V2NaturalLanguageTurnResponse['status'],
  overrides: Partial<V2NaturalLanguageTurnResponse> = {},
): V2NaturalLanguageTurnResponse {
  return {
    status,
    route: 'PERSISTENT_PLAN_EXECUTE',
    planId: 'plan-1',
    projectVersion: 'version-1',
    steps: [],
    finalText: null,
    candidateArtifactId: null,
    outputPaths: [],
    errorCode: null,
    ...overrides,
  };
}

const intakeAck = {
  sessionId: 7,
  turnId: 11,
  userMessageId: 21,
  assistantMessageId: null,
  clientRequestId: 'v2-turn-fixed',
  route: 'PERSISTENT_PLAN_EXECUTE' as const,
  answer: null,
  planId: 'plan-1',
  replayed: false,
};

describe('V2 自然语言请求', () => {
  it('生成稳定请求编号并规范化单一自然语言输入', () => {
    const clientRequestId = newV2NaturalLanguageClientRequestId(() => 'fixed');
    expect(clientRequestId).toBe('v2-turn-fixed');
    expect(normalizeV2NaturalLanguageRequest('  读取   README 并总结  ', clientRequestId)).toEqual({
      content: '读取   README 并总结',
      clientRequestId,
    });
    expect(() => normalizeV2NaturalLanguageRequest(' ', clientRequestId)).toThrow('content-required');
  });

  it('RUNNING 时用同一请求编号续跑，之后读取到终态', async () => {
    const start = vi.fn(async () => intakeAck);
    const resume = vi.fn(async () => intakeAck);
    const states = [outcome('RUNNING'), outcome('SUCCEEDED', { finalText: '完成' })];
    const read = vi.fn(async () => states.shift()!);
    const result = await startThenPollV2NaturalLanguageTurn(start, read, {
      intervalMs: 1_000,
      sleep: async () => undefined,
      now: () => 0,
      resume,
    });
    expect(result.status).toBe('SUCCEEDED');
    expect(start).toHaveBeenCalledTimes(1);
    expect(resume).toHaveBeenCalledTimes(1);
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('不会把 POST intake ack 当作执行结果', async () => {
    const start = vi.fn(async () => ({ ...intakeAck, answer: 'ack 不是最终答案' }));
    const read = vi.fn(async () => outcome('WAITING_CONFIRMATION', {
      candidateArtifactId: 9_223,
      outputPaths: ['src/main/java/Sort.java'],
      steps: [{
        index: 1,
        title: '修复并验证',
        status: 'SUPERSEDED_BY_REPLAN',
        detail: '已改用新的修复步骤',
      }],
    }));
    const result = await startThenPollV2NaturalLanguageTurn(start, read);
    expect(result.status).toBe('WAITING_CONFIRMATION');
    expect(result.candidateArtifactId).toBe(9_223);
    expect(result.outputPaths).toEqual(['src/main/java/Sort.java']);
    expect(result.steps[0]?.status).toBe('SUPERSEDED_BY_REPLAN');
    expect(start).toHaveBeenCalledTimes(1);
    expect(read).toHaveBeenCalledTimes(1);
  });

  it('DIRECT ack 直接显示答案且不会调用 GET', async () => {
    const start = vi.fn(async () => ({
      ...intakeAck,
      route: 'DIRECT' as const,
      answer: '这是直接回答。',
      planId: null,
      assistantMessageId: 22,
    }));
    const read = vi.fn(async () => outcome('FAILED'));
    const result = await startThenPollV2NaturalLanguageTurn(start, read);
    expect(result).toEqual({
      status: 'SUCCEEDED',
      route: 'DIRECT',
      planId: null,
      projectVersion: null,
      steps: [],
      finalText: '这是直接回答。',
      candidateArtifactId: null,
      outputPaths: [],
      errorCode: null,
    });
    expect(start).toHaveBeenCalledTimes(1);
    expect(read).not.toHaveBeenCalled();
  });

  it('DIRECT ack 没有答案时 fail-closed 且不会调用 GET', async () => {
    const start = vi.fn(async () => ({
      ...intakeAck,
      route: 'DIRECT' as const,
      answer: ' ',
      planId: null,
    }));
    const read = vi.fn(async () => outcome('SUCCEEDED'));
    await expect(startThenPollV2NaturalLanguageTurn(start, read))
      .rejects.toThrow('v2-direct-answer-required');
    expect(read).not.toHaveBeenCalled();
  });

  it('POST 响应丢失后使用同一请求编号恢复，GET 确认不存在才结束', async () => {
    const start = vi.fn(async () => {
      throw new Error('response-lost');
    });
    const read = vi.fn(async () => {
      throw { response: { status: 404 } };
    });
    await expect(startThenPollV2NaturalLanguageTurn(start, read))
      .rejects.toBeInstanceOf(V2NaturalLanguageTurnNotCreatedError);
    expect(start).toHaveBeenCalledTimes(1);
    expect(read).toHaveBeenCalledTimes(1);
  });

  it('会话或项目变化会使旧响应失效，Abort 会停止轮询', async () => {
    const identity = { projectId: 3, sessionId: 7, clientRequestId: 'id', sequence: 1 };
    expect(isCurrentV2NaturalLanguageRequest(identity, { ...identity, sessionId: 8 })).toBe(false);
    expect(isCurrentV2NaturalLanguageRequest(identity, { ...identity, projectId: 4 })).toBe(false);
    const controller = new AbortController();
    const read = vi.fn(async () => outcome('RUNNING'));
    await expect(pollV2NaturalLanguageTurn(read, {
      intervalMs: 1_000,
      signal: controller.signal,
      sleep: async () => controller.abort(),
      now: () => 0,
    })).rejects.toMatchObject({ name: 'AbortError' });
    expect(read).toHaveBeenCalledTimes(1);
  });

  it('完整映射五种步骤状态和三种终态', () => {
    expect([
      'PENDING',
      'RUNNING',
      'SUCCEEDED',
      'FAILED',
      'SUPERSEDED_BY_REPLAN',
    ].map((status) => v2NaturalLanguageStepStatusLabel(
      status as Parameters<typeof v2NaturalLanguageStepStatusLabel>[0],
    ))).toEqual([
      '等待执行',
      '正在执行',
      '已完成',
      '执行失败',
      '已被新计划替代',
    ]);
    expect(v2NaturalLanguageStatusLabel('SUCCEEDED')).toBe('已完成');
    expect(v2NaturalLanguageStatusLabel('FAILED')).toBe('执行失败');
    expect(v2NaturalLanguageStatusLabel('RUNNING')).toBe('正在执行');
    expect(v2NaturalLanguageStatusLabel('WAITING_CONFIRMATION')).toBe('等待你的确认');
    expect(isV2NaturalLanguageTerminal(outcome('WAITING_CONFIRMATION'))).toBe(true);
    expect(isV2NaturalLanguageTerminal(outcome('RUNNING'))).toBe(false);
  });

  it('只有确认验证已应用且绑定 revision 时才视为已创建新版本', () => {
    const applied = {
      confirmationValidation: {
        status: 'SUCCEEDED',
        decisionStatus: 'APPLIED',
        applicationOperationId: 101,
        appliedRevisionId: 29,
        appliedProjectVersion: 'f'.repeat(64),
      },
    } as Pick<V2NaturalLanguageTurnHistoryItem, 'confirmationValidation'>;
    expect(isV2CandidateApplied(applied)).toBe(true);
    expect(isV2CandidateApplied({
      confirmationValidation: {
        ...applied.confirmationValidation!,
        decisionStatus: 'PENDING',
      },
    })).toBe(false);
    expect(isV2CandidateApplied({
      confirmationValidation: {
        ...applied.confirmationValidation!,
        appliedRevisionId: null,
      },
    })).toBe(false);
  });
});

describe('V2 自然语言 API 与页面接入', () => {
  const api = readFileSync(new URL('../../api/agent.ts', import.meta.url), 'utf8');
  const page = readFileSync(new URL('../../views/ProjectPreviewPage.vue', import.meta.url), 'utf8');

  it('POST 续跑和 GET 使用同一个 session 与 clientRequestId 契约', () => {
    expect(api).toContain('export interface V2NaturalLanguageTurnStartResponse');
    expect(api).toContain('http.post<V2NaturalLanguageTurnStartResponse>');
    expect(api).toContain('http.get<V2NaturalLanguageTurnResponse>');
    expect(api).toContain('http.get<V2NaturalLanguageTurnHistoryItem[]>');
    expect(api).toContain('`/agent/sessions/${sessionId}/v2/turns`');
    expect(api).toContain('`/agent/sessions/${sessionId}/v2/turns/${encodeURIComponent(clientRequestId)}`');
    expect(page).toContain('listV2NaturalLanguageTurns(sessionId, 50)');
    expect(page).toContain('await startV2NaturalLanguageTurn(sessionId, request, controller.signal)');
    expect(page).toContain('await getV2NaturalLanguageTurn(sessionId, clientRequestId, controller.signal)');
    expect(page).toContain('resume: async () =>');
    expect(page).toContain('<span>{{ step.index }}</span>');
  });

  it('V2 是唯一可见的中文单输入流程', () => {
    expect(page).toContain('class="v2-conversation__composer"');
    expect(page).toContain('@click="sendV2NaturalLanguageTurn"');
    expect(page).not.toContain('class="project-composer"');
    expect(page).not.toContain("@click=\"setAgentMode('v1')\"");
    expect(page).not.toContain('aria-label="选择 V2 任务类型"');
    expect(page).not.toContain('@click="startProjectAnalysis"');
    expect(page).not.toContain('@click="startProjectCandidate"');
  });

  it('展示一一对应的结果、折叠过程、输出位置并复用 Candidate 检查入口', () => {
    expect(page).toContain('v-for="task in v2TurnHistory"');
    expect(page).not.toContain('v2-task-card__role');
    expect(page).toContain('class="v2-conversation__process"');
    expect(page).toContain(':open="task.status === \'PLANNING\' || task.status === \'RUNNING\'"');
    expect(page).toContain("task.status === 'WAITING_CONFIRMATION' ? '等待确认' : '已处理'");
    expect(page).toContain('结果：{{ step.detail }}');
    expect(page).toContain('生成内容位置');
    expect(page).toContain('原项目尚未修改');
    expect(page).toContain('已自动保存，已创建项目版本');
    expect(page).toContain("task.confirmationValidation?.appliedRevisionId");
    expect(page).toContain('查看修改');
    expect(page).toContain('@click="openV2CandidateReview(task.candidateArtifactId)"');
  });

  it('持久化待处理请求并在切换和卸载时中止旧轮询', () => {
    expect(page).toContain('V2_NATURAL_LANGUAGE_STORAGE_KEY');
    expect(page).toContain('storeV2NaturalLanguageRequest(projectId, sessionId, clientRequestId, question)');
    expect(page).toContain('recoverV2NaturalLanguageTurn(activeProjectId.value, sessionId)');
    expect(page).toContain('resetV2NaturalLanguageView();');
    expect(page).toContain('onUnmounted(() =>');
    expect(page).toContain('stopV2NaturalLanguagePolling();');
  });
});
