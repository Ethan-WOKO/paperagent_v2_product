package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;

public sealed interface SingleTurnStepKernelOutcome
        permits SingleTurnNoEffect,
                SingleTurnIntentPersisted,
                SingleTurnPersistenceRejected,
                SingleTurnStepResultProposed {
    PlanId planId();

    PlanStepId stepId();
}
