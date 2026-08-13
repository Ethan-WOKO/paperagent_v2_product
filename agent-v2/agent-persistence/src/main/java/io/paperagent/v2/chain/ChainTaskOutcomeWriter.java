package io.paperagent.v2.chain;

public interface ChainTaskOutcomeWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.TaskOutcomeRecord> appendTaskOutcome(
            ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.TaskOutcomeRecord> outcome);
}
