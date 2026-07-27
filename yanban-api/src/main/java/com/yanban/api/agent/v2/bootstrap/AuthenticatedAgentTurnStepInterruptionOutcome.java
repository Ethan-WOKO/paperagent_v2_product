package com.yanban.api.agent.v2.bootstrap;

import io.paperagent.v2.contracts.PlanId;

/** Closed product result of one authenticated interruption attempt. */
public sealed interface AuthenticatedAgentTurnStepInterruptionOutcome
        permits AuthenticatedAgentTurnStepInterruptionRecoveryRejected,
                AuthenticatedAgentTurnStepInterrupted {
    PlanId planId();
}
