package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainPersistenceRecords.ProposalStateEventRecord;

import java.time.Instant;

/** Binds an accepted TOOL_ACTION or WORKSPACE_CHANGE proposal to its sole formal action binding. */
@FunctionalInterface
public interface ChainActionProposalBinder {
    ProposalStateEventRecord bindAction(Binding binding);

    record Binding(
            String proposalId,
            String taskId,
            String eventId,
            String actionId,
            String sourceIdentitySha256,
            Instant committedAt) {
    }
}
