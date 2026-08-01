import type { AgentPlanResponse } from '@/api/agent';

export function requiresSandboxConfirmation(plan: AgentPlanResponse) {
  if (plan.status.toUpperCase() !== 'REVIEWING') return false;
  return plan.steps.some((step) => step.type.toUpperCase() === 'SANDBOX_EXECUTE'
    && step.status.toUpperCase() === 'PENDING');
}

export function sandboxConfirmationStepCount(plan: AgentPlanResponse) {
  return plan.steps.filter((step) => step.type.toUpperCase() === 'SANDBOX_EXECUTE'
    && step.status.toUpperCase() === 'PENDING').length;
}
