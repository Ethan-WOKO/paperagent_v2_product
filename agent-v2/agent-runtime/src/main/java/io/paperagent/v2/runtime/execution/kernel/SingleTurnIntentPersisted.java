package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedEffectIntent;

public record SingleTurnIntentPersisted(PersistedEffectIntent persistedIntent)
        implements SingleTurnStepKernelOutcome {

    public SingleTurnIntentPersisted {
        persistedIntent = SingleTurnStepKernelValues.required(
                persistedIntent, "singleTurnIntentPersisted.persistedIntent");
    }

    @Override
    public PlanId planId() {
        return persistedIntent.intent().planId();
    }

    @Override
    public PlanStepId stepId() {
        return persistedIntent.intent().stepId();
    }

    @Override
    public String toString() {
        return "SingleTurnIntentPersisted[persistedIntent=<provided>]";
    }
}
