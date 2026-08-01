package com.yanban.api.agent;

import java.util.List;

/** One bounded input to final synthesis, with its authority and provenance made explicit. */
public record SynthesisEvidence(
        String id,
        EvidenceCategory category,
        EvidenceStatus status,
        String statement,
        List<String> basisRefs,
        String projectVersion,
        String path,
        String hash,
        Integer startLine,
        Integer endLine,
        String sourceType,
        ExternalSourceAccess externalAccess,
        ExecutionFact executionFact
) {
    public SynthesisEvidence {
        basisRefs = basisRefs == null ? List.of() : List.copyOf(basisRefs);
        externalAccess = externalAccess == null ? ExternalSourceAccess.UNKNOWN : externalAccess;
    }
}
