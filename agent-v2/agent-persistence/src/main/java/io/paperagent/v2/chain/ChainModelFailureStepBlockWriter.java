package io.paperagent.v2.chain;

public interface ChainModelFailureStepBlockWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<
            ChainPersistenceRecords.ModelFailureStepBlockRecord>
            appendModelFailureStepBlock(
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.ModelFailureStepBlockRecord>
                    stepBlock);
}
