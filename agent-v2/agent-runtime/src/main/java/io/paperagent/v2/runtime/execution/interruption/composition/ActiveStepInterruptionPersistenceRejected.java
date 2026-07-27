package io.paperagent.v2.runtime.execution.interruption.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record ActiveStepInterruptionPersistenceRejected(
        PlanId planId,
        PersistenceFailure failure,
        ActiveStepInterruptionLeaseDisposition leaseDisposition)
        implements ActiveStepInterruptionCompositionOutcome {

    public ActiveStepInterruptionPersistenceRejected {
        ActiveStepInterruptionCompositionValues.requireRejected(
                planId, failure, leaseDisposition);
    }

    @Override
    public String toString() {
        return "ActiveStepInterruptionPersistenceRejected[planId=<provided>, "
                + "failure=" + failure.code() + "/" + failure.path()
                + ", leaseDisposition=" + leaseDisposition + "]";
    }
}
