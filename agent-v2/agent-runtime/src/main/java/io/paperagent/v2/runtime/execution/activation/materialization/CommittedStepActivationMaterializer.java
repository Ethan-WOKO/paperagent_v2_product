package io.paperagent.v2.runtime.execution.activation.materialization;

@FunctionalInterface
public interface CommittedStepActivationMaterializer {
    MaterializedStepActivation materialize(
            CommittedStepActivationMaterializationRequest request);
}
