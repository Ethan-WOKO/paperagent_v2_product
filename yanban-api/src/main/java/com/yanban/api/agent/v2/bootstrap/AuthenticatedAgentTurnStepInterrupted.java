package com.yanban.api.agent.v2.bootstrap;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionCompositionOutcome;
import io.paperagent.v2.runtime.execution.interruption.composition.ActiveStepInterruptionLeaseDisposition;

import java.util.Objects;

/** Preserves the exact stable interruption result without exposing lease authority. */
public record AuthenticatedAgentTurnStepInterrupted(
        ActiveStepInterruptionCompositionOutcome interruption)
        implements AuthenticatedAgentTurnStepInterruptionOutcome {

    public AuthenticatedAgentTurnStepInterrupted {
        Objects.requireNonNull(interruption, "interruption");
    }

    @Override
    public PlanId planId() {
        return interruption.planId();
    }

    public ActiveStepInterruptionLeaseDisposition leaseDisposition() {
        return interruption.leaseDisposition();
    }

    @Override
    public String toString() {
        return "AuthenticatedAgentTurnStepInterrupted"
                + "[interruption=<provided>, "
                + "leaseDisposition=" + leaseDisposition() + "]";
    }
}
