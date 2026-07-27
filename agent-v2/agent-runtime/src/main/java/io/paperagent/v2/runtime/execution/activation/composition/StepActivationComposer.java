package io.paperagent.v2.runtime.execution.activation.composition;

@FunctionalInterface
public interface StepActivationComposer {
    StepActivationCompositionOutcome compose(
            StepActivationCompositionRequest request);
}
