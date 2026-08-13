package io.paperagent.v2.chain;

public interface ChainPermissionDecisionWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.PermissionDecisionRecord>
            appendPermissionDecision(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.PermissionDecisionRecord> decision);
}
