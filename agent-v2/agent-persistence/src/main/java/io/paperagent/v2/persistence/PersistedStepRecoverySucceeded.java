package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.TaskFrame;

import java.util.Objects;
import java.util.Optional;

/** Immutable terminal successful Plan cut. */
public record PersistedStepRecoverySucceeded(
        TaskFrame taskFrame,
        Plan plan,
        VersionedCheckpoint checkpoint,
        Optional<PersistedPlanExecutionContextConfirmed> executionContext)
        implements StepRecoverySnapshot {

    public PersistedStepRecoverySucceeded {
        Objects.requireNonNull(taskFrame, "taskFrame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(checkpoint, "checkpoint");
        executionContext = Objects.requireNonNull(
                executionContext, "executionContext");
    }

    @Override
    public PlanId planId() {
        return plan.id();
    }

    @Override
    public String toString() {
        return "PersistedStepRecoverySucceeded["
                + "taskFrame=<provided>, plan=<provided>, "
                + "checkpoint=<provided>, executionContext=<provided>]";
    }
}
