package io.paperagent.v2.runtime.taskframe;

import io.paperagent.v2.contracts.TaskFrame;

@FunctionalInterface
public interface TaskFrameFreezer {
    TaskFrame freeze(TaskFrameFreezeRequest request);
}
