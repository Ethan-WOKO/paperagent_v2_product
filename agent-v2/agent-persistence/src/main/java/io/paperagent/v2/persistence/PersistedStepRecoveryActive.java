package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.TaskFrame;

import java.util.Objects;
import java.util.Optional;

public record PersistedStepRecoveryActive(
        TaskFrame taskFrame,
        Plan plan,
        VersionedCheckpoint checkpoint,
        PersistedStepActivation activation,
        Optional<PersistedPlanExecutionContextConfirmed> executionContext)
        implements StepRecoverySnapshot {

    public PersistedStepRecoveryActive {
        Objects.requireNonNull(taskFrame, "taskFrame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(activation, "activation");
        executionContext = Objects.requireNonNull(
                executionContext, "executionContext");
    }

    @Override
    public PlanId planId() {
        return plan.id();
    }

    @Override
    public String toString() {
        return "PersistedStepRecoveryActive["
                + "taskFrame=<provided>, "
                + "plan=<provided>, "
                + "checkpoint=<provided>, "
                + "activation=<provided>, "
                + "executionContext=<provided>]";
    }
}
