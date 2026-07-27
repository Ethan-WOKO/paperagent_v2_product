package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreRepositoryTest {
    @Test
    void taskFrameCreateIsCreateOnceAndExactReplayIsIdempotent() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame original = PersistenceFixtures.taskFrame();

        assertEquals(PersistenceOutcome.APPLIED, persistence.taskFrames().create(original).outcome());
        assertEquals(PersistenceOutcome.REPLAYED, persistence.taskFrames().create(original).outcome());

        TaskFrame conflict = PersistenceFixtures.taskFrame(
                PersistenceFixtures.TASK_ID, "A conflicting objective");
        assertFailure(
                persistence.taskFrames().create(conflict),
                PersistenceErrorCode.CONFLICTING_REPLAY);
        assertEquals(original, persistence.taskFrames().find(original.id()).value().orElseThrow());
    }

    @Test
    void planRequiresStoredTaskAndRevisionAppendUsesCurrentVersion() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        Plan plan = PersistenceFixtures.plan();
        assertFailure(persistence.plans().create(plan), PersistenceErrorCode.NOT_FOUND);
        persistence.taskFrames().create(PersistenceFixtures.taskFrame());
        assertEquals(PersistenceOutcome.APPLIED, persistence.plans().create(plan).outcome());
        assertEquals(PersistenceOutcome.REPLAYED, persistence.plans().create(plan).outcome());
        PlanRevision conflictingInitialRevision = new PlanRevision(
                new PlanRevisionId("revision-conflict"),
                PersistenceFixtures.TASK_ID,
                1,
                Optional.empty(),
                "conflicting initial plan",
                PersistenceFixtures.T0,
                PersistenceFixtures.revision1().steps(),
                Map.of());
        assertFailure(
                persistence.plans().create(new Plan(
                        PersistenceFixtures.PLAN_ID,
                        PersistenceFixtures.TASK_ID,
                        List.of(conflictingInitialRevision))),
                PersistenceErrorCode.CONFLICTING_REPLAY);

        PlanRevision revision2 = PersistenceFixtures.revision2("revision-2", "refined plan");
        PersistenceResult<Plan> appended =
                persistence.plans().appendRevision(plan.id(), 1, revision2);
        assertEquals(PersistenceOutcome.APPLIED, appended.outcome());
        assertEquals(2, appended.value().orElseThrow().latestRevision().number());
        assertEquals(
                PersistenceOutcome.REPLAYED,
                persistence.plans().appendRevision(plan.id(), 1, revision2).outcome());

        PlanRevision revision3Shape =
                PersistenceFixtures.revision2("revision-other", "stale attempt");
        assertFailure(
                persistence.plans().appendRevision(plan.id(), 1, revision3Shape),
                PersistenceErrorCode.STALE_VERSION);
    }

    @Test
    void conflictingRevisionIdentityIsRejected() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        PlanRevision accepted = PersistenceFixtures.revision2("revision-2", "accepted");
        persistence.plans().appendRevision(PersistenceFixtures.PLAN_ID, 1, accepted);

        PlanRevision conflict = PersistenceFixtures.revision2("revision-2", "different");
        assertFailure(
                persistence.plans().appendRevision(PersistenceFixtures.PLAN_ID, 2, conflict),
                PersistenceErrorCode.CONFLICTING_REPLAY);
    }

    @Test
    void invalidRevisionParentAndTaskBindingAreRejected() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        PlanRevision wrongParent = new PlanRevision(
                new PlanRevisionId("revision-2"),
                PersistenceFixtures.TASK_ID,
                2,
                Optional.of(new PlanRevisionId("not-the-parent")),
                "wrong parent",
                PersistenceFixtures.T0.plusSeconds(10),
                PersistenceFixtures.revision1().steps(),
                Map.of());
        assertFailure(
                persistence.plans().appendRevision(PersistenceFixtures.PLAN_ID, 1, wrongParent),
                PersistenceErrorCode.PLAN_VALIDATION_FAILED);

        TaskFrameId otherTaskId = new TaskFrameId("task-other");
        PlanRevision wrongTask = new PlanRevision(
                new PlanRevisionId("revision-other"),
                otherTaskId,
                2,
                Optional.of(new PlanRevisionId("revision-1")),
                "wrong task",
                PersistenceFixtures.T0.plusSeconds(10),
                PersistenceFixtures.revision1().steps(),
                Map.of());
        assertFailure(
                persistence.plans().appendRevision(PersistenceFixtures.PLAN_ID, 1, wrongTask),
                PersistenceErrorCode.TASK_FRAME_MISMATCH);
    }

    @Test
    void eventsArePlanGlobalOrderedGapAwareAndReplaySafe() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        EventEnvelope first = event(
                "event-1",
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                1,
                "correlation-a");
        EventEnvelope second = event(
                "event-2",
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                2,
                "correlation-b");
        EventEnvelope fourth = event(
                "event-4",
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                4,
                "correlation-b");

        assertEquals(PersistenceOutcome.APPLIED, persistence.events().append(first).outcome());
        assertEquals(PersistenceOutcome.APPLIED, persistence.events().append(second).outcome());
        assertEquals(PersistenceOutcome.APPLIED, persistence.events().append(fourth).outcome());
        assertEquals(PersistenceOutcome.REPLAYED, persistence.events().append(first).outcome());
        List<EventEnvelope> events = persistence.events()
                .readAfter(PersistenceFixtures.PLAN_ID, 0)
                .value().orElseThrow();
        assertEquals(List.of(first, second, fourth), events);
        assertEquals(
                List.of(second, fourth),
                persistence.events()
                        .readAfter(PersistenceFixtures.PLAN_ID, 1)
                        .value().orElseThrow());
        assertEquals(
                List.of(fourth),
                persistence.events()
                        .readAfter(PersistenceFixtures.PLAN_ID, 3)
                        .value().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> events.add(first));
        PersistenceResult<List<EventEnvelope>> atHighWaterResult =
                persistence.events().readAfter(
                        PersistenceFixtures.PLAN_ID,
                        4);
        List<EventEnvelope> atHighWater =
                atHighWaterResult.value().orElseThrow();
        PersistenceResult<List<EventEnvelope>> aboveHighWaterResult =
                persistence.events().readAfter(
                        PersistenceFixtures.PLAN_ID,
                        100);
        List<EventEnvelope> aboveHighWater =
                aboveHighWaterResult.value().orElseThrow();
        assertEquals(PersistenceOutcome.FOUND, atHighWaterResult.outcome());
        assertEquals(PersistenceOutcome.FOUND, aboveHighWaterResult.outcome());
        assertTrue(atHighWater.isEmpty());
        assertTrue(aboveHighWater.isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> atHighWater.add(first));
        assertThrows(
                UnsupportedOperationException.class,
                () -> aboveHighWater.add(first));

        assertFailure(
                persistence.events().append(event(
                        "event-3",
                        PersistenceFixtures.TASK_ID,
                        PersistenceFixtures.PLAN_ID,
                        3,
                        "correlation-a")),
                PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                "event.sequence");
    }

    @Test
    void eventCollisionReplayAuthorityAndIndependentPlansUseStablePriority() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        EventEnvelope first = event(
                "event-1",
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                1,
                "correlation-a");
        persistence.events().append(first);

        assertFailure(
                persistence.events().append(event(
                        "event-other",
                        PersistenceFixtures.TASK_ID,
                        PersistenceFixtures.PLAN_ID,
                        1,
                        "correlation-b")),
                PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                "event.sequence");
        persistence.events().append(event(
                "event-4",
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                4,
                "correlation-b"));
        assertEquals(
                PersistenceOutcome.REPLAYED,
                persistence.events().append(first).outcome());

        EventEnvelope conflictingId = event(
                "event-1",
                new TaskFrameId("task-unknown"),
                new PlanId("plan-unknown"),
                2,
                "correlation-conflict");
        assertFailure(
                persistence.events().append(conflictingId),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "event.id");

        assertFailure(
                persistence.events().append(event(
                        "event-unknown-plan",
                        PersistenceFixtures.TASK_ID,
                        new PlanId("plan-unknown"),
                        1,
                        "correlation-a")),
                PersistenceErrorCode.NOT_FOUND,
                "event.planId");
        assertFailure(
                persistence.events().append(event(
                        "event-wrong-task",
                        new TaskFrameId("task-wrong"),
                        PersistenceFixtures.PLAN_ID,
                        1,
                        "correlation-a")),
                PersistenceErrorCode.TASK_FRAME_MISMATCH,
                "event.taskFrameId");

        PlanId otherPlanId = new PlanId("plan-independent");
        Plan otherPlan = new Plan(
                otherPlanId,
                PersistenceFixtures.TASK_ID,
                List.of(PersistenceFixtures.revision1()));
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.plans().create(otherPlan).outcome());
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.events().append(event(
                        "event-independent-1",
                        PersistenceFixtures.TASK_ID,
                        otherPlanId,
                        1,
                        "correlation-independent")).outcome());
        assertEquals(first,
                persistence.events().find(new EventId("event-1"))
                        .value().orElseThrow());
    }

    @Test
    void receiptsAreAppendOnlyAndReplaySafe() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        ExecutionReceipt receipt = PersistenceFixtures.receipt("receipt-1", "call-1");

        assertEquals(PersistenceOutcome.APPLIED, persistence.receipts().append(receipt).outcome());
        assertEquals(PersistenceOutcome.REPLAYED, persistence.receipts().append(receipt).outcome());
        assertFailure(
                persistence.receipts().append(
                        PersistenceFixtures.receipt("receipt-1", "call-other")),
                PersistenceErrorCode.CONFLICTING_REPLAY);
        assertEquals(receipt, persistence.receipts().find(receipt.id()).value().orElseThrow());
    }

    @Test
    void returnedContractValuesAreDeeplyImmutable() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        TaskFrame taskFrame =
                persistence.taskFrames().find(PersistenceFixtures.TASK_ID).value().orElseThrow();
        Plan plan = persistence.plans().find(PersistenceFixtures.PLAN_ID).value().orElseThrow();

        assertThrows(UnsupportedOperationException.class, () -> taskFrame.targets().add("other"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.revisions().add(PersistenceFixtures.revision1()));
    }

    @Test
    void invalidAndMissingInputsUseStableCodes() {
        InMemoryPersistence persistence = new InMemoryPersistence();

        assertFailure(persistence.taskFrames().create(null), PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(persistence.taskFrames().find(null), PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.plans().find(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.NOT_FOUND);
        assertFailure(
                persistence.events().readAfter(null, -1),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "planId");
        assertFailure(
                persistence.events().readAfter(PersistenceFixtures.PLAN_ID, -1),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "exclusiveSequence");
        assertFailure(
                persistence.events().readAfter(new PlanId("plan-missing"), -1),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "exclusiveSequence");
        assertFailure(
                persistence.events().readAfter(new PlanId("plan-missing"), 0),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
        assertFailure(
                persistence.events().append(null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "event");
        assertFailure(persistence.receipts().find(null), PersistenceErrorCode.INVALID_ARGUMENT);
    }

    private static EventEnvelope event(
            String id,
            TaskFrameId taskFrameId,
            PlanId planId,
            long sequence,
            String correlationId) {
        EventEnvelope template = PersistenceFixtures.event(id, sequence);
        return new EventEnvelope(
                template.id(),
                taskFrameId,
                planId,
                sequence,
                template.occurredAt(),
                template.type(),
                template.causationId(),
                correlationId,
                template.payload());
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode expectedCode) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertTrue(result.value().isEmpty());
        assertEquals(expectedCode, result.failure().orElseThrow().code());
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode expectedCode,
            String expectedPath) {
        assertFailure(result, expectedCode);
        assertEquals(expectedPath, result.failure().orElseThrow().path());
    }
}
