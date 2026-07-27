package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;

import java.util.Objects;

public record PersistedEffectResult(
        ExecutionReceipt receipt,
        String leaseOwnerId,
        long fencingToken) {

    public PersistedEffectResult {
        Objects.requireNonNull(receipt, "receipt");
        requireText(leaseOwnerId, "leaseOwnerId");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }

    @Override
    public String toString() {
        return "PersistedEffectResult["
                + "receipt=<provided>, "
                + "leaseOwnerId=<provided>, "
                + "fencingToken=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
