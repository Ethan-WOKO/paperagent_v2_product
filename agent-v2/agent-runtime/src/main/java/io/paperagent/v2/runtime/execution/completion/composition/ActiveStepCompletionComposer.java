package io.paperagent.v2.runtime.execution.completion.composition;

import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationRequest;

@FunctionalInterface
public interface ActiveStepCompletionComposer {
    ActiveStepCompletionCompositionOutcome compose(
            ActiveStepCompletionMaterializationRequest request);
}
