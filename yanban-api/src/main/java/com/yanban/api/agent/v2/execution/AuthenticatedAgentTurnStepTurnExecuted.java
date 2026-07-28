package com.yanban.api.agent.v2.execution;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelOutcome;

import java.util.Objects;

/** Preserves the exact stable single-turn kernel outcome. */
public record AuthenticatedAgentTurnStepTurnExecuted(
        SingleTurnStepKernelOutcome outcome)
        implements AuthenticatedAgentTurnStepTurnOutcome {
    public AuthenticatedAgentTurnStepTurnExecuted {
        Objects.requireNonNull(outcome, "outcome");
    }

    @Override
    public PlanId planId() {
        return outcome.planId();
    }

    @Override
    public String toString() {
        return "AuthenticatedAgentTurnStepTurnExecuted[outcome=<provided>]";
    }
}
