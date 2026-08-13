import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

describe('ProjectPreviewPage 正式 V2 Agent 对话', () => {
  const source = readFileSync(
    new URL('../ProjectPreviewPage.vue', import.meta.url),
    'utf8',
  );

  it('只保留单一 Project composer 和既有 Project 会话栏', () => {
    expect(source).toContain('<section class="v2-conversation">');
    expect(source).toContain('class="v2-conversation__composer"');
    expect(source.match(/class="v2-conversation__composer"/g)).toHaveLength(1);
    expect(source).not.toContain('class="project-composer"');
    expect(source).not.toContain("@click=\"setAgentMode('v1')\"");
    expect(source).toContain('v-for="session in projectSessions"');
    expect(source).toContain('@click="selectConversation(session.id)"');
    expect(source).toContain('@click="startNewConversation"');
    expect(source).toContain('createProjectSession(project.id');
  });

  it('展示正式 workState、TaskOutcome、Delivery、PendingItem 和发布事实', () => {
    expect(source).toContain('v-for="task in v2TurnHistory"');
    expect(source).toContain(':data-work-state="task.workState"');
    expect(source).toContain(':data-delivery-status="task.deliveryStatus || undefined"');
    expect(source).toContain("task.deliveryStatus === 'SUCCEEDED' && task.finalText");
    expect(source).toContain("task.deliveryStatus === 'DELIVERY_FAILED'");
    expect(source).toContain("task.pendingItem?.status === 'PENDING'");
    expect(source).toContain('task.publishedProjectVersion && task.revisionId && task.publishReceiptId');
    expect(source).toContain('task.validation.receipts');
    expect(source).toContain('receipt.receiptId');
    expect(source).toContain('selectedCandidatePublishedTurn');
    expect(source).toContain('selectedCandidateValidatedTurn');
    expect(source).toContain("turn.candidateArtifactId === artifactId");
    expect(source).toContain("turn.validation?.status === 'PASSED'");
    expect(source).toContain('Agent 正式验证已通过；尚未创建项目版本');
    expect(source).toContain('下方记录仅用于手动创建项目版本');
    expect(source).toContain('手动版本验证：');
    expect(source).not.toContain('最近通过的沙箱验证：');
    expect(source).toContain("'发布时已核验'");
    expect(source).not.toContain('WAITING_CONFIRMATION');
    expect(source).not.toContain('agentAutomaticValidation');
    expect(source).not.toContain('confirmationValidation');
  });

  it('提供 gap reply、cancel、补充、纠正和替代动作', () => {
    expect(source).toContain('v-if="canReplyToV2ProjectGap(task)"');
    expect(source).toContain('v-if="canCancelV2ProjectTurn(task)"');
    expect(source).toContain('v-if="canSendV2ProjectFollowUp(task)"');
    expect(source).toContain('@click="prepareV2GapReply(task)"');
    expect(source).toContain('@click="cancelV2NaturalLanguageTask(task)"');
    expect(source).toContain(':disabled="v2TurnCancelSubmitting"');
    expect(source).toContain('v2TurnCancelSubmitting.value = true');
    expect(source).toContain(':disabled="loading.deleteProject"');
    expect(source).not.toContain('Current Project Agent request is still running. Please wait before deleting a conversation.');
    expect(source).toContain("@click=\"prepareV2Instruction('SUPPLEMENT', task)\"");
    expect(source).toContain("@click=\"prepareV2Instruction('CORRECTION', task)\"");
    expect(source).toContain("@click=\"prepareV2Instruction('REPLACEMENT', task)\"");
    expect(source).toContain('@click="sendV2NaturalLanguageTurn"');
  });

  it('列表恢复和轮询只读取正式 GET，Delivery 终态才清恢复键', () => {
    expect(source).toContain('listV2NaturalLanguageTurns(sessionId, 50, controller.signal)');
    expect(source).toContain('getV2NaturalLanguageTurn(');
    expect(source).toContain('recoverV2NaturalLanguageTurn(activeProjectId.value, sessionId)');
    expect(source).toContain('shouldClearV2ProjectChainRecovery(outcome)');
    expect(source).toContain('clearStoredV2NaturalLanguageRequest(recovery.projectId, recovery.sessionId)');
    expect(source).not.toContain('resume: async');
    expect(source).not.toContain('startThenPollV2NaturalLanguageTurn');
  });
});
