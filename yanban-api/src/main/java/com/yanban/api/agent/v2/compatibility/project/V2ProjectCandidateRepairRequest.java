package com.yanban.api.agent.v2.compatibility.project;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-only immutable authority for one failed Candidate validation repair Plan. */
public record V2ProjectCandidateRepairRequest(
        String sourceValidationId,
        long sourceCandidateArtifactId,
        String sourceCandidateFingerprint,
        int selectedChangeIndex,
        String selectedPath,
        String failedReceiptDigest,
        String originalProjectVersion,
        int attempt,
        int maxAttempts,
        Map<String, String> sourceReplacements,
        String compilerDiagnostic) {
    public V2ProjectCandidateRepairRequest {
        sourceReplacements = sourceReplacements == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sourceReplacements));
    }
}
