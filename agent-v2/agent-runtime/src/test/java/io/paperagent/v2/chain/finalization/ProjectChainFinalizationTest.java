package io.paperagent.v2.chain.finalization;

import io.paperagent.v2.chain.ChainRuntimePolicy;

import io.paperagent.v2.chain.ChainFinalization;
import io.paperagent.v2.chain.ChainFinalizationCheckWriter;
import io.paperagent.v2.chain.ChainFinalizationRepository;
import io.paperagent.v2.chain.ChainFoundationRepository;
import io.paperagent.v2.chain.ChainPersistenceRecords;
import io.paperagent.v2.chain.ChainPublishRequirement;
import io.paperagent.v2.chain.ChainTaskOutcomeStatus;
import io.paperagent.v2.chain.ChainTaskOutcomeWriter;
import io.paperagent.v2.chain.ChainTransitionStage;
import io.paperagent.v2.chain.ChainWorkflowRepository;
import io.paperagent.v2.chain.review.ChainTaskOutcomeRuntime;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectChainFinalizationTest {
    private static final Instant NOW = Instant.parse("2026-08-07T08:00:00Z");
    private static final String SHA = "a".repeat(64);
    private static final String FINGERPRINT = "b".repeat(64);
    private static final String REQUEST_DIGEST = "c".repeat(64);
    private static final String RECEIPT_DIGEST = "d".repeat(64);
    private static final String PUBLISH_DIGEST = "e".repeat(64);

    @Test
    void persistsTemporaryFailureBeforeRetryThenPublishesAndCompletes() {
        Store store = new Store(readiness());
        store.inspections.add(new ChainFinalizationAuthorityPort
                .TemporarilyUnavailable("validation-authority"));
        store.inspections.add(available(
                FINGERPRINT, FINGERPRINT,
                ChainFinalizationAuthorityPort.Validation.Status.SUCCESSFUL));
        ChainFinalizationRuntime runtime = runtime(store);

        ChainFinalizationRuntime.Completed completed = assertInstanceOf(
                ChainFinalizationRuntime.Completed.class,
                runtime.finalizeReadiness("readiness-1", NOW.plusSeconds(1)));

        assertEquals(2, store.checks.size());
        assertEquals(ChainFinalization.Outcome.FAILED,
                store.checks.get(0).resultStatus());
        assertEquals(ChainFinalization.FailureHandling.RETRYABLE,
                store.checks.get(0).failureDisposition());
        assertTrue(store.secondInspectionObservedPersistedFailure);
        assertEquals(ChainFinalization.Outcome.PASSED,
                completed.check().resultStatus());
        assertEquals("project-v2",
                completed.published().publishedProjectVersion());
        assertEquals(ChainTaskOutcomeStatus.COMPLETED,
                completed.outcome().outcomeType());
        assertEquals("candidate-1", completed.outcome().candidateKey());
        assertEquals("validation-1", completed.outcome().validationId());
        assertEquals("command-current",
                completed.outcome().sourceCommandId());

        ChainFinalizationRuntime.Completed replay = assertInstanceOf(
                ChainFinalizationRuntime.Completed.class,
                runtime.finalizeReadiness("readiness-1", NOW.plusSeconds(99)));
        assertTrue(replay.replayed());
        assertEquals(2, store.checks.size());
        assertEquals(completed.outcome(), replay.outcome());
        assertEquals(NOW.plusSeconds(1), store.transitionCreatedAt);
        assertEquals(List.of(
                        ChainTransitionStage.OPEN,
                        ChainTransitionStage.READINESS_VERIFIED,
                        ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
                        ChainTransitionStage.PUBLISH_COMMITTED_OR_NOT_REQUIRED,
                        ChainTransitionStage.TASK_OUTCOME_COMMITTED,
                        ChainTransitionStage.COMPLETE),
                store.transitionPrefix.stream()
                        .map(ChainFinalizationTransitionPort.StageAuthority::stage)
                        .toList());
        assertEquals("transition-readiness",
                store.events.get(0).transitionId());
        assertFalse(store.checks.get(1).transitionId().equals(
                store.readiness.transitionId()));
    }

    @Test
    void changedCandidateInvalidatesOldSuccessfulValidationBeforePublish() {
        Store store = new Store(readiness(), "f".repeat(64));
        store.inspections.add(available(
                "f".repeat(64), FINGERPRINT,
                ChainFinalizationAuthorityPort.Validation.Status.SUCCESSFUL));

        ChainFinalizationRuntime.CheckFailed failed = assertInstanceOf(
                ChainFinalizationRuntime.CheckFailed.class,
                runtime(store).finalizeReadiness(
                        "readiness-1", NOW.plusSeconds(2)));

        assertEquals(ChainFinalization.ErrorCode.VALIDATION_BINDING_MISMATCH,
                failed.check().errorCode());
        assertEquals(ChainFinalization.FailureHandling.REFLECTOR_REQUIRED,
                failed.check().failureDisposition());
        assertEquals(1, store.checks.size());
        assertEquals(0, store.publishCalls);
        assertTrue(store.taskOutcome.isEmpty());
    }

    @Test
    void exactCandidateIdIsNotReplacedByNewerBindingForSameArtifact() {
        Store store = new Store(readiness());
        store.addCandidate(new ChainPersistenceRecords.WorkspaceCandidateRecord(
                "candidate-newer", "task-1", "event-candidate-newer",
                "action-newer", "workspace-1", "project-v1", 41L,
                "f".repeat(64), SHA, SHA, NOW.plusSeconds(1)));
        store.inspections.add(available(
                FINGERPRINT, FINGERPRINT,
                ChainFinalizationAuthorityPort.Validation.Status.SUCCESSFUL));

        ChainFinalizationRuntime.Completed completed = assertInstanceOf(
                ChainFinalizationRuntime.Completed.class,
                runtime(store).finalizeReadiness(
                        "readiness-1", NOW.plusSeconds(2)));

        assertEquals("candidate-1", completed.outcome().candidateKey());
    }

    @Test
    void formalCandidateArtifactMustMatchReadinessExactly() {
        Store store = new Store(readiness(), FINGERPRINT, 42L);
        store.inspections.add(available(
                FINGERPRINT, FINGERPRINT,
                ChainFinalizationAuthorityPort.Validation.Status.SUCCESSFUL));

        ChainFinalizationRuntime.CheckFailed failed = assertInstanceOf(
                ChainFinalizationRuntime.CheckFailed.class,
                runtime(store).finalizeReadiness(
                        "readiness-1", NOW.plusSeconds(3)));

        assertEquals(ChainFinalization.ErrorCode.CANDIDATE_BINDING_MISMATCH,
                failed.check().errorCode());
        assertEquals(0, store.publishCalls);
    }

    @Test
    void missingProductAuthoritiesBecomeExactFormalChecks() {
        var complete = available(
                FINGERPRINT, FINGERPRINT,
                ChainFinalizationAuthorityPort.Validation.Status.SUCCESSFUL);

        Store missingCandidate = new Store(readiness());
        missingCandidate.inspections.add(copyAvailable(
                complete, null, complete.validation(),
                complete.currentProjectVersion()));
        var candidateFailure = assertInstanceOf(
                ChainFinalizationRuntime.CheckFailed.class,
                runtime(missingCandidate).finalizeReadiness(
                        "readiness-1", NOW.plusSeconds(31)));
        assertEquals(ChainFinalization.ErrorCode.CANDIDATE_BINDING_MISMATCH,
                candidateFailure.check().errorCode());

        Store missingValidation = new Store(readiness());
        missingValidation.inspections.add(copyAvailable(
                complete, complete.candidate(), null,
                complete.currentProjectVersion()));
        var validationFailure = assertInstanceOf(
                ChainFinalizationRuntime.CheckFailed.class,
                runtime(missingValidation).finalizeReadiness(
                        "readiness-1", NOW.plusSeconds(32)));
        assertEquals(ChainFinalization.ErrorCode.VALIDATION_MISSING,
                validationFailure.check().errorCode());

        Store missingProject = new Store(readiness());
        missingProject.inspections.add(copyAvailable(
                complete, complete.candidate(), complete.validation(),
                "MISSING_PROJECT"));
        var staleFailure = assertInstanceOf(
                ChainFinalizationRuntime.CheckFailed.class,
                runtime(missingProject).finalizeReadiness(
                        "readiness-1", NOW.plusSeconds(33)));
        assertEquals(ChainFinalization.ErrorCode.STALE_VERSION_FENCE,
                staleFailure.check().errorCode());

        for (Store store : List.of(
                missingCandidate, missingValidation, missingProject)) {
            assertEquals(1, store.checks.size());
            assertEquals(0, store.publishCalls);
            assertTrue(store.taskOutcome.isEmpty());
        }
    }

    @Test
    void formalFailedValidationCreatesReflectorRequiredCheckAndNoTaskOutcome() {
        Store validationStore = new Store(readiness());
        validationStore.inspections.add(available(
                FINGERPRINT, FINGERPRINT,
                ChainFinalizationAuthorityPort.Validation.Status.FAILED));
        ChainFinalizationRuntime.CheckFailed validationFailure =
                assertInstanceOf(
                        ChainFinalizationRuntime.CheckFailed.class,
                        runtime(validationStore).finalizeReadiness(
                                "readiness-1", NOW.plusSeconds(3)));
        assertEquals(ChainFinalization.ErrorCode.VALIDATION_NOT_SUCCESSFUL,
                validationFailure.check().errorCode());
        assertEquals(ChainFinalization.FailureHandling.REFLECTOR_REQUIRED,
                validationFailure.check().failureDisposition());
        assertTrue(validationStore.taskOutcome.isEmpty());
    }

    @Test
    void actionReceiptBundleWithoutCandidateCanFinalizeWhenPublishNotRequired() {
        var readiness = authorityOnlyReadiness(
                "validation-bundle-1", REQUEST_DIGEST, RECEIPT_DIGEST);
        Store store = new Store(readiness);
        store.inspections.add(new ChainFinalizationAuthorityPort.Available(
                "task-1", "instruction-1", "task-frame-1", "plan-1",
                "revision-1", 1, "step-final", "review-ready",
                readiness.acceptedSet().sha256(), 8, true,
                readiness.coverage().sha256(), null, true,
                new ChainFinalizationAuthorityPort.Validation(
                        "validation-bundle-1", null, null, "project-v1",
                        REQUEST_DIGEST, RECEIPT_DIGEST,
                        ChainFinalizationAuthorityPort.Validation.Status
                                .SUCCESSFUL),
                ChainPublishRequirement.NOT_REQUIRED, PUBLISH_DIGEST,
                "project-v1"));

        var completed = assertInstanceOf(
                ChainFinalizationRuntime.Completed.class,
                runtime(store).finalizeReadiness(
                        "readiness-1", NOW.plusSeconds(34)));

        assertEquals("validation-bundle-1",
                completed.outcome().validationId());
        assertEquals("NONE", completed.outcome().candidateKey());
        assertNull(completed.published());
        assertEquals(0, store.publishCalls);
    }

    @Test
    void formalNotRequiredCanFinalizeWithoutCandidateOrValidationBundle() {
        var readiness = authorityOnlyReadiness("NONE", null, null);
        Store store = new Store(readiness);
        store.inspections.add(new ChainFinalizationAuthorityPort.Available(
                "task-1", "instruction-1", "task-frame-1", "plan-1",
                "revision-1", 1, "step-final", "review-ready",
                readiness.acceptedSet().sha256(), 8, true,
                readiness.coverage().sha256(), null, false, null,
                ChainPublishRequirement.NOT_REQUIRED, PUBLISH_DIGEST,
                "project-v1"));

        var completed = assertInstanceOf(
                ChainFinalizationRuntime.Completed.class,
                runtime(store).finalizeReadiness(
                        "readiness-1", NOW.plusSeconds(35)));

        assertEquals("NONE", completed.outcome().validationId());
        assertEquals("NONE", completed.outcome().candidateKey());
        assertNull(completed.published());
        assertEquals(0, store.publishCalls);
    }

    @Test
    void formalPublishFailureNeverCreatesTaskOutcome() {
        Store publishStore = new Store(readiness());
        publishStore.inspections.add(available(
                FINGERPRINT, FINGERPRINT,
                ChainFinalizationAuthorityPort.Validation.Status.SUCCESSFUL));
        publishStore.publishFailure = new ChainProjectPublishPort.Failed(
                ChainProjectPublishPort.ErrorCode.STALE_VERSION_FENCE,
                "publish-failure-1", 1, "template", false, false);
        ChainFinalizationRuntime.PublishFailed publishFailure =
                assertInstanceOf(
                        ChainFinalizationRuntime.PublishFailed.class,
                        runtime(publishStore).finalizeReadiness(
                                "readiness-1", NOW.plusSeconds(4)));
        assertEquals("publish-failure-1",
                publishFailure.failure().formalFailureRef());
        assertTrue(publishStore.taskOutcome.isEmpty());
        assertNull(publishStore.published);
        assertEquals(List.of(
                        ChainTransitionStage.OPEN,
                        ChainTransitionStage.READINESS_VERIFIED,
                        ChainTransitionStage.FINALIZATION_CHECK_COMMITTED),
                publishStore.transitionPrefix.stream()
                        .map(ChainFinalizationTransitionPort.StageAuthority::stage)
                        .toList());
    }

    @Test
    void persistsFormalPublishFailureBeforeBoundedRetryWithStableIdentity() {
        Store store = new Store(readiness());
        store.inspections.add(available(
                FINGERPRINT, FINGERPRINT,
                ChainFinalizationAuthorityPort.Validation.Status.SUCCESSFUL));
        store.publishFailures.add(new ChainProjectPublishPort.Failed(
                ChainProjectPublishPort.ErrorCode
                        .AUTHORITY_TEMPORARILY_UNAVAILABLE,
                "publish-failure-temporary", 1, "template", true, false));

        ChainFinalizationRuntime runtime = runtime(store);
        ChainFinalizationRuntime.PublishFailed first = assertInstanceOf(
                ChainFinalizationRuntime.PublishFailed.class,
                runtime.finalizeReadiness(
                        "readiness-1", NOW.plusSeconds(5)));
        assertTrue(first.failure().retryable());
        assertFalse(first.failure().replayed());
        assertEquals(1, store.realPublishCalls);

        ChainFinalizationRuntime.Completed completed = assertInstanceOf(
                ChainFinalizationRuntime.Completed.class,
                runtime.finalizeReadiness(
                        "readiness-1", NOW.plusSeconds(6)));

        assertEquals(3, store.publishCalls);
        assertEquals(2, store.realPublishCalls);
        assertTrue(store.secondPublishObservedFormalFailure);
        assertEquals(2, store.publishIdempotencyKeys.stream().distinct().count());
        assertEquals("project-v2",
                completed.published().publishedProjectVersion());
    }

    private static ChainFinalizationRuntime runtime(Store store) {
        return new ChainFinalizationRuntime(
                store, store, store, store, store, store, store, store,
                ignored -> ChainRuntimePolicy.current());
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord readiness() {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness-1", "task-1", "event-readiness-1",
                "transition-readiness", SHA, "task-frame-1", "plan-1",
                "revision-1", 1, "step-final", "review-ready",
                json("[\"accepted-1\"]"), 8, 41L, "candidate-1",
                "workspace-1", "validation-1", REQUEST_DIGEST,
                RECEIPT_DIGEST, json("{\"result\":\"covered\"}"),
                ChainPublishRequirement.REQUIRED, PUBLISH_DIGEST,
                "instruction-1", "project-v1", NOW);
    }

    private static ChainPersistenceRecords.FinalizationReadinessRecord
            authorityOnlyReadiness(
                    String validationId, String requestDigest,
                    String receiptDigest) {
        return new ChainPersistenceRecords.FinalizationReadinessRecord(
                "readiness-1", "task-1", "event-readiness-1",
                "transition-readiness", SHA, "task-frame-1", "plan-1",
                "revision-1", 1, "step-final", "review-ready",
                json("[\"accepted-1\"]"), 8, null, "NONE", "NONE",
                validationId, requestDigest, receiptDigest,
                json("{\"result\":\"covered\"}"),
                ChainPublishRequirement.NOT_REQUIRED, PUBLISH_DIGEST,
                "instruction-1", "project-v1", NOW);
    }

    private static ChainFinalizationAuthorityPort.Available available(
            String candidateFingerprint,
            String validationFingerprint,
            ChainFinalizationAuthorityPort.Validation.Status status) {
        ChainPersistenceRecords.FinalizationReadinessRecord readiness =
                readiness();
        return new ChainFinalizationAuthorityPort.Available(
                "task-1", "instruction-1", "task-frame-1", "plan-1",
                "revision-1", 1, "step-final", "review-ready",
                readiness.acceptedSet().sha256(), 8, true,
                readiness.coverage().sha256(),
                new ChainFinalizationAuthorityPort.Candidate(
                        "candidate-1", "workspace-1", 41L,
                        candidateFingerprint, "project-v1"),
                true,
                new ChainFinalizationAuthorityPort.Validation(
                        "validation-1", 41L, validationFingerprint,
                        "project-v1", REQUEST_DIGEST, RECEIPT_DIGEST, status),
                ChainPublishRequirement.REQUIRED, PUBLISH_DIGEST,
                "project-v1");
    }

    private static ChainFinalizationAuthorityPort.Available copyAvailable(
            ChainFinalizationAuthorityPort.Available source,
            ChainFinalizationAuthorityPort.Candidate candidate,
            ChainFinalizationAuthorityPort.Validation validation,
            String currentProjectVersion) {
        return new ChainFinalizationAuthorityPort.Available(
                source.taskId(), source.currentInstructionId(),
                source.taskFrameId(), source.planId(),
                source.planRevisionId(), source.planRevisionNumber(),
                source.finalStepId(), source.reviewDecisionId(),
                source.acceptedSetSha256(),
                source.applicabilityCutEventSequence(),
                source.taskContractSatisfied(), source.coverageSha256(),
                candidate, source.validationRequired(), validation,
                source.publishRequirement(),
                source.publishRequirementDigest(), currentProjectVersion);
    }

    private static ChainPersistenceRecords.CanonicalJson json(String value) {
        return new ChainPersistenceRecords.CanonicalJson(1, sha(value), value);
    }

    private static String sha(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Store implements
            ChainFoundationRepository,
            ChainFinalizationRepository,
            ChainFinalizationCheckWriter,
            ChainWorkflowRepository,
            ChainFinalizationAuthorityPort,
            ChainProjectPublishPort,
            ChainCompletedOutcomePort,
            ChainFinalizationTransitionPort,
            ChainTaskOutcomeWriter {
        private final ChainPersistenceRecords.TaskRecord task =
                new ChainPersistenceRecords.TaskRecord(
                        "task-1", "command-root", "instruction-root", null,
                        3, 7, 9, 10L, "client-root", SHA,
                        11L, "project-v1", 0, NOW);
        private final ChainPersistenceRecords.FinalizationReadinessRecord readiness;
        private final List<ChainPersistenceRecords.AuthorityEventRecord> events =
                new ArrayList<>();
        private final List<ChainPersistenceRecords.FinalizationCheckRecord> checks =
                new ArrayList<>();
        private final Deque<ChainFinalizationAuthorityPort.Inspection> inspections =
                new ArrayDeque<>();
        private final Deque<ChainProjectPublishPort.Failed> publishFailures =
                new ArrayDeque<>();
        private final List<String> publishIdempotencyKeys = new ArrayList<>();
        private final List<String> formalPublishFailures = new ArrayList<>();
        private final java.util.Map<String, ChainProjectPublishPort.Failed>
                storedPublishFailures = new java.util.HashMap<>();
        private final ChainPersistenceRecords.WorkspaceCandidateRecord candidate;
        private final List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                additionalCandidates = new ArrayList<>();
        private Optional<ChainPersistenceRecords.TaskOutcomeRecord> taskOutcome =
                Optional.empty();
        private boolean secondInspectionObservedPersistedFailure;
        private int inspectionCalls;
        private int publishCalls;
        private int realPublishCalls;
        private ChainProjectPublishPort.Failed publishFailure;
        private ChainProjectPublishPort.Published published;
        private boolean secondPublishObservedFormalFailure;
        private List<ChainFinalizationTransitionPort.StageAuthority>
                transitionPrefix = List.of();
        private String transitionId;
        private Instant transitionCreatedAt;

        private Store(
                ChainPersistenceRecords.FinalizationReadinessRecord readiness) {
            this(readiness, FINGERPRINT);
        }

        private Store(
                ChainPersistenceRecords.FinalizationReadinessRecord readiness,
                String candidateFingerprint) {
            this(readiness, candidateFingerprint, 41L);
        }

        private Store(
                ChainPersistenceRecords.FinalizationReadinessRecord readiness,
                String candidateFingerprint,
                long candidateArtifactId) {
            this.readiness = readiness;
            this.candidate = new ChainPersistenceRecords.WorkspaceCandidateRecord(
                    "candidate-1", "task-1", "event-candidate-1", "action-1",
                    "workspace-1", "project-v1", candidateArtifactId,
                    candidateFingerprint, SHA, SHA, NOW.minusSeconds(1));
            addAuthority(new ChainPersistenceRecords.AuthorityEventRequest(
                    readiness.eventId(), readiness.taskId(),
                    "FINALIZATION_READINESS", readiness.transitionId(),
                    readiness.readinessScopeKey(), readiness.createdAt()));
            addAuthority(new ChainPersistenceRecords.AuthorityEventRequest(
                    candidate.eventId(), candidate.taskId(),
                    "WORKSPACE_CANDIDATE", null,
                    candidate.versionFenceSha256(), candidate.createdAt()));
        }

        private void addCandidate(
                ChainPersistenceRecords.WorkspaceCandidateRecord value) {
            additionalCandidates.add(value);
            addAuthority(new ChainPersistenceRecords.AuthorityEventRequest(
                    value.eventId(), value.taskId(), "WORKSPACE_CANDIDATE",
                    null, value.versionFenceSha256(), value.createdAt()));
        }

        @Override public Inspection inspect(
                ChainPersistenceRecords.FinalizationReadinessRecord value) {
            inspectionCalls++;
            if (inspectionCalls == 2) {
                secondInspectionObservedPersistedFailure = checks.size() == 1
                        && checks.get(0).resultStatus()
                        == ChainFinalization.Outcome.FAILED;
            }
            return inspections.removeFirst();
        }

        @Override public PublishResult publish(PublishCommand command) {
            publishCalls++;
            publishIdempotencyKeys.add(command.idempotencyKey());
            if (publishCalls > 1 && !formalPublishFailures.isEmpty()) {
                secondPublishObservedFormalFailure = true;
            }
            ChainProjectPublishPort.Failed stored = storedPublishFailures.get(
                    command.idempotencyKey());
            if (stored != null) {
                return new ChainProjectPublishPort.Failed(
                        stored.errorCode(), stored.formalFailureRef(),
                        command.attemptNo(), command.idempotencyKey(),
                        stored.retryable(), true);
            }
            realPublishCalls++;
            ChainProjectPublishPort.Failed template = !publishFailures.isEmpty()
                    ? publishFailures.removeFirst() : publishFailure;
            if (template != null) {
                ChainProjectPublishPort.Failed failure =
                        new ChainProjectPublishPort.Failed(
                                template.errorCode(),
                                template.formalFailureRef(),
                                command.attemptNo(), command.idempotencyKey(),
                                template.retryable(), false);
                formalPublishFailures.add(failure.formalFailureRef());
                storedPublishFailures.put(command.idempotencyKey(), failure);
                return failure;
            }
            published = new ChainProjectPublishPort.Published(
                    "publish-operation-1", command.attemptNo(),
                    command.idempotencyKey(), false,
                    command.baseProjectVersion(),
                    command.candidateKey(), command.validationId(),
                    "project-v2", 2L, "publish-receipt-1");
            return published;
        }

        @Override public CompletionSubmission complete(
                CompletionCommand command) {
            ChainPersistenceRecords.FinalizationReadinessRecord value =
                    command.readiness();
            ChainProjectPublishPort.Published publish = command.published();
            ChainTaskOutcomeRuntime.OutcomeDraft draft =
                    new ChainTaskOutcomeRuntime.OutcomeDraft(
                            value.taskId(), "event-task-outcome",
                            command.sourceCommandId(), value.instructionId(),
                            value.taskFrameId(), value.finalPlanId(),
                            value.finalPlanRevisionId(), value.coverage(),
                            value.acceptedSet(), value.artifactId(),
                            value.candidateKey(), value.readinessId(),
                            command.check().finalizationCheckId(),
                            value.validationId(),
                            value.validationRequestDigest(),
                            value.validationReceiptDigest(),
                            value.publishRequirement(),
                            value.publishRequirementDigest(),
                            publish == null ? null : publish.operationId(),
                            publish == null
                                    ? null : publish.publishedProjectVersion(),
                            publish == null
                                    ? null : publish.publishedRevisionId(),
                            publish == null ? null : publish.publishReceiptId(),
                            json("[]"), json("[]"), json("[]"),
                            NOW.plusSeconds(20));
            ChainTaskOutcomeRuntime runtime = new ChainTaskOutcomeRuntime(
                    this, completedVerifier(
                    command.finalizationTransitionId()));
            ChainTaskOutcomeRuntime.CommitResult committed = runtime.commit(
                    new ChainTaskOutcomeRuntime.Completed(
                            draft, command.finalizationTransitionId()));
            return new CompletionSubmission(
                    committed.outcome(), committed.replayed());
        }

        @Override public void advance(AdvanceCommand command) {
            if (transitionId == null) {
                transitionId = command.transitionId();
                transitionCreatedAt = command.committedAt();
            }
            assertEquals(transitionId, command.transitionId());
            assertEquals(readiness.taskId(), command.taskId());
            assertEquals(readiness.reviewDecisionId(),
                    command.sourceDecisionId());
            if (command.requiredPrefix().size() <= transitionPrefix.size()) {
                assertEquals(command.requiredPrefix(), transitionPrefix.subList(
                        0, command.requiredPrefix().size()));
            } else {
                assertEquals(transitionPrefix,
                        command.requiredPrefix().subList(
                                0, transitionPrefix.size()));
                transitionPrefix = command.requiredPrefix();
            }
        }

        private static ChainTaskOutcomeRuntime.FormalSourceVerifier
                completedVerifier(String transitionId) {
            return new ChainTaskOutcomeRuntime.FormalSourceVerifier() {
                @Override public void verifyCompleted(
                        ChainTaskOutcomeRuntime.Completed command) {
                    assertEquals(transitionId,
                            command.finalizationTransitionId());
                }

                @Override public void verifyFailed(
                        ChainTaskOutcomeRuntime.Failed command) {
                    throw new AssertionError("unexpected failed outcome");
                }

                @Override public void verifyCancelled(
                        ChainTaskOutcomeRuntime.Cancelled command) {
                    throw new AssertionError("unexpected cancelled outcome");
                }

                @Override public void verifySuperseded(
                        ChainTaskOutcomeRuntime.Superseded command) {
                    throw new AssertionError("unexpected superseded outcome");
                }
            };
        }

        @Override public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.FinalizationCheckRecord>
                appendFinalizationCheck(
                        ChainPersistenceRecords.AuthoritativeFact<
                                ChainPersistenceRecords
                                        .FinalizationCheckRecord> requested) {
            ChainPersistenceRecords.FinalizationCheckRecord existing = checks
                    .stream()
                    .filter(value -> value.attemptNo()
                            == requested.fact().attemptNo())
                    .findFirst().orElse(null);
            boolean replayed = existing != null;
            if (existing == null) {
                existing = withCreatedAt(requested.fact(),
                        requested.fact().createdAt().plusMillis(1));
                checks.add(existing);
                addAuthority(new ChainPersistenceRecords.AuthorityEventRequest(
                        requested.event().eventId(),
                        requested.event().taskId(),
                        requested.event().eventType(),
                        requested.event().transitionId(),
                        requested.event().sourceIdentitySha256(),
                        existing.createdAt()));
            }
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event(existing.eventId()), existing, replayed);
        }

        private static ChainPersistenceRecords.FinalizationCheckRecord
                withCreatedAt(
                ChainPersistenceRecords.FinalizationCheckRecord value,
                Instant createdAt) {
            return new ChainPersistenceRecords.FinalizationCheckRecord(
                    value.finalizationCheckId(), value.taskId(),
                    value.eventId(), value.readinessId(), value.transitionId(),
                    value.attemptNo(), value.taskFrameId(),
                    value.finalPlanRevisionId(), value.acceptedSetSha256(),
                    value.candidateKey(), value.workspaceId(),
                    value.validationId(), value.validationRequestDigest(),
                    value.validationReceiptDigest(),
                    value.publishRequirementDigest(), value.instructionId(),
                    value.projectVersion(), value.inputDigest(),
                    value.contentDigest(), value.publishDigest(),
                    value.resultStatus(), value.errorCode(),
                    value.failureDisposition(), value.runtimePolicyVersion(),
                    createdAt);
        }

        @Override public ChainPersistenceRecords.AuthoritativeAppendResult<
                ChainPersistenceRecords.TaskOutcomeRecord> appendTaskOutcome(
                ChainPersistenceRecords.AuthoritativeFact<
                        ChainPersistenceRecords.TaskOutcomeRecord> requested) {
            boolean replayed = taskOutcome.isPresent();
            if (taskOutcome.isEmpty()) {
                ChainPersistenceRecords.TaskOutcomeRecord value =
                        requested.fact();
                Instant storedAt = value.createdAt().plusMillis(1);
                taskOutcome = Optional.of(new ChainPersistenceRecords
                        .TaskOutcomeRecord(
                        value.outcomeId(), value.taskId(), value.eventId(),
                        value.sourceCommandId(), value.outcomeType(),
                        value.instructionId(), value.taskFrameId(),
                        value.finalPlanId(), value.finalPlanRevisionId(),
                        value.coverage(), value.acceptedSet(),
                        value.finalArtifactId(), value.candidateKey(),
                        value.finalizationReadinessId(),
                        value.finalizationCheckId(), value.validationId(),
                        value.validationRequestDigest(),
                        value.validationReceiptDigest(),
                        value.publishRequirement(),
                        value.publishRequirementDigest(),
                        value.publishOperationId(),
                        value.publishedProjectVersion(),
                        value.publishedRevisionId(), value.publishReceiptId(),
                        value.incompleteItems(), value.limitations(),
                        value.risks(), value.failureCategory(),
                        value.failureCode(), value.sourceDecisionId(),
                        storedAt));
                addAuthority(new ChainPersistenceRecords.AuthorityEventRequest(
                        requested.event().eventId(),
                        requested.event().taskId(),
                        requested.event().eventType(),
                        requested.event().transitionId(),
                        requested.event().sourceIdentitySha256(), storedAt));
            } else if (!sameIgnoringTime(
                    taskOutcome.orElseThrow(), requested.fact())) {
                throw new IllegalStateException(
                        "TaskOutcome replay changed immutable contents");
            }
            ChainPersistenceRecords.TaskOutcomeRecord stored =
                    taskOutcome.orElseThrow();
            return new ChainPersistenceRecords.AuthoritativeAppendResult<>(
                    event(stored.eventId()), stored, replayed);
        }

        private static boolean sameIgnoringTime(Record left, Record right) {
            try {
                for (var component : left.getClass().getRecordComponents()) {
                    if (component.getName().equals("createdAt")
                            || component.getName().equals("committedAt")) {
                        continue;
                    }
                    if (!Objects.equals(component.getAccessor().invoke(left),
                            component.getAccessor().invoke(right))) {
                        return false;
                    }
                }
                return true;
            } catch (ReflectiveOperationException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        private void addAuthority(
                ChainPersistenceRecords.AuthorityEventRequest request) {
            events.add(new ChainPersistenceRecords.AuthorityEventRecord(
                    request.eventId(), request.taskId(), events.size() + 1L,
                    request.eventType(), request.transitionId(),
                    request.sourceIdentitySha256(), request.committedAt()));
        }

        private ChainPersistenceRecords.AuthorityEventRecord event(
                String eventId) {
            return events.stream()
                    .filter(value -> eventId.equals(value.eventId()))
                    .findFirst().orElseThrow();
        }

        @Override public Optional<ChainPersistenceRecords.CommandRecord>
                findCommand(long user, long session, String client) {
            return Optional.empty();
        }

        @Override public Optional<ChainPersistenceRecords.CommandRecord>
                findCommand(String id) {
            return Optional.empty();
        }

        @Override public Optional<ChainPersistenceRecords.TaskRecord> findTask(
                String id) {
            return task.taskId().equals(id) ? Optional.of(task) : Optional.empty();
        }

        @Override public Optional<ChainPersistenceRecords.InstructionRecord>
                findInstruction(String id) {
            if (!readiness.instructionId().equals(id)) return Optional.empty();
            return Optional.of(new ChainPersistenceRecords.InstructionRecord(
                    id, "command-current", task.sessionId(), task.taskId(),
                    12L, SHA, "message-current",
                    io.paperagent.v2.chain.ChainInstructionRelation.SUPPLEMENT,
                    task.sourceInstructionId(), null, SHA, NOW));
        }

        @Override public List<ChainPersistenceRecords.TaskInstructionBindingRecord>
                findTaskInstructions(String taskId, long cut) {
            return List.of();
        }

        @Override public List<ChainPersistenceRecords.AuthorityEventRecord>
                findAuthorityEvents(String taskId, long cut) {
            return events.stream()
                    .filter(value -> value.eventSequence() <= cut).toList();
        }

        @Override public long highestAuthorityEventSequence(String taskId) {
            return events.size();
        }

        @Override public Optional<ChainPersistenceRecords
                .FinalizationReadinessRecord> findReadinessById(String id) {
            return readiness.readinessId().equals(id)
                    ? Optional.of(readiness) : Optional.empty();
        }

        @Override public Optional<ChainPersistenceRecords
                .FinalizationReadinessRecord> findReadinessByScope(String id) {
            return Optional.empty();
        }

        @Override public List<ChainPersistenceRecords.FinalizationReadinessRecord>
                findReadiness(String taskId) {
            return List.of(readiness);
        }

        @Override public List<ChainPersistenceRecords.FinalizationCheckRecord>
                findFinalizationChecks(String readinessId) {
            return List.copyOf(checks);
        }

        @Override public Optional<ChainPersistenceRecords.TaskOutcomeRecord>
                findTaskOutcome(String taskId) {
            return taskOutcome;
        }

        @Override public List<ChainPersistenceRecords.DeliveryRecord>
                findDeliveries(String taskId) {
            return List.of();
        }

        @Override public List<ChainPersistenceRecords.DeliveryRecord>
                findIncompleteDeliveries(String taskId) {
            return List.of();
        }

        @Override public List<ChainPersistenceRecords.DeliveryEventRecord>
                findDeliveryEvents(String deliveryId) {
            return List.of();
        }

        @Override public Optional<ChainPersistenceRecords.TransitionRecord>
                findTransition(String transitionId) {
            return Optional.empty();
        }

        @Override public List<ChainPersistenceRecords.TransitionStageRecord>
                findTransitionStages(String transitionId) {
            return List.of();
        }

        @Override public List<ChainPersistenceRecords.TransitionRecord>
                findIncompleteTransitions(String taskId) {
            return List.of();
        }

        @Override public List<ChainPersistenceRecords.RouteDecisionRecord>
                findRouteDecisions(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.PlanBindingRecord>
                findPlanBindings(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.CandidateStepResultRecord>
                findCandidateStepResults(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.ReviewDecisionRecord>
                findReviewDecisions(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.AcceptedResultRecord>
                findAcceptedResults(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.ResultApplicabilityRecord>
                findApplicabilityDecisions(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.PendingItemRecord>
                findPendingItems(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.PendingItemRecord>
                findOpenPendingItems(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.PendingItemEventRecord>
                findPendingItemEvents(String gapId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.PermissionDecisionRecord>
                findPermissionDecisions(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.ActionBindingRecord>
                findActionBindings(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.ActionBindingRecord>
                findInFlightActions(String taskId) { return List.of(); }

        @Override public List<ChainPersistenceRecords.WorkspaceCandidateRecord>
                findWorkspaceCandidates(String taskId) {
            List<ChainPersistenceRecords.WorkspaceCandidateRecord> values =
                    new ArrayList<>();
            values.add(candidate);
            values.addAll(additionalCandidates);
            return List.copyOf(values);
        }
    }
}
