package com.yanban.api.agent.v2.progression;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import java.util.Objects;

/**
 * Caller-owned attempts for completing one ACTIVE Step from all its durable
 * successful effect Receipts.
 */
public record EffectDrivenStepCompletionCommand(
        PlanId planId,
        PlanStepId stepId,
        StepRecoveryLeaseAttempt currentStepRecoveryAttempt,
        EffectDrivenStepProgressionActivationLeaseAttempt
                nextStepActivationAttempt) {
    public EffectDrivenStepCompletionCommand {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(
                currentStepRecoveryAttempt, "currentStepRecoveryAttempt");
        Objects.requireNonNull(
                nextStepActivationAttempt, "nextStepActivationAttempt");
    }
}
