package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;

import java.util.Objects;

public record EffectIntentRequest(
        EffectIntent intent,
        String leaseToken,
        long fencingToken,
        EventId expectedActivationEventId) {

    public EffectIntentRequest {
        Objects.requireNonNull(intent, "intent");
        requireText(leaseToken, "leaseToken");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(expectedActivationEventId, "expectedActivationEventId");
    }

    @Override
    public String toString() {
        return "EffectIntentRequest["
                + "intent=<provided>, "
                + "leaseToken=<provided>, "
                + "fencingToken=<provided>, "
                + "expectedActivationEventId=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
