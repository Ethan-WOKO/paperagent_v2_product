package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.ToolCallId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActiveStepReplanRepositoryBoundaryTest {

    @Test
    void publicValuesValidateAllComponentsAndRedactPayloads() {
        ActiveStepReplanTestSupport.Harness harness =
                ActiveStepReplanTestSupport.active("opaque");
        ActiveStepReplanRequest request =
                ActiveStepReplanTestSupport.request(harness, "opaque");
        PersistedActiveStepReplan persisted = new PersistedActiveStepReplan(
                request.planId(), request.activeStepId(), "opaque-owner", 1,
                request.supersessionEvent(),
                new VersionedCheckpoint(4, request.supersededCheckpoint()),
                request.replanEvent(), request.replannedRevision(),
                new VersionedCheckpoint(5, request.replannedCheckpoint()));

        for (String sentinel : List.of(
                "active-replan-token", "opaque-owner", "active-replan-supersession-opaque",
                "active-replan-revision-opaque", "active replan opaque")) {
            assertFalse(request.toString().contains(sentinel), request.toString());
            assertFalse(persisted.toString().contains(sentinel), persisted.toString());
        }
        assertThrows(NullPointerException.class, () -> new ActiveStepReplanRequest(
                null, request.leaseToken(), request.fencingToken(),
                request.expectedRevisionId(), request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(), request.expectedEventHeadSequence(),
                request.activeStepId(), request.supersessionEvent(),
                request.supersededCheckpoint(), request.replanEvent(),
                request.replannedRevision(), request.replannedCheckpoint()));
        assertThrows(IllegalArgumentException.class, () -> new ActiveStepReplanRequest(
                request.planId(), " ", request.fencingToken(),
                request.expectedRevisionId(), request.expectedRevisionNumber(), 2,
                request.expectedEventHeadSequence(), request.activeStepId(),
                request.supersessionEvent(), request.supersededCheckpoint(),
                request.replanEvent(), request.replannedRevision(), request.replannedCheckpoint()));
        assertThrows(IllegalArgumentException.class, () -> new PersistedActiveStepReplan(
                request.planId(), request.activeStepId(), "owner", 1,
                request.supersessionEvent(),
                new VersionedCheckpoint(3, request.supersededCheckpoint()),
                request.replanEvent(), request.replannedRevision(),
                new VersionedCheckpoint(4, request.replannedCheckpoint())));

        TaskFrameId foreignTaskFrame = new TaskFrameId("foreign-task-frame");
        Checkpoint crossTaskSuperseded = new Checkpoint(
                foreignTaskFrame, request.supersededCheckpoint().planId(),
                request.supersededCheckpoint().revisionId(),
                request.supersededCheckpoint().revisionNumber(),
                request.supersededCheckpoint().lastEventSequence(),
                request.supersededCheckpoint().planState(),
                request.supersededCheckpoint().stepStates(),
                request.supersededCheckpoint().receiptReferences(),
                request.supersededCheckpoint().createdAt());
        Checkpoint crossTaskReplanned = new Checkpoint(
                foreignTaskFrame, request.replannedCheckpoint().planId(),
                request.replannedCheckpoint().revisionId(),
                request.replannedCheckpoint().revisionNumber(),
                request.replannedCheckpoint().lastEventSequence(),
                request.replannedCheckpoint().planState(),
                request.replannedCheckpoint().stepStates(),
                request.replannedCheckpoint().receiptReferences(),
                request.replannedCheckpoint().createdAt());
        assertThrows(IllegalArgumentException.class, () -> new PersistedActiveStepReplan(
                request.planId(), request.activeStepId(), "owner", 1,
                request.supersessionEvent(),
                new VersionedCheckpoint(4, crossTaskSuperseded),
                request.replanEvent(), request.replannedRevision(),
                new VersionedCheckpoint(5, crossTaskReplanned)));
    }

    @Test
    void invalidInputAndTornCompositeProvenanceRejectBeforeClock() {
        PersistenceFixtures.MutableCountingClock emptyClock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryPersistence empty = new InMemoryPersistence(emptyClock);
        emptyClock.failOnObservation();
        ActiveStepReplanTestSupport.assertFailure(empty.activeStepReplans().supersedeAndReplan(null),
                PersistenceErrorCode.INVALID_ARGUMENT, "request");

        ActiveStepReplanTestSupport.Harness harness =
                ActiveStepReplanTestSupport.active("torn");
        ActiveStepReplanRequest request =
                ActiveStepReplanTestSupport.request(harness, "torn");
        ActiveStepReplanTestSupport.requireApplied(
                harness.activeReplans().supersedeAndReplan(request));
        harness.state().executionMutationLinks.get(harness.plan().id()).remove(1);
        assertFalse(InMemoryExecutionMutationAuthority.validateAuthoritativeSource(
                harness.state(), harness.plan().id()) != null);
        harness.clock().failOnObservation();

        ActiveStepReplanTestSupport.assertFailure(
                harness.activeReplans().supersedeAndReplan(request),
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_PARTIAL_STATE, "activeStepReplan");
    }

    @Test
    void staleFenceInvalidEventAndGlobalIdentityCollisionMakeNoBusinessWrites() {
        ActiveStepReplanTestSupport.Harness fence =
                ActiveStepReplanTestSupport.active("fence");
        ActiveStepReplanRequest valid = ActiveStepReplanTestSupport.request(fence, "fence");
        ActiveStepReplanRequest staleFence = new ActiveStepReplanRequest(
                valid.planId(), valid.leaseToken(), 2, valid.expectedRevisionId(),
                valid.expectedRevisionNumber(), valid.expectedCheckpointVersion(),
                valid.expectedEventHeadSequence(), valid.activeStepId(),
                valid.supersessionEvent(), valid.supersededCheckpoint(), valid.replanEvent(),
                valid.replannedRevision(), valid.replannedCheckpoint());
        ActiveStepReplanTestSupport.Snapshot fenceBefore =
                ActiveStepReplanTestSupport.snapshot(fence.state());
        ActiveStepReplanTestSupport.assertFailure(
                fence.activeReplans().supersedeAndReplan(staleFence),
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID, "request.fencingToken");
        assertEquals(fenceBefore, ActiveStepReplanTestSupport.snapshot(fence.state()));

        ActiveStepReplanTestSupport.Harness invalid =
                ActiveStepReplanTestSupport.active("event");
        ActiveStepReplanRequest eventRequest =
                ActiveStepReplanTestSupport.request(invalid, "event");
        EventEnvelope nonMonotonic = PersistenceFixtures.event(
                "active-replan-bad-order", invalid.plan().taskFrameId(),
                invalid.plan().id(), 2);
        ActiveStepReplanRequest badOrder = new ActiveStepReplanRequest(
                eventRequest.planId(), eventRequest.leaseToken(), eventRequest.fencingToken(),
                eventRequest.expectedRevisionId(), eventRequest.expectedRevisionNumber(),
                eventRequest.expectedCheckpointVersion(), eventRequest.expectedEventHeadSequence(),
                eventRequest.activeStepId(), nonMonotonic,
                eventRequest.supersededCheckpoint(), eventRequest.replanEvent(),
                eventRequest.replannedRevision(), eventRequest.replannedCheckpoint());
        ActiveStepReplanTestSupport.assertFailure(
                invalid.activeReplans().supersedeAndReplan(badOrder),
                PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                "request.supersessionEvent.sequence");

        ActiveStepReplanTestSupport.Harness collision =
                ActiveStepReplanTestSupport.active("collision");
        ActiveStepReplanRequest collisionRequest =
                ActiveStepReplanTestSupport.request(collision, "collision");
        collision.state().eventsById.put(collisionRequest.replanEvent().id(),
                PersistenceFixtures.event("active-replan-event-collision",
                        collision.plan().taskFrameId(), new PlanId("another-plan"), 4));
        ActiveStepReplanTestSupport.assertFailure(
                collision.activeReplans().supersedeAndReplan(collisionRequest),
                PersistenceErrorCode.CONFLICTING_REPLAY, "request.replanEvent.id");
    }

    @Test
    void eventTaskFrameAndPlanMustMatchTheAuthoritativeSource() {
        ActiveStepReplanTestSupport.Harness harness =
                ActiveStepReplanTestSupport.active("identity");
        ActiveStepReplanRequest request =
                ActiveStepReplanTestSupport.request(harness, "identity");
        EventEnvelope wrongPlan = PersistenceFixtures.event(
                "active-replan-wrong-plan", harness.plan().taskFrameId(),
                new PlanId("wrong-plan"), 3);
        ActiveStepReplanRequest malformed = new ActiveStepReplanRequest(
                request.planId(), request.leaseToken(), request.fencingToken(),
                request.expectedRevisionId(), request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(), request.expectedEventHeadSequence(),
                request.activeStepId(), wrongPlan, request.supersededCheckpoint(),
                request.replanEvent(), request.replannedRevision(), request.replannedCheckpoint());

        ActiveStepReplanTestSupport.assertFailure(
                harness.activeReplans().supersedeAndReplan(malformed),
                PersistenceErrorCode.INVALID_ARGUMENT, "request.supersessionEvent.planId");
    }

    @Test
    void corruptDetachedDuplicateDanglingAndCrossIntentFinalProvenanceFailClosed() {
        ActiveStepReplanTestSupport.Harness corrupt =
                ActiveStepReplanTestSupport.active("corrupt-final");
        EffectIntent corruptIntent = ActiveStepReplanTestSupport.persistSelectedIntent(
                corrupt, "corrupt-final");
        ActiveStepReplanTestSupport.recordFinalReceipt(
                corrupt, corruptIntent, "corrupt-final", ReceiptStatus.SUCCESS);
        corrupt.state().effectResults.put(corruptIntent.toolCallId(), null);
        assertFinalProvenanceFailure(corrupt, "corrupt-final");

        ActiveStepReplanTestSupport.Harness detached =
                ActiveStepReplanTestSupport.active("detached-final");
        EffectIntent detachedIntent = ActiveStepReplanTestSupport.persistSelectedIntent(
                detached, "detached-final");
        ExecutionReceipt detachedReceipt = ActiveStepReplanTestSupport.recordFinalReceipt(
                detached, detachedIntent, "detached-final",
                ReceiptStatus.SUCCESS);
        detached.state().receipts.remove(detachedReceipt.id());
        assertFinalProvenanceFailure(detached, "detached-final");

        ActiveStepReplanTestSupport.Harness duplicate =
                ActiveStepReplanTestSupport.active("duplicate-final");
        EffectIntent duplicateIntent = ActiveStepReplanTestSupport.persistSelectedIntent(
                duplicate, "duplicate-final");
        ActiveStepReplanTestSupport.recordFinalReceipt(
                duplicate, duplicateIntent, "duplicate-final",
                ReceiptStatus.SUCCESS);
        ExecutionReceipt duplicateReceipt = ActiveStepReplanTestSupport.finalReceipt(
                "active-replan-receipt-duplicate-extra", duplicateIntent.toolCallId(),
                ReceiptStatus.SUCCESS);
        duplicate.state().receipts.put(duplicateReceipt.id(), duplicateReceipt);
        assertFinalProvenanceFailure(duplicate, "duplicate-final");

        ActiveStepReplanTestSupport.Harness dangling =
                ActiveStepReplanTestSupport.active("dangling-final");
        EffectIntent danglingIntent = ActiveStepReplanTestSupport.persistSelectedIntent(
                dangling, "dangling-final");
        ActiveStepReplanTestSupport.recordFinalReceipt(
                dangling, danglingIntent, "dangling-final",
                ReceiptStatus.SUCCESS);
        dangling.state().effectResults.put(new ToolCallId("dangling-final-result"),
                dangling.state().effectResults.remove(danglingIntent.toolCallId()));
        assertFinalProvenanceFailure(dangling, "dangling-final");

        ActiveStepReplanTestSupport.Harness crossIntent =
                ActiveStepReplanTestSupport.active("cross-intent-final");
        EffectIntent left = ActiveStepReplanTestSupport.persistSelectedIntent(
                crossIntent, "cross-intent-left");
        EffectIntent right = ActiveStepReplanTestSupport.persistSelectedIntent(
                crossIntent, "cross-intent-right");
        ActiveStepReplanTestSupport.recordFinalReceipt(
                crossIntent, left, "cross-intent-left",
                ReceiptStatus.SUCCESS);
        ActiveStepReplanTestSupport.recordFinalReceipt(
                crossIntent, right, "cross-intent-right",
                ReceiptStatus.SUCCESS);
        crossIntent.state().effectResults.put(left.toolCallId(),
                crossIntent.state().effectResults.get(right.toolCallId()));
        assertFinalProvenanceFailure(crossIntent, "cross-intent-final");
    }

    private static void assertFinalProvenanceFailure(
            ActiveStepReplanTestSupport.Harness harness,
            String suffix) {
        ActiveStepReplanTestSupport.Snapshot before =
                ActiveStepReplanTestSupport.snapshot(harness.state());

        ActiveStepReplanTestSupport.assertFailure(
                harness.activeReplans().supersedeAndReplan(
                        ActiveStepReplanTestSupport.request(harness, suffix)),
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_PARTIAL_STATE,
                "activeStepReplan");

        assertEquals(before, ActiveStepReplanTestSupport.snapshot(harness.state()));
    }
}
