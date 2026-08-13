package io.paperagent.v2.chain;

public interface ChainTransitionWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.TransitionRecord> appendTransition(
            ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.TransitionRecord> transition);

    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.TransitionStageRecord>
            appendTransitionStage(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.TransitionStageRecord> stage);
}
