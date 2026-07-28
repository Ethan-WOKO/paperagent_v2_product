package com.yanban.api.agent.v2.execution;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryCompositionOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseRejected;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryPersistenceRejected;

import java.util.Objects;

/** Preserves one exact stable recovery rejection without calling a provider. */
public record AuthenticatedAgentTurnStepTurnRecoveryRejected(
        StepRecoveryCompositionOutcome recovery)
        implements AuthenticatedAgentTurnStepTurnOutcome {
    public AuthenticatedAgentTurnStepTurnRecoveryRejected {
        Objects.requireNonNull(recovery, "recovery");
        if (!(recovery instanceof StepRecoveryLeaseRejected)
                && !(recovery instanceof StepRecoveryPersistenceRejected)) {
            throw new IllegalArgumentException(
                    "recovery must be a stable rejected outcome");
        }
    }

    @Override
    public PlanId planId() {
        return recovery.planId();
    }

    @Override
    public String toString() {
        return "AuthenticatedAgentTurnStepTurnRecoveryRejected"
                + "[recovery=<provided>]";
    }
}
