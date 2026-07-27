package io.paperagent.v2.runtime.execution.interruption.composition;

import io.paperagent.v2.contracts.PlanId;

public sealed interface ActiveStepInterruptionCompositionOutcome
        permits ActiveStepInterruptionCommitted,
                ActiveStepInterruptionPersistenceRejected {
    PlanId planId();

    ActiveStepInterruptionLeaseDisposition leaseDisposition();
}
