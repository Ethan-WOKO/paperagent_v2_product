package io.paperagent.v2.chain;

public interface ChainApplicabilityWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.ResultApplicabilityRecord>
            appendApplicability(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.ResultApplicabilityRecord> applicability);
}
