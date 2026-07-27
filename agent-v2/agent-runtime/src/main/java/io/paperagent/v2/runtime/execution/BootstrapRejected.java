package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.persistence.PersistenceFailure;

import java.util.Objects;

public record BootstrapRejected(PersistenceFailure failure)
        implements FreshExecutionDecision {
    public BootstrapRejected {
        Objects.requireNonNull(failure, "failure");
    }
}
