package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;

public record SingleTurnStepKernelRequest(RecoveredActiveStep recoveredStep) {
    public SingleTurnStepKernelRequest {
        recoveredStep = SingleTurnStepKernelValues.required(
                recoveredStep, "singleTurnStepKernelRequest.recoveredStep");
    }

    @Override
    public String toString() {
        return "SingleTurnStepKernelRequest[recoveredStep=<provided>]";
    }
}
