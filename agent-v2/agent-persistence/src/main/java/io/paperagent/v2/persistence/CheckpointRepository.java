package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.PlanId;

public interface CheckpointRepository {
    PersistenceResult<VersionedCheckpoint> save(long expectedVersion, Checkpoint checkpoint);

    PersistenceResult<VersionedCheckpoint> find(PlanId planId);
}
