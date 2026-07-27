package io.paperagent.v2.runtime.execution.completion.materialization;

import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;

import java.time.Instant;

public record ActiveStepCompletionMaterializationRequest(
        RecoveredActiveStep recoveredActiveStep,
        ActiveStepCompletionFactDraft completionFactDraft,
        ActiveStepCompletionEventDraft eventDraft,
        ActiveStepCompletionRevisionDraft revisionDraft,
        Instant checkpointCreatedAt) {

    public ActiveStepCompletionMaterializationRequest {
        ActiveStepCompletionMaterializationValues.required(
                recoveredActiveStep,
                "activeStepCompletionMaterializationRequest"
                        + ".recoveredActiveStep");
        ActiveStepCompletionMaterializationValues.required(
                completionFactDraft,
                "activeStepCompletionMaterializationRequest"
                        + ".completionFactDraft");
        ActiveStepCompletionMaterializationValues.required(
                eventDraft,
                "activeStepCompletionMaterializationRequest.eventDraft");
        ActiveStepCompletionMaterializationValues.required(
                revisionDraft,
                "activeStepCompletionMaterializationRequest.revisionDraft");
        ActiveStepCompletionMaterializationValues.required(
                checkpointCreatedAt,
                "activeStepCompletionMaterializationRequest"
                        + ".checkpointCreatedAt");
    }

    @Override
    public String toString() {
        return "ActiveStepCompletionMaterializationRequest"
                + "[recoveredActiveStep=<redacted>, "
                + "completionFactDraft=<redacted>, eventDraft=<redacted>, "
                + "revisionDraft=<redacted>, "
                + "checkpointCreatedAt=<provided>]";
    }
}
