package io.paperagent.v2.runtime.execution.activation.composition;

@FunctionalInterface
public interface StepActivationComposer {
    StepActivationCompositionOutcome compose(
            StepActivationCompositionRequest request);

    default StepActivationCompositionOutcome composeReady(
            ReadyStepActivationCompositionRequest request) {
        throw new UnsupportedOperationException(
                "ready-Step activation is not supported");
    }
}
