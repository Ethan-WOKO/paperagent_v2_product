package com.yanban.api.agent;

public record AgentMemorySelectionRef(
        String stableId,
        String version,
        int rank,
        String digest,
        String projection
) {
    public AgentMemorySelectionRef {
        stableId = bounded(stableId, "stableId", 256);
        version = bounded(version, "version", 128);
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
        if (digest == null || !digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("digest must be lowercase SHA-256");
        }
        if (projection != null && (projection.isBlank()
                || projection.length() > 4_000)) {
            throw new IllegalArgumentException("projection is invalid");
        }
    }

    public AgentMemorySelectionRef(
            String stableId,
            String version,
            int rank,
            String digest) {
        this(stableId, version, rank, digest, null);
    }

    private static String bounded(String value, String name, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
