package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;

import java.util.Objects;

/** One provider attempt bound to an already COMPLETE immutable ContextRevision. */
public record ChainModelCallRequest(
        String invocationId,
        String contextRevisionId,
        String completionToken,
        ChainRole role,
        ChainWorkState workState,
        String callReason,
        String expectedProvider,
        String expectedModel,
        String canonicalPrompt,
        int attemptNo,
        boolean protocolRepair,
        String repairFeedback,
        String previousInvalidOutput) {
    public ChainModelCallRequest(
            String invocationId, String contextRevisionId,
            String completionToken, ChainRole role,
            ChainWorkState workState, String callReason,
            String expectedProvider, String expectedModel,
            String canonicalPrompt, int attemptNo,
            boolean protocolRepair, String repairFeedback) {
        this(invocationId, contextRevisionId, completionToken, role,
                workState, callReason, expectedProvider, expectedModel,
                canonicalPrompt, attemptNo, protocolRepair, repairFeedback,
                null);
    }

    public ChainModelCallRequest {
        invocationId = required(invocationId, "invocationId");
        contextRevisionId = required(contextRevisionId, "contextRevisionId");
        completionToken = required(completionToken, "completionToken");
        role = Objects.requireNonNull(role, "role");
        workState = Objects.requireNonNull(workState, "workState");
        callReason = required(callReason, "callReason");
        expectedProvider = required(expectedProvider, "expectedProvider");
        expectedModel = required(expectedModel, "expectedModel");
        canonicalPrompt = required(canonicalPrompt, "canonicalPrompt");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        if (protocolRepair != (repairFeedback != null)) {
            throw new IllegalArgumentException("protocol repair and feedback must be paired");
        }
        if (repairFeedback != null) {
            repairFeedback = required(repairFeedback, "repairFeedback");
        }
        if (previousInvalidOutput != null) {
            if (!protocolRepair) {
                throw new IllegalArgumentException(
                        "previous invalid output is only legal during protocol repair");
            }
            previousInvalidOutput = required(previousInvalidOutput,
                    "previousInvalidOutput");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
