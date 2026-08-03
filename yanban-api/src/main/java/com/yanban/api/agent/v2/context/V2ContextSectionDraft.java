package com.yanban.api.agent.v2.context;

public record V2ContextSectionDraft(
        ContextSectionType type,
        int fixedPercentage,
        long tokenLimit,
        long tokensBefore,
        long tokensAfter,
        V2ContextSectionStatus status,
        String sourceRefsJson,
        String projectionJson,
        String compactionReason) {

    public V2ContextSectionDraft {
        if (type == null || status == null) {
            throw new IllegalArgumentException("section type and status are required");
        }
        if (fixedPercentage != type.percentage()) {
            throw new IllegalArgumentException("section percentage does not match profile");
        }
        if (tokenLimit < 0 || tokensBefore < 0 || tokensAfter < 0) {
            throw new IllegalArgumentException("section token values must not be negative");
        }
        sourceRefsJson = requiredJson(sourceRefsJson, "sourceRefsJson");
        projectionJson = requiredJson(projectionJson, "projectionJson");
        compactionReason = blankToNull(compactionReason);
    }

    private static String requiredJson(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
