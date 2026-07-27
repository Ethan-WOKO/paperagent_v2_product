package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;

import java.util.Objects;

public record ExecutionStartRequest(
        PlanId planId,
        String leaseToken,
        long fencingToken,
        EventEnvelope startEvent,
        Checkpoint startedCheckpoint) {

    public ExecutionStartRequest {
        Objects.requireNonNull(planId, "planId");
        requireText(leaseToken, "leaseToken");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(startEvent, "startEvent");
        Objects.requireNonNull(startedCheckpoint, "startedCheckpoint");
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
