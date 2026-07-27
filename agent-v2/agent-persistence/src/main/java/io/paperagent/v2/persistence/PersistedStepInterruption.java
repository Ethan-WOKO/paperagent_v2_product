package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;

import java.util.Objects;

public record PersistedStepInterruption(
        PlanId planId,
        PlanStepId stepId,
        StepInterruptionKind kind,
        String leaseOwnerId,
        long fencingToken,
        EventEnvelope interruptionEvent,
        VersionedCheckpoint interruptedCheckpoint) {

    public PersistedStepInterruption {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(kind, "kind");
        requireText(leaseOwnerId, "leaseOwnerId");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(interruptionEvent, "interruptionEvent");
        Objects.requireNonNull(interruptedCheckpoint, "interruptedCheckpoint");
        if (interruptedCheckpoint.version() < 4) {
            throw new IllegalArgumentException(
                    "interruptedCheckpoint.version must be at least 4");
        }
        StepExecutionState expectedStepState = stepState(kind);
        PlanExecutionState expectedPlanState = planState(kind);
        if (!interruptionEvent.planId().equals(planId)
                || !interruptedCheckpoint.checkpoint().planId().equals(planId)
                || !interruptionEvent.taskFrameId().equals(
                        interruptedCheckpoint.checkpoint().taskFrameId())
                || interruptionEvent.sequence()
                        != interruptedCheckpoint.checkpoint().lastEventSequence()
                || interruptedCheckpoint.checkpoint().stepStates().get(stepId)
                        != expectedStepState
                || interruptedCheckpoint.checkpoint().planState()
                        != expectedPlanState) {
            throw new IllegalArgumentException(
                    "interruption components must describe one fixed authority");
        }
    }

    @Override
    public String toString() {
        return "PersistedStepInterruption["
                + "planId=<provided>, "
                + "stepId=<provided>, "
                + "kind=<provided>, "
                + "leaseOwnerId=<provided>, "
                + "fencingToken=<provided>, "
                + "interruptionEvent=<provided>, "
                + "interruptedCheckpoint=<provided>]";
    }

    private static StepExecutionState stepState(StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> StepExecutionState.PAUSED;
            case FAIL -> StepExecutionState.FAILED;
            case CANCEL -> StepExecutionState.CANCELLED;
        };
    }

    private static PlanExecutionState planState(StepInterruptionKind kind) {
        return switch (kind) {
            case PAUSE -> PlanExecutionState.PAUSED;
            case FAIL -> PlanExecutionState.FAILED;
            case CANCEL -> PlanExecutionState.CANCELLED;
        };
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
