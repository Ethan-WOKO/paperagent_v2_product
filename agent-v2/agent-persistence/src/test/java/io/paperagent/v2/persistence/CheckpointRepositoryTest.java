package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointRepositoryTest {
    @Test
    void rejectsHistoricalRevisionAfterPlanAppendThenCreatesLatestCheckpointAtVersionOne() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        PlanRevision latest =
                PersistenceFixtures.revision2("revision-2", "continue with latest");
        PersistenceResult<?> appended = persistence.plans().appendRevision(
                PersistenceFixtures.PLAN_ID,
                1,
                latest);
        assertEquals(PersistenceOutcome.APPLIED, appended.outcome());

        Checkpoint historical =
                PersistenceFixtures.checkpoint(0, PersistenceFixtures.T0, List.of());
        assertFailure(
                persistence.checkpoints().save(0, historical),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "checkpoint");
        assertFailure(
                persistence.checkpoints().find(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.NOT_FOUND,
                "planId");

        Checkpoint current = checkpoint(
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                latest.id(),
                latest.number(),
                0,
                PersistenceFixtures.T0.plusSeconds(10),
                List.of());
        PersistenceResult<VersionedCheckpoint> created =
                persistence.checkpoints().save(0, current);

        assertEquals(PersistenceOutcome.APPLIED, created.outcome());
        VersionedCheckpoint expected = new VersionedCheckpoint(1, current);
        assertEquals(expected, created.value().orElseThrow());
        assertEquals(
                expected,
                persistence.checkpoints()
                        .find(PersistenceFixtures.PLAN_ID)
                        .value()
                        .orElseThrow());
    }

    @Test
    void initialSaveAndCasUpdateProduceMonotonicVersions() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        Checkpoint first = PersistenceFixtures.checkpoint(0, PersistenceFixtures.T0, List.of());
        PersistenceResult<VersionedCheckpoint> created =
                persistence.checkpoints().save(0, first);

        assertEquals(PersistenceOutcome.APPLIED, created.outcome());
        assertEquals(1, created.value().orElseThrow().version());
        assertFailure(
                persistence.checkpoints().save(0, first),
                PersistenceErrorCode.STALE_VERSION);
        VersionedCheckpoint unchanged =
                persistence.checkpoints().find(PersistenceFixtures.PLAN_ID).value().orElseThrow();
        assertEquals(1, unchanged.version());
        assertEquals(first, unchanged.checkpoint());

        Checkpoint second =
                PersistenceFixtures.checkpoint(1, PersistenceFixtures.T0.plusSeconds(1), List.of());
        persistence.events().append(PersistenceFixtures.event("event-1", 1));
        PersistenceResult<VersionedCheckpoint> updated =
                persistence.checkpoints().save(1, second);
        assertEquals(PersistenceOutcome.APPLIED, updated.outcome());
        assertEquals(2, updated.value().orElseThrow().version());
        assertEquals(second, persistence.checkpoints()
                .find(PersistenceFixtures.PLAN_ID).value().orElseThrow().checkpoint());
    }

    @Test
    void staleCasIsRejectedWithoutChangingStoredCheckpoint() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        Checkpoint first = PersistenceFixtures.checkpoint(0, PersistenceFixtures.T0, List.of());
        persistence.checkpoints().save(0, first);
        Checkpoint stale =
                PersistenceFixtures.checkpoint(2, PersistenceFixtures.T0.plusSeconds(2), List.of());

        assertFailure(
                persistence.checkpoints().save(0, stale),
                PersistenceErrorCode.STALE_VERSION);
        assertEquals(first, persistence.checkpoints()
                .find(PersistenceFixtures.PLAN_ID).value().orElseThrow().checkpoint());
    }

    @Test
    void checkpointMustReferenceStoredTaskPlanAndExactRevision() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        Checkpoint unknownPlan = checkpoint(
                PersistenceFixtures.TASK_ID,
                new PlanId("unknown-plan"),
                new PlanRevisionId("revision-1"),
                1,
                0,
                PersistenceFixtures.T0,
                List.of());
        assertFailure(
                persistence.checkpoints().save(0, unknownPlan),
                PersistenceErrorCode.NOT_FOUND);

        Checkpoint unknownRevision = checkpoint(
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                new PlanRevisionId("revision-other"),
                1,
                0,
                PersistenceFixtures.T0,
                List.of());
        assertFailure(
                persistence.checkpoints().save(0, unknownRevision),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED);

        TaskFrameId otherTaskId = new TaskFrameId("task-other");
        persistence.taskFrames().create(
                PersistenceFixtures.taskFrame(otherTaskId, "Another task"));
        Checkpoint wrongTask = checkpoint(
                otherTaskId,
                PersistenceFixtures.PLAN_ID,
                new PlanRevisionId("revision-1"),
                1,
                0,
                PersistenceFixtures.T0,
                List.of());
        assertFailure(
                persistence.checkpoints().save(0, wrongTask),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED);
    }

    @Test
    void invalidCheckpointHistoryCannotRegressEventsOrRemoveReceipts() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        persistence.events().append(PersistenceFixtures.event("event-1", 1));
        persistence.events().append(PersistenceFixtures.event("event-2", 2));
        persistence.events().append(PersistenceFixtures.event("event-3", 3));
        ReceiptId receiptId = new ReceiptId("receipt-1");
        Checkpoint first =
                PersistenceFixtures.checkpoint(2, PersistenceFixtures.T0, List.of(receiptId));
        persistence.checkpoints().save(0, first);

        Checkpoint eventRegression =
                PersistenceFixtures.checkpoint(1, PersistenceFixtures.T0.plusSeconds(1), List.of(receiptId));
        assertFailure(
                persistence.checkpoints().save(1, eventRegression),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED);

        Checkpoint receiptRemoval =
                PersistenceFixtures.checkpoint(3, PersistenceFixtures.T0.plusSeconds(1), List.of());
        assertFailure(
                persistence.checkpoints().save(1, receiptRemoval),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED);
    }

    @Test
    void exactlyOneConcurrentCasWriterWins() throws Exception {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        persistence.events().append(PersistenceFixtures.event("event-1", 1));
        persistence.events().append(PersistenceFixtures.event("event-2", 2));
        persistence.checkpoints().save(
                0, PersistenceFixtures.checkpoint(0, PersistenceFixtures.T0, List.of()));
        Checkpoint candidateA =
                PersistenceFixtures.checkpoint(1, PersistenceFixtures.T0.plusSeconds(1), List.of());
        Checkpoint candidateB =
                PersistenceFixtures.checkpoint(2, PersistenceFixtures.T0.plusSeconds(2), List.of());
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<VersionedCheckpoint>> first =
                    executor.submit(() -> saveAfterBarrier(persistence, barrier, candidateA));
            Future<PersistenceResult<VersionedCheckpoint>> second =
                    executor.submit(() -> saveAfterBarrier(persistence, barrier, candidateB));

            List<PersistenceResult<VersionedCheckpoint>> results = List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    results.stream()
                            .filter(result -> result.outcome() == PersistenceOutcome.APPLIED)
                            .count());
            assertEquals(
                    1,
                    results.stream()
                            .filter(result -> result.failure()
                                    .map(failure -> failure.code() == PersistenceErrorCode.STALE_VERSION)
                                    .orElse(false))
                            .count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void checkpointCursorCanBeZeroOrRealAndCannotPointOutsideItsPlan() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        EventEnvelope first = PersistenceFixtures.event("event-link-1", 1);
        EventEnvelope second = PersistenceFixtures.event("event-link-2", 2);
        EventEnvelope fourth = PersistenceFixtures.event("event-link-4", 4);
        persistence.events().append(first);
        persistence.events().append(second);
        persistence.events().append(fourth);

        Checkpoint zero =
                PersistenceFixtures.checkpoint(0, PersistenceFixtures.T0, List.of());
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.checkpoints().save(0, zero).outcome());
        Checkpoint lagging = PersistenceFixtures.checkpoint(
                2,
                PersistenceFixtures.T0.plusSeconds(2),
                List.of());
        PersistenceResult<VersionedCheckpoint> laggingSave =
                persistence.checkpoints().save(1, lagging);
        assertEquals(PersistenceOutcome.APPLIED, laggingSave.outcome());
        assertEquals(2, laggingSave.value().orElseThrow().version());

        PlanId otherPlanId = new PlanId("plan-cursor-other");
        persistence.plans().create(new Plan(
                otherPlanId,
                PersistenceFixtures.TASK_ID,
                List.of(PersistenceFixtures.revision1())));
        persistence.events().append(eventForPlan(
                "event-other-plan-3",
                otherPlanId,
                3));

        VersionedCheckpoint unchanged = laggingSave.value().orElseThrow();
        assertUnlinkedWithoutWrite(
                persistence,
                PersistenceFixtures.checkpoint(
                        3,
                        PersistenceFixtures.T0.plusSeconds(3),
                        List.of()),
                unchanged);
        assertUnlinkedWithoutWrite(
                persistence,
                PersistenceFixtures.checkpoint(
                        5,
                        PersistenceFixtures.T0.plusSeconds(5),
                        List.of()),
                unchanged);

        Checkpoint latest = PersistenceFixtures.checkpoint(
                4,
                PersistenceFixtures.T0.plusSeconds(4),
                List.of());
        PersistenceResult<VersionedCheckpoint> latestSave =
                persistence.checkpoints().save(2, latest);
        assertEquals(PersistenceOutcome.APPLIED, latestSave.outcome());
        assertEquals(3, latestSave.value().orElseThrow().version());
        assertEquals(latest, latestSave.value().orElseThrow().checkpoint());
    }

    @Test
    void staleCasWinsBeforeCanonicalAndCursorLinkValidation() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        Checkpoint current =
                PersistenceFixtures.checkpoint(0, PersistenceFixtures.T0, List.of());
        persistence.checkpoints().save(0, current);
        Checkpoint unlinked = PersistenceFixtures.checkpoint(
                99,
                PersistenceFixtures.T0.plusSeconds(1),
                List.of());

        assertFailure(
                persistence.checkpoints().save(0, unlinked),
                PersistenceErrorCode.STALE_VERSION,
                "expectedVersion");
        assertEquals(
                new VersionedCheckpoint(1, current),
                persistence.checkpoints()
                        .find(PersistenceFixtures.PLAN_ID)
                        .value().orElseThrow());
    }

    @Test
    void concurrentEventAppendAndCheckpointSaveIsSerializable()
            throws Exception {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        Checkpoint zero =
                PersistenceFixtures.checkpoint(0, PersistenceFixtures.T0, List.of());
        persistence.checkpoints().save(0, zero);
        EventEnvelope event = PersistenceFixtures.event("event-race-1", 1);
        Checkpoint linked = PersistenceFixtures.checkpoint(
                1,
                PersistenceFixtures.T0.plusSeconds(1),
                List.of());
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<EventEnvelope>> eventFuture =
                    executor.submit(() -> appendAfterBarrier(
                            persistence, barrier, event));
            Future<PersistenceResult<VersionedCheckpoint>> checkpointFuture =
                    executor.submit(() -> saveAfterBarrier(
                            persistence, barrier, linked));

            PersistenceResult<EventEnvelope> eventResult =
                    eventFuture.get(5, TimeUnit.SECONDS);
            PersistenceResult<VersionedCheckpoint> checkpointResult =
                    checkpointFuture.get(5, TimeUnit.SECONDS);

            assertEquals(PersistenceOutcome.APPLIED, eventResult.outcome());
            VersionedCheckpoint stored = persistence.checkpoints()
                    .find(PersistenceFixtures.PLAN_ID)
                    .value().orElseThrow();
            if (checkpointResult.outcome() == PersistenceOutcome.APPLIED) {
                assertEquals(new VersionedCheckpoint(2, linked), stored);
            } else {
                assertFailure(
                        checkpointResult,
                        PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                        "checkpoint.lastEventSequence");
                assertEquals(new VersionedCheckpoint(1, zero), stored);
            }
            assertEquals(
                    List.of(event),
                    persistence.events()
                            .readAfter(PersistenceFixtures.PLAN_ID, 0)
                            .value().orElseThrow());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nullAndInvalidCasInputsUseStableCodes() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        assertFailure(
                persistence.checkpoints().save(-1, PersistenceFixtures.checkpoint(
                        0, PersistenceFixtures.T0, List.of())),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.checkpoints().save(0, null),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.checkpoints().find(null),
                PersistenceErrorCode.INVALID_ARGUMENT);
    }

    private static PersistenceResult<VersionedCheckpoint> saveAfterBarrier(
            InMemoryPersistence persistence,
            CyclicBarrier barrier,
            Checkpoint checkpoint) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return persistence.checkpoints().save(1, checkpoint);
    }

    private static PersistenceResult<EventEnvelope> appendAfterBarrier(
            InMemoryPersistence persistence,
            CyclicBarrier barrier,
            EventEnvelope event) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return persistence.events().append(event);
    }

    private static EventEnvelope eventForPlan(
            String id,
            PlanId planId,
            long sequence) {
        EventEnvelope template = PersistenceFixtures.event(id, sequence);
        return new EventEnvelope(
                template.id(),
                template.taskFrameId(),
                planId,
                sequence,
                template.occurredAt(),
                template.type(),
                template.causationId(),
                template.correlationId(),
                template.payload());
    }

    private static void assertUnlinkedWithoutWrite(
            InMemoryPersistence persistence,
            Checkpoint candidate,
            VersionedCheckpoint expectedStored) {
        assertFailure(
                persistence.checkpoints().save(
                        expectedStored.version(),
                        candidate),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "checkpoint.lastEventSequence");
        assertEquals(
                expectedStored,
                persistence.checkpoints()
                        .find(PersistenceFixtures.PLAN_ID)
                        .value().orElseThrow());
    }

    private static Checkpoint checkpoint(
            TaskFrameId taskFrameId,
            PlanId planId,
            PlanRevisionId revisionId,
            long revisionNumber,
            long eventSequence,
            Instant createdAt,
            List<ReceiptId> receiptIds) {
        return new Checkpoint(
                taskFrameId,
                planId,
                revisionId,
                revisionNumber,
                eventSequence,
                PlanExecutionState.ACTIVE,
                Map.of(
                        PersistenceFixtures.STEP_1, StepExecutionState.ACTIVE,
                        PersistenceFixtures.STEP_2, StepExecutionState.NOT_STARTED),
                receiptIds,
                createdAt);
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode expectedCode) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
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
