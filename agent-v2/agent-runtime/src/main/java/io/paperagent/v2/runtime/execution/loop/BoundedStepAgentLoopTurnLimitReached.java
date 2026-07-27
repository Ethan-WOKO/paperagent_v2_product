package io.paperagent.v2.runtime.execution.loop;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedEffectIntent;

import java.util.List;

public record BoundedStepAgentLoopTurnLimitReached(
        PlanId planId,
        PlanStepId stepId,
        int turnsExecuted,
        List<PersistedEffectIntent> persistedIntents)
        implements BoundedStepAgentLoopOutcome {

    public BoundedStepAgentLoopTurnLimitReached {
        planId = BoundedStepAgentLoopValues.required(
                planId, "boundedStepAgentLoopTurnLimitReached.planId");
        stepId = BoundedStepAgentLoopValues.required(
                stepId, "boundedStepAgentLoopTurnLimitReached.stepId");
        turnsExecuted = BoundedStepAgentLoopValues.positiveTurns(
                turnsExecuted, "boundedStepAgentLoopTurnLimitReached.turnsExecuted");
        persistedIntents = BoundedStepAgentLoopValues.intents(
                persistedIntents,
                "boundedStepAgentLoopTurnLimitReached.persistedIntents");
        BoundedStepAgentLoopValues.requireAllIntentCount(
                turnsExecuted, persistedIntents,
                "boundedStepAgentLoopTurnLimitReached.persistedIntents");
    }

    @Override
    public String toString() {
        return "BoundedStepAgentLoopTurnLimitReached[planId=<provided>, stepId=<provided>, "
                + "turnsExecuted=" + turnsExecuted + ", persistedIntents=<provided>]";
    }
}
