package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;

import java.time.Instant;
import java.util.Objects;

public record ChainModelProtocolRequest(
        String taskId,
        String invocationId,
        String contextRevisionId,
        String completionToken,
        ChainRole role,
        ChainWorkState workState,
        String callReason,
        String provider,
        String model,
        int invocationOrdinal,
        String boundGapId,
        Instant createdAt) {
    public ChainModelProtocolRequest {
        taskId = required(taskId, "taskId");
        invocationId = required(invocationId, "invocationId");
        contextRevisionId = required(contextRevisionId, "contextRevisionId");
        completionToken = required(completionToken, "completionToken");
        role = Objects.requireNonNull(role, "role");
        workState = Objects.requireNonNull(workState, "workState");
        callReason = required(callReason, "callReason");
        provider = required(provider, "provider");
        model = required(model, "model");
        if (invocationOrdinal < 1) {
            throw new IllegalArgumentException("invocationOrdinal must be positive");
        }
        if (workState == ChainWorkState.VALIDATING_PENDING_ITEM) {
            boundGapId = required(boundGapId, "boundGapId");
        } else if (boundGapId != null) {
            throw new IllegalArgumentException("boundGapId requires VALIDATING_PENDING_ITEM");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
