package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;

public interface TaskFrameRepository {
    PersistenceResult<TaskFrame> create(TaskFrame taskFrame);

    PersistenceResult<TaskFrame> find(TaskFrameId taskFrameId);
}
