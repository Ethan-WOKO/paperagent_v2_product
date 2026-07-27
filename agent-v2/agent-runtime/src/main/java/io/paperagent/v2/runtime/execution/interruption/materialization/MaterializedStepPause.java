package io.paperagent.v2.runtime.execution.interruption.materialization;

import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.StepPauseRequest;

public record MaterializedStepPause(StepPauseRequest request)
        implements MaterializedActiveStepInterruption {
    public MaterializedStepPause {
        ActiveStepInterruptionMaterializationValues.required(
                request, "materializedStepPause.request");
    }

    @Override
    public StepInterruptionKind kind() {
        return StepInterruptionKind.PAUSE;
    }

    @Override
    public String toString() {
        return "MaterializedStepPause[request=<provided>]";
    }
}
