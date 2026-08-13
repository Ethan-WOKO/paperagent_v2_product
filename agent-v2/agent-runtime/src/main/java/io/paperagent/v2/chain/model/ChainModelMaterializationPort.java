package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.ChainPersistenceRecords;

/** Atomic success boundary: validated attempt, optional body and ref-only proposal. */
@FunctionalInterface
public interface ChainModelMaterializationPort {
    SuccessfulMaterialization persistSuccessfulAttempt(
            ChainPersistenceRecords.ProviderAttemptRecord attempt,
            ChainPersistenceRecords.ContentRecord bodyContent,
            ChainPersistenceRecords.ModelProposalRecord proposal);

    record SuccessfulMaterialization(
            ChainPersistenceRecords.ProviderAttemptRecord attempt,
            ChainPersistenceRecords.ContentRecord bodyContent,
            ChainPersistenceRecords.ModelProposalRecord proposal,
            boolean replayed) {
        public SuccessfulMaterialization {
            java.util.Objects.requireNonNull(attempt, "attempt");
            java.util.Objects.requireNonNull(proposal, "proposal");
        }
    }
}
