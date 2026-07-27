package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.TaskFrame;

public interface PlanBootstrapRepository {
    PersistenceResult<PersistedPlanBootstrap> bootstrap(
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint checkpoint);
}
