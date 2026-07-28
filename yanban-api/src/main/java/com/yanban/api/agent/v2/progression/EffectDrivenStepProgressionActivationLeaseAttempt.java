package com.yanban.api.agent.v2.progression;

import java.time.Instant;
import java.util.Objects;

/**
 * Caller-owned lease facts for activating the next READY Step.
 *
 * <p>Event identity, payload and timestamps are deliberately absent: they are
 * derived by the progression composition from committed persistence facts.
 */
public record EffectDrivenStepProgressionActivationLeaseAttempt(
        String leaseOwnerId,
        String leaseToken,
        Instant leaseExpiresAt) {

    public EffectDrivenStepProgressionActivationLeaseAttempt {
        requireText(leaseOwnerId, "leaseOwnerId");
        requireText(leaseToken, "leaseToken");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }

    @Override
    public String toString() {
        return "EffectDrivenStepProgressionActivationLeaseAttempt["
                + "leaseOwnerId=<provided>, leaseToken=<redacted>, "
                + "leaseExpiresAt=<provided>]";
    }
}
