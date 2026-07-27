package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record StepActivationPersistenceRejected(
        PlanId planId,
        PersistenceFailure failure,
        StepActivationLeaseDisposition leaseDisposition)
        implements StepActivationCompositionOutcome {

    public StepActivationPersistenceRejected {
        StepActivationCompositionValues.requirePersistenceRejected(
                planId, failure, leaseDisposition);
    }

    @Override
    public String toString() {
        return "StepActivationPersistenceRejected[planId=<provided>, failure="
                + failure.code() + "/" + failure.path()
                + ", leaseDisposition=" + leaseDisposition + "]";
    }
}
