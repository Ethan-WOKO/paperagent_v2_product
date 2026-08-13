package io.paperagent.v2.chain;

public interface ChainContextRevisionWriter {
    ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.ContextRevisionRecord> createContextRevision(
            ChainPersistenceRecords.ContextRevisionRecord revision);

    ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.ContextModuleRecord> appendContextModule(
            ChainPersistenceRecords.ContextModuleRecord module);

    ChainPersistenceRecords.ContextRevisionRecord completeContextRevision(
            ChainPersistenceRecords.ContextRevisionRecord completeRevision);

    ChainPersistenceRecords.ContextRevisionRecord blockContextRevision(
            ChainPersistenceRecords.ContextRevisionRecord blockedRevision);
}
