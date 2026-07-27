package io.paperagent.v2.runtime.execution.interruption.composition;

import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationRequest;

@FunctionalInterface
public interface ActiveStepInterruptionComposer {
    ActiveStepInterruptionCompositionOutcome compose(
            ActiveStepInterruptionMaterializationRequest request);
}
