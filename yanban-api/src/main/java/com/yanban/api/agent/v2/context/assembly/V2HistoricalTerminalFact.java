package com.yanban.api.agent.v2.context.assembly;

public record V2HistoricalTerminalFact(
        Long userId,
        Long sessionId,
        Long turnId,
        String stableId,
        String version,
        int rank,
        String digest,
        String finalStatus,
        String errorCode,
        String planId,
        String resultId,
        String candidateArtifactId
) {
    public V2HistoricalTerminalFact {
        planId = optionalBounded(planId, "planId", 128);
        resultId = optionalBounded(resultId, "resultId", 128);
        candidateArtifactId = optionalBounded(
                candidateArtifactId, "candidateArtifactId", 256);
        if (userId == null || sessionId == null || turnId == null
                || stableId == null || stableId.isBlank()
                || stableId.length() > 256
                || version == null || version.isBlank()
                || version.length() > 128 || rank < 1
                || digest == null || !digest.matches("[a-f0-9]{64}")
                || !("SUCCEEDED".equals(finalStatus)
                    || "FAILED".equals(finalStatus)
                    || "WAITING_CONFIRMATION".equals(finalStatus))
                || (planId == null && resultId == null
                    && candidateArtifactId == null)) {
            throw new IllegalArgumentException(
                    "historical terminal fact is invalid");
        }
    }

    private static String optionalBounded(
            String value,
            String name,
            int maximum) {
        if (value == null) return null;
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.trim();
    }
}
