package io.paperagent.v2.chain.validation;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainValidationRuntimeTest {
    private static final String HASH_1 = "1".repeat(64);
    private static final String HASH_2 = "2".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-09T02:00:00Z");

    @Test
    void bindsTypedRequirementsAndUsesTheOriginalReceiptSetDigest() {
        InMemoryRepository repository = new InMemoryRepository();
        ChainValidationRuntime runtime = new ChainValidationRuntime(
                authority(), repository);
        var command = command(List.of(
                new ProposalFields.ValidationSource(
                        "requirement-action", "receipt-action"),
                new ProposalFields.ValidationSource(
                        "requirement-candidate", "receipt-candidate")));

        var first = runtime.commit(command);
        var replay = runtime.commit(command);

        String expectedReceiptDigest = ChainValidationRuntime
                .receiptSetDigest(List.of(
                        new ChainValidationRuntime.ReceiptIdentity(
                                "requirement-candidate",
                                "receipt-candidate", HASH_2),
                        new ChainValidationRuntime.ReceiptIdentity(
                                "requirement-action",
                                "receipt-action", HASH_1)));
        assertEquals(expectedReceiptDigest,
                first.validation().receiptSetDigest());
        assertEquals(1, first.candidateItems().size());
        assertEquals(1, first.actionReceiptItems().size());
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
    }

    @Test
    void rejectsMissingOrDriftedRequirementReceiptMapping() {
        ChainValidationRuntime runtime = new ChainValidationRuntime(
                authority(), new InMemoryRepository());
        var missing = command(List.of(new ProposalFields.ValidationSource(
                "requirement-action", "receipt-action")));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.commit(missing));

        var drifted = command(List.of(
                new ProposalFields.ValidationSource(
                        "requirement-action", "wrong-receipt"),
                new ProposalFields.ValidationSource(
                        "requirement-candidate", "receipt-candidate")));
        assertThrows(IllegalStateException.class,
                () -> runtime.commit(drifted));
    }

    @Test
    void notRequiredStepCannotAccidentallyCreateAnEmptyValidationSet() {
        ChainValidationRuntime runtime = new ChainValidationRuntime(
                authority(), new InMemoryRepository());
        var command = new ChainValidationRuntime.CommitCommand(
                new ChainValidationRuntime.Scope(
                        "task-1", "frame-1", "plan-1", "revision-1", 1,
                        "step-1", "activation-1", "validation-key-1", NOW),
                List.of(), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> runtime.commit(command));
    }

    private static ChainValidationRuntime.CommitCommand command(
            List<ProposalFields.ValidationSource> sources) {
        return new ChainValidationRuntime.CommitCommand(
                new ChainValidationRuntime.Scope(
                        "task-1", "frame-1", "plan-1", "revision-1", 1,
                        "step-1", "activation-1", "validation-key-1", NOW),
                List.of(
                        new ValidationRequirement(
                                "requirement-candidate",
                                ValidationSubject.CANDIDATE,
                                "candidate verified"),
                        new ValidationRequirement(
                                "requirement-action",
                                ValidationSubject.ACTION_RECEIPT,
                                "action receipt verified")),
                sources);
    }

    private static ChainValidationAuthorityPort authority() {
        return new ChainValidationAuthorityPort() {
            @Override
            public VerifiedCandidate verifyCandidate(
                    ChainValidationRuntime.Scope scope,
                    ValidationRequirement requirement,
                    String receiptRef) {
                return new VerifiedCandidate(
                        "candidate-action", "validation-action",
                        "receipt-candidate", HASH_2, HASH_1,
                        "candidate-1", "workspace-1", 101L,
                        "3".repeat(64), "4".repeat(64));
            }

            @Override
            public VerifiedActionReceipt verifyActionReceipt(
                    ChainValidationRuntime.Scope scope,
                    ValidationRequirement requirement,
                    String receiptRef) {
                return new VerifiedActionReceipt(
                        "action-1", "receipt-action", HASH_1, HASH_2);
            }
        };
    }

    private static final class InMemoryRepository
            implements ChainValidationRepository {
        private ValidationAppendResult stored;

        @Override
        public ValidationAppendResult appendValidation(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.ValidationSetRecord> validation,
                List<ChainPersistenceRecords.CandidateValidationItemRecord>
                        candidateItems,
                List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                        actionReceiptItems) {
            if (stored != null) {
                return new ValidationAppendResult(stored.event(),
                        stored.validation(), stored.candidateItems(),
                        stored.actionReceiptItems(), true);
            }
            var event = new ChainPersistenceRecords.AuthorityEventRecord(
                    validation.event().eventId(), validation.event().taskId(),
                    1, validation.event().eventType(), null,
                    validation.event().sourceIdentitySha256(), NOW);
            stored = new ValidationAppendResult(event, validation.fact(),
                    candidateItems, actionReceiptItems, false);
            return stored;
        }

        @Override
        public Optional<ChainPersistenceRecords.ValidationSetRecord>
                findValidation(String validationId) {
            return stored == null ? Optional.empty()
                    : Optional.of(stored.validation());
        }

        @Override
        public List<ChainPersistenceRecords.CandidateValidationItemRecord>
                findCandidateItems(String validationId) {
            return stored == null ? List.of() : stored.candidateItems();
        }

        @Override
        public List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                findActionReceiptItems(String validationId) {
            return stored == null ? List.of() : stored.actionReceiptItems();
        }
    }
}
