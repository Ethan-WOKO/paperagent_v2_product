package io.paperagent.v2.persistence;

import java.util.Objects;
import java.util.Optional;

public record IdempotencyRecord(
        IdempotencyKey key,
        String requestFingerprint,
        IdempotencyState state,
        Optional<String> resultReference) {

    public IdempotencyRecord {
        Objects.requireNonNull(key, "key");
        requestFingerprint = requireText(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(state, "state");
        resultReference = Objects.requireNonNull(resultReference, "resultReference")
                .map(value -> requireText(value, "resultReference"));
        if (state == IdempotencyState.IN_PROGRESS && resultReference.isPresent()) {
            throw new IllegalArgumentException("an in-progress record cannot have a result reference");
        }
    }

    private static String requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
        return value;
    }
}
