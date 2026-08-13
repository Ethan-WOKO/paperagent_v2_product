package com.yanban.api.agent.v2.chain.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Shared deterministic identities for selector-visible model contexts. */
public final class ProductChainContextIdentity {
    private ProductChainContextIdentity() {
    }

    public static String taskOutcomeAnswer(
            String taskId, String outcomeId) {
        return "context." + sha256(required(taskId, "taskId")
                + "\0ANSWER\0" + required(outcomeId, "outcomeId"));
    }

    public static String pendingItemAnswer(
            String taskId, String gapId, String workState) {
        return "context." + sha256(required(taskId, "taskId")
                + "\0ANSWER_PENDING\0" + required(gapId, "gapId")
                + "\0" + required(workState, "workState"));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
