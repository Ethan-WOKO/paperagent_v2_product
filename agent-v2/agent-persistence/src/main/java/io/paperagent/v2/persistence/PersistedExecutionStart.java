package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;

import java.util.Objects;

public record PersistedExecutionStart(
        PlanId planId,
        String leaseOwnerId,
        long fencingToken,
        EventEnvelope startEvent,
        VersionedCheckpoint startedCheckpoint) {

    public PersistedExecutionStart {
        Objects.requireNonNull(planId, "planId");
        requireText(leaseOwnerId, "leaseOwnerId");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(startEvent, "startEvent");
        Objects.requireNonNull(startedCheckpoint, "startedCheckpoint");
        if (startedCheckpoint.version() != 2) {
            throw new IllegalArgumentException("startedCheckpoint version must be 2");
        }
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
