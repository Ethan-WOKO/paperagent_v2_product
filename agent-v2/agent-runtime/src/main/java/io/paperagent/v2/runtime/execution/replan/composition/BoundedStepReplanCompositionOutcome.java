package io.paperagent.v2.runtime.execution.replan.composition;

import io.paperagent.v2.contracts.PlanId;

public sealed interface BoundedStepReplanCompositionOutcome
        permits BoundedStepReplanApplied,
                BoundedStepReplanReplayed,
                BoundedStepReplanPersistenceRejected {
    PlanId planId();
}
