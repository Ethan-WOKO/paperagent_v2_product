package io.paperagent.v2.chain.model;

import io.paperagent.v2.chain.ChainPersistenceRecords;

public sealed interface ChainModelProtocolOutcome permits
        ChainModelProtocolOutcome.ProposalReady, ChainModelProtocolOutcome.ModelCallFailed {

    record ProposalReady(
            ChainPersistenceRecords.ModelProposalRecord proposal,
            ChainPersistenceRecords.ContentRecord bodyContent,
            int attempts,
            boolean recovered) implements ChainModelProtocolOutcome {
        public ProposalReady {
            java.util.Objects.requireNonNull(proposal, "proposal");
            if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
        }

        /** A validated proposal is never executable before AdmissionResult says ACCEPTED. */
        public boolean executable() {
            return false;
        }

        public boolean admissionRequired() {
            return true;
        }
    }

    record ModelCallFailed(
            String invocationId,
            String errorCode,
            int attempts) implements ChainModelProtocolOutcome {
        public ModelCallFailed {
            if (invocationId == null || invocationId.isBlank()) {
                throw new IllegalArgumentException("invocationId must not be blank");
            }
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("errorCode must not be blank");
            }
            if (attempts < 1) throw new IllegalArgumentException("attempts must be positive");
        }
    }
}
