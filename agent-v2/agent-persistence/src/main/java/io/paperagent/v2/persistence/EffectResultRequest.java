package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.ExecutionReceipt;

import java.util.Objects;

public record EffectResultRequest(
        ExecutionReceipt receipt,
        String leaseToken,
        long fencingToken) {

    public EffectResultRequest {
        Objects.requireNonNull(receipt, "receipt");
        requireText(leaseToken, "leaseToken");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }

    @Override
    public String toString() {
        return "EffectResultRequest["
                + "receipt=<provided>, "
                + "leaseToken=<provided>, "
                + "fencingToken=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
