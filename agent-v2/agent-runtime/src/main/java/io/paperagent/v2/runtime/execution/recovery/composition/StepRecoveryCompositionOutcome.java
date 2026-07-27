package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;

public sealed interface StepRecoveryCompositionOutcome
        permits RecoveredActiveStep,
                StepRecoveryLeaseRejected,
                StepRecoveryPersistenceRejected {
    PlanId planId();

    StepRecoveryLeaseDisposition leaseDisposition();
}
