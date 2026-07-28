package com.yanban.api.agent.v2.progression;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;

import java.util.Objects;

/** Untrusted identifiers plus caller-owned lease attempts for one progression. */
public record EffectDrivenStepProgressionCommand(
        PlanId planId,
        ToolCallId toolCallId,
        StepRecoveryLeaseAttempt currentStepRecoveryAttempt,
        EffectDrivenStepProgressionActivationLeaseAttempt
                nextStepActivationAttempt) {

    public EffectDrivenStepProgressionCommand {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(
                currentStepRecoveryAttempt, "currentStepRecoveryAttempt");
        Objects.requireNonNull(
                nextStepActivationAttempt, "nextStepActivationAttempt");
    }

    @Override
    public String toString() {
        return "EffectDrivenStepProgressionCommand["
                + "planId=<provided>, toolCallId=<provided>, "
                + "currentStepRecoveryAttempt=<redacted>, "
                + "nextStepActivationAttempt=<redacted>]";
    }
}
