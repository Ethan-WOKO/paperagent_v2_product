package io.paperagent.v2.runtime.execution.recovery.materialization;

import io.paperagent.v2.runtime.execution.MaterializedExecutionStart;

@FunctionalInterface
public interface RecoveryReadyExecutionStartMaterializer {
    MaterializedExecutionStart materialize(
            RecoveryReadyExecutionStartMaterializationRequest request);
}
