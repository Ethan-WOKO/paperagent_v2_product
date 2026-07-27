package io.paperagent.v2.runtime.execution.interruption.materialization;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.StepPauseRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationFixture.PEER;
import static io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationFixture.T0;
import static io.paperagent.v2.runtime.execution.interruption.materialization.ActiveStepInterruptionMaterializationFixture.TARGET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicActiveStepInterruptionMaterializerTest {
    private final ActiveStepInterruptionMaterializer materializer =
            new DeterministicActiveStepInterruptionMaterializer();

    @Test
    void materializesAllThreeExactStableRequestVariants() {
        assertPause();
        assertFailure();
        assertCancellation();
    }

    @Test
    void preservesAuthorityPeerStatesReceiptsAndOptionalCausation() {
        ActiveStepInterruptionMaterializationRequest base =
                ActiveStepInterruptionMaterializationFixture.request(
                        StepInterruptionKind.PAUSE);
        ActiveStepInterruptionEventDraft noCause =
                new ActiveStepInterruptionEventDraft(
                        base.eventDraft().id(),
                        base.eventDraft().occurredAt(),
                        base.eventDraft().type(),
                        Optional.empty(),
                        base.eventDraft().correlationId(),
                        base.eventDraft().payload());
        MaterializedStepPause result = assertInstanceOf(
                MaterializedStepPause.class,
                materializer.materialize(
                        new ActiveStepInterruptionMaterializationRequest(
                                base.recoveredActiveStep(),
                                base.kind(),
                                noCause,
                                base.checkpointCreatedAt())));
        StepPauseRequest request = result.request();
        Checkpoint before =
                base.recoveredActiveStep().recovery().checkpoint().checkpoint();
        assertEquals(Optional.empty(), request.pauseEvent().causationId());
        assertEquals(before.revisionId(), request.pausedCheckpoint().revisionId());
        assertEquals(
                before.revisionNumber(),
                request.pausedCheckpoint().revisionNumber());
        assertEquals(
                before.receiptReferences(),
                request.pausedCheckpoint().receiptReferences());
        assertEquals(
                StepExecutionState.NOT_STARTED,
                request.pausedCheckpoint().stepStates().get(PEER));
        assertNotSame(before.stepStates(), request.pausedCheckpoint().stepStates());
    }

    @Test
    void replayMaterializationIsDeterministicallyEqual() {
        ActiveStepInterruptionMaterializationRequest request =
                ActiveStepInterruptionMaterializationFixture.request(
                        StepInterruptionKind.CANCEL);
        assertEquals(
                materializer.materialize(request),
                materializer.materialize(request));
    }

    @Test
    void rejectsNullDraftSelfCausationAndRegressedCheckpointTime() {
        ActiveStepInterruptionMaterializationValidationException missing =
                assertThrows(
                        ActiveStepInterruptionMaterializationValidationException
                                .class,
                        () -> materializer.materialize(null));
        assertEquals(
                ActiveStepInterruptionMaterializationValidationCode
                        .REQUIRED_VALUE_MISSING,
                missing.code());

        ActiveStepInterruptionMaterializationValidationException self =
                assertThrows(
                        ActiveStepInterruptionMaterializationValidationException
                                .class,
                        () -> new ActiveStepInterruptionEventDraft(
                                new EventId("same"),
                                T0,
                                new io.paperagent.v2.contracts.EventType("type"),
                                Optional.of(new EventId("same")),
                                "correlation",
                                new io.paperagent.v2.contracts.InlineEventPayload(
                                        new io.paperagent.v2.contracts.ObjectValue(
                                                java.util.Map.of()))));
        assertEquals(
                ActiveStepInterruptionMaterializationValidationCode
                        .EVENT_SELF_CAUSATION,
                self.code());

        ActiveStepInterruptionMaterializationRequest base =
                ActiveStepInterruptionMaterializationFixture.request(
                        StepInterruptionKind.FAIL);
        ActiveStepInterruptionMaterializationValidationException time =
                assertThrows(
                        ActiveStepInterruptionMaterializationValidationException
                                .class,
                        () -> materializer.materialize(
                                new ActiveStepInterruptionMaterializationRequest(
                                        base.recoveredActiveStep(),
                                        base.kind(),
                                        base.eventDraft(),
                                        T0)));
        assertEquals(
                ActiveStepInterruptionMaterializationValidationCode
                        .CHECKPOINT_TIME_REGRESSION,
                time.code());
        assertEquals(
                ActiveStepInterruptionMaterializationStage.CHECKPOINT,
                time.stage());

        assertThrows(
                ActiveStepInterruptionMaterializationValidationException.class,
                () -> new ActiveStepInterruptionMaterializationRequest(
                        base.recoveredActiveStep(),
                        null,
                        base.eventDraft(),
                        base.checkpointCreatedAt()));
        assertThrows(
                ActiveStepInterruptionMaterializationValidationException.class,
                () -> new ActiveStepInterruptionMaterializationRequest(
                        base.recoveredActiveStep(),
                        base.kind(),
                        null,
                        base.checkpointCreatedAt()));
        assertThrows(
                ActiveStepInterruptionMaterializationValidationException.class,
                () -> new ActiveStepInterruptionMaterializationRequest(
                        base.recoveredActiveStep(),
                        base.kind(),
                        base.eventDraft(),
                        null));
    }

    @Test
    void rejectsWrongCheckpointVersionSequenceAndDisposition() {
        RecoveredActiveStep source =
                ActiveStepInterruptionMaterializationFixture.recovered();
        Checkpoint old = source.recovery().checkpoint().checkpoint();
        VersionedCheckpoint wrongVersion = new VersionedCheckpoint(4, old);
        PersistedStepActivation activation =
                ActiveStepInterruptionMaterializationFixture.activation(
                        source, TARGET, wrongVersion);
        assertAuthorityFailure(
                ActiveStepInterruptionMaterializationFixture.withCheckpoint(
                        source, wrongVersion, activation));

        Checkpoint wrongSequence = checkpoint(
                old, old.stepStates(), old.planState(), 3);
        VersionedCheckpoint versioned = new VersionedCheckpoint(3, wrongSequence);
        PersistedStepActivation sequenceActivation =
                new PersistedStepActivation(
                        source.planId(),
                        TARGET,
                        "owner",
                        1,
                        new io.paperagent.v2.contracts.EventEnvelope(
                                new EventId("activation-sequence-3"),
                                old.taskFrameId(),
                                old.planId(),
                                3,
                                T0.plusSeconds(2),
                                new io.paperagent.v2.contracts.EventType("type"),
                                Optional.empty(),
                                "correlation",
                                new io.paperagent.v2.contracts.InlineEventPayload(
                                        new io.paperagent.v2.contracts.ObjectValue(
                                                java.util.Map.of()))),
                        versioned);
        assertAuthorityFailure(
                ActiveStepInterruptionMaterializationFixture.withCheckpoint(
                        source, versioned, sequenceActivation));

        LeaseRecord wrongPlanLease = new LeaseRecord(
                new io.paperagent.v2.contracts.PlanId("other-plan"),
                source.lease().ownerId(),
                source.lease().leaseToken(),
                source.lease().fencingToken(),
                source.lease().acquiredAt(),
                source.lease().expiresAt());
        assertThrows(
                io.paperagent.v2.runtime.execution.recovery.composition
                        .StepRecoveryValidationException.class,
                () -> new RecoveredActiveStep(
                        source.recovery(),
                        wrongPlanLease,
                        source.leaseDisposition()));
        assertThrows(
                io.paperagent.v2.runtime.execution.recovery.composition
                        .StepRecoveryValidationException.class,
                () -> new RecoveredActiveStep(
                        source.recovery(),
                        source.lease(),
                        io.paperagent.v2.runtime.execution.recovery.composition
                                .StepRecoveryLeaseDisposition.NOT_ACQUIRED));
    }

    @Test
    void rejectsNoOrMultipleActiveAndDisallowedPeerStates() {
        RecoveredActiveStep source =
                ActiveStepInterruptionMaterializationFixture.recovered();
        Checkpoint old = source.recovery().checkpoint().checkpoint();

        var none = new LinkedHashMap<>(old.stepStates());
        none.put(TARGET, StepExecutionState.PAUSED);
        Checkpoint noneActive = checkpoint(
                old, none, PlanExecutionState.PAUSED, 2);
        assertMaterializationRejects(
                ActiveStepInterruptionMaterializationFixture.withCheckpoint(
                        source,
                        new VersionedCheckpoint(3, noneActive),
                        source.recovery().activation()));

        var multiple = new LinkedHashMap<>(old.stepStates());
        multiple.put(PEER, StepExecutionState.ACTIVE);
        assertMalformedStep(source, old, multiple);

        var disallowed = new LinkedHashMap<>(old.stepStates());
        disallowed.put(PEER, StepExecutionState.PAUSED);
        assertMalformedStep(source, old, disallowed);
    }

    @Test
    void rejectsMismatchedPlanActivationStepAndMalformedCheckpointAuthority() {
        RecoveredActiveStep source =
                ActiveStepInterruptionMaterializationFixture.recovered();
        PersistedStepRecoveryActive old = source.recovery();

        Plan mismatchedPlan = new Plan(
                new io.paperagent.v2.contracts.PlanId("different-plan"),
                old.plan().taskFrameId(),
                old.plan().revisions());
        LeaseRecord matchingDifferentLease = new LeaseRecord(
                mismatchedPlan.id(),
                source.lease().ownerId(),
                source.lease().leaseToken(),
                source.lease().fencingToken(),
                source.lease().acquiredAt(),
                source.lease().expiresAt());
        assertAuthorityFailure(new RecoveredActiveStep(
                new PersistedStepRecoveryActive(
                        old.taskFrame(),
                        mismatchedPlan,
                        old.checkpoint(),
                        old.activation(),
                        old.executionContext()),
                matchingDifferentLease,
                source.leaseDisposition()));

        Checkpoint checkpoint = old.checkpoint().checkpoint();
        var onlyTarget = new LinkedHashMap<>(checkpoint.stepStates());
        onlyTarget.remove(PEER);
        VersionedCheckpoint malformedCheckpoint = new VersionedCheckpoint(
                3,
                checkpoint(
                        checkpoint,
                        onlyTarget,
                        checkpoint.planState(),
                        checkpoint.lastEventSequence()));
        assertAuthorityFailure(
                ActiveStepInterruptionMaterializationFixture.withCheckpoint(
                        source,
                        malformedCheckpoint,
                        old.activation()));

        var peerActiveStates = new LinkedHashMap<>(
                old.checkpoint().checkpoint().stepStates());
        peerActiveStates.put(TARGET, StepExecutionState.NOT_STARTED);
        peerActiveStates.put(PEER, StepExecutionState.ACTIVE);
        VersionedCheckpoint peerActiveCheckpoint = new VersionedCheckpoint(
                3,
                checkpoint(
                        old.checkpoint().checkpoint(),
                        peerActiveStates,
                        PlanExecutionState.ACTIVE,
                        2));
        PersistedStepActivation wrongStep =
                ActiveStepInterruptionMaterializationFixture.activation(
                        source, PEER, peerActiveCheckpoint);
        assertAuthorityFailure(
                ActiveStepInterruptionMaterializationFixture.withCheckpoint(
                        source, old.checkpoint(), wrongStep));
    }

    @Test
    void rejectsACompletedTargetEvenWhenTheSnapshotClaimsItIsActive() {
        RecoveredActiveStep source =
                ActiveStepInterruptionMaterializationFixture.recovered();
        PersistedStepRecoveryActive old = source.recovery();
        PlanRevision revision = old.plan().latestRevision();
        PlanRevision withCompletedTarget = new PlanRevision(
                revision.id(),
                revision.taskFrameId(),
                revision.number(),
                revision.parentRevisionId(),
                revision.reason(),
                revision.createdAt(),
                revision.steps(),
                Map.of(
                        TARGET,
                        new CompletionFact(
                                TARGET,
                                "outcome-hash",
                                T0.plusSeconds(1),
                                List.of())));
        Plan plan = new Plan(
                old.plan().id(),
                old.plan().taskFrameId(),
                List.of(withCompletedTarget));
        RecoveredActiveStep malformed = new RecoveredActiveStep(
                new PersistedStepRecoveryActive(
                        old.taskFrame(),
                        plan,
                        old.checkpoint(),
                        old.activation(),
                        old.executionContext()),
                source.lease(),
                source.leaseDisposition());
        assertAuthorityFailure(malformed);
    }

    @Test
    void diagnosticsAndStringFormsDoNotExposeLeaseToken() {
        ActiveStepInterruptionMaterializationRequest request =
                ActiveStepInterruptionMaterializationFixture.request(
                        StepInterruptionKind.PAUSE);
        MaterializedActiveStepInterruption result =
                materializer.materialize(request);
        String token = ActiveStepInterruptionMaterializationFixture.LEASE_TOKEN;
        assertTrue(!request.toString().contains(token));
        assertTrue(!request.recoveredActiveStep().toString().contains(token));
        assertTrue(!result.toString().contains(token));
        assertTrue(!assertThrows(
                        ActiveStepInterruptionMaterializationValidationException
                                .class,
                        () -> materializer.materialize(null))
                .getMessage()
                .contains(token));
    }

    private void assertPause() {
        ActiveStepInterruptionMaterializationRequest input =
                ActiveStepInterruptionMaterializationFixture.request(
                        StepInterruptionKind.PAUSE);
        MaterializedStepPause output = assertInstanceOf(
                MaterializedStepPause.class, materializer.materialize(input));
        assertCommon(
                input,
                output.kind(),
                output.request().planId(),
                output.request().leaseToken(),
                output.request().fencingToken(),
                output.request().expectedRevisionNumber(),
                output.request().expectedCheckpointVersion(),
                output.request().expectedEventHeadSequence(),
                output.request().pauseEvent(),
                output.request().pausedCheckpoint(),
                StepExecutionState.PAUSED,
                PlanExecutionState.PAUSED);
    }

    private void assertFailure() {
        ActiveStepInterruptionMaterializationRequest input =
                ActiveStepInterruptionMaterializationFixture.request(
                        StepInterruptionKind.FAIL);
        MaterializedStepFailure output = assertInstanceOf(
                MaterializedStepFailure.class, materializer.materialize(input));
        StepFailRequest request = output.request();
        assertCommon(
                input, output.kind(), request.planId(), request.leaseToken(),
                request.fencingToken(), request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(), request.failureEvent(),
                request.failedCheckpoint(), StepExecutionState.FAILED,
                PlanExecutionState.FAILED);
    }

    private void assertCancellation() {
        ActiveStepInterruptionMaterializationRequest input =
                ActiveStepInterruptionMaterializationFixture.request(
                        StepInterruptionKind.CANCEL);
        MaterializedStepCancellation output = assertInstanceOf(
                MaterializedStepCancellation.class,
                materializer.materialize(input));
        StepCancelRequest request = output.request();
        assertCommon(
                input, output.kind(), request.planId(), request.leaseToken(),
                request.fencingToken(), request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(),
                request.cancellationEvent(), request.cancelledCheckpoint(),
                StepExecutionState.CANCELLED, PlanExecutionState.CANCELLED);
    }

    private static void assertCommon(
            ActiveStepInterruptionMaterializationRequest input,
            StepInterruptionKind kind,
            io.paperagent.v2.contracts.PlanId planId,
            String leaseToken,
            long fence,
            long revisionNumber,
            long checkpointVersion,
            long eventHead,
            io.paperagent.v2.contracts.EventEnvelope event,
            Checkpoint checkpoint,
            StepExecutionState stepState,
            PlanExecutionState planState) {
        RecoveredActiveStep recovered = input.recoveredActiveStep();
        assertEquals(input.kind(), kind);
        assertEquals(recovered.planId(), planId);
        assertEquals(
                recovered.recovery().plan().latestRevision().id(),
                switch (kind) {
                    case PAUSE -> ((MaterializedStepPause)
                            new DeterministicActiveStepInterruptionMaterializer()
                                    .materialize(input))
                            .request()
                            .expectedRevisionId();
                    case FAIL -> ((MaterializedStepFailure)
                            new DeterministicActiveStepInterruptionMaterializer()
                                    .materialize(input))
                            .request()
                            .expectedRevisionId();
                    case CANCEL -> ((MaterializedStepCancellation)
                            new DeterministicActiveStepInterruptionMaterializer()
                                    .materialize(input))
                            .request()
                            .expectedRevisionId();
                });
        assertEquals(recovered.lease().leaseToken(), leaseToken);
        assertEquals(recovered.lease().fencingToken(), fence);
        assertEquals(1, revisionNumber);
        assertEquals(3, checkpointVersion);
        assertEquals(2, eventHead);
        assertEquals(3, event.sequence());
        assertEquals(recovered.recovery().taskFrame().id(), event.taskFrameId());
        assertEquals(recovered.planId(), event.planId());
        assertEquals(input.eventDraft().id(), event.id());
        assertEquals(stepState, checkpoint.stepStates().get(TARGET));
        assertEquals(planState, checkpoint.planState());
        assertEquals(3, checkpoint.lastEventSequence());
        assertEquals(input.checkpointCreatedAt(), checkpoint.createdAt());
    }

    private void assertMalformedStep(
            RecoveredActiveStep source,
            Checkpoint old,
            java.util.Map<io.paperagent.v2.contracts.PlanStepId,
                    StepExecutionState> states) {
        Checkpoint changed = checkpoint(old, states, old.planState(), 2);
        VersionedCheckpoint versioned = new VersionedCheckpoint(3, changed);
        PersistedStepActivation activation =
                ActiveStepInterruptionMaterializationFixture.activation(
                        source, TARGET, versioned);
        ActiveStepInterruptionMaterializationProtocolException failure =
                assertMaterializationRejects(
                        ActiveStepInterruptionMaterializationFixture
                                .withCheckpoint(source, versioned, activation));
        assertTrue(
                failure.code()
                                == ActiveStepInterruptionMaterializationProtocolCode
                                        .STEP_NOT_ELIGIBLE
                        || failure.code()
                                == ActiveStepInterruptionMaterializationProtocolCode
                        .INCONSISTENT_RECOVERED_AUTHORITY);
    }

    private ActiveStepInterruptionMaterializationProtocolException
            assertMaterializationRejects(RecoveredActiveStep malformed) {
        return assertThrows(
                ActiveStepInterruptionMaterializationProtocolException.class,
                () -> materializer.materialize(
                        new ActiveStepInterruptionMaterializationRequest(
                                malformed,
                                StepInterruptionKind.PAUSE,
                                ActiveStepInterruptionMaterializationFixture
                                        .draft("invalid-step"),
                                T0.plusSeconds(4))));
    }

    private void assertAuthorityFailure(RecoveredActiveStep malformed) {
        ActiveStepInterruptionMaterializationProtocolException failure =
                assertThrows(
                        ActiveStepInterruptionMaterializationProtocolException
                                .class,
                        () -> materializer.materialize(
                                new ActiveStepInterruptionMaterializationRequest(
                                        malformed,
                                        StepInterruptionKind.PAUSE,
                                        ActiveStepInterruptionMaterializationFixture
                                                .draft("invalid-authority"),
                                        T0.plusSeconds(4))));
        assertEquals(
                ActiveStepInterruptionMaterializationProtocolCode
                        .INCONSISTENT_RECOVERED_AUTHORITY,
                failure.code());
    }

    private static Checkpoint checkpoint(
            Checkpoint source,
            java.util.Map<io.paperagent.v2.contracts.PlanStepId,
                    StepExecutionState> states,
            PlanExecutionState planState,
            long sequence) {
        return new Checkpoint(
                source.taskFrameId(),
                source.planId(),
                source.revisionId(),
                source.revisionNumber(),
                sequence,
                planState,
                states,
                source.receiptReferences(),
                source.createdAt());
    }
}
