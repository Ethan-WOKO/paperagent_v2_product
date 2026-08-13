package io.paperagent.v2.chain;

import java.util.Optional;

public interface ChainCandidateMaterializationFailureRepository {
    Optional<ChainPersistenceRecords.CandidateMaterializationFailureRecord>
            findCandidateMaterializationFailure(String taskId, String actionId);
}
