package io.paperagent.v2.chain;

public interface ChainFinalizationCheckWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.FinalizationCheckRecord>
            appendFinalizationCheck(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.FinalizationCheckRecord> check);
}
