package io.paperagent.v2.runtime.execution.completion.materialization;

import io.paperagent.v2.persistence.StepCompletionRequest;

@FunctionalInterface
public interface ActiveStepCompletionMaterializer {
    StepCompletionRequest materialize(
            ActiveStepCompletionMaterializationRequest request);
}
