package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;

import java.time.Instant;
import java.util.Objects;

/**
 * A committed lease generation.
 *
 * <p>{@code acquiredAt} is the persistence adapter's trusted effective commit
 * time. It is retained by renewals; callers do not supply observation time.
 */
public record LeaseRecord(
        PlanId planId,
        String ownerId,
        String leaseToken,
        long fencingToken,
        Instant acquiredAt,
        Instant expiresAt) {

    public LeaseRecord {
        Objects.requireNonNull(planId, "planId");
        requireText(ownerId, "ownerId");
        requireText(leaseToken, "leaseToken");
        if (fencingToken < 1) {
            throw new IllegalArgumentException("fencingToken must be positive");
        }
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(acquiredAt)) {
            throw new IllegalArgumentException("expiresAt must be after acquiredAt");
        }
    }

    public boolean isExpiredAt(Instant instant) {
        return !instant.isBefore(expiresAt);
    }

    private static void requireText(String value, String path) {
        Objects.requireNonNull(value, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
