package io.paperagent.v2.chain;

public interface ChainProposalStateWriter {
    ChainPersistenceRecords.AuthoritativeAppendResult<ChainPersistenceRecords.ProposalStateEventRecord>
            appendProposalState(
                    ChainPersistenceRecords.AuthoritativeFact<ChainPersistenceRecords.ProposalStateEventRecord> event);
}
