package io.paperagent.v2.chain;

public interface ChainContentWriter {
    ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.ContentRecord> appendContent(
            ChainPersistenceRecords.ContentRecord content);
}
