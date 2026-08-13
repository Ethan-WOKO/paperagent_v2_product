package com.yanban.api.agent.v2.effect.project;

import com.yanban.api.agent.v2.chain.effect.ChainActionWorkspaceAuthority;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;

public record AuthenticatedProjectEffectExecutionCommand(
        PlanId planId,
        ToolCallId toolCallId,
        StepRecoveryLeaseAttempt recoveryAttempt,
        ModelProvider requestProvider,
        ChainActionWorkspaceAuthority chainAuthority) {
    public AuthenticatedProjectEffectExecutionCommand(
            PlanId planId, ToolCallId toolCallId,
            StepRecoveryLeaseAttempt recoveryAttempt,
            ModelProvider requestProvider) {
        this(planId, toolCallId, recoveryAttempt, requestProvider, null);
    }

    public AuthenticatedProjectEffectExecutionCommand(
            PlanId planId, ToolCallId toolCallId,
            StepRecoveryLeaseAttempt recoveryAttempt) {
        this(planId, toolCallId, recoveryAttempt, null, null);
    }
}
