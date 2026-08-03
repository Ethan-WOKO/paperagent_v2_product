package com.yanban.api.agent;

public record AgentRagSelectionRef(
        String stableId,
        String version,
        int rank,
        String digest
) {
    public AgentRagSelectionRef {
        stableId = bounded(stableId, "stableId", 256);
        version = bounded(version, "version", 128);
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
        if (digest == null || !digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("digest must be lowercase SHA-256");
        }
    }

    private static String bounded(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
