package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;

final class InMemoryTaskFrameRepository implements TaskFrameRepository {
    private final InMemoryState state;

    InMemoryTaskFrameRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<TaskFrame> create(TaskFrame taskFrame) {
        if (PersistenceChecks.missing(taskFrame)) {
            return PersistenceChecks.invalid("taskFrame");
        }
        synchronized (state.monitor) {
            TaskFrame existing = state.taskFrames.get(taskFrame.id());
            if (existing == null) {
                state.taskFrames.put(taskFrame.id(), taskFrame);
                return PersistenceResult.applied(taskFrame);
            }
            if (existing.equals(taskFrame)) {
                return PersistenceResult.replayed(existing);
            }
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CONFLICTING_REPLAY, "taskFrame.id");
        }
    }

    @Override
    public PersistenceResult<TaskFrame> find(TaskFrameId taskFrameId) {
        if (PersistenceChecks.missing(taskFrameId)) {
            return PersistenceChecks.invalid("taskFrameId");
        }
        synchronized (state.monitor) {
            TaskFrame taskFrame = state.taskFrames.get(taskFrameId);
            return taskFrame == null
                    ? PersistenceChecks.notFound("taskFrameId")
                    : PersistenceResult.found(taskFrame);
        }
    }
}
