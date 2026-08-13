package io.paperagent.v2.chain;

import java.util.List;
import java.util.Optional;

/** Atomic persistence boundary for one Validation set and its typed items. */
public interface ChainValidationRepository {
    ValidationAppendResult appendValidation(
            ChainPersistenceRecords.AuthoritativeFact<
                    ChainPersistenceRecords.ValidationSetRecord> validation,
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    candidateItems,
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    actionReceiptItems);

    Optional<ChainPersistenceRecords.ValidationSetRecord> findValidation(
            String validationId);

    List<ChainPersistenceRecords.CandidateValidationItemRecord>
            findCandidateItems(String validationId);

    List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
            findActionReceiptItems(String validationId);

    record ValidationAppendResult(
            ChainPersistenceRecords.AuthorityEventRecord event,
            ChainPersistenceRecords.ValidationSetRecord validation,
            List<ChainPersistenceRecords.CandidateValidationItemRecord>
                    candidateItems,
            List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                    actionReceiptItems,
            boolean replayed) {
        public ValidationAppendResult {
            candidateItems = List.copyOf(candidateItems);
            actionReceiptItems = List.copyOf(actionReceiptItems);
        }
    }
}
