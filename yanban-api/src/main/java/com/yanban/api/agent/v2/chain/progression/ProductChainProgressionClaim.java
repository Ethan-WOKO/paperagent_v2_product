package com.yanban.api.agent.v2.chain.progression;

import java.time.Instant;

/** Short-lived, task-scoped authority for one product progression tick. */
public record ProductChainProgressionClaim(
        String taskId,
        String ownerId,
        String claimToken,
        long fence,
        long authorityEventCut,
        Instant acquiredAt,
        Instant expiresAt) {
    public ProductChainProgressionClaim {
        requireText(taskId, "taskId");
        requireText(ownerId, "ownerId");
        requireText(claimToken, "claimToken");
        if (fence <= 0) {
            throw new IllegalArgumentException("fence must be positive");
        }
        if (authorityEventCut < 0) {
            throw new IllegalArgumentException(
                    "authorityEventCut must not be negative");
        }
        if (acquiredAt == null || expiresAt == null
                || !expiresAt.isAfter(acquiredAt)) {
            throw new IllegalArgumentException(
                    "expiresAt must be after acquiredAt");
        }
    }

    private static void requireText(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be blank");
        }
    }
}
