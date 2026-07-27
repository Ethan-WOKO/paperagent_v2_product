package io.paperagent.v2.runtime.execution.completion.composition;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedStepCompletion;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCompletionRepository;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationProtocolException;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationRequest;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionMaterializationValidationException;
import io.paperagent.v2.runtime.execution.completion.materialization.ActiveStepCompletionEventDraft;
import io.paperagent.v2.runtime.execution.completion.materialization.DeterministicActiveStepCompletionMaterializer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultActiveStepCompletionComposerTest {
    @Test
    void appliedInvokesEachCollaboratorOnceAndDispatchesSameRequestObject() {
        ActiveStepCompletionMaterializationRequest input =
                ActiveStepCompletionCompositionTestFixture.request();
        StepCompletionRequest derived =
                ActiveStepCompletionCompositionTestFixture.materialized(input);
        AtomicInteger materializations = new AtomicInteger();
        RecordingRepository repository = new RecordingRepository();
        PersistedStepCompletion persisted =
                ActiveStepCompletionCompositionTestFixture.persisted(derived);
        repository.result = PersistenceResult.applied(persisted);

        ActiveStepCompletionCommitted outcome = assertInstanceOf(
                ActiveStepCompletionCommitted.class,
                new DefaultActiveStepCompletionComposer(
                        request -> {
                            materializations.incrementAndGet();
                            assertSame(input, request);
                            return derived;
                        },
                        repository)
                        .compose(input));

        assertEquals(1, materializations.get());
        assertEquals(1, repository.calls.get());
        assertSame(derived, repository.request);
        assertEquals(PersistenceOutcome.APPLIED,
                outcome.persistenceOutcome());
        assertSame(persisted, outcome.persistedCompletion());
        assertEquals(derived.stepId(), outcome.stepId());
        assertEquals(
                ActiveStepCompletionLeaseDisposition.RETAINED_FOR_RECOVERY,
                outcome.leaseDisposition());
        assertFalse(outcome.toString().contains(
                ActiveStepCompletionCompositionTestFixture.LEASE_TOKEN));
    }

    @Test
    void replayIsCommittedWithoutRegeneration() {
        ActiveStepCompletionMaterializationRequest input =
                ActiveStepCompletionCompositionTestFixture.request();
        StepCompletionRequest derived =
                ActiveStepCompletionCompositionTestFixture.materialized(input);
        RecordingRepository repository = new RecordingRepository();
        PersistedStepCompletion persisted =
                ActiveStepCompletionCompositionTestFixture.persisted(derived);
        repository.result = PersistenceResult.replayed(persisted);

        ActiveStepCompletionCommitted outcome = assertInstanceOf(
                ActiveStepCompletionCommitted.class,
                new DefaultActiveStepCompletionComposer(
                        ignored -> derived, repository).compose(input));

        assertEquals(PersistenceOutcome.REPLAYED,
                outcome.persistenceOutcome());
        assertSame(persisted, outcome.persistedCompletion());
        assertEquals(derived.stepId(), outcome.stepId());
        assertEquals(1, repository.calls.get());
    }

    @Test
    void rejectionPreservesExactFailureAndRetainedLease() {
        ActiveStepCompletionMaterializationRequest input =
                ActiveStepCompletionCompositionTestFixture.request();
        PersistenceFailure failure = new PersistenceFailure(
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");
        RecordingRepository repository = new RecordingRepository();
        repository.result =
                PersistenceResult.rejected(failure.code(), failure.path());

        ActiveStepCompletionPersistenceRejected outcome = assertInstanceOf(
                ActiveStepCompletionPersistenceRejected.class,
                new DefaultActiveStepCompletionComposer(
                        ActiveStepCompletionCompositionTestFixture
                                ::materialized,
                        repository)
                        .compose(input));

        assertEquals(input.recoveredActiveStep().recovery().planId(),
                outcome.planId());
        assertEquals(
                input.recoveredActiveStep().recovery().activation().stepId(),
                outcome.stepId());
        assertEquals(failure, outcome.failure());
        assertEquals(
                ActiveStepCompletionLeaseDisposition.RETAINED_FOR_RECOVERY,
                outcome.leaseDisposition());
        assertEquals(1, repository.calls.get());
    }

    @Test
    void materializerTypedFailuresPropagateUnchangedBeforePersistence() {
        ActiveStepCompletionMaterializationRequest valid =
                ActiveStepCompletionCompositionTestFixture.request();
        ActiveStepCompletionMaterializationRequest regression =
                new ActiveStepCompletionMaterializationRequest(
                        valid.recoveredActiveStep(),
                        valid.completionFactDraft(),
                        valid.eventDraft(),
                        valid.revisionDraft(),
                        ActiveStepCompletionCompositionTestFixture.T0);
        RecordingRepository repository = new RecordingRepository();
        DefaultActiveStepCompletionComposer composer =
                new DefaultActiveStepCompletionComposer(
                        new DeterministicActiveStepCompletionMaterializer(),
                        repository);
        assertThrows(
                ActiveStepCompletionMaterializationValidationException.class,
                () -> composer.compose(regression));

        ActiveStepCompletionMaterializationRequest inconsistent =
                new ActiveStepCompletionMaterializationRequest(
                        valid.recoveredActiveStep(),
                        valid.completionFactDraft(),
                        new ActiveStepCompletionEventDraft(
                                valid.eventDraft().id(),
                                valid.eventDraft().occurredAt(),
                                valid.eventDraft().type(),
                                valid.eventDraft().causationId(),
                                "invalid correlation",
                                valid.eventDraft().payload()),
                        valid.revisionDraft(),
                        valid.checkpointCreatedAt());
        ActiveStepCompletionMaterializationProtocolException expected =
                assertThrows(
                        ActiveStepCompletionMaterializationProtocolException
                                .class,
                        () -> new DeterministicActiveStepCompletionMaterializer()
                                .materialize(inconsistent));
        ActiveStepCompletionMaterializationProtocolException actual =
                assertThrows(
                        ActiveStepCompletionMaterializationProtocolException
                                .class,
                        () -> new DefaultActiveStepCompletionComposer(
                                request -> {
                                    throw expected;
                                },
                                repository).compose(valid));
        assertSame(expected, actual);
        assertEquals(0, repository.calls.get());
    }

    @Test
    void nullThrowingAndMismatchedMaterializersFailTokenSafely() {
        ActiveStepCompletionMaterializationRequest input =
                ActiveStepCompletionCompositionTestFixture.request();
        RecordingRepository repository = new RecordingRepository();
        assertProtocol(
                () -> new DefaultActiveStepCompletionComposer(
                        request -> null, repository).compose(input),
                ActiveStepCompletionCompositionStage.MATERIALIZE,
                ActiveStepCompletionCompositionProtocolCode
                        .NULL_COLLABORATOR_RESULT,
                "activeStepCompletionComposition.materialization");

        String secret = "materializer-secret-detail";
        ActiveStepCompletionCompositionProtocolException thrown =
                assertProtocol(
                        () -> new DefaultActiveStepCompletionComposer(
                                request -> {
                                    throw new IllegalStateException(secret);
                                },
                                repository).compose(input),
                        ActiveStepCompletionCompositionStage.MATERIALIZE,
                        ActiveStepCompletionCompositionProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        "activeStepCompletionComposition.materialization");
        assertFalse(thrown.toString().contains(secret));
        assertFalse(thrown.getCause().toString().contains(secret));

        StepCompletionRequest valid =
                ActiveStepCompletionCompositionTestFixture.materialized(input);
        StepCompletionRequest wrongToken = copy(
                valid, "wrong-token", valid.completionEvent(),
                valid.completedRevision(), valid.completedCheckpoint());
        assertProtocol(
                () -> new DefaultActiveStepCompletionComposer(
                        request -> wrongToken, repository).compose(input),
                ActiveStepCompletionCompositionStage.MATERIALIZE,
                ActiveStepCompletionCompositionProtocolCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                "activeStepCompletionComposition.materialization.value");
        assertEquals(0, repository.calls.get());
    }

    @Test
    void foundNullAndThrowingRepositoryAreProtocolFailuresWithoutRetry() {
        ActiveStepCompletionMaterializationRequest input =
                ActiveStepCompletionCompositionTestFixture.request();
        StepCompletionRequest derived =
                ActiveStepCompletionCompositionTestFixture.materialized(input);
        RecordingRepository repository = new RecordingRepository();

        repository.result = null;
        assertProtocol(
                () -> new DefaultActiveStepCompletionComposer(
                        ignored -> derived, repository).compose(input),
                ActiveStepCompletionCompositionStage.PERSIST,
                ActiveStepCompletionCompositionProtocolCode
                        .NULL_COLLABORATOR_RESULT,
                "activeStepCompletionComposition.persistence");

        repository.result = PersistenceResult.found(
                ActiveStepCompletionCompositionTestFixture.persisted(derived));
        assertProtocol(
                () -> new DefaultActiveStepCompletionComposer(
                        ignored -> derived, repository).compose(input),
                ActiveStepCompletionCompositionStage.PERSIST,
                ActiveStepCompletionCompositionProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                "activeStepCompletionComposition.persistence.outcome");

        repository.failure = new IllegalStateException("repository-secret");
        ActiveStepCompletionCompositionProtocolException exception =
                assertProtocol(
                        () -> new DefaultActiveStepCompletionComposer(
                                ignored -> derived, repository).compose(input),
                        ActiveStepCompletionCompositionStage.PERSIST,
                        ActiveStepCompletionCompositionProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        "activeStepCompletionComposition.persistence");
        assertFalse(exception.toString().contains("repository-secret"));
        assertEquals(3, repository.calls.get());
    }

    @Test
    void mismatchedPersistedPlanAndStepAuthorityFailsClosed() {
        ActiveStepCompletionMaterializationRequest input =
                ActiveStepCompletionCompositionTestFixture.request();
        StepCompletionRequest derived =
                ActiveStepCompletionCompositionTestFixture.materialized(input);
        var otherRecovered =
                ActiveStepCompletionCompositionTestFixture.recovered(
                        new PlanId("other-plan"),
                        new PlanStepId("other-target"),
                        new PlanStepId("other-peer"));
        StepCompletionRequest other =
                ActiveStepCompletionCompositionTestFixture.materialized(
                        ActiveStepCompletionCompositionTestFixture.request(
                                otherRecovered));
        RecordingRepository repository = new RecordingRepository();
        repository.result = PersistenceResult.applied(
                ActiveStepCompletionCompositionTestFixture.persisted(other));

        assertProtocol(
                () -> new DefaultActiveStepCompletionComposer(
                        ignored -> derived, repository).compose(input),
                ActiveStepCompletionCompositionStage.PERSIST,
                ActiveStepCompletionCompositionProtocolCode
                        .INCONSISTENT_PERSISTENCE_RESULT,
                "activeStepCompletionComposition.persistence.value");
    }

    @Test
    void mismatchedEventRevisionCheckpointOwnerAndFenceFailClosed() {
        ActiveStepCompletionMaterializationRequest input =
                ActiveStepCompletionCompositionTestFixture.request();
        StepCompletionRequest derived =
                ActiveStepCompletionCompositionTestFixture.materialized(input);
        var lease = input.recoveredActiveStep().lease();
        EventEnvelope otherEvent = new EventEnvelope(
                new EventId("other-completion-event"),
                derived.completionEvent().taskFrameId(),
                derived.planId(),
                derived.completionEvent().sequence(),
                derived.completionEvent().occurredAt(),
                derived.completionEvent().type(),
                derived.completionEvent().causationId(),
                derived.completionEvent().correlationId(),
                derived.completionEvent().payload());
        PlanRevision otherRevision = new PlanRevision(
                derived.completedRevision().id(),
                derived.completedRevision().taskFrameId(),
                derived.completedRevision().number(),
                derived.completedRevision().parentRevisionId(),
                "other reason",
                derived.completedRevision().createdAt(),
                derived.completedRevision().steps(),
                derived.completedRevision().completedFacts());
        Checkpoint otherCheckpoint = new Checkpoint(
                derived.completedCheckpoint().taskFrameId(),
                derived.completedCheckpoint().planId(),
                derived.completedCheckpoint().revisionId(),
                derived.completedCheckpoint().revisionNumber(),
                derived.completedCheckpoint().lastEventSequence(),
                derived.completedCheckpoint().planState(),
                derived.completedCheckpoint().stepStates(),
                derived.completedCheckpoint().receiptReferences(),
                derived.completedCheckpoint().createdAt().plusSeconds(1));
        java.util.List<PersistedStepCompletion> mismatches = java.util.List.of(
                new PersistedStepCompletion(
                        derived.planId(), derived.stepId(), "other-owner",
                        lease.fencingToken(), derived.completionEvent(),
                        derived.completedRevision(),
                        new VersionedCheckpoint(
                                4, derived.completedCheckpoint())),
                new PersistedStepCompletion(
                        derived.planId(), derived.stepId(), lease.ownerId(),
                        lease.fencingToken() + 1, derived.completionEvent(),
                        derived.completedRevision(),
                        new VersionedCheckpoint(
                                4, derived.completedCheckpoint())),
                new PersistedStepCompletion(
                        derived.planId(), derived.stepId(), lease.ownerId(),
                        lease.fencingToken(), otherEvent,
                        derived.completedRevision(),
                        new VersionedCheckpoint(
                                4, derived.completedCheckpoint())),
                new PersistedStepCompletion(
                        derived.planId(), derived.stepId(), lease.ownerId(),
                        lease.fencingToken(), derived.completionEvent(),
                        otherRevision,
                        new VersionedCheckpoint(
                                4, derived.completedCheckpoint())),
                new PersistedStepCompletion(
                        derived.planId(), derived.stepId(), lease.ownerId(),
                        lease.fencingToken(), derived.completionEvent(),
                        derived.completedRevision(),
                        new VersionedCheckpoint(4, otherCheckpoint)));
        for (PersistedStepCompletion mismatch : mismatches) {
            RecordingRepository repository = new RecordingRepository();
            repository.result = PersistenceResult.applied(mismatch);
            assertProtocol(
                    () -> new DefaultActiveStepCompletionComposer(
                            ignored -> derived, repository).compose(input),
                    ActiveStepCompletionCompositionStage.PERSIST,
                    ActiveStepCompletionCompositionProtocolCode
                            .INCONSISTENT_PERSISTENCE_RESULT,
                    "activeStepCompletionComposition.persistence.value");
            assertEquals(1, repository.calls.get());
        }
    }

    @Test
    void constructorAndOutcomeValidationUseTypedPaths() {
        ActiveStepCompletionCompositionValidationException missing =
                assertThrows(
                        ActiveStepCompletionCompositionValidationException
                                .class,
                        () -> new DefaultActiveStepCompletionComposer(
                                null, request -> null));
        assertEquals(
                ActiveStepCompletionCompositionValidationCode
                        .REQUIRED_VALUE_MISSING,
                missing.code());
        assertEquals("activeStepCompletionComposition.materializer",
                missing.path());
        assertThrows(
                ActiveStepCompletionCompositionValidationException.class,
                () -> new ActiveStepCompletionCommitted(
                        PersistenceOutcome.FOUND,
                        ActiveStepCompletionCompositionTestFixture.persisted(
                                ActiveStepCompletionCompositionTestFixture
                                        .materialized(
                                                ActiveStepCompletionCompositionTestFixture
                                                        .request())),
                        ActiveStepCompletionLeaseDisposition
                                .RETAINED_FOR_RECOVERY));
        ActiveStepCompletionCompositionValidationException missingStep =
                assertThrows(
                        ActiveStepCompletionCompositionValidationException
                                .class,
                        () -> new ActiveStepCompletionPersistenceRejected(
                                new PlanId("plan"),
                                null,
                                new PersistenceFailure(
                                        PersistenceErrorCode.STALE_VERSION,
                                        "request.expectedCheckpointVersion"),
                                ActiveStepCompletionLeaseDisposition
                                        .RETAINED_FOR_RECOVERY));
        assertEquals(
                ActiveStepCompletionCompositionValidationCode
                        .REQUIRED_VALUE_MISSING,
                missingStep.code());
        assertEquals(
                "activeStepCompletionPersistenceRejected.stepId",
                missingStep.path());
    }

    private static StepCompletionRequest copy(
            StepCompletionRequest source,
            String leaseToken,
            EventEnvelope event,
            PlanRevision revision,
            Checkpoint checkpoint) {
        return new StepCompletionRequest(
                source.planId(),
                leaseToken,
                source.fencingToken(),
                source.expectedRevisionId(),
                source.expectedRevisionNumber(),
                source.expectedCheckpointVersion(),
                source.expectedEventHeadSequence(),
                source.stepId(),
                source.completionFact(),
                event,
                revision,
                checkpoint);
    }

    private static ActiveStepCompletionCompositionProtocolException
            assertProtocol(
                    Runnable invocation,
                    ActiveStepCompletionCompositionStage stage,
                    ActiveStepCompletionCompositionProtocolCode code,
                    String path) {
        ActiveStepCompletionCompositionProtocolException failure =
                assertThrows(
                        ActiveStepCompletionCompositionProtocolException.class,
                        invocation::run);
        assertEquals(stage, failure.stage());
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
        assertEquals(
                ActiveStepCompletionLeaseDisposition.RETAINED_FOR_RECOVERY,
                failure.leaseDisposition());
        assertFalse(failure.getMessage().contains(
                ActiveStepCompletionCompositionTestFixture.LEASE_TOKEN));
        return failure;
    }

    private static final class RecordingRepository
            implements StepCompletionRepository {
        private final AtomicInteger calls = new AtomicInteger();
        private PersistenceResult<PersistedStepCompletion> result;
        private RuntimeException failure;
        private StepCompletionRequest request;

        @Override
        public PersistenceResult<PersistedStepCompletion> complete(
                StepCompletionRequest request) {
            calls.incrementAndGet();
            this.request = request;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
