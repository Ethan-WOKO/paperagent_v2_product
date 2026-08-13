package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextRevisionStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;

import java.util.Objects;

public record ChainContextFreezeRequest(
        ContextRevisionRecord buildingRevision,
        int maxRequestCharacters) {
    public ChainContextFreezeRequest {
        Objects.requireNonNull(buildingRevision, "buildingRevision");
        if (buildingRevision.status() != ChainContextRevisionStatus.BUILDING
                || buildingRevision.moduleCount() != 0) {
            throw new ChainContextException(
                    ChainContextErrorCode.CONTEXT_REVISION_NOT_BUILDING,
                    "a context freeze must start from a zero-module BUILDING revision");
        }
        if (maxRequestCharacters < 1) {
            throw new IllegalArgumentException("maxRequestCharacters must be positive");
        }
    }
}
