package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;

import java.util.Objects;

public record PersistedEffectIntent(
        EffectIntent intent,
        String leaseOwnerId,
        long fencingToken,
        EventId activationEventId) {

    public PersistedEffectIntent {
        Objects.requireNonNull(intent, "intent");
        requireText(leaseOwnerId, "leaseOwnerId");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(activationEventId, "activationEventId");
    }

    @Override
    public String toString() {
        return "PersistedEffectIntent["
                + "intent=<provided>, "
                + "leaseOwnerId=<provided>, "
                + "fencingToken=<provided>, "
                + "activationEventId=<provided>]";
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
