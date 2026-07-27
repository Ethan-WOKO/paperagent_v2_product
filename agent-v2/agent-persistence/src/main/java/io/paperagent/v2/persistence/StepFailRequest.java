package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;

import java.util.Objects;

public record StepFailRequest(
        PlanId planId,
        String leaseToken,
        long fencingToken,
        PlanRevisionId expectedRevisionId,
        long expectedRevisionNumber,
        long expectedCheckpointVersion,
        long expectedEventHeadSequence,
        PlanStepId stepId,
        EventEnvelope failureEvent,
        Checkpoint failedCheckpoint) {

    public StepFailRequest {
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
                || expectedCheckpointVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "expectedCheckpointVersion must be between 3 and Long.MAX_VALUE");
        }
        if (expectedEventHeadSequence < 2) {
            throw new IllegalArgumentException(
                    "expectedEventHeadSequence must be at least 2");
        }
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(failureEvent, "failureEvent");
        Objects.requireNonNull(failedCheckpoint, "failedCheckpoint");
    }

    @Override
    public String toString() {
        return "StepFailRequest["
                + "planId=<provided>, "
                + "leaseToken=<provided>, "
                + "fencingToken=<provided>, "
                + "expectedRevisionId=<provided>, "
                + "expectedRevisionNumber=<provided>, "
                + "expectedCheckpointVersion=<provided>, "
                + "expectedEventHeadSequence=<provided>, "
                + "stepId=<provided>, "
                + "failureEvent=<provided>, "
                + "failedCheckpoint=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
