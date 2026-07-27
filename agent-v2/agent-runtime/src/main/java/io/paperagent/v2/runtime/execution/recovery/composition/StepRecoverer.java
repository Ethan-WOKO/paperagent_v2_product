package io.paperagent.v2.runtime.execution.recovery.composition;

@FunctionalInterface
public interface StepRecoverer {
    StepRecoveryCompositionOutcome recover(StepRecoveryRequest request);
}
