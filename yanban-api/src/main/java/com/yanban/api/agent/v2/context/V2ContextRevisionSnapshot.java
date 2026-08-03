package com.yanban.api.agent.v2.context;

import java.util.List;

public record V2ContextRevisionSnapshot(
        Long id,
        V2ContextRevisionOutcome outcome,
        V2ContextRevisionDraft revision,
        String canonicalJson,
        String contextDigest,
        List<String> projectionDigests) {
    public V2ContextRevisionSnapshot {
        if (id == null || id <= 0 || outcome == null || revision == null
                || canonicalJson == null || canonicalJson.isBlank()
                || contextDigest == null || !contextDigest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("revision snapshot is invalid");
        }
        projectionDigests = List.copyOf(projectionDigests);
    }
}
