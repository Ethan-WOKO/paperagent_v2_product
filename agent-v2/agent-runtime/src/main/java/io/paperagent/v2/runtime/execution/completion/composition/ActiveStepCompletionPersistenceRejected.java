package io.paperagent.v2.runtime.execution.completion.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record ActiveStepCompletionPersistenceRejected(
        PlanId planId,
        PlanStepId stepId,
        PersistenceFailure failure,
        ActiveStepCompletionLeaseDisposition leaseDisposition)
        implements ActiveStepCompletionCompositionOutcome {

    public ActiveStepCompletionPersistenceRejected {
        ActiveStepCompletionCompositionValues.requireRejected(
                planId, stepId, failure, leaseDisposition);
    }

    @Override
    public String toString() {
        return "ActiveStepCompletionPersistenceRejected[planId=<provided>, "
                + "stepId=<provided>, "
                + "failure=" + failure.code() + "/" + failure.path()
                + ", leaseDisposition=" + leaseDisposition + "]";
    }
}
