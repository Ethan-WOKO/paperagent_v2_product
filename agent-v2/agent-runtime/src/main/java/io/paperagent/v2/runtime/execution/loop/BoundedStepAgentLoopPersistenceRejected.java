package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistedEffectIntent;

import java.util.List;

public record BoundedStepAgentLoopPersistenceRejected(
        PlanId planId,
        PlanStepId stepId,
        int turnsExecuted,
        List<PersistedEffectIntent> persistedIntents,
        PersistenceFailure failure)
        implements BoundedStepAgentLoopOutcome {

    public BoundedStepAgentLoopPersistenceRejected {
        planId = BoundedStepAgentLoopValues.required(
                planId, "boundedStepAgentLoopPersistenceRejected.planId");
        stepId = BoundedStepAgentLoopValues.required(
                stepId, "boundedStepAgentLoopPersistenceRejected.stepId");
        turnsExecuted = BoundedStepAgentLoopValues.positiveTurns(
                turnsExecuted, "boundedStepAgentLoopPersistenceRejected.turnsExecuted");
        persistedIntents = BoundedStepAgentLoopValues.intents(
                persistedIntents,
                "boundedStepAgentLoopPersistenceRejected.persistedIntents");
        failure = BoundedStepAgentLoopValues.required(
                failure, "boundedStepAgentLoopPersistenceRejected.failure");
        BoundedStepAgentLoopValues.requirePriorIntentCount(
                turnsExecuted, persistedIntents,
                "boundedStepAgentLoopPersistenceRejected.persistedIntents");
    }

    @Override
    public String toString() {
        return "BoundedStepAgentLoopPersistenceRejected[planId=<provided>, "
                + "stepId=<provided>, turnsExecuted=" + turnsExecuted
                + ", persistedIntents=<provided>, failure=<provided>]";
    }
}
