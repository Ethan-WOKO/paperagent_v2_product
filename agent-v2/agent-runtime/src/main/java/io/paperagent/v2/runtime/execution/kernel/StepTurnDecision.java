package io.paperagent.v2.runtime.execution.kernel;

public sealed interface StepTurnDecision
        permits NoEffectDecision, EffectIntentDecision,
                StepResultDecision {
}
