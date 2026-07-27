package io.paperagent.v2.runtime.execution.interruption.materialization;

import io.paperagent.v2.persistence.StepInterruptionKind;

public sealed interface MaterializedActiveStepInterruption
        permits MaterializedStepPause, MaterializedStepFailure,
                MaterializedStepCancellation {
    StepInterruptionKind kind();
}
