package com.yanban.api.agent.v2.chain.context;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainValidationConclusion;
import io.paperagent.v2.contracts.ExecutionReceipt;

import java.util.List;

/** Body-free typed Validation authority projected into a ContextRevision. */
record ProductTypedValidationView(
        Scope scope,
        String authorityRef,
        String requestDigest,
        String receiptSetDigest,
        String conclusionDigest,
        ChainValidationConclusion conclusion,
        List<SetView> sets) {
    ProductTypedValidationView {
        sets = List.copyOf(sets);
    }

    enum Scope {
        CURRENT_STEP,
        PLAN
    }

    record SetView(
            ChainPersistenceRecords.ValidationSetRecord validation,
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    candidateItems,
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    actionReceiptItems,
            List<ReceiptView> receiptBodies) {
        SetView {
            candidateItems = List.copyOf(candidateItems);
            actionReceiptItems = List.copyOf(actionReceiptItems);
            receiptBodies = List.copyOf(receiptBodies);
        }
    }

    record ReceiptView(String requirementId, ExecutionReceipt receipt) {
        ReceiptView {
            if (requirementId == null || requirementId.isBlank()) {
                throw new IllegalArgumentException(
                        "requirementId must not be blank");
            }
            java.util.Objects.requireNonNull(receipt, "receipt");
        }
    }
}
