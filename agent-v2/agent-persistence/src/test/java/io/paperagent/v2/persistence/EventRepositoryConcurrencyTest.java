package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventRepositoryConcurrencyTest {
    @Test
    void concurrentExactEventHasOneAppliedAndEveryOtherReplay() throws Exception {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        EventEnvelope event = event(
                "event-concurrent-exact",
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                1,
                "correlation-a");
        int participants = 8;
        CyclicBarrier barrier = new CyclicBarrier(participants);
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        try {
            List<Future<PersistenceResult<EventEnvelope>>> futures =
                    new ArrayList<>();
            for (int index = 0; index < participants; index++) {
                futures.add(executor.submit(
                        () -> appendAfterBarrier(
                                persistence, barrier, event)));
            }

            List<PersistenceResult<EventEnvelope>> results = collect(futures);

            assertEquals(1, countOutcome(results, PersistenceOutcome.APPLIED));
            assertEquals(
                    participants - 1,
                    countOutcome(results, PersistenceOutcome.REPLAYED));
            assertEquals(
                    List.of(event),
                    persistence.events()
                            .readAfter(PersistenceFixtures.PLAN_ID, 0)
                            .value().orElseThrow());
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void concurrentMixedCorrelationSequenceCollisionHasOneWinner()
            throws Exception {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        EventEnvelope first = event(
                "event-collision-a",
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                1,
                "correlation-a");
        EventEnvelope second = event(
                "event-collision-b",
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                1,
                "correlation-b");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<PersistenceResult<EventEnvelope>> results = collect(List.of(
                    executor.submit(
                            () -> appendAfterBarrier(
                                    persistence, barrier, first)),
                    executor.submit(
                            () -> appendAfterBarrier(
                                    persistence, barrier, second))));

            assertEquals(1, countOutcome(results, PersistenceOutcome.APPLIED));
            assertEquals(
                    1,
                    countFailure(
                            results,
                            PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC));
            assertEquals(
                    1,
                    persistence.events()
                            .readAfter(PersistenceFixtures.PLAN_ID, 0)
                            .value().orElseThrow().size());
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void differentPlansIndependentlyCommitSequenceOne() throws Exception {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        PlanId otherPlanId = new PlanId("plan-concurrent-independent");
        persistence.plans().create(new Plan(
                otherPlanId,
                PersistenceFixtures.TASK_ID,
                List.of(PersistenceFixtures.revision1())));
        EventEnvelope first = event(
                "event-plan-a",
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                1,
                "correlation-a");
        EventEnvelope second = event(
                "event-plan-b",
                PersistenceFixtures.TASK_ID,
                otherPlanId,
                1,
                "correlation-b");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<PersistenceResult<EventEnvelope>> results = collect(List.of(
                    executor.submit(
                            () -> appendAfterBarrier(
                                    persistence, barrier, first)),
                    executor.submit(
                            () -> appendAfterBarrier(
                                    persistence, barrier, second))));

            assertEquals(2, countOutcome(results, PersistenceOutcome.APPLIED));
            assertEquals(
                    List.of(first),
                    persistence.events()
                            .readAfter(PersistenceFixtures.PLAN_ID, 0)
                            .value().orElseThrow());
            assertEquals(
                    List.of(second),
                    persistence.events()
                            .readAfter(otherPlanId, 0)
                            .value().orElseThrow());
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void racingDifferentSequencesAreNeverReordered() throws Exception {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        EventEnvelope lower = event(
                "event-lower",
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                2,
                "correlation-a");
        EventEnvelope higher = event(
                "event-higher",
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                4,
                "correlation-b");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<EventEnvelope>> lowerFuture =
                    executor.submit(
                            () -> appendAfterBarrier(
                                    persistence, barrier, lower));
            Future<PersistenceResult<EventEnvelope>> higherFuture =
                    executor.submit(
                            () -> appendAfterBarrier(
                                    persistence, barrier, higher));
            PersistenceResult<EventEnvelope> lowerResult =
                    lowerFuture.get(5, TimeUnit.SECONDS);
            PersistenceResult<EventEnvelope> higherResult =
                    higherFuture.get(5, TimeUnit.SECONDS);

            assertEquals(PersistenceOutcome.APPLIED, higherResult.outcome());
            assertTrue(
                    lowerResult.outcome() == PersistenceOutcome.APPLIED
                            || lowerResult.failure()
                                    .map(failure -> failure.code()
                                            == PersistenceErrorCode
                                                    .EVENT_SEQUENCE_NOT_MONOTONIC)
                                    .orElse(false));
            List<EventEnvelope> stored = persistence.events()
                    .readAfter(PersistenceFixtures.PLAN_ID, 0)
                    .value().orElseThrow();
            assertEquals(
                    lowerResult.outcome() == PersistenceOutcome.APPLIED
                            ? List.of(lower, higher)
                            : List.of(higher),
                    stored);
        } finally {
            shutdown(executor);
        }
    }

    private static PersistenceResult<EventEnvelope> appendAfterBarrier(
            InMemoryPersistence persistence,
            CyclicBarrier barrier,
            EventEnvelope event) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return persistence.events().append(event);
    }

    private static List<PersistenceResult<EventEnvelope>> collect(
            List<Future<PersistenceResult<EventEnvelope>>> futures)
            throws Exception {
        List<PersistenceResult<EventEnvelope>> results = new ArrayList<>();
        for (Future<PersistenceResult<EventEnvelope>> future : futures) {
            results.add(future.get(5, TimeUnit.SECONDS));
        }
        return List.copyOf(results);
    }

    private static long countOutcome(
            List<PersistenceResult<EventEnvelope>> results,
            PersistenceOutcome outcome) {
        return results.stream()
                .filter(result -> result.outcome() == outcome)
                .count();
    }

    private static long countFailure(
            List<PersistenceResult<EventEnvelope>> results,
            PersistenceErrorCode code) {
        return results.stream()
                .filter(result -> result.failure()
                        .map(failure -> failure.code() == code)
                        .orElse(false))
                .count();
    }

    private static void shutdown(ExecutorService executor)
            throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
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
}
