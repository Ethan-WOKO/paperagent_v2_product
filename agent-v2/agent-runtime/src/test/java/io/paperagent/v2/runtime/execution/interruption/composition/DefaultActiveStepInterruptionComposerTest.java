package io.paperagent.v2.runtime.execution.interruption.composition;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.StepInterruptionRepository;
import io.paperagent.v2.persistence.StepPauseRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationProtocolException;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationRequest;
import io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationValidationException;
import io.paperagent.v2.runtime.execution.interruption.materialization.DeterministicActiveStepInterruptionMaterializer;
import io.paperagent.v2.runtime.execution.interruption.materialization.MaterializedActiveStepInterruption;
import io.paperagent.v2.runtime.execution.interruption.materialization.MaterializedStepPause;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultActiveStepInterruptionComposerTest {
    @Test
    void dispatchesAllKindsOnceWithExactMaterializedRequest() {
        for (StepInterruptionKind kind : StepInterruptionKind.values()) {
            ActiveStepInterruptionMaterializationRequest input =
                    ActiveStepInterruptionCompositionTestFixture.request(kind);
            MaterializedActiveStepInterruption materialized =
                    ActiveStepInterruptionCompositionTestFixture
                            .materialized(input);
            AtomicInteger materializations = new AtomicInteger();
            RecordingRepository repository = new RecordingRepository();
            repository.result = PersistenceResult.applied(
                    ActiveStepInterruptionCompositionTestFixture.persisted(
                            input, materialized));

            ActiveStepInterruptionCommitted outcome = assertInstanceOf(
                    ActiveStepInterruptionCommitted.class,
                    new DefaultActiveStepInterruptionComposer(
                            request -> {
                                materializations.incrementAndGet();
                                assertSame(input, request);
                                return materialized;
                            },
                            repository)
                            .compose(input));

            assertEquals(1, materializations.get());
            assertEquals(1, repository.calls());
            assertEquals(kind, repository.calledKind);
            assertSame(materializedRequest(materialized),
                    repository.calledRequest);
            assertEquals(PersistenceOutcome.APPLIED,
                    outcome.persistenceOutcome());
            assertSame(repository.result.value().orElseThrow(),
                    outcome.persistedInterruption());
            assertEquals(
                    ActiveStepInterruptionLeaseDisposition
                            .RETAINED_FOR_RECOVERY,
                    outcome.leaseDisposition());
        }
    }

    @Test
    void exactReplayIsCommittedWithoutRegeneration() {
        ActiveStepInterruptionMaterializationRequest input =
                ActiveStepInterruptionCompositionTestFixture.request(
                        StepInterruptionKind.FAIL);
        MaterializedActiveStepInterruption materialized =
                ActiveStepInterruptionCompositionTestFixture.materialized(input);
        PersistedStepInterruption persisted =
                ActiveStepInterruptionCompositionTestFixture.persisted(
                        input, materialized);
        RecordingRepository repository = new RecordingRepository();
        repository.result = PersistenceResult.replayed(persisted);

        ActiveStepInterruptionCommitted outcome = assertInstanceOf(
                ActiveStepInterruptionCommitted.class,
                new DefaultActiveStepInterruptionComposer(
                        request -> materialized, repository).compose(input));

        assertEquals(PersistenceOutcome.REPLAYED,
                outcome.persistenceOutcome());
        assertSame(persisted, outcome.persistedInterruption());
        assertEquals(1, repository.calls());
    }

    @Test
    void rejectionPreservesExactFailureAndDoesNotCallAnotherMethod() {
        ActiveStepInterruptionMaterializationRequest input =
                ActiveStepInterruptionCompositionTestFixture.request(
                        StepInterruptionKind.CANCEL);
        PersistenceFailure failure = new PersistenceFailure(
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");
        RecordingRepository repository = new RecordingRepository();
        repository.result = PersistenceResult.rejected(
                failure.code(), failure.path());

        ActiveStepInterruptionPersistenceRejected outcome = assertInstanceOf(
                ActiveStepInterruptionPersistenceRejected.class,
                new DefaultActiveStepInterruptionComposer(
                        ActiveStepInterruptionCompositionTestFixture
                                ::materialized,
                        repository)
                        .compose(input));

        assertEquals(failure, outcome.failure());
        assertEquals(StepInterruptionKind.CANCEL, repository.calledKind);
        assertEquals(1, repository.cancelCalls.get());
        assertEquals(0, repository.pauseCalls.get());
        assertEquals(0, repository.failCalls.get());
    }

    @Test
    void materializerTypedFailuresPropagateUnchangedBeforePersistence() {
        ActiveStepInterruptionMaterializationRequest valid =
                ActiveStepInterruptionCompositionTestFixture.request(
                        StepInterruptionKind.PAUSE);
        ActiveStepInterruptionMaterializationRequest regression =
                new ActiveStepInterruptionMaterializationRequest(
                        valid.recoveredActiveStep(),
                        valid.kind(),
                        valid.eventDraft(),
                        ActiveStepInterruptionCompositionTestFixture.T0);
        RecordingRepository repository = new RecordingRepository();
        DefaultActiveStepInterruptionComposer composer =
                new DefaultActiveStepInterruptionComposer(
                        new DeterministicActiveStepInterruptionMaterializer(),
                        repository);
        ActiveStepInterruptionMaterializationValidationException validation =
                assertThrows(
                        ActiveStepInterruptionMaterializationValidationException
                                .class,
                        () -> composer.compose(regression));
        assertSame(
                validation,
                assertThrows(
                        ActiveStepInterruptionMaterializationValidationException
                                .class,
                        () -> {
                            throw validation;
                        }));

        ActiveStepInterruptionMaterializationRequest inconsistent =
                withTwoActiveSteps(valid);
        ActiveStepInterruptionMaterializationProtocolException protocol =
                assertThrows(
                        ActiveStepInterruptionMaterializationProtocolException
                                .class,
                        () -> composer.compose(inconsistent));
        assertSame(
                protocol,
                assertThrows(
                        ActiveStepInterruptionMaterializationProtocolException
                                .class,
                        () -> {
                            throw protocol;
                        }));
        assertEquals(0, repository.calls());
    }

    @Test
    void nullAndMismatchedMaterializationFailBeforePersistence() {
        ActiveStepInterruptionMaterializationRequest input =
                ActiveStepInterruptionCompositionTestFixture.request(
                        StepInterruptionKind.PAUSE);
        RecordingRepository repository = new RecordingRepository();
        assertProtocol(
                () -> new DefaultActiveStepInterruptionComposer(
                        request -> null, repository).compose(input),
                ActiveStepInterruptionCompositionStage.MATERIALIZE,
                ActiveStepInterruptionCompositionProtocolCode
                        .NULL_COLLABORATOR_RESULT,
                "activeStepInterruptionComposition.materialization");

        MaterializedStepPause valid = (MaterializedStepPause)
                ActiveStepInterruptionCompositionTestFixture.materialized(input);
        StepPauseRequest request = valid.request();
        MaterializedStepPause wrong = new MaterializedStepPause(
                new StepPauseRequest(
                        request.planId(),
                        "different-token",
                        request.fencingToken(),
                        request.expectedRevisionId(),
                        request.expectedRevisionNumber(),
                        request.expectedCheckpointVersion(),
                        request.expectedEventHeadSequence(),
                        request.stepId(),
                        request.pauseEvent(),
                        request.pausedCheckpoint()));
        assertProtocol(
                () -> new DefaultActiveStepInterruptionComposer(
                        ignored -> wrong, repository).compose(input),
                ActiveStepInterruptionCompositionStage.MATERIALIZE,
                ActiveStepInterruptionCompositionProtocolCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                "activeStepInterruptionComposition.materialization.value");
        MaterializedActiveStepInterruption wrongKind =
                ActiveStepInterruptionCompositionTestFixture.materialized(
                        ActiveStepInterruptionCompositionTestFixture.request(
                                StepInterruptionKind.FAIL));
        assertProtocol(
                () -> new DefaultActiveStepInterruptionComposer(
                        ignored -> wrongKind, repository).compose(input),
                ActiveStepInterruptionCompositionStage.MATERIALIZE,
                ActiveStepInterruptionCompositionProtocolCode
                        .INCONSISTENT_MATERIALIZATION_AUTHORITY,
                "activeStepInterruptionComposition.materialization.value");
        assertEquals(0, repository.calls());
    }

    @Test
    void repositoryNullFoundAndExceptionAreTokenSafeProtocolFailures() {
        ActiveStepInterruptionMaterializationRequest input =
                ActiveStepInterruptionCompositionTestFixture.request(
                        StepInterruptionKind.PAUSE);
        RecordingRepository repository = new RecordingRepository();
        repository.result = null;
        assertProtocol(
                () -> composer(repository).compose(input),
                ActiveStepInterruptionCompositionStage.PERSIST,
                ActiveStepInterruptionCompositionProtocolCode
                        .NULL_COLLABORATOR_RESULT,
                "activeStepInterruptionComposition.persistence");

        PersistedStepInterruption persisted =
                ActiveStepInterruptionCompositionTestFixture.persisted(
                        input,
                        ActiveStepInterruptionCompositionTestFixture
                                .materialized(input));
        repository.result = PersistenceResult.found(persisted);
        assertProtocol(
                () -> composer(repository).compose(input),
                ActiveStepInterruptionCompositionStage.PERSIST,
                ActiveStepInterruptionCompositionProtocolCode
                        .UNEXPECTED_PERSISTENCE_OUTCOME,
                "activeStepInterruptionComposition.persistence.outcome");

        repository.throwFailure = true;
        ActiveStepInterruptionCompositionProtocolException failure =
                assertProtocol(
                        () -> composer(repository).compose(input),
                        ActiveStepInterruptionCompositionStage.PERSIST,
                        ActiveStepInterruptionCompositionProtocolCode
                                .COLLABORATOR_EXCEPTION,
                        "activeStepInterruptionComposition.persistence");
        assertTrue(failure.getCause().getMessage()
                .contains(IllegalStateException.class.getName()));
        assertFalse(failure.getCause().getMessage()
                .contains(ActiveStepInterruptionCompositionTestFixture
                        .LEASE_TOKEN));
        assertFalse(failure.getMessage().contains(
                ActiveStepInterruptionCompositionTestFixture.LEASE_TOKEN));
    }

    @Test
    void everyPersistedAuthorityMismatchFailsClosed() {
        ActiveStepInterruptionMaterializationRequest input =
                ActiveStepInterruptionCompositionTestFixture.request(
                        StepInterruptionKind.PAUSE);
        MaterializedActiveStepInterruption materialized =
                ActiveStepInterruptionCompositionTestFixture.materialized(input);
        PersistedStepInterruption valid =
                ActiveStepInterruptionCompositionTestFixture.persisted(
                        input, materialized);
        for (PersistedStepInterruption mismatch : mismatches(valid)) {
            RecordingRepository repository = new RecordingRepository();
            repository.result = PersistenceResult.applied(mismatch);
            assertProtocol(
                    () -> new DefaultActiveStepInterruptionComposer(
                            request -> materialized, repository).compose(input),
                    ActiveStepInterruptionCompositionStage.PERSIST,
                    ActiveStepInterruptionCompositionProtocolCode
                            .INCONSISTENT_PERSISTENCE_RESULT,
                    "activeStepInterruptionComposition.persistence.value");
        }
    }

    @Test
    void invalidConstructorAndOutcomeStatesAreTyped() {
        assertThrows(
                ActiveStepInterruptionCompositionValidationException.class,
                () -> new DefaultActiveStepInterruptionComposer(
                        null, new RecordingRepository()));
        assertThrows(
                ActiveStepInterruptionCompositionValidationException.class,
                () -> new DefaultActiveStepInterruptionComposer(
                        ActiveStepInterruptionCompositionTestFixture
                                ::materialized,
                        null));
        assertThrows(
                ActiveStepInterruptionCompositionValidationException.class,
                () -> composer(new RecordingRepository()).compose(null));
        PersistedStepInterruption persisted =
                ActiveStepInterruptionCompositionTestFixture.persisted(
                        ActiveStepInterruptionCompositionTestFixture.request(
                                StepInterruptionKind.FAIL),
                        ActiveStepInterruptionCompositionTestFixture.materialized(
                                ActiveStepInterruptionCompositionTestFixture
                                        .request(StepInterruptionKind.FAIL)));
        assertThrows(
                ActiveStepInterruptionCompositionValidationException.class,
                () -> new ActiveStepInterruptionCommitted(
                        PersistenceOutcome.FOUND,
                        persisted,
                        ActiveStepInterruptionLeaseDisposition
                                .RETAINED_FOR_RECOVERY));
    }

    private static DefaultActiveStepInterruptionComposer composer(
            StepInterruptionRepository repository) {
        return new DefaultActiveStepInterruptionComposer(
                new DeterministicActiveStepInterruptionMaterializer(),
                repository);
    }

    private static Object materializedRequest(
            MaterializedActiveStepInterruption materialized) {
        if (materialized
                instanceof io.paperagent.v2.runtime.execution.interruption
                        .materialization.MaterializedStepPause value) {
            return value.request();
        }
        if (materialized
                instanceof io.paperagent.v2.runtime.execution.interruption
                        .materialization.MaterializedStepFailure value) {
            return value.request();
        }
        return ((io.paperagent.v2.runtime.execution.interruption
                .materialization.MaterializedStepCancellation) materialized)
                .request();
    }

    private static ActiveStepInterruptionMaterializationRequest
            withTwoActiveSteps(
                    ActiveStepInterruptionMaterializationRequest input) {
        RecoveredActiveStep recovered = input.recoveredActiveStep();
        var old = recovered.recovery();
        Checkpoint checkpoint = old.checkpoint().checkpoint();
        var states = new LinkedHashMap<>(checkpoint.stepStates());
        states.put(
                ActiveStepInterruptionCompositionTestFixture.PEER,
                StepExecutionState.ACTIVE);
        Checkpoint corrupted = new Checkpoint(
                checkpoint.taskFrameId(),
                checkpoint.planId(),
                checkpoint.revisionId(),
                checkpoint.revisionNumber(),
                checkpoint.lastEventSequence(),
                checkpoint.planState(),
                states,
                checkpoint.receiptReferences(),
                checkpoint.createdAt());
        VersionedCheckpoint versioned = new VersionedCheckpoint(
                old.checkpoint().version(), corrupted);
        var activation = new io.paperagent.v2.persistence.PersistedStepActivation(
                old.activation().planId(),
                old.activation().stepId(),
                old.activation().leaseOwnerId(),
                old.activation().fencingToken(),
                old.activation().activationEvent(),
                versioned);
        RecoveredActiveStep inconsistent = new RecoveredActiveStep(
                new io.paperagent.v2.persistence.PersistedStepRecoveryActive(
                        old.taskFrame(),
                        old.plan(),
                        versioned,
                        activation,
                        old.executionContext()),
                recovered.lease(),
                recovered.leaseDisposition());
        return new ActiveStepInterruptionMaterializationRequest(
                inconsistent,
                input.kind(),
                input.eventDraft(),
                input.checkpointCreatedAt());
    }

    private static java.util.List<PersistedStepInterruption> mismatches(
            PersistedStepInterruption valid) {
        Checkpoint checkpoint = valid.interruptedCheckpoint().checkpoint();
        EventEnvelope wrongEvent = new EventEnvelope(
                new EventId("different-event"),
                valid.interruptionEvent().taskFrameId(),
                valid.interruptionEvent().planId(),
                valid.interruptionEvent().sequence(),
                valid.interruptionEvent().occurredAt(),
                valid.interruptionEvent().type(),
                valid.interruptionEvent().causationId(),
                valid.interruptionEvent().correlationId(),
                valid.interruptionEvent().payload());
        var wrongStates = new LinkedHashMap<>(checkpoint.stepStates());
        wrongStates.put(
                ActiveStepInterruptionCompositionTestFixture.PEER,
                StepExecutionState.PAUSED);
        Checkpoint wrongCheckpoint = new Checkpoint(
                checkpoint.taskFrameId(),
                checkpoint.planId(),
                checkpoint.revisionId(),
                checkpoint.revisionNumber(),
                checkpoint.lastEventSequence(),
                checkpoint.planState(),
                wrongStates,
                checkpoint.receiptReferences(),
                checkpoint.createdAt());
        PlanId wrongPlanId = new PlanId("other-plan");
        EventEnvelope wrongPlanEvent = event(
                valid.interruptionEvent(), wrongPlanId,
                valid.interruptionEvent().id());
        Checkpoint wrongPlanCheckpoint = checkpoint(
                checkpoint,
                wrongPlanId,
                checkpoint.planState(),
                checkpoint.stepStates());
        var wrongStepStates = new LinkedHashMap<>(checkpoint.stepStates());
        wrongStepStates.put(
                ActiveStepInterruptionCompositionTestFixture.PEER,
                StepExecutionState.PAUSED);
        Checkpoint wrongStepCheckpoint = checkpoint(
                checkpoint,
                checkpoint.planId(),
                checkpoint.planState(),
                wrongStepStates);
        var wrongKindStates = new LinkedHashMap<>(checkpoint.stepStates());
        wrongKindStates.put(
                ActiveStepInterruptionCompositionTestFixture.TARGET,
                StepExecutionState.FAILED);
        Checkpoint wrongKindCheckpoint = checkpoint(
                checkpoint,
                checkpoint.planId(),
                io.paperagent.v2.contracts.PlanExecutionState.FAILED,
                wrongKindStates);
        return java.util.List.of(
                new PersistedStepInterruption(
                        wrongPlanId, valid.stepId(), valid.kind(),
                        valid.leaseOwnerId(), valid.fencingToken(),
                        wrongPlanEvent,
                        new VersionedCheckpoint(4, wrongPlanCheckpoint)),
                new PersistedStepInterruption(
                        valid.planId(),
                        ActiveStepInterruptionCompositionTestFixture.PEER,
                        valid.kind(),
                        valid.leaseOwnerId(), valid.fencingToken(),
                        valid.interruptionEvent(),
                        new VersionedCheckpoint(4, wrongStepCheckpoint)),
                new PersistedStepInterruption(
                        valid.planId(), valid.stepId(),
                        StepInterruptionKind.FAIL,
                        valid.leaseOwnerId(), valid.fencingToken(),
                        valid.interruptionEvent(),
                        new VersionedCheckpoint(4, wrongKindCheckpoint)),
                new PersistedStepInterruption(
                        valid.planId(), valid.stepId(), valid.kind(),
                        "wrong-owner", valid.fencingToken(),
                        valid.interruptionEvent(),
                        valid.interruptedCheckpoint()),
                new PersistedStepInterruption(
                        valid.planId(), valid.stepId(), valid.kind(),
                        valid.leaseOwnerId(), valid.fencingToken() + 1,
                        valid.interruptionEvent(),
                        valid.interruptedCheckpoint()),
                new PersistedStepInterruption(
                        valid.planId(), valid.stepId(), valid.kind(),
                        valid.leaseOwnerId(), valid.fencingToken(),
                        wrongEvent, valid.interruptedCheckpoint()),
                new PersistedStepInterruption(
                        valid.planId(), valid.stepId(), valid.kind(),
                        valid.leaseOwnerId(), valid.fencingToken(),
                        valid.interruptionEvent(),
                        new VersionedCheckpoint(4, wrongCheckpoint)));
    }

    private static EventEnvelope event(
            EventEnvelope source,
            PlanId planId,
            EventId eventId) {
        return new EventEnvelope(
                eventId,
                source.taskFrameId(),
                planId,
                source.sequence(),
                source.occurredAt(),
                source.type(),
                source.causationId(),
                source.correlationId(),
                source.payload());
    }

    private static Checkpoint checkpoint(
            Checkpoint source,
            PlanId planId,
            io.paperagent.v2.contracts.PlanExecutionState planState,
            java.util.Map<io.paperagent.v2.contracts.PlanStepId,
                    StepExecutionState> stepStates) {
        return new Checkpoint(
                source.taskFrameId(),
                planId,
                source.revisionId(),
                source.revisionNumber(),
                source.lastEventSequence(),
                planState,
                stepStates,
                source.receiptReferences(),
                source.createdAt());
    }

    private static ActiveStepInterruptionCompositionProtocolException
            assertProtocol(
                    org.junit.jupiter.api.function.Executable action,
                    ActiveStepInterruptionCompositionStage stage,
                    ActiveStepInterruptionCompositionProtocolCode code,
                    String path) {
        ActiveStepInterruptionCompositionProtocolException failure =
                assertThrows(
                        ActiveStepInterruptionCompositionProtocolException.class,
                        action);
        assertEquals(stage, failure.stage());
        assertEquals(code, failure.code());
        assertEquals(path, failure.path());
        assertEquals(
                ActiveStepInterruptionLeaseDisposition.RETAINED_FOR_RECOVERY,
                failure.leaseDisposition());
        return failure;
    }

    private static final class RecordingRepository
            implements StepInterruptionRepository {
        private final AtomicInteger pauseCalls = new AtomicInteger();
        private final AtomicInteger failCalls = new AtomicInteger();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private StepInterruptionKind calledKind;
        private Object calledRequest;
        private PersistenceResult<PersistedStepInterruption> result;
        private boolean throwFailure;

        @Override
        public PersistenceResult<PersistedStepInterruption> pause(
                StepPauseRequest request) {
            pauseCalls.incrementAndGet();
            calledKind = StepInterruptionKind.PAUSE;
            calledRequest = request;
            return answer();
        }

        @Override
        public PersistenceResult<PersistedStepInterruption> fail(
                StepFailRequest request) {
            failCalls.incrementAndGet();
            calledKind = StepInterruptionKind.FAIL;
            calledRequest = request;
            return answer();
        }

        @Override
        public PersistenceResult<PersistedStepInterruption> cancel(
                StepCancelRequest request) {
            cancelCalls.incrementAndGet();
            calledKind = StepInterruptionKind.CANCEL;
            calledRequest = request;
            return answer();
        }

        int calls() {
            return pauseCalls.get() + failCalls.get() + cancelCalls.get();
        }

        private PersistenceResult<PersistedStepInterruption> answer() {
            if (throwFailure) {
                throw new IllegalStateException(
                        "repository leaked " + ActiveStepInterruptionCompositionTestFixture
                                .LEASE_TOKEN);
            }
            return result;
        }
    }
}
