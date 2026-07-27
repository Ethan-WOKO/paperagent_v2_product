package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record StepRecoveryLeaseRejected(
        PlanId planId,
        PersistenceFailure failure,
        StepRecoveryLeaseDisposition leaseDisposition)
        implements StepRecoveryCompositionOutcome {

    public StepRecoveryLeaseRejected {
        StepRecoveryCompositionValues.requireLeaseRejected(
                planId, failure, leaseDisposition);
    }

    @Override
    public String toString() {
        return "StepRecoveryLeaseRejected[planId=<provided>, failure="
                + failure.code() + "/" + failure.path()
                + ", leaseDisposition=" + leaseDisposition + "]";
    }
}
