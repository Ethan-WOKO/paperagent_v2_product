package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.TaskFrame;

import java.util.Objects;
import java.util.Optional;

/** Durable gap between one committed completion and the next activation. */
public record PersistedStepRecoveryReady(
        TaskFrame taskFrame,
        Plan plan,
        VersionedCheckpoint checkpoint,
        PlanStepId readyStepId,
        Optional<PersistedPlanExecutionContextConfirmed> executionContext)
        implements StepRecoverySnapshot {

    public PersistedStepRecoveryReady {
        Objects.requireNonNull(taskFrame, "taskFrame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(readyStepId, "readyStepId");
        executionContext = Objects.requireNonNull(
                executionContext, "executionContext");
    }

    @Override
    public PlanId planId() {
        return plan.id();
    }

    @Override
    public String toString() {
        return "PersistedStepRecoveryReady["
                + "taskFrame=<provided>, plan=<provided>, "
                + "checkpoint=<provided>, readyStepId=<provided>, "
                + "executionContext=<provided>]";
    }
}
