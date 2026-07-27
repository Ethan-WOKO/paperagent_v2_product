package io.paperagent.v2.runtime.execution.completion.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record ActiveStepCompletionPersistenceRejected(
        PlanId planId,
        PersistenceFailure failure,
        ActiveStepCompletionLeaseDisposition leaseDisposition)
        implements ActiveStepCompletionCompositionOutcome {

    public ActiveStepCompletionPersistenceRejected {
        ActiveStepCompletionCompositionValues.requireRejected(
                planId, failure, leaseDisposition);
    }

    @Override
    public String toString() {
        return "ActiveStepCompletionPersistenceRejected[planId=<provided>, "
                + "failure=" + failure.code() + "/" + failure.path()
                + ", leaseDisposition=" + leaseDisposition + "]";
    }
}
