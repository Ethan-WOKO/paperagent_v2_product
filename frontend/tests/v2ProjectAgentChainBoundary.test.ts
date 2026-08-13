import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

describe('Project Agent 链路前端边界', () => {
  const agent = read('../src/api/agent.ts');
  const project = read('../src/views/ProjectPreviewPage.vue');
  const chat = read('../src/views/ChatPage.vue');

  it('保持原 start/get/list 名称并只补同一 turn 资源的 gap reply 与 cancel', () => {
    expect(agent).toContain('export function startV2NaturalLanguageTurn(');
    expect(agent).toContain('export function getV2NaturalLanguageTurn(');
    expect(agent).toContain('export function listV2NaturalLanguageTurns(');
    expect(agent).toContain('/pending-items/${encodeURIComponent(gapId)}/reply`');
    expect(agent).toContain('/${encodeURIComponent(targetClientRequestId)}/cancel`');
    const requestContract = agent.slice(
      agent.indexOf('export interface V2NaturalLanguageTurnRequest'),
      agent.indexOf('export type V2ProjectInstructionKind'),
    );
    expect(requestContract).toContain('instructionKind?: V2ProjectInstructionKind');
    expect(requestContract).toContain('targetClientRequestId?: string | null');
    expect(requestContract).not.toContain('experiment');
  });

  it('使用单一 Project composer 和正式状态，不恢复 adaptive 状态或合成 DIRECT 终态', () => {
    expect(project.match(/class="v2-conversation__composer"/g)).toHaveLength(1);
    expect(project).toContain('task.workState');
    expect(project).toContain('task.taskOutcomeStatus');
    expect(project).toContain('task.deliveryStatus');
    expect(project).toContain('task.pendingItem');
    expect(project).toContain('canSendV2ProjectFollowUp(task)');
    expect(project).toContain("task.deliveryStatus === 'SUCCEEDED' && task.finalText");
    expect(project).toContain("task.deliveryStatus === 'DELIVERY_FAILED'");
    expect(project).not.toContain('WAITING_CONFIRMATION');
    expect(project).not.toContain('agentAutomaticValidation');
    expect(project).not.toContain('confirmationValidation');
    expect(project).not.toContain('v2-direct-answer-required');
  });

  it('刷新只读取 GET，不使用轮询 POST 续跑，并保持四元 stale fence 与恢复键', () => {
    expect(project).toContain('getV2NaturalLanguageTurn(');
    expect(project).toContain('V2_PROJECT_CHAIN_REFRESH_INTERVAL_MS');
    expect(project).not.toContain('resume: async');
    expect(project).not.toContain('startThenPollV2NaturalLanguageTurn');
    expect(project).toContain('projectId: activeProjectId.value ?? -1');
    expect(project).toContain('sessionId: activeSessionId.value ?? -1');
    expect(project).toContain("clientRequestId: v2TurnClientRequestId ?? ''");
    expect(project).toContain('sequence: v2TurnSequence');
    expect(project).toMatch(/async function loadV2TurnHistory[\s\S]*?v2HistoryRequestSequence \+= 1[\s\S]*?v2HistoryAbortController\?\.abort\(\)[\s\S]*?listV2NaturalLanguageTurns\(sessionId, 50, controller.signal\)/);
    expect(project).toContain('isCurrentV2ProjectHistoryRequest(expected, currentV2HistoryRequestIdentity())');
    expect(project.match(/startV2NaturalLanguageTurn\(/g)).toHaveLength(1);
    expect(project.match(/replyV2NaturalLanguagePendingItem\(/g)).toHaveLength(1);
    expect(project.match(/cancelV2NaturalLanguageTurn\(/g)).toHaveLength(1);
    expect(project).toContain('shouldClearV2ProjectChainRecovery(outcome)');
    expect(project).toContain('v2TurnSubmitting.value || v2TurnInteractionBlocking.value');
    expect(project).toContain('v2TurnInteractionBlocking.value = isV2ProjectTurnInteractionBlocking(outcome)');
    expect(project).toContain('selectedCandidatePublishedTurn');
    expect(project).toMatch(/async function cancelV2NaturalLanguageTask[\s\S]*?v2ProjectCommandFailureRecoveryDecision\(cause\)[\s\S]*?await loadV2TurnHistory\(sessionId, epoch\)[\s\S]*?recoverV2NaturalLanguageTurn\(projectId, sessionId, recovery\)/);
    expect(project.match(/\{ preserveError: true \}/g)).toHaveLength(3);
    expect(project).toContain("if (!options.preserveError) v2TurnError.value = ''");
    expect(project).toContain("if (!preserveError) v2TurnError.value = ''");
    expect(project).toContain('if (!preserveError) v2TurnError.value = apiError(cause)');
    expect(project).not.toContain('isV2ProjectChainTargetNotFound(cause)');
    expect(project).not.toContain('targetNotFoundRetries');
    expect(project).not.toContain('V2_PROJECT_CHAIN_TARGET_NOT_FOUND_RETRY_LIMIT');
  });

  it('保留 Workspace Chat /messages 客户端与页面调用，不把 Project 合同扩散过去', () => {
    expect(agent).toContain('export function sendMessage(');
    expect(agent).toContain('`/agent/sessions/${sessionId}/messages`');
    expect(chat).toContain('sendMessage as sendAgentMessage');
    expect(chat).toContain('sendAgentMessage(');
    expect(chat).not.toContain('V2ProjectWorkState');
    expect(chat).not.toContain('replyV2NaturalLanguagePendingItem');
  });
});
