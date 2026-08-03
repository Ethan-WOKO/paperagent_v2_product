package com.yanban.api.agent.v2.context;

import java.util.List;

public record V2ContextRevisionDraft(
        Long userId,
        Long sessionId,
        Long turnId,
        int revisionNumber,
        Long parentSnapshotId,
        String parentDigest,
        V2ContextStage stage,
        String stableStageKey,
        V2ContextRevisionStatus status,
        String modelProvider,
        String model,
        long contextWindowTokens,
        long maxOutputTokens,
        String tokenCounterVersion,
        String profileVersion,
        long totalTokens,
        long outputReserveTokens,
        List<V2ContextSectionDraft> sections) {

    public V2ContextRevisionDraft {
        positive(userId, "userId");
        positive(sessionId, "sessionId");
        positive(turnId, "turnId");
        if (revisionNumber <= 0) throw new IllegalArgumentException("revisionNumber must be positive");
        if (stage == null || status == null) throw new IllegalArgumentException("stage and status are required");
        stableStageKey = bounded(stableStageKey, 255, "stableStageKey");
        modelProvider = bounded(modelProvider, 64, "modelProvider");
        model = bounded(model, 128, "model");
        tokenCounterVersion = bounded(tokenCounterVersion, 64, "tokenCounterVersion");
        profileVersion = bounded(profileVersion, 64, "profileVersion");
        if (contextWindowTokens <= 0 || maxOutputTokens <= 0
                || maxOutputTokens > contextWindowTokens) {
            throw new IllegalArgumentException("model token limits are invalid");
        }
        if (totalTokens < 0 || outputReserveTokens < 0) {
            throw new IllegalArgumentException("revision token values must not be negative");
        }
        if (revisionNumber == 1 && (parentSnapshotId != null || parentDigest != null)) {
            throw new IllegalArgumentException("revision 1 cannot have a parent");
        }
        if (revisionNumber > 1 && (parentSnapshotId == null || parentDigest == null)) {
            throw new IllegalArgumentException("later revisions require a direct parent");
        }
        if (parentDigest != null && !parentDigest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("parentDigest must be lowercase SHA-256");
        }
        sections = sections == null ? List.of() : List.copyOf(sections);
        if (sections.isEmpty()) throw new IllegalArgumentException("sections are required");
        long distinct = sections.stream().map(V2ContextSectionDraft::type).distinct().count();
        if (distinct != sections.size()) throw new IllegalArgumentException("section types must be unique");
    }

    private static void positive(Long value, String field) {
        if (value == null || value <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    private static String bounded(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }
}
