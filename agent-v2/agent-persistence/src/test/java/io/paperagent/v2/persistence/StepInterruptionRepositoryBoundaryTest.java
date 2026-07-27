package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StepInterruptionRepositoryBoundaryTest {
    @Test
    void wrongLeaseStaleSourceAndMalformedCheckpointRejectWithoutBusinessWrites() {
        StepInterruptionRepositoryTest.Harness harness =
                StepInterruptionRepositoryTest.active("boundaries");
        StepPauseRequest request = StepInterruptionRepositoryTest.pauseRequest(
                harness, "pause-boundaries");
        StateSnapshot before = StateSnapshot.capture(harness.state());

        StepPauseRequest wrongLease = new StepPauseRequest(
                request.planId(),
                "wrong-token",
                request.fencingToken(),
                request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(),
                request.stepId(),
                request.pauseEvent(),
                request.pausedCheckpoint());
        StepInterruptionRepositoryTest.assertFailure(
                harness.interruptions().pause(wrongLease),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");

        StepPauseRequest stale = new StepPauseRequest(
                request.planId(),
                request.leaseToken(),
                request.fencingToken(),
                request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion() + 1,
                request.expectedEventHeadSequence(),
                request.stepId(),
                request.pauseEvent(),
                request.pausedCheckpoint());
        StepInterruptionRepositoryTest.assertFailure(
                harness.interruptions().pause(stale),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedCheckpointVersion");

        Map<PlanStepId, StepExecutionState> changedStates = new LinkedHashMap<>(
                request.pausedCheckpoint().stepStates());
        changedStates.put(PersistenceFixtures.STEP_2, StepExecutionState.ACTIVE);
        Checkpoint malformedCheckpoint = new Checkpoint(
                request.pausedCheckpoint().taskFrameId(),
                request.pausedCheckpoint().planId(),
                request.pausedCheckpoint().revisionId(),
                request.pausedCheckpoint().revisionNumber(),
                request.pausedCheckpoint().lastEventSequence(),
                PlanExecutionState.PAUSED,
                changedStates,
                request.pausedCheckpoint().receiptReferences(),
                request.pausedCheckpoint().createdAt());
        StepPauseRequest malformed = new StepPauseRequest(
                request.planId(),
                request.leaseToken(),
                request.fencingToken(),
                request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(),
                request.stepId(),
                request.pauseEvent(),
                malformedCheckpoint);
        StepInterruptionRepositoryTest.assertFailure(
                harness.interruptions().pause(malformed),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.pausedCheckpoint");

        assertEquals(before, StateSnapshot.capture(harness.state()));
    }

    @Test
    void malformedReplayMarkersAndCrossMapDuplicatesFailClosedWithoutClockAccess() {
        StepInterruptionRepositoryTest.Harness malformedHarness =
                StepInterruptionRepositoryTest.active("malformed-marker");
        StepPauseRequest malformedRequest =
                StepInterruptionRepositoryTest.pauseRequest(
                        malformedHarness, "pause-malformed-marker");
        StepInterruptionRepositoryTest.requireApplied(
                malformedHarness.interruptions().pause(malformedRequest));
        InMemoryState.StepPauseMarker marker = malformedHarness.state().stepPauses
                .get(malformedHarness.plan().id())
                .get(malformedRequest.pauseEvent().id());
        malformedHarness.state().stepPauses.get(malformedHarness.plan().id()).put(
                malformedRequest.pauseEvent().id(),
                new InMemoryState.StepPauseMarker(
                        marker.request(), marker.result(), null));
        int malformedBefore = malformedHarness.clock().observationCount();
        malformedHarness.clock().failOnObservation();

        StepInterruptionRepositoryTest.assertFailure(
                malformedHarness.interruptions().pause(malformedRequest),
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                "stepInterruption");
        assertEquals(malformedBefore, malformedHarness.clock().observationCount());

        StepInterruptionRepositoryTest.Harness duplicateHarness =
                StepInterruptionRepositoryTest.active("duplicate-marker");
        StepPauseRequest duplicateRequest =
                StepInterruptionRepositoryTest.pauseRequest(
                        duplicateHarness, "pause-duplicate-marker");
        StepInterruptionRepositoryTest.requireApplied(
                duplicateHarness.interruptions().pause(duplicateRequest));
        duplicateHarness.state().stepFailures.get(duplicateHarness.plan().id()).put(
                duplicateRequest.pauseEvent().id(), null);
        int duplicateBefore = duplicateHarness.clock().observationCount();
        duplicateHarness.clock().failOnObservation();

        StepInterruptionRepositoryTest.assertFailure(
                duplicateHarness.interruptions().pause(duplicateRequest),
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                "stepInterruption");
        assertEquals(duplicateBefore, duplicateHarness.clock().observationCount());
    }

    @Test
    void orphanedInterruptionMarkerOrEventProjectionFailsClosedWithoutClockAccess() {
        StepInterruptionRepositoryTest.Harness linkHarness =
                StepInterruptionRepositoryTest.active("orphaned-link");
        StepPauseRequest linkRequest = StepInterruptionRepositoryTest.pauseRequest(
                linkHarness, "pause-orphaned-link");
        StepInterruptionRepositoryTest.requireApplied(
                linkHarness.interruptions().pause(linkRequest));
        linkHarness.state().executionMutationLinks.get(linkRequest.planId())
                .remove(1);
        assertPartialWithoutClock(linkHarness, linkRequest);

        StepInterruptionRepositoryTest.Harness streamHarness =
                StepInterruptionRepositoryTest.active("orphaned-stream");
        StepPauseRequest streamRequest = StepInterruptionRepositoryTest.pauseRequest(
                streamHarness, "pause-orphaned-stream");
        StepInterruptionRepositoryTest.requireApplied(
                streamHarness.interruptions().pause(streamRequest));
        streamHarness.state().eventStreams.get(streamRequest.planId())
                .remove(streamRequest.pauseEvent().sequence());
        assertPartialWithoutClock(streamHarness, streamRequest);

        StepInterruptionRepositoryTest.Harness indexHarness =
                StepInterruptionRepositoryTest.active("orphaned-index");
        StepPauseRequest indexRequest = StepInterruptionRepositoryTest.pauseRequest(
                indexHarness, "pause-orphaned-index");
        StepInterruptionRepositoryTest.requireApplied(
                indexHarness.interruptions().pause(indexRequest));
        indexHarness.state().eventsById.remove(indexRequest.pauseEvent().id());
        assertPartialWithoutClock(indexHarness, indexRequest);
    }

    @Test
    void replayRejectsShapeValidMarkerThatChangesANonTargetStepWithoutClockAccess() {
        StepInterruptionRepositoryTest.Harness harness =
                StepInterruptionRepositoryTest.active("replay-non-target");
        StepPauseRequest request = StepInterruptionRepositoryTest.pauseRequest(
                harness, "pause-replay-non-target");
        StepInterruptionRepositoryTest.requireApplied(
                harness.interruptions().pause(request));

        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>(
                request.pausedCheckpoint().stepStates());
        states.put(PersistenceFixtures.STEP_2, StepExecutionState.PAUSED);
        Checkpoint altered = new Checkpoint(
                request.pausedCheckpoint().taskFrameId(),
                request.pausedCheckpoint().planId(),
                request.pausedCheckpoint().revisionId(),
                request.pausedCheckpoint().revisionNumber(),
                request.pausedCheckpoint().lastEventSequence(),
                request.pausedCheckpoint().planState(),
                states,
                request.pausedCheckpoint().receiptReferences(),
                request.pausedCheckpoint().createdAt());
        replacePauseMarker(harness, request, altered);

        assertPartialWithoutClock(harness, request);
    }

    @Test
    void replayRejectsShapeValidMarkerThatAppendsAReceiptWithoutClockAccess() {
        StepInterruptionRepositoryTest.Harness harness =
                StepInterruptionRepositoryTest.active("replay-receipt");
        StepPauseRequest request = StepInterruptionRepositoryTest.pauseRequest(
                harness, "pause-replay-receipt");
        StepInterruptionRepositoryTest.requireApplied(
                harness.interruptions().pause(request));

        var receipt = PersistenceFixtures.receipt(
                "receipt-replay-receipt", "tool-replay-receipt");
        harness.state().receipts.put(receipt.id(), receipt);
        Checkpoint altered = new Checkpoint(
                request.pausedCheckpoint().taskFrameId(),
                request.pausedCheckpoint().planId(),
                request.pausedCheckpoint().revisionId(),
                request.pausedCheckpoint().revisionNumber(),
                request.pausedCheckpoint().lastEventSequence(),
                request.pausedCheckpoint().planState(),
                request.pausedCheckpoint().stepStates(),
                List.of(receipt.id()),
                request.pausedCheckpoint().createdAt());
        replacePauseMarker(harness, request, altered);

        assertPartialWithoutClock(harness, request);
    }

    @Test
    void publicRequestsRejectMalformedComponentsBeforeAnyClockObservation() {
        StepInterruptionRepositoryTest.Harness harness =
                StepInterruptionRepositoryTest.active("constructors");
        StepPauseRequest valid = StepInterruptionRepositoryTest.pauseRequest(
                harness, "pause-constructors");
        int before = harness.clock().observationCount();

        assertThrows(NullPointerException.class, () -> new StepPauseRequest(
                null,
                valid.leaseToken(),
                valid.fencingToken(),
                valid.expectedRevisionId(),
                valid.expectedRevisionNumber(),
                valid.expectedCheckpointVersion(),
                valid.expectedEventHeadSequence(),
                valid.stepId(),
                valid.pauseEvent(),
                valid.pausedCheckpoint()));
        assertThrows(IllegalArgumentException.class, () -> new StepFailRequest(
                valid.planId(),
                " ",
                valid.fencingToken(),
                valid.expectedRevisionId(),
                valid.expectedRevisionNumber(),
                valid.expectedCheckpointVersion(),
                valid.expectedEventHeadSequence(),
                valid.stepId(),
                valid.pauseEvent(),
                valid.pausedCheckpoint()));
        assertThrows(IllegalArgumentException.class, () -> new StepCancelRequest(
                valid.planId(),
                valid.leaseToken(),
                valid.fencingToken(),
                valid.expectedRevisionId(),
                valid.expectedRevisionNumber(),
                2,
                valid.expectedEventHeadSequence(),
                valid.stepId(),
                valid.pauseEvent(),
                valid.pausedCheckpoint()));

        assertEquals(before, harness.clock().observationCount());
        assertFalse(valid.toString().contains(valid.leaseToken()));
        StepFailRequest failure = new StepFailRequest(
                valid.planId(),
                valid.leaseToken(),
                valid.fencingToken(),
                valid.expectedRevisionId(),
                valid.expectedRevisionNumber(),
                valid.expectedCheckpointVersion(),
                valid.expectedEventHeadSequence(),
                valid.stepId(),
                valid.pauseEvent(),
                valid.pausedCheckpoint());
        StepCancelRequest cancellation = new StepCancelRequest(
                valid.planId(),
                valid.leaseToken(),
                valid.fencingToken(),
                valid.expectedRevisionId(),
                valid.expectedRevisionNumber(),
                valid.expectedCheckpointVersion(),
                valid.expectedEventHeadSequence(),
                valid.stepId(),
                valid.pauseEvent(),
                valid.pausedCheckpoint());
        assertFalse(failure.toString().contains(valid.leaseToken()));
        assertFalse(cancellation.toString().contains(valid.leaseToken()));

        PersistedStepInterruption result = StepInterruptionRepositoryTest.requireApplied(
                harness.interruptions().pause(valid));
        assertFalse(result.toString().contains(valid.leaseToken()));
        assertFalse(result.toString().contains("worker-a"));
        assertFalse(result.toString().contains(valid.pauseEvent().id().value()));
        assertFalse(result.toString().contains("PAUSED"));
    }

    private record StateSnapshot(
            Map<?, ?> plans,
            Map<?, ?> events,
            Map<?, ?> streams,
            Map<?, ?> checkpoints,
            Map<?, ?> heads,
            Map<?, ?> links,
            Map<?, ?> pauses,
            Map<?, ?> failures,
            Map<?, ?> cancellations,
            Map<?, ?> receipts,
            Map<?, ?> effectIntents,
            Map<?, ?> effectProgresses,
            Map<?, ?> effectResults,
            Map<?, ?> leases,
            Map<?, ?> idempotency) {

        static StateSnapshot capture(InMemoryState state) {
            return new StateSnapshot(
                    Map.copyOf(state.plans),
                    Map.copyOf(state.eventsById),
                    Map.copyOf(state.eventStreams),
                    Map.copyOf(state.checkpoints),
                    Map.copyOf(state.executionMutationHeads),
                    Map.copyOf(state.executionMutationLinks),
                    Map.copyOf(state.stepPauses),
                    Map.copyOf(state.stepFailures),
                    Map.copyOf(state.stepCancellations),
                    Map.copyOf(state.receipts),
                    Map.copyOf(state.effectIntents),
                    Map.copyOf(state.effectProgresses),
                    Map.copyOf(state.effectResults),
                    Map.copyOf(state.leases),
                    Map.copyOf(state.idempotency));
        }
    }

    private static void assertPartialWithoutClock(
            StepInterruptionRepositoryTest.Harness harness,
            StepPauseRequest request) {
        int before = harness.clock().observationCount();
        harness.clock().failOnObservation();

        StepInterruptionRepositoryTest.assertFailure(
                harness.interruptions().pause(request),
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                "stepInterruption");
        assertEquals(before, harness.clock().observationCount());
    }

    private static void replacePauseMarker(
            StepInterruptionRepositoryTest.Harness harness,
            StepPauseRequest original,
            Checkpoint alteredCheckpoint) {
        InMemoryState.StepPauseMarker marker = harness.state().stepPauses
                .get(original.planId())
                .get(original.pauseEvent().id());
        StepPauseRequest alteredRequest = new StepPauseRequest(
                original.planId(),
                original.leaseToken(),
                original.fencingToken(),
                original.expectedRevisionId(),
                original.expectedRevisionNumber(),
                original.expectedCheckpointVersion(),
                original.expectedEventHeadSequence(),
                original.stepId(),
                original.pauseEvent(),
                alteredCheckpoint);
        VersionedCheckpoint alteredVersioned = new VersionedCheckpoint(
                marker.result().interruptedCheckpoint().version(),
                alteredCheckpoint);
        PersistedStepInterruption alteredResult = new PersistedStepInterruption(
                marker.result().planId(),
                marker.result().stepId(),
                marker.result().kind(),
                marker.result().leaseOwnerId(),
                marker.result().fencingToken(),
                marker.result().interruptionEvent(),
                alteredVersioned);
        harness.state().stepPauses.get(original.planId()).put(
                original.pauseEvent().id(),
                new InMemoryState.StepPauseMarker(
                        alteredRequest, alteredResult, marker.provenanceLink()));
        harness.state().checkpoints.put(original.planId(), alteredVersioned);
    }
}
