package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;

public record RecoveredActiveStep(
        PersistedStepRecoveryActive recovery,
        LeaseRecord lease,
        StepRecoveryLeaseDisposition leaseDisposition)
        implements StepRecoveryCompositionOutcome {

    public RecoveredActiveStep {
        StepRecoveryCompositionValues.requireRecovered(
                recovery, lease, leaseDisposition);
    }

    @Override
    public PlanId planId() {
        return recovery.planId();
    }

    @Override
    public String toString() {
        return "RecoveredActiveStep[recovery=<provided>, lease=<redacted>, "
                + "leaseDisposition=" + leaseDisposition + "]";
    }
}
