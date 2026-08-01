import type { AgentPlanResponse, SendMessageResponse } from '@/api/agent';
import { requiresSandboxConfirmation } from '@/utils/projectSandboxConfirmation';

const CONTROLLED_PARTIAL_STOP_REASONS = new Set([
  'TOOL_CALL_BUDGET_EXHAUSTED',
  'MAX_STEPS_BUDGET_EXHAUSTED',
  'MODEL_OUTPUT_TRUNCATED',
  'PLAN_PARTIAL',
  'WAITING_FOR_USER',
]);
const INTERNAL_EVIDENCE_REF_LINE = /^[ \t]*\[projectEvidenceRefs=[^\r\n\]]*\][ \t]*(?:\r?\n|$)/gm;
const INTERNAL_EVIDENCE_REF = /\[projectEvidenceRefs=[^\r\n\]]*\]/g;

export function isControlledProjectPartial(response: SendMessageResponse) {
  return response.completionStatus === 'PARTIAL'
    && response.outcome === 'PARTIAL'
    && response.stopReason != null
    && CONTROLLED_PARTIAL_STOP_REASONS.has(response.stopReason)
    && Boolean(response.assistantContent?.trim());
}

export function withoutInternalProjectEvidenceRefs(content?: string | null) {
  if (!content) return '';
  return content
    .replace(INTERNAL_EVIDENCE_REF_LINE, '')
    .replace(INTERNAL_EVIDENCE_REF, '')
    .trimEnd();
}

export function withoutInternalRuntimeCodes(content?: string | null) {
  if (isSandboxConfirmationRequiredText(content)) return '';
  return withoutInternalProjectEvidenceRefs(content)
    .replace(/\bSANDBOX_CONFIRMATION_REQUIRED\b\s*:?\s*/gi, '')
    .replace(/\bDOMAIN_[A-Z0-9_]+\b\s*:?\s*/g, '')
    .replace(/\bDEPENDENCY_PARTIAL\b\s*:?\s*/g, '')
    .trim();
}

export function isSandboxConfirmationRequiredText(content?: string | null) {
  return Boolean(content && (/\bSANDBOX_CONFIRMATION_REQUIRED\b/i.test(content)
    || /Plan execution is waiting for your confirmation or another required action\.?/i.test(content)));
}

export function isInternalRuntimeFailureText(content?: string | null) {
  if (!content) return false;
  const value = content.trim();
  const internalCode = '(?:DOMAIN|SANDBOX|UNKNOWN_COMPLETION|DEPENDENCY)_[A-Z0-9_]+';
  const plannerFailure = /^(?:Project Plan creation failed\s*\[traceId=[^\]]+\]\s*:\s*)?Planner failed\s*\[[A-Z0-9_]+\]\s*:/i;
  const internalCodeOnly = new RegExp(`^${internalCode}$`, 'i');
  const codeAndTraceEnvelope = new RegExp(`^(?:code\\s*[:=]\\s*)?${internalCode}\\s*(?:[:;|])[^\\r\\n]*\\btraceId\\s*=\\s*\\S+\\s*$`, 'i');
  const failureAndTraceEnvelope = /^(?:Project Plan creation failed|Request failed|Internal (?:server )?error|Unexpected runtime failure)\b[^\r\n]*\btraceId\s*=\s*\S+\s*$/i;
  const traceOnly = /^traceId\s*=\s*\S+\s*$/i;
  return plannerFailure.test(value)
    || internalCodeOnly.test(value)
    || codeAndTraceEnvelope.test(value)
    || failureAndTraceEnvelope.test(value)
    || traceOnly.test(value);
}

export function projectAssistantPresentation(content: string, requestFailedCopy: string) {
  const markers = [
    '\n\n受支持的解释与推理：\n',
    '\n\nSupported interpretation and inference:\n',
    '\n\n结果摘要：\n',
    '\n\nResult summary:\n',
  ];
  const limitations = ['\n\n限制与适用范围：\n', '\n\nLimitations and scope:\n'];
  const mainMarker = markers
    .map((marker) => ({ marker, index: content.indexOf(marker) }))
    .filter((item) => item.index >= 0)
    .sort((left, right) => left.index - right.index)[0];

  if (mainMarker) {
    const bodyStart = mainMarker.index + mainMarker.marker.length;
    const limitation = limitations
      .map((marker) => ({ marker, index: content.indexOf(marker, bodyStart) }))
      .filter((item) => item.index >= 0)
      .sort((left, right) => left.index - right.index)[0];
    const bodyEnd = limitation?.index ?? content.length;
    const body = content.slice(bodyStart, bodyEnd).trim();
    const technical = [
      content.slice(0, mainMarker.index).trim(),
      limitation ? content.slice(limitation.index + limitation.marker.length).trim() : '',
    ].filter(Boolean).join('\n\n');
    return { content: body || requestFailedCopy, technicalContent: technical || undefined };
  }

  if (isInternalRuntimeFailureText(content)) {
    return { content: requestFailedCopy, technicalContent: content };
  }
  return { content, technicalContent: undefined };
}

export function projectPlanFailureReason(plan: AgentPlanResponse) {
  if (requiresSandboxConfirmation(plan)) return '';
  const planError = withoutInternalRuntimeCodes(plan.errorMessage);
  if (planError) return planError;
  const failedStep = [...plan.steps]
    .sort((left, right) => right.sortOrder - left.sortOrder)
    .find((step) => withoutInternalRuntimeCodes(step.errorMessage));
  return withoutInternalRuntimeCodes(failedStep?.errorMessage);
}

export function projectPlanLifecycle(plan: AgentPlanResponse) {
  return plan.status;
}

export function projectPlanExecutionOutcome(plan: AgentPlanResponse) {
  return plan.executionOutcome;
}

export function projectPlanDisplayStatus(plan: AgentPlanResponse, english: boolean) {
  const outcome = projectPlanExecutionOutcome(plan);
  if (outcome === 'TIMED_OUT') return english ? 'Timed out' : '已超时';
  return outcome;
}

export function projectPlanFinalAnswer(plan: AgentPlanResponse) {
  return withoutInternalProjectEvidenceRefs(plan.finalAnswer).trim();
}
