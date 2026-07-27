package io.paperagent.v2.runtime.execution.kernel;

/** The injected, provider-neutral source of exactly one Step turn decision. */
@FunctionalInterface
public interface StepTurnPort {
    StepTurnDecision decide(StepTurnInput input);
}
