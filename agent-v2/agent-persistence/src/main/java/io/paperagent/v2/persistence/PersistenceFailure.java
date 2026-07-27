package io.paperagent.v2.persistence;

import java.util.Objects;

public record PersistenceFailure(PersistenceErrorCode code, String path) {
    public PersistenceFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
    }
}
