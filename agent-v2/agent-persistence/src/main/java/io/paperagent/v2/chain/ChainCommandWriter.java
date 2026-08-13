package io.paperagent.v2.chain;

public interface ChainCommandWriter {
    ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.CommandRecord> registerCommand(
            ChainPersistenceRecords.CommandRecord command);

    ChainPersistenceRecords.CommandRecord commitCommand(
            String commandId, String resultTaskId, String resultEventId, String resultInstructionId);

    ChainPersistenceRecords.CommandRecord failCommand(String commandId, String failureCode);
}
