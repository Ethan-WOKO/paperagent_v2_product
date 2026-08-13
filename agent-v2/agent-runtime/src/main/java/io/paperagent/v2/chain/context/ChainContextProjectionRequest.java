package io.paperagent.v2.chain.context;

import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;

import java.util.List;
import java.util.Objects;

public record ChainContextProjectionRequest(
        ContextRevisionRecord buildingRevision,
        int maxRequestCharacters) {
    public ChainContextProjectionRequest {
        Objects.requireNonNull(buildingRevision, "buildingRevision");
        if (maxRequestCharacters < 1) {
            throw new IllegalArgumentException("maxRequestCharacters must be positive");
        }
    }

    public List<String> requiredFields(ChainContextModule module) {
        return ChainContextInputMatrix.requiredProjectionFields(buildingRevision.role(), module);
    }
}
