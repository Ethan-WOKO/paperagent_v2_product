package io.paperagent.v2.chain;

public interface ChainCandidateMaterializationFailureWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<
            ChainPersistenceRecords.CandidateMaterializationFailureRecord>
            appendCandidateMaterializationFailure(
                    ChainPersistenceRecords.AuthoritativeFact<
                            ChainPersistenceRecords.CandidateMaterializationFailureRecord>
                            failure);
}
