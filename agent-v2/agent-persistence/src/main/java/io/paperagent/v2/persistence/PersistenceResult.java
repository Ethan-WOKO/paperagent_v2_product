package io.paperagent.v2.persistence;

import java.util.Objects;
import java.util.Optional;

public record PersistenceResult<T>(
        PersistenceOutcome outcome,
        Optional<T> value,
        Optional<PersistenceFailure> failure) {

    public PersistenceResult {
        Objects.requireNonNull(outcome, "outcome");
        value = Objects.requireNonNull(value, "value");
        failure = Objects.requireNonNull(failure, "failure");
        boolean rejected = outcome == PersistenceOutcome.REJECTED;
        if (rejected != failure.isPresent() || rejected == value.isPresent()) {
            throw new IllegalArgumentException("result outcome, value, and failure are inconsistent");
        }
    }

    public static <T> PersistenceResult<T> applied(T value) {
        return successful(PersistenceOutcome.APPLIED, value);
    }

    public static <T> PersistenceResult<T> replayed(T value) {
        return successful(PersistenceOutcome.REPLAYED, value);
    }

    public static <T> PersistenceResult<T> found(T value) {
        return successful(PersistenceOutcome.FOUND, value);
    }

    public static <T> PersistenceResult<T> rejected(PersistenceErrorCode code, String path) {
        return new PersistenceResult<>(
                PersistenceOutcome.REJECTED,
                Optional.empty(),
                Optional.of(new PersistenceFailure(code, path)));
    }

    public boolean successful() {
        return outcome != PersistenceOutcome.REJECTED;
    }

    private static <T> PersistenceResult<T> successful(PersistenceOutcome outcome, T value) {
        return new PersistenceResult<>(
                outcome,
                Optional.of(Objects.requireNonNull(value, "value")),
                Optional.empty());
    }
}
