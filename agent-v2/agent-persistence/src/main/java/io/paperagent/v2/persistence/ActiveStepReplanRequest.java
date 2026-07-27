package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;

import java.util.Objects;

public record ActiveStepReplanRequest(
        PlanId planId,
        String leaseToken,
        long fencingToken,
        PlanRevisionId expectedRevisionId,
        long expectedRevisionNumber,
        long expectedCheckpointVersion,
        long expectedEventHeadSequence,
        PlanStepId activeStepId,
        EventEnvelope supersessionEvent,
        Checkpoint supersededCheckpoint,
        EventEnvelope replanEvent,
        PlanRevision replannedRevision,
        Checkpoint replannedCheckpoint) {

    public ActiveStepReplanRequest {
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
        if (expectedCheckpointVersion < 3
                || expectedCheckpointVersion > Long.MAX_VALUE - 2) {
            throw new IllegalArgumentException(
                    "expectedCheckpointVersion must be between 3 and Long.MAX_VALUE - 2");
        }
        if (expectedEventHeadSequence < 1) {
            throw new IllegalArgumentException(
                    "expectedEventHeadSequence must be at least 1");
        }
        Objects.requireNonNull(activeStepId, "activeStepId");
        Objects.requireNonNull(supersessionEvent, "supersessionEvent");
        Objects.requireNonNull(supersededCheckpoint, "supersededCheckpoint");
        Objects.requireNonNull(replanEvent, "replanEvent");
        Objects.requireNonNull(replannedRevision, "replannedRevision");
        Objects.requireNonNull(replannedCheckpoint, "replannedCheckpoint");
    }

    @Override
    public String toString() {
        return "ActiveStepReplanRequest["
                + "planId=<provided>, "
                + "leaseToken=<provided>, "
                + "fencingToken=<provided>, "
                + "expectedRevisionId=<provided>, "
                + "expectedRevisionNumber=<provided>, "
                + "expectedCheckpointVersion=<provided>, "
                + "expectedEventHeadSequence=<provided>, "
                + "activeStepId=<provided>, "
                + "supersessionEvent=<provided>, "
                + "supersededCheckpoint=<provided>, "
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
