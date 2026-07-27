package io.paperagent.v2.runtime.execution.completion.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;

public sealed interface ActiveStepCompletionCompositionOutcome
        permits ActiveStepCompletionCommitted,
                ActiveStepCompletionPersistenceRejected {
    PlanId planId();

    PlanStepId stepId();

    ActiveStepCompletionLeaseDisposition leaseDisposition();
}
