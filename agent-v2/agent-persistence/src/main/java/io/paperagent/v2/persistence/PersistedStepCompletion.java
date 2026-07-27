package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;

import java.util.Objects;

public record PersistedStepCompletion(
        PlanId planId,
        PlanStepId stepId,
        String leaseOwnerId,
        long fencingToken,
        EventEnvelope completionEvent,
        PlanRevision completedRevision,
        VersionedCheckpoint completedCheckpoint) {

    public PersistedStepCompletion {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(stepId, "stepId");
        requireText(leaseOwnerId, "leaseOwnerId");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(completionEvent, "completionEvent");
        Objects.requireNonNull(completedRevision, "completedRevision");
        Objects.requireNonNull(completedCheckpoint, "completedCheckpoint");
        if (completedCheckpoint.version() < 4) {
            throw new IllegalArgumentException(
                    "completedCheckpoint.version must be at least 4");
        }
        Checkpoint checkpoint = completedCheckpoint.checkpoint();
        boolean allStepsSucceeded = checkpoint.stepStates().values().stream()
                .allMatch(state -> state == StepExecutionState.SUCCEEDED);
        if (!completionEvent.planId().equals(planId)
                || !checkpoint.planId().equals(planId)
                || !completionEvent.taskFrameId().equals(checkpoint.taskFrameId())
                || !completedRevision.taskFrameId().equals(checkpoint.taskFrameId())
                || !checkpoint.revisionId().equals(completedRevision.id())
                || checkpoint.revisionNumber() != completedRevision.number()
                || checkpoint.lastEventSequence() != completionEvent.sequence()
                || checkpoint.stepStates().get(stepId) != StepExecutionState.SUCCEEDED
                || completedRevision.completedFacts().get(stepId) == null
                || !completedRevision.completedFacts().get(stepId).stepId().equals(stepId)
                || allStepsSucceeded
                        && checkpoint.planState() != PlanExecutionState.SUCCEEDED
                || !allStepsSucceeded
                        && checkpoint.planState() != PlanExecutionState.ACTIVE) {
            throw new IllegalArgumentException(
                    "completion components must describe one succeeded completion");
        }
    }

    @Override
    public String toString() {
        return "PersistedStepCompletion["
                + "planId=<provided>, "
                + "stepId=<provided>, "
                + "leaseOwnerId=<provided>, "
                + "fencingToken=<provided>, "
                + "completionEvent=<provided>, "
                + "completedRevision=<provided>, "
                + "completedCheckpoint=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
