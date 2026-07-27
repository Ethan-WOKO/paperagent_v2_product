package io.paperagent.v2.runtime.execution.interruption.materialization;

@FunctionalInterface
public interface ActiveStepInterruptionMaterializer {
    MaterializedActiveStepInterruption materialize(
            ActiveStepInterruptionMaterializationRequest request);
}
