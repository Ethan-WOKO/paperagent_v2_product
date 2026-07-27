package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.StepExecutionState;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record PersistedPlanReplan(
        PlanId planId,
        String leaseOwnerId,
        long fencingToken,
        EventEnvelope replanEvent,
        PlanRevision replannedRevision,
        VersionedCheckpoint replannedCheckpoint) {

    public PersistedPlanReplan {
        Objects.requireNonNull(planId, "planId");
        requireText(leaseOwnerId, "leaseOwnerId");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(replanEvent, "replanEvent");
        Objects.requireNonNull(replannedRevision, "replannedRevision");
        Objects.requireNonNull(replannedCheckpoint, "replannedCheckpoint");
        if (replannedCheckpoint.version() < 3) {
            throw new IllegalArgumentException(
                    "replannedCheckpoint.version must be at least 3");
        }
        Checkpoint checkpoint = replannedCheckpoint.checkpoint();
        Set<?> stepIds = replannedRevision.steps().stream()
                .map(PlanStep::id)
                .collect(Collectors.toSet());
        boolean expectedStates = checkpoint.stepStates().keySet().equals(stepIds)
                && replannedRevision.steps().stream().allMatch(step ->
                        checkpoint.stepStates().get(step.id())
                                == (replannedRevision.completedFacts()
                                        .containsKey(step.id())
                                ? StepExecutionState.SUCCEEDED
                                : StepExecutionState.NOT_STARTED));
        if (!planId.equals(replanEvent.planId())
                || !planId.equals(checkpoint.planId())
                || !replanEvent.taskFrameId().equals(checkpoint.taskFrameId())
                || !replannedRevision.taskFrameId().equals(checkpoint.taskFrameId())
                || !checkpoint.revisionId().equals(replannedRevision.id())
                || checkpoint.revisionNumber() != replannedRevision.number()
                || checkpoint.lastEventSequence() != replanEvent.sequence()
                || checkpoint.planState() != PlanExecutionState.ACTIVE
                || !expectedStates) {
            throw new IllegalArgumentException(
                    "replan result components must describe one active replan");
        }
    }

    @Override
    public String toString() {
        return "PersistedPlanReplan["
                + "planId=<provided>, "
                + "leaseOwnerId=<provided>, "
                + "fencingToken=<provided>, "
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
