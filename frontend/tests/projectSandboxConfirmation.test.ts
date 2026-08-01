import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import type { AgentPlanResponse } from '../src/api/agent';
import { requiresSandboxConfirmation, sandboxConfirmationStepCount } from '../src/utils/projectSandboxConfirmation';
import { isControlledProjectPartial, isSandboxConfirmationRequiredText, projectPlanFailureReason, withoutInternalRuntimeCodes } from '../src/utils/projectCompletion';

function plan(status: string, type = 'SANDBOX_EXECUTE', stepStatus = 'PENDING'): AgentPlanResponse {
  return {
    id: 66,
    sessionId: 113,
    goal: 'Run Java in the sandbox',
    summary: null,
    status,
    ragDisabled: true,
    skillId: null,
    errorMessage: null,
    createdAt: '2026-07-19T22:09:07+08:00',
    updatedAt: '2026-07-19T22:09:09+08:00',
    startedAt: null,
    finishedAt: null,
    executionOutcome: status,
    finalAnswer: null,
    steps: [{
      id: 189,
      stepKey: 'sandbox-run',
      sortOrder: 1,
      title: 'Execute Java file in sandbox',
      description: 'Run src/main/java/xhs_1111.java.',
      type,
      dependencies: [],
      allowedTools: ['sandbox_execute'],
      successCriteria: 'Return stdout.',
      status: stepStatus,
      attemptCount: 0,
      result: null,
      errorMessage: null,
      startedAt: null,
      finishedAt: null,
    }],
  };
}

describe('Project sandbox confirmation', () => {
  it('requires confirmation only for a pending sandbox step held in REVIEWING', () => {
    expect(requiresSandboxConfirmation(plan('REVIEWING'))).toBe(true);
    expect(sandboxConfirmationStepCount(plan('REVIEWING'))).toBe(1);
    expect(requiresSandboxConfirmation(plan('RUNNING'))).toBe(false);
    expect(requiresSandboxConfirmation(plan('REVIEWING', 'ANALYSIS'))).toBe(false);
    expect(requiresSandboxConfirmation(plan('REVIEWING', 'SANDBOX_EXECUTE', 'COMPLETED'))).toBe(false);
  });

  it('recognizes the persisted user-action placeholder without showing fixed English chat copy', () => {
    expect(isSandboxConfirmationRequiredText(
      'Plan execution is waiting for your confirmation or another required action.',
    )).toBe(true);
  });

  it('keeps the actionable confirmation card free of red internal failure strings', () => {
    const waiting = plan('REVIEWING');
    waiting.errorMessage = 'SANDBOX_CONFIRMATION_REQUIRED: DOMAIN_SANDBOX_POLICY: confirmation required';
    waiting.steps[0].errorMessage = 'DOMAIN_SANDBOX_POLICY: internal detail';

    expect(requiresSandboxConfirmation(waiting)).toBe(true);
    expect(projectPlanFailureReason(waiting)).toBe('');
    expect(isSandboxConfirmationRequiredText(waiting.errorMessage)).toBe(true);
    expect(withoutInternalRuntimeCodes(waiting.errorMessage)).toBe('');
  });

  it('treats an actionable confirmation wait as partial instead of a chat failure', () => {
    expect(isControlledProjectPartial({
      success: false,
      assistantContent: 'Plan execution is waiting for your confirmation or another required action.',
      steps: 1,
      errorMessage: null,
      navigationUrl: null,
      messages: [],
      debug: null,
      projectEvidence: [],
      completionStatus: 'PARTIAL',
      stopReason: 'WAITING_FOR_USER',
      outcome: 'PARTIAL',
      candidateArtifact: null,
    })).toBe(true);
  });

  it('keeps legacy Plan completion support in workspace chat without restoring the old Project V1 flow', () => {
    const workspaceChat = readFileSync(new URL('../src/views/ChatPage.vue', import.meta.url), 'utf8');
    const projectPreview = readFileSync(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url), 'utf8');

    expect(workspaceChat).toContain("from '@/utils/projectCompletion'");
    expect(workspaceChat).toContain('projectPlanExecutionOutcome');
    expect(projectPreview).not.toContain('isSandboxConfirmationRequiredText');
    expect(projectPreview).not.toContain('loadPlans(');
    expect(projectPreview).not.toContain('sendProjectMessage(');
  });
});
