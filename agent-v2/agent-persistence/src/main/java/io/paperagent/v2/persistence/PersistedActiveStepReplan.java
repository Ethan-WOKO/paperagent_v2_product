package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record PersistedActiveStepReplan(
        PlanId planId,
        PlanStepId supersededStepId,
        String leaseOwnerId,
        long fencingToken,
        EventEnvelope supersessionEvent,
        VersionedCheckpoint supersededCheckpoint,
        EventEnvelope replanEvent,
        PlanRevision replannedRevision,
        VersionedCheckpoint replannedCheckpoint) {

    public PersistedActiveStepReplan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(supersededStepId, "supersededStepId");
        requireText(leaseOwnerId, "leaseOwnerId");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(supersessionEvent, "supersessionEvent");
        Objects.requireNonNull(supersededCheckpoint, "supersededCheckpoint");
        Objects.requireNonNull(replanEvent, "replanEvent");
        Objects.requireNonNull(replannedRevision, "replannedRevision");
        Objects.requireNonNull(replannedCheckpoint, "replannedCheckpoint");
        if (supersededCheckpoint.version() < 4
                || replannedCheckpoint.version()
                != supersededCheckpoint.version() + 1) {
            throw new IllegalArgumentException(
                    "active-step replan checkpoint versions must be consecutive");
        }
        Checkpoint superseded = supersededCheckpoint.checkpoint();
        Checkpoint replanned = replannedCheckpoint.checkpoint();
        Set<PlanStepId> replannedStepIds = replannedRevision.steps().stream()
                .map(PlanStep::id)
                .collect(Collectors.toSet());
        boolean expectedReplannedStates = replanned.stepStates().keySet()
                .equals(replannedStepIds)
                && replannedRevision.steps().stream().allMatch(step ->
                        replanned.stepStates().get(step.id())
                                == (replannedRevision.completedFacts()
                                .containsKey(step.id())
                                ? StepExecutionState.SUCCEEDED
                                : StepExecutionState.NOT_STARTED));
        if (supersessionEvent.id().equals(replanEvent.id())
                || supersessionEvent.sequence() >= replanEvent.sequence()
                || supersessionEvent.occurredAt().isAfter(replanEvent.occurredAt())
                || !planId.equals(supersessionEvent.planId())
                || !planId.equals(replanEvent.planId())
                || !supersessionEvent.taskFrameId().equals(replanEvent.taskFrameId())
                || !planId.equals(superseded.planId())
                || !planId.equals(replanned.planId())
                || !superseded.taskFrameId().equals(replanned.taskFrameId())
                || !supersessionEvent.taskFrameId().equals(superseded.taskFrameId())
                || !replanEvent.taskFrameId().equals(replanned.taskFrameId())
                || !replannedRevision.taskFrameId().equals(replanned.taskFrameId())
                || superseded.planState() != PlanExecutionState.ACTIVE
                || superseded.stepStates().get(supersededStepId)
                != StepExecutionState.SUPERSEDED_BY_REPLAN
                || superseded.lastEventSequence() != supersessionEvent.sequence()
                || replanned.lastEventSequence() != replanEvent.sequence()
                || replanned.planState() != PlanExecutionState.ACTIVE
                || !replanned.revisionId().equals(replannedRevision.id())
                || replanned.revisionNumber() != replannedRevision.number()
                || !expectedReplannedStates) {
            throw new IllegalArgumentException(
                    "active-step replan result components must describe one atomic replan");
        }
    }

    @Override
    public String toString() {
        return "PersistedActiveStepReplan["
                + "planId=<provided>, "
                + "supersededStepId=<provided>, "
                + "leaseOwnerId=<provided>, "
                + "fencingToken=<provided>, "
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
