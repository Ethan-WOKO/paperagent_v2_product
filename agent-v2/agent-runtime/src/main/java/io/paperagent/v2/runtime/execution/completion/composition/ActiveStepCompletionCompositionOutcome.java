package io.paperagent.v2.runtime.execution.completion.composition;

import io.paperagent.v2.contracts.PlanId;

public sealed interface ActiveStepCompletionCompositionOutcome
        permits ActiveStepCompletionCommitted,
                ActiveStepCompletionPersistenceRejected {
    PlanId planId();

    ActiveStepCompletionLeaseDisposition leaseDisposition();
}
