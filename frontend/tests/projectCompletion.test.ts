import { describe, expect, it } from 'vitest';

import type { AgentPlanResponse, SendMessageResponse } from '../src/api/agent';
import {
  isControlledProjectPartial,
  projectPlanDisplayStatus,
  projectPlanExecutionOutcome,
  projectPlanFinalAnswer,
  projectPlanLifecycle,
  withoutInternalProjectEvidenceRefs,
} from '../src/utils/projectCompletion';

function response(overrides: Partial<SendMessageResponse> = {}): SendMessageResponse {
  return {
    success: false,
    assistantContent: 'Governed completion status: PARTIAL',
    steps: 3,
    errorMessage: null,
    navigationUrl: null,
    messages: [],
    debug: null,
    projectEvidence: [],
    completionStatus: 'PARTIAL',
    stopReason: 'PLAN_PARTIAL',
    outcome: 'PARTIAL',
    ...overrides,
  };
}

describe('Project controlled PARTIAL response', () => {
  it.each([
    'TOOL_CALL_BUDGET_EXHAUSTED',
    'MAX_STEPS_BUDGET_EXHAUSTED',
    'MODEL_OUTPUT_TRUNCATED',
    'PLAN_PARTIAL',
  ])('accepts governed partial content for %s', (stopReason) => {
    expect(isControlledProjectPartial(response({ stopReason }))).toBe(true);
  });

  it('rejects ungoverned, empty, or unrelated failed responses', () => {
    expect(isControlledProjectPartial(response({ completionStatus: 'FAILED' }))).toBe(false);
    expect(isControlledProjectPartial(response({ outcome: 'FAILURE' }))).toBe(false);
    expect(isControlledProjectPartial(response({ stopReason: 'RUNTIME_FAILED' }))).toBe(false);
    expect(isControlledProjectPartial(response({ assistantContent: '   ' }))).toBe(false);
  });
});

describe('Project internal Evidence reference presentation', () => {
  it('removes embedded and standalone markers while preserving surrounding step text', () => {
    const content = [
      'Cross-material analysis result',
      '[projectEvidenceRefs=trusted-tool:42:paper.tex:hash:call-1]',
      'Q1: semantic consistency remains unresolved.',
      'Inline [projectEvidenceRefs=trusted-tool:43:code.py:hash:call-2] marker removed.',
      '[projectEvidenceRefs=trusted-tool:44:paper.tex:hash:call-3]',
    ].join('\n');

    expect(withoutInternalProjectEvidenceRefs(content))
      .toBe('Cross-material analysis result\nQ1: semantic consistency remains unresolved.\nInline  marker removed.');
  });
});

describe('Project Plan server-owned terminal projection', () => {
  it('keeps lifecycle COMPLETED separate from execution outcome PARTIAL', () => {
    const plan: AgentPlanResponse = {
      id: 19,
      sessionId: 11,
      goal: 'cross material analysis',
      summary: 'summary',
      status: 'COMPLETED',
      ragDisabled: true,
      skillId: null,
      errorMessage: null,
      createdAt: '2026-07-18T00:00:00',
      updatedAt: '2026-07-18T00:01:00',
      startedAt: '2026-07-18T00:00:01',
      finishedAt: '2026-07-18T00:01:00',
      steps: [],
      executionOutcome: 'PARTIAL',
      finalAnswer: 'bounded answer\n[projectEvidenceRefs=internal]',
    };

    expect(projectPlanLifecycle(plan)).toBe('COMPLETED');
    expect(projectPlanExecutionOutcome(plan)).toBe('PARTIAL');
    expect(projectPlanFinalAnswer(plan)).toBe('bounded answer');
  });

  it('does not promote a completed intermediate step when the server has no canonical final answer', () => {
    const plan: AgentPlanResponse = {
      id: 39,
      sessionId: 11,
      goal: 'cross material analysis',
      summary: 'summary',
      status: 'FAILED',
      ragDisabled: true,
      skillId: null,
      errorMessage: 'code step failed',
      createdAt: '2026-07-18T00:00:00',
      updatedAt: '2026-07-18T00:01:00',
      startedAt: '2026-07-18T00:00:01',
      finishedAt: '2026-07-18T00:01:00',
      steps: [{
        id: 1,
        stepKey: 'paper',
        sortOrder: 1,
        title: 'Paper analysis',
        description: 'Read paper',
        type: 'ANALYSIS',
        dependencies: [],
        allowedTools: ['project_read_file'],
        successCriteria: 'done',
        status: 'COMPLETED',
        attemptCount: 1,
        result: 'paper intermediate result',
        errorMessage: null,
        startedAt: null,
        finishedAt: null,
      }],
      executionOutcome: 'PARTIAL',
      finalAnswer: null,
    };

    expect(projectPlanFinalAnswer(plan)).toBe('');
  });

  it('renders timeout from execution outcome while lifecycle remains FAILED', () => {
    const plan: AgentPlanResponse = {
      id: 49,
      sessionId: 11,
      goal: 'run in sandbox',
      summary: 'summary',
      status: 'FAILED',
      ragDisabled: true,
      skillId: null,
      errorMessage: 'sandbox timed out',
      createdAt: '2026-07-22T00:00:00',
      updatedAt: '2026-07-22T00:01:00',
      startedAt: '2026-07-22T00:00:01',
      finishedAt: '2026-07-22T00:01:00',
      steps: [],
      executionOutcome: 'TIMED_OUT',
      finalAnswer: null,
    };

    expect(projectPlanLifecycle(plan)).toBe('FAILED');
    expect(projectPlanExecutionOutcome(plan)).toBe('TIMED_OUT');
    expect(projectPlanDisplayStatus(plan, false)).toBe('已超时');
    expect(projectPlanDisplayStatus(plan, true)).toBe('Timed out');
  });
});
