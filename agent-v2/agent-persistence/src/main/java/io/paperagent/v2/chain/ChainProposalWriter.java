package io.paperagent.v2.chain;

public interface ChainProposalWriter {
    ChainPersistenceRecords.AppendResult<ChainPersistenceRecords.ModelProposalRecord> appendProposal(
            ChainPersistenceRecords.ModelProposalRecord proposal);
}
