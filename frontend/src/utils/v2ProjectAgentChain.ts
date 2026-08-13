import type {
  V2NaturalLanguageTurnHistoryItem,
  V2NaturalLanguageTurnRequest,
  V2NaturalLanguageTurnResponse,
  V2ProjectDeliveryStatus,
  V2ProjectInstructionKind,
  V2ProjectStepStatus,
  V2ProjectTaskOutcomeStatus,
  V2ProjectWorkState,
} from '@/api/agent';

// Browser refresh cadence only. It never limits Agent progression or decides a terminal state.
export const V2_PROJECT_CHAIN_REFRESH_INTERVAL_MS = 2_000;
export const V2_PROJECT_CHAIN_TRANSIENT_READ_RETRY_LIMIT = 5;

export interface V2ProjectChainRecoveryRecord {
  version: 1;
  projectId: number;
  sessionId: number;
  rootClientRequestId: string;
  commandClientRequestId: string;
  question: string;
}

export interface V2ProjectHistoryRequestIdentity {
  projectId: number;
  sessionId: number;
  clientRequestId: string;
  sequence: number;
  historySequence: number;
}

export type V2ProjectCommandFailureRecoveryDecision =
  | 'DROP_UNBOUND_RECOVERY'
  | 'READ_FORMAL_STATE';

export function isCurrentV2ProjectHistoryRequest(
  expected: V2ProjectHistoryRequestIdentity,
  current: V2ProjectHistoryRequestIdentity,
) {
  return expected.projectId === current.projectId
    && expected.sessionId === current.sessionId
    && expected.clientRequestId === current.clientRequestId
    && expected.sequence === current.sequence
    && expected.historySequence === current.historySequence;
}

export function v2ProjectCommandFailureRecoveryDecision(
  cause: unknown,
): V2ProjectCommandFailureRecoveryDecision {
  const status = (cause as { response?: { status?: unknown } } | null)?.response?.status;
  return typeof status === 'number' && status >= 400 && status < 500 && status !== 408
    ? 'DROP_UNBOUND_RECOVERY'
    : 'READ_FORMAL_STATE';
}

export function isV2ProjectTurnReadTransientFailure(cause: unknown) {
  const status = (cause as { response?: { status?: unknown } } | null)?.response?.status;
  return typeof status !== 'number' || status === 408 || status === 429 || status >= 500;
}

export function normalizeV2ProjectInstructionRequest(
  content: string,
  clientRequestId: string,
  instructionKind: V2ProjectInstructionKind = 'INITIAL',
  targetClientRequestId: string | null = null,
): V2NaturalLanguageTurnRequest {
  const normalized = content.trim();
  if (!normalized) throw new Error('content-required');
  if (normalized.length > 20_000) throw new Error('content-too-long');
  if (!clientRequestId.trim()) throw new Error('client-request-id-required');
  if (instructionKind === 'INITIAL' && targetClientRequestId != null) {
    throw new Error('initial-target-forbidden');
  }
  if (instructionKind !== 'INITIAL' && !targetClientRequestId?.trim()) {
    throw new Error('instruction-target-required');
  }
  return {
    content: normalized,
    clientRequestId,
    instructionKind,
    ...(instructionKind === 'INITIAL' ? {} : { targetClientRequestId }),
  };
}

export function isV2ProjectDeliveryTerminal(
  deliveryStatus: V2ProjectDeliveryStatus | null,
) {
  return deliveryStatus === 'SUCCEEDED' || deliveryStatus === 'DELIVERY_FAILED';
}

export function isV2ProjectTaskOutcomeTerminal(
  taskOutcomeStatus: V2ProjectTaskOutcomeStatus | null,
) {
  return taskOutcomeStatus === 'COMPLETED'
    || taskOutcomeStatus === 'FAILED'
    || taskOutcomeStatus === 'CANCELLED'
    || taskOutcomeStatus === 'SUPERSEDED';
}

export function isV2ProjectTurnInteractionBlocking(
  turn: Pick<V2NaturalLanguageTurnResponse,
  'taskOutcomeStatus' | 'deliveryStatus' | 'pendingItem'>,
) {
  return !isV2ProjectTaskOutcomeTerminal(turn.taskOutcomeStatus)
    && !isV2ProjectDeliveryTerminal(turn.deliveryStatus)
    && turn.pendingItem?.status !== 'PENDING';
}

export function shouldClearV2ProjectChainRecovery(
  turn: Pick<V2NaturalLanguageTurnResponse, 'deliveryStatus'>,
) {
  return isV2ProjectDeliveryTerminal(turn.deliveryStatus);
}

export function shouldRefreshV2ProjectChainTurn(
  turn: Pick<V2NaturalLanguageTurnResponse, 'workState' | 'deliveryStatus' | 'pendingItem'>,
) {
  if (turn.workState === 'BLOCKED' || isV2ProjectDeliveryTerminal(turn.deliveryStatus)) return false;
  return turn.pendingItem?.status !== 'PENDING';
}

export function canReplyToV2ProjectGap(
  turn: Pick<V2NaturalLanguageTurnResponse, 'pendingItem' | 'deliveryStatus'>,
) {
  return !isV2ProjectDeliveryTerminal(turn.deliveryStatus)
    && turn.pendingItem?.status === 'PENDING';
}

export function canCancelV2ProjectTurn(
  turn: Pick<V2NaturalLanguageTurnResponse, 'taskOutcomeStatus' | 'deliveryStatus'>,
) {
  return turn.taskOutcomeStatus == null
    && !isV2ProjectDeliveryTerminal(turn.deliveryStatus);
}

export function canSendV2ProjectFollowUp(
  turn: Pick<V2NaturalLanguageTurnResponse, 'taskOutcomeStatus' | 'deliveryStatus'>,
) {
  return turn.taskOutcomeStatus == null
    && !isV2ProjectDeliveryTerminal(turn.deliveryStatus);
}

export function sortV2ProjectChainHistory(items: V2NaturalLanguageTurnHistoryItem[]) {
  return [...items].sort((left, right) => {
    // The server emits one normalized ISO representation with microseconds.
    // Date.parse truncates those values to milliseconds and can reorder two
    // formal tasks created inside the same millisecond. Comparing the
    // normalized representation preserves the full database precision.
    const byTime = left.createdAt.localeCompare(right.createdAt);
    return byTime || left.clientRequestId.localeCompare(right.clientRequestId);
  });
}

export function parseV2ProjectChainRecoveryRecord(
  raw: string | null,
  projectId: number,
  sessionId: number,
): V2ProjectChainRecoveryRecord | null {
  if (!raw) return null;
  try {
    const value = JSON.parse(raw) as Partial<V2ProjectChainRecoveryRecord>;
    if (value.version !== 1
        || value.projectId !== projectId
        || value.sessionId !== sessionId
        || typeof value.rootClientRequestId !== 'string'
        || !value.rootClientRequestId
        || typeof value.commandClientRequestId !== 'string'
        || !value.commandClientRequestId
        || typeof value.question !== 'string'
        || !value.question) return null;
    return value as V2ProjectChainRecoveryRecord;
  } catch {
    return null;
  }
}

export function v2ProjectWorkStateLabel(workState: V2ProjectWorkState) {
  const labels: Record<V2ProjectWorkState, string> = {
    PLANNING: '正在制定计划',
    CLASSIFYING_INSTRUCTION: '正在处理新指令',
    DIRECT_ANSWERING: '正在组织回答',
    EXECUTING: '正在执行',
    AWAITING_REVIEW: '等待正式评审',
    VALIDATING_PENDING_ITEM: '正在核验你的回复',
    WAITING_USER: '等待你的回复',
    WAITING_PERMISSION: '等待权限确认',
    FINALIZING: '正在最终化',
    DELIVERING: '正在投递结果',
    BLOCKED: '已停止（连续无进展）',
    TERMINAL: '流程已结束',
  };
  return labels[workState];
}

export function v2ProjectTaskOutcomeLabel(status: V2ProjectTaskOutcomeStatus | null) {
  if (status === 'COMPLETED') return '任务已完成';
  if (status === 'FAILED') return '任务失败';
  if (status === 'CANCELLED') return '任务已取消';
  if (status === 'SUPERSEDED') return '任务已被替代';
  return null;
}

export function v2ProjectDeliveryLabel(status: V2ProjectDeliveryStatus | null) {
  if (status === 'PENDING') return '等待投递';
  if (status === 'RETRYING') return '正在重试投递';
  if (status === 'SUCCEEDED') return '已交付';
  if (status === 'DELIVERY_FAILED') return '交付失败';
  return null;
}

export function v2ProjectTurnLabel(
  turn: Pick<V2NaturalLanguageTurnResponse,
    'workState' | 'taskOutcomeStatus' | 'deliveryStatus'>,
) {
  return v2ProjectDeliveryLabel(turn.deliveryStatus)
    || v2ProjectTaskOutcomeLabel(turn.taskOutcomeStatus)
    || v2ProjectWorkStateLabel(turn.workState);
}

export function v2ProjectTurnTagType(
  turn: Pick<V2NaturalLanguageTurnResponse,
    'workState' | 'taskOutcomeStatus' | 'deliveryStatus'>,
): 'default' | 'error' | 'warning' | 'success' | 'info' {
  if (turn.deliveryStatus === 'SUCCEEDED') return 'success';
  if (turn.deliveryStatus === 'DELIVERY_FAILED' || turn.taskOutcomeStatus === 'FAILED'
      || turn.workState === 'BLOCKED') return 'error';
  if (turn.taskOutcomeStatus === 'CANCELLED' || turn.taskOutcomeStatus === 'SUPERSEDED') return 'warning';
  if (turn.workState === 'WAITING_USER' || turn.workState === 'WAITING_PERMISSION') return 'warning';
  return 'info';
}

export function v2ProjectStepLabel(status: V2ProjectStepStatus) {
  const labels: Record<V2ProjectStepStatus, string> = {
    NOT_STARTED: '尚未开始',
    READY: '等待执行',
    ACTIVE: '正在执行',
    AWAITING_REVIEW: '等待评审',
    WAITING_GAP: '等待回复',
    COMPLETED: '已完成',
    SUPERSEDED_BY_REPLAN: '已被新计划替代',
  };
  return labels[status];
}

export function v2ProjectStepTagType(status: V2ProjectStepStatus) {
  if (status === 'COMPLETED') return 'success' as const;
  if (status === 'SUPERSEDED_BY_REPLAN' || status === 'WAITING_GAP') return 'warning' as const;
  if (status === 'ACTIVE' || status === 'AWAITING_REVIEW') return 'info' as const;
  return 'default' as const;
}

export function v2ProjectInstructionKindLabel(kind: V2ProjectInstructionKind) {
  if (kind === 'SUPPLEMENT') return '补充';
  if (kind === 'CORRECTION') return '纠正';
  if (kind === 'REPLACEMENT') return '替代';
  return '新任务';
}
