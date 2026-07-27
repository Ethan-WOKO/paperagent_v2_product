package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record StepRecoveryPersistenceRejected(
        PlanId planId,
        StepRecoveryStage stage,
        PersistenceFailure failure,
        StepRecoveryLeaseDisposition leaseDisposition)
        implements StepRecoveryCompositionOutcome {

    public StepRecoveryPersistenceRejected {
        StepRecoveryCompositionValues.requirePersistenceRejected(
                planId, stage, failure, leaseDisposition);
    }

    @Override
    public String toString() {
        return "StepRecoveryPersistenceRejected[planId=<provided>, stage="
                + stage + ", failure=" + failure.code() + "/" + failure.path()
                + ", leaseDisposition=" + leaseDisposition + "]";
    }
}
