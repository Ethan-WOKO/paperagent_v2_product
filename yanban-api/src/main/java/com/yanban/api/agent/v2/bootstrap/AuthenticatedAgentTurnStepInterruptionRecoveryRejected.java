package com.yanban.api.agent.v2.bootstrap;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryCompositionOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseRejected;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryPersistenceRejected;

import java.util.Objects;

/** Preserves an exact stable non-recovered Step recovery result. */
public record AuthenticatedAgentTurnStepInterruptionRecoveryRejected(
        StepRecoveryCompositionOutcome recovery)
        implements AuthenticatedAgentTurnStepInterruptionOutcome {

    public AuthenticatedAgentTurnStepInterruptionRecoveryRejected {
        Objects.requireNonNull(recovery, "recovery");
        if (!(recovery instanceof StepRecoveryLeaseRejected)
                && !(recovery instanceof StepRecoveryPersistenceRejected)) {
            throw new IllegalArgumentException(
                    "recovery must be a stable non-recovered outcome");
        }
    }

    @Override
    public PlanId planId() {
        return recovery.planId();
    }

    public StepRecoveryLeaseDisposition leaseDisposition() {
        return recovery.leaseDisposition();
    }

    @Override
    public String toString() {
        return "AuthenticatedAgentTurnStepInterruptionRecoveryRejected"
                + "[recovery=<provided>, leaseDisposition="
                + leaseDisposition() + "]";
    }
}
