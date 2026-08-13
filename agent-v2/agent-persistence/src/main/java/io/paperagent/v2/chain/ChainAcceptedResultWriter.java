package io.paperagent.v2.chain;

public interface ChainAcceptedResultWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.AcceptedResultRecord>
            appendAcceptedResult(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.AcceptedResultRecord> result);
}
