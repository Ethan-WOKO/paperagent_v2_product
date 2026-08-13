package io.paperagent.v2.chain;

public interface ChainTaskWriter {
    ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.TaskRecord> appendTask(
            ChainPersistenceRecords.TaskRecord task);
}
