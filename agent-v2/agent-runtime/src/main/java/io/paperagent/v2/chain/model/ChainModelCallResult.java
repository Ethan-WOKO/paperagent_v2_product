package io.paperagent.v2.chain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Provider transport result. Raw output is intentionally transient. */
public sealed interface ChainModelCallResult permits ChainModelCallResult.Success, ChainModelCallResult.Failure {
    long durationMs();

    String finishReason();

    Map<String, String> safeMetadata();

    record Success(
            String rawOutput,
            String finishReason,
            long durationMs,
            Map<String, String> safeMetadata) implements ChainModelCallResult {
        public Success {
            rawOutput = required(rawOutput, "rawOutput");
            finishReason = required(finishReason, "finishReason");
            nonNegative(durationMs);
            safeMetadata = safe(safeMetadata);
        }
    }

    record Failure(
            String errorCode,
            String finishReason,
            long durationMs,
            Map<String, String> safeMetadata) implements ChainModelCallResult {
        public Failure {
            errorCode = required(errorCode, "errorCode");
            finishReason = required(finishReason, "finishReason");
            nonNegative(durationMs);
            safeMetadata = safe(safeMetadata);
        }
    }

    private static Map<String, String> safe(Map<String, String> values) {
        if (values == null) {
            throw new IllegalArgumentException("safeMetadata must not be null");
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(required(key, "metadata key"), required(value, "metadata value")));
        return Map.copyOf(copy);
    }

    private static void nonNegative(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
