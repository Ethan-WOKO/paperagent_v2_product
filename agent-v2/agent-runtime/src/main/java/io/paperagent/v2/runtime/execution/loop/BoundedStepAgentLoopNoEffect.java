package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedEffectIntent;

import java.util.List;

public record BoundedStepAgentLoopNoEffect(
        PlanId planId,
        PlanStepId stepId,
        int turnsExecuted,
        List<PersistedEffectIntent> persistedIntents)
        implements BoundedStepAgentLoopOutcome {

    public BoundedStepAgentLoopNoEffect {
        planId = BoundedStepAgentLoopValues.required(
                planId, "boundedStepAgentLoopNoEffect.planId");
        stepId = BoundedStepAgentLoopValues.required(
                stepId, "boundedStepAgentLoopNoEffect.stepId");
        turnsExecuted = BoundedStepAgentLoopValues.positiveTurns(
                turnsExecuted, "boundedStepAgentLoopNoEffect.turnsExecuted");
        persistedIntents = BoundedStepAgentLoopValues.intents(
                persistedIntents, "boundedStepAgentLoopNoEffect.persistedIntents");
        BoundedStepAgentLoopValues.requirePriorIntentCount(
                turnsExecuted, persistedIntents,
                "boundedStepAgentLoopNoEffect.persistedIntents");
    }

    @Override
    public String toString() {
        return "BoundedStepAgentLoopNoEffect[planId=<provided>, stepId=<provided>, "
                + "turnsExecuted=" + turnsExecuted + ", persistedIntents=<provided>]";
    }
}
