package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;

import java.util.Objects;

public record PersistedStepActivation(
        PlanId planId,
        PlanStepId stepId,
        String leaseOwnerId,
        long fencingToken,
        EventEnvelope activationEvent,
        VersionedCheckpoint activatedCheckpoint) {

    public PersistedStepActivation {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(stepId, "stepId");
        requireText(leaseOwnerId, "leaseOwnerId");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(activationEvent, "activationEvent");
        Objects.requireNonNull(activatedCheckpoint, "activatedCheckpoint");
        if (activatedCheckpoint.version() < 3) {
            throw new IllegalArgumentException(
                    "activatedCheckpoint version must be at least 3");
        }
        if (!planId.equals(activationEvent.planId())
                || !planId.equals(activatedCheckpoint.checkpoint().planId())
                || !activationEvent.taskFrameId().equals(
                        activatedCheckpoint.checkpoint().taskFrameId())
                || activationEvent.sequence()
                        != activatedCheckpoint.checkpoint().lastEventSequence()
                || activatedCheckpoint.checkpoint().stepStates().get(stepId)
                        != StepExecutionState.ACTIVE) {
            throw new IllegalArgumentException(
                    "activation result components must describe one authority");
        }
    }

    @Override
    public String toString() {
        return "PersistedStepActivation["
                + "planId=<provided>, "
                + "stepId=<provided>, "
                + "leaseOwnerId=<provided>, "
                + "fencingToken=<provided>, "
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
