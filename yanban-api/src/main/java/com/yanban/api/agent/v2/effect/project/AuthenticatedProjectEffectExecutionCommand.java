package com.yanban.api.agent.v2.effect.project;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;

public record AuthenticatedProjectEffectExecutionCommand(
        PlanId planId,
        ToolCallId toolCallId,
        StepRecoveryLeaseAttempt recoveryAttempt) {
}
