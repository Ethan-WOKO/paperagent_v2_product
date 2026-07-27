package io.paperagent.v2.providers;

import java.util.Map;

public record UsageMetadata(
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        Map<String, Long> additionalCounts) {

    public UsageMetadata {
        requireNonNegative(inputTokens, "usageMetadata.inputTokens");
        requireNonNegative(outputTokens, "usageMetadata.outputTokens");
        requireNonNegative(cachedInputTokens, "usageMetadata.cachedInputTokens");
        additionalCounts = ProviderValues.map(
                additionalCounts,
                "usageMetadata.additionalCounts");
        additionalCounts.forEach((key, value) -> {
            ProviderValues.text(key, "usageMetadata.additionalCounts.key");
            requireNonNegative(value, "usageMetadata.additionalCounts.value");
        });
    }

    private static void requireNonNegative(long value, String path) {
        if (value < 0) {
            ProviderValues.fail(
                    ProviderValidationCode.INVALID_USAGE_COUNT,
                    path,
                    "usage counts must be non-negative");
        }
    }
}
