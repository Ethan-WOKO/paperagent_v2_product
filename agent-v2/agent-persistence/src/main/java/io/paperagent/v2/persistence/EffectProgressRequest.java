package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EffectProgress;

import java.util.Objects;

public record EffectProgressRequest(
        EffectProgress progress,
        String leaseToken,
        long fencingToken) {

    public EffectProgressRequest {
        Objects.requireNonNull(progress, "progress");
        requireText(leaseToken, "leaseToken");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
    }

    @Override
    public String toString() {
        return "EffectProgressRequest["
                + "progress=<provided>, "
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
