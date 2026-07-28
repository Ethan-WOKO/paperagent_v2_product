package com.yanban.api.agent.v2.execution;

import io.paperagent.v2.contracts.PlanId;

/** Closed product result for one authenticated provider-backed Step turn. */
public sealed interface AuthenticatedAgentTurnStepTurnOutcome
        permits AuthenticatedAgentTurnStepTurnRecoveryRejected,
                AuthenticatedAgentTurnStepTurnExecuted {
    PlanId planId();
}
