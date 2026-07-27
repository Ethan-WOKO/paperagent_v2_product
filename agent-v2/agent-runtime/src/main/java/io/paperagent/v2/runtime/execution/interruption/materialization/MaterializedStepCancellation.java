package io.paperagent.v2.runtime.execution.interruption.materialization;

import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepInterruptionKind;

public record MaterializedStepCancellation(StepCancelRequest request)
        implements MaterializedActiveStepInterruption {
    public MaterializedStepCancellation {
        ActiveStepInterruptionMaterializationValues.required(
                request, "materializedStepCancellation.request");
    }

    @Override
    public StepInterruptionKind kind() {
        return StepInterruptionKind.CANCEL;
    }

    @Override
    public String toString() {
        return "MaterializedStepCancellation[request=<provided>]";
    }
}
