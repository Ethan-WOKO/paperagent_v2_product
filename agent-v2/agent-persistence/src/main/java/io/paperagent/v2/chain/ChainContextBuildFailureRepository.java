package io.paperagent.v2.chain;

import java.util.Optional;

/** Read side for a Context source failure committed before all thirteen modules froze. */
public interface ChainContextBuildFailureRepository {
    Optional<ChainPersistenceRecords.ContextBuildFailureRecord>
            findContextBuildFailure(String contextRevisionId);
}
