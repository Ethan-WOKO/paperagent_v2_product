package com.yanban.api.agent.v2.chain.api;

import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainValidationRepository;
import io.paperagent.v2.chain.ProposalFields;
import io.paperagent.v2.chain.validation.ChainValidationAuthorityPort;
import io.paperagent.v2.chain.validation.ChainValidationRuntime;
import io.paperagent.v2.contracts.ValidationRequirement;
import io.paperagent.v2.contracts.ValidationSubject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductChainExecutorValidationRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-08-09T08:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void requiredMultiReceiptRecoveryReplaysExactImmutableValidation() {
        Repository repository = new Repository();
        ChainValidationRuntime runtime = new ChainValidationRuntime(
                authority(), repository);

        var first = recover(runtime, requirements(), sources());
        var replay = recover(runtime, requirements(), List.of(
                sources().get(1), sources().get(0)));

        assertThat(first.validation().validationId()).isEqualTo(
                replay.validation().validationId());
        assertThat(first.validation().requestDigest()).isEqualTo(
                replay.validation().requestDigest());
        assertThat(first.validation().receiptSetDigest()).isEqualTo(
                replay.validation().receiptSetDigest());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.candidateItems()).hasSize(1);
        assertThat(replay.actionReceiptItems()).hasSize(1);
    }

    @Test
    void recoveryRejectsWrongRequirementMappingAndPreInvocationIdentity() {
        ChainValidationRuntime runtime = new ChainValidationRuntime(
                authority(), new Repository());
        assertThatThrownBy(() -> recover(runtime, requirements(), List.of(
                new ProposalFields.ValidationSource(
                        "requirement-action", "receipt-action"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly cover");

        assertThatThrownBy(() -> ProductChainExecutorProgression
                .recoverStepResultValidation(runtime, "validation-old", null,
                        null, scope(), requirements(), sources()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAIN_EXECUTOR_RECOVERY_VALIDATION_INVALID");
    }

    @Test
    void notRequiredRecoveryCreatesNoSetAndRejectsClaimedSources() {
        Repository repository = new Repository();
        ChainValidationRuntime runtime = new ChainValidationRuntime(
                authority(), repository);

        assertThat(ProductChainExecutorProgression
                .recoverStepResultValidation(runtime, null, null, null,
                        scope(), List.of(), List.of())).isNull();
        assertThat(repository.stored).isNull();
        assertThatThrownBy(() -> ProductChainExecutorProgression
                .recoverStepResultValidation(runtime, null, null, null,
                        scope(), List.of(), List.of(
                                new ProposalFields.ValidationSource(
                                        "undeclared", "receipt-action"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAIN_STEP_VALIDATION_NOT_REQUIRED");
    }

    private static ChainValidationRuntime.CommitResult recover(
            ChainValidationRuntime runtime,
            List<ValidationRequirement> requirements,
            List<ProposalFields.ValidationSource> sources) {
        return ProductChainExecutorProgression.recoverStepResultValidation(
                runtime, null, null, null, scope(), requirements, sources);
    }

    private static ChainValidationRuntime.Scope scope() {
        return new ChainValidationRuntime.Scope("task-1", "frame-1",
                "plan-1", "revision-1", 1, "step-1", "activation-1",
                "chain-validation-key", NOW);
    }

    private static List<ValidationRequirement> requirements() {
        return List.of(new ValidationRequirement("requirement-candidate",
                        ValidationSubject.CANDIDATE, "candidate verified"),
                new ValidationRequirement("requirement-action",
                        ValidationSubject.ACTION_RECEIPT,
                        "action receipt verified"));
    }

    private static List<ProposalFields.ValidationSource> sources() {
        return List.of(new ProposalFields.ValidationSource(
                        "requirement-candidate", "receipt-candidate"),
                new ProposalFields.ValidationSource(
                        "requirement-action", "receipt-action"));
    }

    private static ChainValidationAuthorityPort authority() {
        return new ChainValidationAuthorityPort() {
            @Override
            public VerifiedCandidate verifyCandidate(
                    ChainValidationRuntime.Scope scope,
                    ValidationRequirement requirement,
                    String receiptRef) {
                return new VerifiedCandidate("candidate-action",
                        "validation-action", "receipt-candidate", HASH_A,
                        HASH_B, "candidate-1", "workspace-1", 7L,
                        "c".repeat(64), "d".repeat(64));
            }

            @Override
            public VerifiedActionReceipt verifyActionReceipt(
                    ChainValidationRuntime.Scope scope,
                    ValidationRequirement requirement,
                    String receiptRef) {
                return new VerifiedActionReceipt("action-1",
                        "receipt-action", HASH_B, HASH_A);
            }
        };
    }

    private static final class Repository implements ChainValidationRepository {
        private ValidationAppendResult stored;

        @Override
        public ValidationAppendResult appendValidation(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.ValidationSetRecord> value,
                List<ChainPersistenceRecords.CandidateValidationItemRecord>
                        candidates,
                List<ChainPersistenceRecords.ActionReceiptValidationItemRecord>
                        actions) {
            if (stored != null) {
                return new ValidationAppendResult(stored.event(),
                        stored.validation(), stored.candidateItems(),
                        stored.actionReceiptItems(), true);
            }
            var event = new ChainPersistenceRecords.AuthorityEventRecord(
                    value.event().eventId(), value.event().taskId(), 1,
                    value.event().eventType(), null,
                    value.event().sourceIdentitySha256(), NOW);
            stored = new ValidationAppendResult(event, value.fact(),
                    candidates, actions, false);
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
