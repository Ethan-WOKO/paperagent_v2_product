package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record BoundedStepReplanPersistenceRejected(
        PlanId planId,
        PersistenceFailure failure)
        implements BoundedStepReplanCompositionOutcome {

    public BoundedStepReplanPersistenceRejected {
        BoundedStepReplanCompositionValues.requireRejected(planId, failure);
    }

    @Override
    public String toString() {
        return "BoundedStepReplanPersistenceRejected[planId=<provided>, failure="
                + failure.code() + "/" + failure.path() + "]";
    }
}
