package com.yanban.api.agent.v2.context.runtime;

import com.yanban.api.agent.v2.context.V2ContextSectionDraft;

public record V2SectionCompactionResult(
        boolean success,
        long targetTokens,
        long tokensBefore,
        long tokensAfter,
        String oldProjectionDigest,
        String newProjectionDigest,
        String keptRefsJson,
        String removedRefsJson,
        V2ContextSectionDraft section,
        String code
) {
    public V2SectionCompactionResult {
        if (targetTokens < 0 || tokensBefore < 0 || tokensAfter < 0
                || oldProjectionDigest == null
                || !oldProjectionDigest.matches("[a-f0-9]{64}")
                || newProjectionDigest == null
                || !newProjectionDigest.matches("[a-f0-9]{64}")
                || keptRefsJson == null || removedRefsJson == null
                || section == null || code == null || code.isBlank()) {
            throw new IllegalArgumentException("compaction result is invalid");
        }
    }
}
