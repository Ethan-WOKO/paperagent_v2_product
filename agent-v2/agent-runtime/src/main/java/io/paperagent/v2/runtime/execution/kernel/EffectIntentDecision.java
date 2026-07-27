package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.EffectIntent;

public record EffectIntentDecision(EffectIntent intent) implements StepTurnDecision {
    public EffectIntentDecision {
        intent = SingleTurnStepKernelValues.required(
                intent, "effectIntentDecision.intent");
    }

    @Override
    public String toString() {
        return "EffectIntentDecision[intent=<provided>]";
    }
}
