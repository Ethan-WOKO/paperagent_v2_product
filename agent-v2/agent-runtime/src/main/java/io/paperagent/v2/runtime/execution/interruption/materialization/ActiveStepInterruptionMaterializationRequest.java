package io.paperagent.v2.runtime.execution.interruption.materialization;

import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;

import java.time.Instant;

public record ActiveStepInterruptionMaterializationRequest(
        RecoveredActiveStep recoveredActiveStep,
        StepInterruptionKind kind,
        ActiveStepInterruptionEventDraft eventDraft,
        Instant checkpointCreatedAt) {

    public ActiveStepInterruptionMaterializationRequest {
        ActiveStepInterruptionMaterializationValues.required(
                recoveredActiveStep,
                "activeStepInterruptionMaterializationRequest"
                        + ".recoveredActiveStep");
        ActiveStepInterruptionMaterializationValues.required(
                kind,
                "activeStepInterruptionMaterializationRequest.kind");
        ActiveStepInterruptionMaterializationValues.required(
                eventDraft,
                "activeStepInterruptionMaterializationRequest.eventDraft");
        ActiveStepInterruptionMaterializationValues.required(
                checkpointCreatedAt,
                "activeStepInterruptionMaterializationRequest"
                        + ".checkpointCreatedAt");
    }

    @Override
    public String toString() {
        return "ActiveStepInterruptionMaterializationRequest"
                + "[recoveredActiveStep=<provided>, kind=<provided>, "
                + "eventDraft=<provided>, checkpointCreatedAt=<provided>]";
    }
}
