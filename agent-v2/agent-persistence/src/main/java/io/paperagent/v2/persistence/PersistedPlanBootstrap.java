package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.TaskFrame;

import java.util.Objects;

public record PersistedPlanBootstrap(
        TaskFrame taskFrame,
        Plan plan,
        VersionedCheckpoint initialCheckpoint) {

    public PersistedPlanBootstrap {
        Objects.requireNonNull(taskFrame, "taskFrame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(initialCheckpoint, "initialCheckpoint");
        if (initialCheckpoint.version() != 1) {
            throw new IllegalArgumentException("initialCheckpoint version must be 1");
        }
    }
}
