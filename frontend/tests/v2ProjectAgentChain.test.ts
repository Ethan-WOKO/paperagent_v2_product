import { describe, expect, it } from 'vitest';

import type { V2NaturalLanguageTurnResponse } from '../src/api/agent';
import {
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
  v2ProjectTurnLabel,
  v2ProjectCommandFailureRecoveryDecision,
} from '../src/utils/v2ProjectAgentChain';

const turn = (overrides: Partial<V2NaturalLanguageTurnResponse> = {}): V2NaturalLanguageTurnResponse => ({
  clientRequestId: 'root-1',
  workState: 'EXECUTING',
  taskOutcomeStatus: null,
  deliveryStatus: null,
  route: 'PERSISTENT_PLAN_EXECUTE',
  planId: 'plan-1',
  baseProjectVersion: 'project:version.1',
  publishedProjectVersion: null,
  revisionId: null,
  publishReceiptId: null,
  steps: [],
  pendingItem: null,
  validation: null,
  finalText: null,
  candidateArtifactId: null,
  outputPaths: [],
  failureCategory: null,
  failureCode: null,
  deliveryErrorCode: null,
  ...overrides,
});

describe('Project V2 Agent 正式链路投影', () => {
  it('创建初始、补充、纠正和替代 command，非初始请求必须绑定根 task', () => {
    expect(normalizeV2ProjectInstructionRequest('  新任务  ', 'command-1')).toEqual({
      content: '新任务',
      clientRequestId: 'command-1',
      instructionKind: 'INITIAL',
    });
    for (const kind of ['SUPPLEMENT', 'CORRECTION', 'REPLACEMENT'] as const) {
      expect(normalizeV2ProjectInstructionRequest('内容', `command-${kind}`, kind, 'root-1')).toEqual({
        content: '内容',
        clientRequestId: `command-${kind}`,
        instructionKind: kind,
        targetClientRequestId: 'root-1',
      });
      expect(() => normalizeV2ProjectInstructionRequest('内容', 'command', kind))
        .toThrow('instruction-target-required');
    }
    expect(() => normalizeV2ProjectInstructionRequest('内容', 'command', 'INITIAL', 'root-1'))
      .toThrow('initial-target-forbidden');
  });

  it('只有正式 Delivery 终态清除恢复键，TaskOutcome 和 workState 都不能替代它', () => {
    expect(shouldClearV2ProjectChainRecovery(turn({
      workState: 'TERMINAL',
      taskOutcomeStatus: 'COMPLETED',
      deliveryStatus: 'PENDING',
    }))).toBe(false);
    expect(shouldClearV2ProjectChainRecovery(turn({
      workState: 'TERMINAL',
      taskOutcomeStatus: 'FAILED',
      deliveryStatus: 'RETRYING',
    }))).toBe(false);
    expect(shouldClearV2ProjectChainRecovery(turn({
      workState: 'WAITING_USER',
      pendingItem: {
        gapId: 'gap-1',
        type: 'USER_CHOICE',
        status: 'PENDING',
        question: '请选择',
        expectedFormat: null,
      },
    }))).toBe(false);
    expect(shouldClearV2ProjectChainRecovery(turn({ deliveryStatus: 'SUCCEEDED' }))).toBe(true);
    expect(shouldClearV2ProjectChainRecovery(turn({ deliveryStatus: 'DELIVERY_FAILED' }))).toBe(true);
    expect(v2ProjectTurnLabel(turn({
      workState: 'TERMINAL',
      taskOutcomeStatus: 'COMPLETED',
      deliveryStatus: 'PENDING',
    }))).toBe('等待投递');
  });

  it('后端确定性 BLOCKED 停止 GET 刷新，不再无限转圈', () => {
    const blocked = turn({
      workState: 'BLOCKED',
      taskOutcomeStatus: null,
      deliveryStatus: null,
    });
    expect(shouldRefreshV2ProjectChainTurn(blocked)).toBe(false);
    expect(v2ProjectTurnLabel(blocked)).toBe('已停止（连续无进展）');
  });

  it('PENDING gap 停止 GET 刷新并开放回复；回答提交后才继续读取正式投影', () => {
    const pending = turn({
      workState: 'WAITING_USER',
      pendingItem: {
        gapId: 'gap-1',
        type: 'USER_INFORMATION',
        status: 'PENDING',
        question: '请补充材料',
        expectedFormat: '文件名',
      },
    });
    expect(canReplyToV2ProjectGap(pending)).toBe(true);
    expect(shouldRefreshV2ProjectChainTurn(pending)).toBe(false);
    expect(shouldRefreshV2ProjectChainTurn(turn({
      ...pending,
      pendingItem: { ...pending.pendingItem!, status: 'RESPONSE_RECEIVED' },
    }))).toBe(true);
    expect(canCancelV2ProjectTurn(pending)).toBe(true);
    expect(canCancelV2ProjectTurn(turn({ taskOutcomeStatus: 'CANCELLED' }))).toBe(false);
    expect(canSendV2ProjectFollowUp(pending)).toBe(true);
    expect(canSendV2ProjectFollowUp(turn({ taskOutcomeStatus: 'COMPLETED' }))).toBe(false);
  });

  it('恢复记录同时绑定 project、session、root、command，跨作用域记录失效', () => {
    const raw = JSON.stringify({
      version: 1,
      projectId: 64,
      sessionId: 6401,
      rootClientRequestId: 'root-1',
      commandClientRequestId: 'command-2',
      question: '问题',
    });
    expect(parseV2ProjectChainRecoveryRecord(raw, 64, 6401)).toMatchObject({
      rootClientRequestId: 'root-1',
      commandClientRequestId: 'command-2',
    });
    expect(parseV2ProjectChainRecoveryRecord(raw, 65, 6401)).toBeNull();
    expect(parseV2ProjectChainRecoveryRecord(raw, 64, 6402)).toBeNull();
  });

  it('每次 history list 都必须匹配独立序列，旧请求不能覆盖较新的同作用域结果', () => {
    const expected = {
      projectId: 64,
      sessionId: 6401,
      clientRequestId: 'command-1',
      sequence: 7,
      historySequence: 3,
    };
    expect(isCurrentV2ProjectHistoryRequest(expected, expected)).toBe(true);
    expect(isCurrentV2ProjectHistoryRequest(expected, {
      ...expected,
      historySequence: 4,
    })).toBe(false);
    expect(isCurrentV2ProjectHistoryRequest(expected, {
      ...expected,
      clientRequestId: 'command-2',
    })).toBe(false);
  });

  it('除 408 外所有明确 4xx 都丢弃未绑定 recovery，未知提交结果才读取正式状态', () => {
    for (const status of [400, 401, 403, 404, 409, 415, 422, 429, 499]) {
      expect(v2ProjectCommandFailureRecoveryDecision({ response: { status } }))
        .toBe('DROP_UNBOUND_RECOVERY');
    }
    expect(v2ProjectCommandFailureRecoveryDecision({ response: { status: 408 } }))
      .toBe('READ_FORMAL_STATE');
    expect(v2ProjectCommandFailureRecoveryDecision(new Error('network lost')))
      .toBe('READ_FORMAL_STATE');
    expect(v2ProjectCommandFailureRecoveryDecision({ response: { status: 500 } }))
      .toBe('READ_FORMAL_STATE');
  });

  it('按服务端微秒时间排序，不把同一毫秒内的正式顺序压平', () => {
    const later = {
      ...turn(),
      clientRequestId: 'root-a',
      question: 'later',
      createdAt: '2026-08-08T20:03:31.323900+08:00',
      updatedAt: '2026-08-08T20:03:31.323900+08:00',
    };
    const earlier = {
      ...turn(),
      clientRequestId: 'root-z',
      question: 'earlier',
      createdAt: '2026-08-08T20:03:31.323100+08:00',
      updatedAt: '2026-08-08T20:03:31.323100+08:00',
    };
    expect(sortV2ProjectChainHistory([later, earlier]).map((item) => item.question))
      .toEqual(['earlier', 'later']);
  });

  it('unlocks page interactions at terminal TaskOutcome while delivery polling continues', () => {
    const delivering = turn({
      workState: 'DELIVERING',
      taskOutcomeStatus: 'COMPLETED',
      deliveryStatus: null,
    });
    expect(isV2ProjectTurnInteractionBlocking(delivering)).toBe(false);
    expect(shouldRefreshV2ProjectChainTurn(delivering)).toBe(true);
    expect(isV2ProjectTurnInteractionBlocking(turn({
      workState: 'EXECUTING',
      taskOutcomeStatus: null,
      deliveryStatus: null,
    }))).toBe(true);
  });

  it('retries transient turn reads but stops retrying deterministic client failures', () => {
    for (const status of [408, 429, 500, 502, 503, 504]) {
      expect(isV2ProjectTurnReadTransientFailure({ response: { status } })).toBe(true);
    }
    expect(isV2ProjectTurnReadTransientFailure(new Error('network lost'))).toBe(true);
    for (const status of [400, 401, 403, 404, 409, 422]) {
      expect(isV2ProjectTurnReadTransientFailure({ response: { status } })).toBe(false);
    }
  });
});
