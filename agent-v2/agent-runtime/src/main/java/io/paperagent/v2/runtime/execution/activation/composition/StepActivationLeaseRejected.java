package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record StepActivationLeaseRejected(
        PlanId planId,
        PersistenceFailure failure,
        StepActivationLeaseDisposition leaseDisposition)
        implements StepActivationCompositionOutcome {

    public StepActivationLeaseRejected {
        StepActivationCompositionValues.requireLeaseRejected(
                planId, failure, leaseDisposition);
    }

    @Override
    public String toString() {
        return "StepActivationLeaseRejected[planId=<provided>, failure="
                + failure.code() + "/" + failure.path()
                + ", leaseDisposition=" + leaseDisposition + "]";
    }
}
