package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.PlanId;

public sealed interface StepActivationCompositionOutcome
        permits StepActivationCommitted,
                StepActivationLeaseRejected,
                StepActivationPersistenceRejected {
    PlanId planId();

    StepActivationLeaseDisposition leaseDisposition();
}
