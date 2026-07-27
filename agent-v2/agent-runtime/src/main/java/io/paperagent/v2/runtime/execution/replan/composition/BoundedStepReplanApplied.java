package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;

public record BoundedStepReplanApplied(
        PersistedActiveStepReplan persistedReplan)
        implements BoundedStepReplanCompositionOutcome {

    public BoundedStepReplanApplied {
        BoundedStepReplanCompositionValues.requireSuccess(
                persistedReplan, "boundedStepReplanApplied.persistedReplan");
    }

    @Override
    public PlanId planId() {
        return persistedReplan.planId();
    }

    @Override
    public String toString() {
        return "BoundedStepReplanApplied[persistedReplan=<provided>]";
    }
}
