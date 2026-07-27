package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;

import java.util.Optional;

public record FreshExecutionStartRequest(
        PersistenceResult<PersistedPlanBootstrap> bootstrapResult,
        Optional<FreshExecutionStartAttempt> attempt) {

    public FreshExecutionStartRequest {
        FreshExecutionStartValues.required(
                bootstrapResult,
                "freshExecutionStartRequest.bootstrapResult");
        attempt = FreshExecutionStartValues.required(
                attempt,
                "freshExecutionStartRequest.attempt");
    }
}
