package io.paperagent.v2.chain;

public interface ChainWorkspaceCandidateWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.WorkspaceCandidateRecord>
            appendWorkspaceCandidate(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.WorkspaceCandidateRecord> candidate);
}
