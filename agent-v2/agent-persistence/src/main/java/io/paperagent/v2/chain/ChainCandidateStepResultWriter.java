package io.paperagent.v2.chain;

public interface ChainCandidateStepResultWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.CandidateStepResultRecord>
            appendCandidateStepResult(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.CandidateStepResultRecord> result);
}
