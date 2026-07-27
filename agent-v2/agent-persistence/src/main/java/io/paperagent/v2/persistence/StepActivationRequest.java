package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;

import java.util.Objects;

public record StepActivationRequest(
        PlanId planId,
        String leaseToken,
        long fencingToken,
        PlanRevisionId expectedRevisionId,
        long expectedRevisionNumber,
        long expectedCheckpointVersion,
        long expectedEventHeadSequence,
        PlanStepId stepId,
        EventEnvelope activationEvent,
        Checkpoint activatedCheckpoint) {

    public StepActivationRequest {
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
                    "expectedEventHeadSequence must be positive");
        }
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(activationEvent, "activationEvent");
        Objects.requireNonNull(activatedCheckpoint, "activatedCheckpoint");
    }

    @Override
    public String toString() {
        return "StepActivationRequest["
                + "planId=<provided>, "
                + "leaseToken=<provided>, "
                + "fencingToken=<provided>, "
                + "expectedRevisionId=<provided>, "
                + "expectedRevisionNumber=<provided>, "
                + "expectedCheckpointVersion=<provided>, "
                + "expectedEventHeadSequence=<provided>, "
                + "stepId=<provided>, "
                + "activationEvent=<provided>, "
                + "activatedCheckpoint=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
