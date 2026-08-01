import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import type { AgentPlanResponse, SynthesisEvidence } from '../src/api/agent';
import { isInternalRuntimeFailureText, projectAssistantPresentation } from '../src/utils/projectCompletion';
import {
  answerStatusPresentation,
  evidenceDisplayGroup,
  executionOutcomePresentation,
  groupSynthesisEvidence,
  planUserStatusPresentation,
  taskOutcomePresentation,
} from '../src/utils/projectResultPresentation';

function plan(overrides: Partial<AgentPlanResponse> = {}): AgentPlanResponse {
  return {
    id: 22,
    sessionId: 220,
    goal: 'Run a bounded task',
    summary: 'Bounded task',
    status: 'COMPLETED',
    ragDisabled: true,
    skillId: null,
    errorMessage: null,
    createdAt: '2026-07-22T00:00:00Z',
    updatedAt: '2026-07-22T00:00:05Z',
    startedAt: '2026-07-22T00:00:01Z',
    finishedAt: '2026-07-22T00:00:05Z',
    steps: [],
    executionOutcome: 'SUCCESS',
    taskOutcome: 'SUCCESS',
    answerStatus: 'SUPPORTED',
    finalAnswer: 'The single canonical answer.',
    ...overrides,
  };
}

function evidence(overrides: Partial<SynthesisEvidence>): SynthesisEvidence {
  return {
    id: 'evidence-1',
    category: 'UNVERIFIED_INPUT',
    status: 'UNVERIFIED',
    statement: 'A bounded statement',
    basisRefs: [],
    projectVersion: null,
    path: null,
    hash: null,
    startLine: null,
    endLine: null,
    sourceType: null,
    externalAccess: 'UNKNOWN',
    executionFact: null,
    ...overrides,
  };
}

describe('Project result three-layer presentation', () => {
  it('does not turn successful execution with a supported partial task into a validation failure', () => {
    expect(executionOutcomePresentation('SUCCESS')).toEqual({
      key: 'project.result.execution.success',
      tone: 'success',
    });
    expect(taskOutcomePresentation('PARTIAL')).toEqual({
      key: 'project.result.task.partial',
      tone: 'warning',
    });
    expect(answerStatusPresentation('SUPPORTED')).toEqual({
      key: 'project.result.answer.supported',
      tone: 'success',
    });
    expect(planUserStatusPresentation(plan({ taskOutcome: 'PARTIAL' }), false).key)
      .toBe('project.result.status.partial');
  });

  it.each([
    ['FAILED', 'FAILED', 'project.result.status.failed', 'error'],
    ['TIMED_OUT', 'TIMED_OUT', 'project.result.status.timedOut', 'error'],
    ['CANCELLED', 'CANCELLED', 'project.result.status.cancelled', 'warning'],
    ['UNAVAILABLE', 'FAILED', 'project.result.status.failed', 'error'],
  ] as const)('never presents terminal execution %s / task %s as success', (executionOutcome, taskOutcome, key, tone) => {
    const result = planUserStatusPresentation(plan({ status: executionOutcome === 'CANCELLED' ? 'CANCELLED' : 'FAILED', executionOutcome, taskOutcome }), false);
    expect(result).toEqual({ key, tone });
    expect(executionOutcomePresentation(executionOutcome).tone).not.toBe('success');
  });

  it('keeps running and waiting-for-confirmation as visible user action states', () => {
    expect(planUserStatusPresentation(plan({ status: 'RUNNING', taskOutcome: undefined }), false).key)
      .toBe('project.result.status.running');
    expect(planUserStatusPresentation(plan({ status: 'REVIEWING', taskOutcome: undefined }), true).key)
      .toBe('project.result.status.waitingConfirmation');
  });

  it.each([
    ['VERIFIED', 'project.result.answer.verified', 'success'],
    ['SUPPORTED', 'project.result.answer.supported', 'success'],
    ['INFERRED', 'project.result.answer.inferred', 'info'],
    ['UNVERIFIED', 'project.result.answer.unverified', 'warning'],
    ['CONFLICTING', 'project.result.answer.conflicting', 'error'],
    ['STALE', 'project.result.answer.stale', 'warning'],
  ] as const)('maps answer status %s independently', (status, key, tone) => {
    expect(answerStatusPresentation(status)).toEqual({ key, tone });
  });
});

describe('Project result Evidence grouping', () => {
  const cases: Array<[SynthesisEvidence, string]> = [
    [evidence({ id: 'exec', category: 'EXECUTION_FACT', status: 'VERIFIED' }), 'execution'],
    [evidence({ id: 'project', category: 'VERIFIED_PROJECT_EVIDENCE', status: 'VERIFIED' }), 'project'],
    [evidence({ id: 'opened', category: 'EXTERNAL_SOURCE', status: 'SUPPORTED', externalAccess: 'OPENED' }), 'openedExternal'],
    [evidence({ id: 'search', category: 'EXTERNAL_SOURCE', status: 'UNVERIFIED', externalAccess: 'SEARCH_SUMMARY' }), 'unverifiedExternal'],
    [evidence({ id: 'unknown', category: 'EXTERNAL_SOURCE', status: 'UNVERIFIED', externalAccess: 'UNKNOWN' }), 'unverifiedExternal'],
    [evidence({ id: 'inference', category: 'INFERENCE', status: 'INFERRED' }), 'inference'],
    [evidence({ id: 'unverified', category: 'UNVERIFIED_INPUT', status: 'UNVERIFIED' }), 'unverified'],
    [evidence({ id: 'conflict', category: 'VERIFIED_PROJECT_EVIDENCE', status: 'CONFLICTING' }), 'conflicting'],
    [evidence({ id: 'stale', category: 'VERIFIED_PROJECT_EVIDENCE', status: 'STALE' }), 'stale'],
  ];

  it.each(cases)('classifies %s without a numeric confidence score', (item, expected) => {
    expect(evidenceDisplayGroup(item)).toBe(expected);
  });

  it('returns stable, explainable category counts', () => {
    const groups = groupSynthesisEvidence(cases.map(([item]) => item));
    expect(groups.map((group) => [group.group, group.evidence.length])).toEqual([
      ['execution', 1],
      ['project', 1],
      ['openedExternal', 1],
      ['unverifiedExternal', 2],
      ['inference', 1],
      ['unverified', 1],
      ['conflicting', 1],
      ['stale', 1],
    ]);
  });
});

describe('Project result page contract', () => {
  const source = readFileSync(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url), 'utf8');

  it('keeps execution details collapsed while confirmation and applied states stay visible', () => {
    expect(source).toContain('class="v2-conversation__process"');
    expect(source).not.toContain('class="v2-conversation__process" open');
    expect(source).toContain("task.status === 'WAITING_CONFIRMATION'");
    expect(source).toContain('v2TaskApplied(task)');
    expect(source).toContain('@click="openV2CandidateReview(task.candidateArtifactId)"');
  });

  it('keeps bounded Step results behind execution details', () => {
    expect(source).toContain('v-for="step in task.steps"');
    expect(source).toContain('v-if="step.detail"');
    expect(source).toContain('{{ step.detail }}');
    expect(source).not.toContain('task.executionReceipt.standardOutput');
    expect(source).not.toContain('task.executionReceipt.standardError');
  });

  it('keeps internal planner and domain codes out of assistant body text', () => {
    const raw = 'Planner failed [INVALID_PLAN]: DOMAIN_TOOL_NOT_EXECUTED; traceId=worker22';
    expect(isInternalRuntimeFailureText(raw)).toBe(true);
    expect(isInternalRuntimeFailureText('任务未能完成。请调整请求后重试。')).toBe(false);
    expect(projectAssistantPresentation(raw, '任务未能完成。')).toEqual({
      content: '任务未能完成。',
      technicalContent: raw,
    });
    expect(source).toContain("projectAssistantPresentation(rawContent, t('project.result.requestFailed'))");
  });

  it('does not mistake normal explanations of internal terms for failure envelopes', () => {
    const sandboxExplanation = 'SANDBOX_EXECUTE 是 Plan 步骤内的受控执行工具。';
    const domainQuestion = '请解释 DOMAIN_TOOL_NOT_EXECUTED 的含义。';
    expect(isInternalRuntimeFailureText(sandboxExplanation)).toBe(false);
    expect(isInternalRuntimeFailureText(domainQuestion)).toBe(false);
    expect(projectAssistantPresentation(sandboxExplanation, '任务未能完成。')).toEqual({
      content: sandboxExplanation,
      technicalContent: undefined,
    });
    expect(projectAssistantPresentation(domainQuestion, '任务未能完成。')).toEqual({
      content: domainQuestion,
      technicalContent: undefined,
    });
  });

  it('preserves code and log content containing internal terms', () => {
    const codeAndLog = [
      '```text',
      'DOMAIN_TOOL_NOT_EXECUTED is returned when a required tool did not run.',
      '2026-07-22 WARN SANDBOX_EXECUTE remained disabled for this example.',
      '```',
    ].join('\n');
    expect(isInternalRuntimeFailureText(codeAndLog)).toBe(false);
    expect(projectAssistantPresentation(codeAndLog, '任务未能完成。')).toEqual({
      content: codeAndLog,
      technicalContent: undefined,
    });
  });

  it('still folds structured planner failures into the technical record', () => {
    const raw = 'Planner failed [INVALID_PLAN]: DOMAIN_TOOL_NOT_EXECUTED; traceId=worker22';
    expect(isInternalRuntimeFailureText(raw)).toBe(true);
    expect(isInternalRuntimeFailureText('DOMAIN_TOOL_NOT_EXECUTED')).toBe(true);
    expect(isInternalRuntimeFailureText('traceId=worker22')).toBe(true);
    expect(isInternalRuntimeFailureText('Project Plan creation failed [traceId=worker22]: Planner failed [INVALID_PLAN]: no executable plan')).toBe(true);
    expect(projectAssistantPresentation(raw, '任务未能完成。')).toEqual({
      content: '任务未能完成。',
      technicalContent: raw,
    });
  });

  it('presents only the answer body while retaining authoritative metadata and limitations', () => {
    const wrapped = [
      '已验证的执行事实：',
      '- executionOutcome=SUCCESS, taskOutcome=SUCCESS, answerStatus=SUPPORTED',
      '- provider=e2b, status=SUCCEEDED, exitCode=0, timedOut=false',
      '',
      '受支持的解释与推理：',
      '程序输出为 WORKER22_JAVA_OK。',
      '',
      '限制与适用范围：',
      '- 成功 receipt 只验证本次执行。',
    ].join('\n');
    expect(projectAssistantPresentation(wrapped, '失败')).toEqual({
      content: '程序输出为 WORKER22_JAVA_OK。',
      technicalContent: [
        '已验证的执行事实：',
        '- executionOutcome=SUCCESS, taskOutcome=SUCCESS, answerStatus=SUPPORTED',
        '- provider=e2b, status=SUCCEEDED, exitCode=0, timedOut=false',
        '',
        '- 成功 receipt 只验证本次执行。',
      ].join('\n'),
    });
  });

  it('renders one persisted Agent result and never renders Plan finalAnswer as a second answer', () => {
    expect(source).toContain('class="v2-task-card__result"');
    expect(source).toContain(':content="task.finalText"');
    expect(source).toContain("task.status === 'FAILED'");
    expect(source).not.toContain('item.plan.finalAnswer');
  });
});
