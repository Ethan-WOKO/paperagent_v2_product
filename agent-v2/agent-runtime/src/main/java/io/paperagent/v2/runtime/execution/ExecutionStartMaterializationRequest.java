package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.persistence.PersistedPlanBootstrap;

import java.time.Instant;

public record ExecutionStartMaterializationRequest(
        PersistedPlanBootstrap bootstrap,
        ExecutionStartEventDraft eventDraft,
        Instant checkpointCreatedAt) {

    public ExecutionStartMaterializationRequest {
        ExecutionStartMaterializationValues.required(
                bootstrap,
                "executionStartMaterializationRequest.bootstrap");
        ExecutionStartMaterializationValues.required(
                eventDraft,
                "executionStartMaterializationRequest.eventDraft");
        ExecutionStartMaterializationValues.required(
                checkpointCreatedAt,
                "executionStartMaterializationRequest.checkpointCreatedAt");
    }
}
