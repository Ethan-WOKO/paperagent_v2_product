package io.paperagent.v2.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyRepositoryTest {
    private static final IdempotencyKey KEY =
            new IdempotencyKey("plan-execution", "request-1");

    @Test
    void startRetryCompleteAndCompletedReplayReturnSameRecord() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        PersistenceResult<IdempotencyRecord> started =
                persistence.idempotency().start(KEY, "fingerprint-a");
        assertEquals(PersistenceOutcome.APPLIED, started.outcome());
        assertEquals(IdempotencyState.IN_PROGRESS, started.value().orElseThrow().state());

        PersistenceResult<IdempotencyRecord> retry =
                persistence.idempotency().start(KEY, "fingerprint-a");
        assertEquals(PersistenceOutcome.REPLAYED, retry.outcome());
        assertEquals(started.value(), retry.value());

        PersistenceResult<IdempotencyRecord> completed =
                persistence.idempotency().complete(
                        KEY, "fingerprint-a", Optional.of("receipt-1"));
        assertEquals(PersistenceOutcome.APPLIED, completed.outcome());
        assertEquals(IdempotencyState.COMPLETED, completed.value().orElseThrow().state());
        assertEquals("receipt-1", completed.value().orElseThrow()
                .resultReference().orElseThrow());

        assertEquals(
                PersistenceOutcome.REPLAYED,
                persistence.idempotency()
                        .complete(KEY, "fingerprint-a", Optional.of("receipt-1"))
                        .outcome());
        PersistenceResult<IdempotencyRecord> completedStartReplay =
                persistence.idempotency().start(KEY, "fingerprint-a");
        assertEquals(PersistenceOutcome.REPLAYED, completedStartReplay.outcome());
        assertEquals(IdempotencyState.COMPLETED,
                completedStartReplay.value().orElseThrow().state());
    }

    @Test
    void fingerprintConflictAndIllegalCompletionAreStableFailures() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        persistence.idempotency().start(KEY, "fingerprint-a");
        assertFailure(
                persistence.idempotency().start(KEY, "fingerprint-other"),
                PersistenceErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT);
        assertFailure(
                persistence.idempotency().complete(
                        KEY, "fingerprint-other", Optional.of("receipt-1")),
                PersistenceErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT);

        persistence.idempotency().complete(
                KEY, "fingerprint-a", Optional.of("receipt-1"));
        assertFailure(
                persistence.idempotency().complete(
                        KEY, "fingerprint-a", Optional.of("receipt-other")),
                PersistenceErrorCode.IDEMPOTENCY_ILLEGAL_TRANSITION);
        assertFailure(
                persistence.idempotency().complete(
                        new IdempotencyKey("plan-execution", "missing"),
                        "fingerprint-a",
                        Optional.of("receipt-1")),
                PersistenceErrorCode.IDEMPOTENCY_ILLEGAL_TRANSITION);
    }

    @Test
    void completionMayOmitResultReferenceAndStillReplaysExactly() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        persistence.idempotency().start(KEY, "fingerprint-a");

        PersistenceResult<IdempotencyRecord> completed =
                persistence.idempotency().complete(KEY, "fingerprint-a", Optional.empty());
        assertEquals(PersistenceOutcome.APPLIED, completed.outcome());
        assertTrue(completed.value().orElseThrow().resultReference().isEmpty());
        assertEquals(
                PersistenceOutcome.REPLAYED,
                persistence.idempotency()
                        .complete(KEY, "fingerprint-a", Optional.empty())
                        .outcome());
    }

    @Test
    void exactlyOneConcurrentStarterAcquires() throws Exception {
        InMemoryPersistence persistence = new InMemoryPersistence();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<IdempotencyRecord>> first =
                    executor.submit(() -> startAfterBarrier(persistence, barrier));
            Future<PersistenceResult<IdempotencyRecord>> second =
                    executor.submit(() -> startAfterBarrier(persistence, barrier));
            List<PersistenceResult<IdempotencyRecord>> results = List.of(
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
                            .filter(result -> result.outcome() == PersistenceOutcome.REPLAYED)
                            .count());
            assertEquals(results.get(0).value(), results.get(1).value());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidInputsAndMissingLookupUseStableCodes() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        assertFailure(
                persistence.idempotency().start(null, "fingerprint"),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.idempotency().start(
                        new IdempotencyKey("bad scope", "key"), "fingerprint"),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.idempotency().start(KEY, " "),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.idempotency().complete(KEY, "fingerprint", null),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.idempotency().find(KEY),
                PersistenceErrorCode.NOT_FOUND);
    }

    private static PersistenceResult<IdempotencyRecord> startAfterBarrier(
            InMemoryPersistence persistence,
            CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return persistence.idempotency().start(KEY, "fingerprint-a");
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode expectedCode) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(expectedCode, result.failure().orElseThrow().code());
    }
}
