package com.yanban.api.agent.v2.context.runtime;

import com.yanban.api.agent.v2.context.ContextSectionType;
import com.yanban.api.agent.v2.context.V2ContextStage;
import com.yanban.api.agent.v2.context.V2ContextSectionDraft;
import java.util.List;

public record V2ContextBoundaryRequest(
        Long userId,
        Long sessionId,
        Long turnId,
        Long parentSnapshotId,
        String parentDigest,
        V2ContextStage stage,
        List<String> canonicalAuthorityTuple,
        String subCall,
        int attempt,
        String modelProvider,
        String model,
        long contextWindowTokens,
        long maxOutputTokens,
        String tokenCounterVersion,
        String profileVersion,
        long outputReserveTokens,
        List<V2ContextSectionDraft> sections,
        ContextSectionType compactionTarget,
        List<V2ContextPhaseRevision> phaseRevisions
) {
    public V2ContextBoundaryRequest {
        if (userId == null || sessionId == null || turnId == null
                || stage == null || contextWindowTokens <= 0
                || maxOutputTokens <= 0 || sections == null
                || sections.isEmpty() || phaseRevisions == null
                || phaseRevisions.isEmpty()) {
            throw new IllegalArgumentException("boundary request is invalid");
        }
        if (canonicalAuthorityTuple == null || canonicalAuthorityTuple.isEmpty()
                || subCall == null || subCall.isBlank() || attempt < 1) {
            throw new IllegalArgumentException("authority cut is invalid");
        }
        if ((parentSnapshotId == null) != (parentDigest == null)) {
            throw new IllegalArgumentException("parent identity must be complete");
        }
        if (parentDigest != null && !parentDigest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("parent digest is invalid");
        }
        sections = List.copyOf(sections);
        canonicalAuthorityTuple = List.copyOf(canonicalAuthorityTuple);
        phaseRevisions = List.copyOf(phaseRevisions);
        if (phaseRevisions.stream().map(V2ContextPhaseRevision::revisionNumber)
                .distinct().count() != phaseRevisions.size()) {
            throw new IllegalArgumentException("phase revisions must be unique");
        }
    }
}
