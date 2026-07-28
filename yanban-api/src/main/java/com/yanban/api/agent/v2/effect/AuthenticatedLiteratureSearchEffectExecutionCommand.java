package com.yanban.api.agent.v2.effect;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;

public record AuthenticatedLiteratureSearchEffectExecutionCommand(
        PlanId planId,
        ToolCallId toolCallId,
        StepRecoveryLeaseAttempt recoveryAttempt) {
    @Override
    public String toString() {
        return "AuthenticatedLiteratureSearchEffectExecutionCommand["
                + "planId=<provided>, toolCallId=<provided>, "
                + "recoveryAttempt=<redacted>]";
    }
}
