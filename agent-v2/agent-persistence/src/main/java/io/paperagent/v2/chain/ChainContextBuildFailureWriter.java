package io.paperagent.v2.chain;

/** Sole append boundary for a typed Context build failure authority. */
public interface ChainContextBuildFailureWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<
            ChainPersistenceRecords.ContextBuildFailureRecord>
            appendContextBuildFailure(
                    ChainPersistenceRecords.AuthoritativeFact<
                            ChainPersistenceRecords.ContextBuildFailureRecord> failure);
}
