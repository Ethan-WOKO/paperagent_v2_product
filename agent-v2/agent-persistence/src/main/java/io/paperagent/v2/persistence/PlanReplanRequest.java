package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;

import java.util.Objects;

public record PlanReplanRequest(
        PlanId planId,
        String leaseToken,
        long fencingToken,
        PlanRevisionId expectedRevisionId,
        long expectedRevisionNumber,
        long expectedCheckpointVersion,
        long expectedEventHeadSequence,
        EventEnvelope replanEvent,
        PlanRevision replannedRevision,
        Checkpoint replannedCheckpoint) {

    public PlanReplanRequest {
        Objects.requireNonNull(planId, "planId");
        requireText(leaseToken, "leaseToken");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(expectedRevisionId, "expectedRevisionId");
        if (expectedRevisionNumber < 1) {
            throw new IllegalArgumentException(
                    "expectedRevisionNumber must be positive");
        }
        if (expectedCheckpointVersion < 2
                || expectedCheckpointVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "expectedCheckpointVersion must be between 2 and Long.MAX_VALUE - 1");
        }
        if (expectedEventHeadSequence < 1) {
            throw new IllegalArgumentException(
                    "expectedEventHeadSequence must be at least 1");
        }
        Objects.requireNonNull(replanEvent, "replanEvent");
        Objects.requireNonNull(replannedRevision, "replannedRevision");
        Objects.requireNonNull(replannedCheckpoint, "replannedCheckpoint");
    }

    @Override
    public String toString() {
        return "PlanReplanRequest["
                + "planId=<provided>, "
                + "leaseToken=<provided>, "
                + "fencingToken=<provided>, "
                + "expectedRevisionId=<provided>, "
                + "expectedRevisionNumber=<provided>, "
                + "expectedCheckpointVersion=<provided>, "
                + "expectedEventHeadSequence=<provided>, "
                + "replanEvent=<provided>, "
                + "replannedRevision=<provided>, "
                + "replannedCheckpoint=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
