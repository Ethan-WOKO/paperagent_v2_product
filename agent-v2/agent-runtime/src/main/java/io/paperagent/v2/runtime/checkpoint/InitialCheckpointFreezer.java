package io.paperagent.v2.runtime.checkpoint;

import io.paperagent.v2.contracts.Checkpoint;

@FunctionalInterface
public interface InitialCheckpointFreezer {
    Checkpoint freeze(InitialCheckpointFreezeRequest request);
}
