package com.yanban.api.agent.v2.adaptive.reflection;

import java.util.List;
import java.util.Objects;

/** Bounded persisted Step-result proposal supplied to reflection. */
public record ReflectionStepResult(
        String resultId,
        String stepId,
        String source,
        String proposedText,
        String proposedSha256,
        List<String> evidenceReceiptIds) {
    public ReflectionStepResult {
        resultId = required(resultId, "resultId");
        stepId = required(stepId, "stepId");
        source = required(source, "source");
        proposedText = required(proposedText, "proposedText");
        proposedSha256 = required(proposedSha256, "proposedSha256");
        evidenceReceiptIds = List.copyOf(
                Objects.requireNonNull(
                        evidenceReceiptIds, "evidenceReceiptIds"));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
