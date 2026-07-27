package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.TaskFrame;

import java.util.Collections;

final class InMemoryCheckpointRepository implements CheckpointRepository {
    private final InMemoryState state;

    InMemoryCheckpointRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<VersionedCheckpoint> save(
            long expectedVersion,
            Checkpoint checkpoint) {
        if (expectedVersion < 0) {
            return PersistenceChecks.invalid("expectedVersion");
        }
        if (PersistenceChecks.missing(checkpoint)) {
            return PersistenceChecks.invalid("checkpoint");
        }
        synchronized (state.monitor) {
            if (state.executionStarts.containsKey(checkpoint.planId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.EXECUTION_MUTATION_REQUIRES_FENCE,
                        "checkpoint.planId");
            }
            VersionedCheckpoint current = state.checkpoints.get(checkpoint.planId());
            long currentVersion = current == null ? 0 : current.version();
            if (expectedVersion != currentVersion) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION, "expectedVersion");
            }
            TaskFrame taskFrame = state.taskFrames.get(checkpoint.taskFrameId());
            Plan plan = state.plans.get(checkpoint.planId());
            if (taskFrame == null) {
                return PersistenceChecks.notFound("checkpoint.taskFrameId");
            }
            if (plan == null) {
                return PersistenceChecks.notFound("checkpoint.planId");
            }
            Checkpoint previous = current == null ? null : current.checkpoint();
            if (!CheckpointValidators.validate(checkpoint, taskFrame, plan, previous).isEmpty()) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED, "checkpoint");
            }
            if (checkpoint.lastEventSequence() != 0
                    && !state.eventStreams
                            .getOrDefault(
                                    checkpoint.planId(),
                                    Collections.emptyNavigableMap())
                            .containsKey(checkpoint.lastEventSequence())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                        "checkpoint.lastEventSequence");
            }
            VersionedCheckpoint updated =
                    new VersionedCheckpoint(currentVersion + 1, checkpoint);
            state.checkpoints.put(checkpoint.planId(), updated);
            return PersistenceResult.applied(updated);
        }
    }

    @Override
    public PersistenceResult<VersionedCheckpoint> find(PlanId planId) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        synchronized (state.monitor) {
            VersionedCheckpoint checkpoint = state.checkpoints.get(planId);
            return checkpoint == null
                    ? PersistenceChecks.notFound("planId")
                    : PersistenceResult.found(checkpoint);
        }
    }
}
