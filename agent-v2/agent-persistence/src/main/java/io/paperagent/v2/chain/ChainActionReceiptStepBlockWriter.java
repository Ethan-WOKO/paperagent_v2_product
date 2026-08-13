package io.paperagent.v2.chain;

public interface ChainActionReceiptStepBlockWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<
            ChainPersistenceRecords.ActionReceiptStepBlockRecord>
            appendActionReceiptStepBlock(
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.ActionReceiptStepBlockRecord>
                    stepBlock);
}
