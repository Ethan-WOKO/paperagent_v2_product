package io.paperagent.v2.runtime.execution.interruption.materialization;

import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepInterruptionKind;

public record MaterializedStepFailure(StepFailRequest request)
        implements MaterializedActiveStepInterruption {
    public MaterializedStepFailure {
        ActiveStepInterruptionMaterializationValues.required(
                request, "materializedStepFailure.request");
    }

    @Override
    public StepInterruptionKind kind() {
        return StepInterruptionKind.FAIL;
    }

    @Override
    public String toString() {
        return "MaterializedStepFailure[request=<provided>]";
    }
}
