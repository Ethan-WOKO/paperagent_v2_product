package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainPersistenceRecords.ProposalStateEventRecord;

import java.time.Instant;

/** Narrow authority boundary that binds an accepted proposal to its candidate. */
@FunctionalInterface
public interface ChainCandidateProposalBinder {
    ProposalStateEventRecord bindCandidate(Binding binding);

    record Binding(
            String proposalId,
            String taskId,
            String eventId,
            String candidateResultId,
            String sourceIdentitySha256,
            Instant committedAt) {
        public Binding {
            required(proposalId, "proposalId");
            required(taskId, "taskId");
            required(eventId, "eventId");
            required(candidateResultId, "candidateResultId");
            if (sourceIdentitySha256 == null
                    || !sourceIdentitySha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "sourceIdentitySha256 must be lowercase SHA-256");
            }
            if (committedAt == null) {
                throw new IllegalArgumentException(
                        "committedAt must not be null");
            }
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
