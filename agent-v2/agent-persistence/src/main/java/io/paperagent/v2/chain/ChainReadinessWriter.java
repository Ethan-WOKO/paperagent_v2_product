package io.paperagent.v2.chain;

public interface ChainReadinessWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.FinalizationReadinessRecord>
            appendReadiness(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.FinalizationReadinessRecord> readiness);
}
