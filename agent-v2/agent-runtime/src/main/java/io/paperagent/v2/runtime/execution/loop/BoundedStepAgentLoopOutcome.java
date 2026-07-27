package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedEffectIntent;

import java.util.List;

public sealed interface BoundedStepAgentLoopOutcome
        permits BoundedStepAgentLoopNoEffect,
                BoundedStepAgentLoopPersistenceRejected,
                BoundedStepAgentLoopTurnLimitReached {
    PlanId planId();

    PlanStepId stepId();

    int turnsExecuted();

    List<PersistedEffectIntent> persistedIntents();
}
