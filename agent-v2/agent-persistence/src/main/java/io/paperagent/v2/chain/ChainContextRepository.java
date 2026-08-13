package io.paperagent.v2.chain;

import java.util.List;
import java.util.Optional;

public interface ChainContextRepository {
    Optional<ChainPersistenceRecords.ContextRevisionRecord> findContextRevision(String contextRevisionId);

    List<ChainPersistenceRecords.ContextRevisionRecord> findContextRevisions(
            String taskId);

    List<ChainPersistenceRecords.ContextModuleRecord> findContextModules(String contextRevisionId);
}
