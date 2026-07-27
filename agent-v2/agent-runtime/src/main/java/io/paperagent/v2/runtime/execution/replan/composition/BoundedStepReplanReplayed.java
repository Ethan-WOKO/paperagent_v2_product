package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;

public record BoundedStepReplanReplayed(
        PersistedActiveStepReplan persistedReplan)
        implements BoundedStepReplanCompositionOutcome {

    public BoundedStepReplanReplayed {
        BoundedStepReplanCompositionValues.requireSuccess(
                persistedReplan, "boundedStepReplanReplayed.persistedReplan");
    }

    @Override
    public PlanId planId() {
        return persistedReplan.planId();
    }

    @Override
    public String toString() {
        return "BoundedStepReplanReplayed[persistedReplan=<provided>]";
    }
}
