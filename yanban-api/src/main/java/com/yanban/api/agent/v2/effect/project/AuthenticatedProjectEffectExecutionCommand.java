package com.yanban.api.agent.v2.effect.project;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import io.paperagent.v2.providers.ModelProvider;

public record AuthenticatedProjectEffectExecutionCommand(
        PlanId planId,
        ToolCallId toolCallId,
        StepRecoveryLeaseAttempt recoveryAttempt,
        ModelProvider requestProvider) {
    public AuthenticatedProjectEffectExecutionCommand(
            PlanId planId, ToolCallId toolCallId,
            StepRecoveryLeaseAttempt recoveryAttempt) {
        this(planId, toolCallId, recoveryAttempt, null);
    }
}
