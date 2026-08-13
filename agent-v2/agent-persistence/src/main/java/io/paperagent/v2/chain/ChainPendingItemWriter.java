package io.paperagent.v2.chain;

public interface ChainPendingItemWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.PendingItemRecord> appendPendingItem(
            ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.PendingItemRecord> item);

    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.PendingItemEventRecord>
            appendPendingItemEvent(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.PendingItemEventRecord> event);
}
