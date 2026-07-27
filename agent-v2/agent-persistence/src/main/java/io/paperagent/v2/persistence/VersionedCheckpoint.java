package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;

import java.util.Objects;

public record VersionedCheckpoint(long version, Checkpoint checkpoint) {
    public VersionedCheckpoint {
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        Objects.requireNonNull(checkpoint, "checkpoint");
    }
}
