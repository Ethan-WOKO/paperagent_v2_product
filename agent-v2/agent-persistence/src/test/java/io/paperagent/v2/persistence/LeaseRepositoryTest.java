package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseRepositoryTest {
    private static final Instant T0 = PersistenceFixtures.T0;
    private static final PlanId PLAN_2 = new PlanId("plan-2");

    @Test
    void trustedAcquisitionTimeAndLiveReplayArePreservedByRenewal() {
        MutableCountingClock clock = new MutableCountingClock(T0);
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence(clock);

        PersistenceResult<LeaseRecord> acquired = observeOnce(
                clock,
                () -> persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-a",
                        "lease-token-a",
                        T0.plusSeconds(10)));
        LeaseRecord original = acquired.value().orElseThrow();
        assertEquals(PersistenceOutcome.APPLIED, acquired.outcome());
        assertEquals(T0, original.acquiredAt());
        assertEquals(1, original.fencingToken());

        PersistenceResult<LeaseRecord> acquireReplay = observeOnce(
                clock,
                () -> persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-a",
                        "lease-token-a",
                        T0.plusSeconds(10)));
        assertEquals(PersistenceOutcome.REPLAYED, acquireReplay.outcome());
        assertEquals(original, acquireReplay.value().orElseThrow());

        clock.set(T0.plusSeconds(1));
        PersistenceResult<LeaseRecord> renewed = observeOnce(
                clock,
                () -> persistence.leases().renew(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a",
                        T0.plusSeconds(20)));
        LeaseRecord extended = renewed.value().orElseThrow();
        assertEquals(PersistenceOutcome.APPLIED, renewed.outcome());
        assertEquals(original.acquiredAt(), extended.acquiredAt());
        assertEquals(original.fencingToken(), extended.fencingToken());

        PersistenceResult<LeaseRecord> renewReplay = observeOnce(
                clock,
                () -> persistence.leases().renew(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a",
                        T0.plusSeconds(20)));
        assertEquals(PersistenceOutcome.REPLAYED, renewReplay.outcome());
        assertEquals(extended, renewReplay.value().orElseThrow());
    }

    @Test
    void everyBusinessOutcomeObservesClockExactlyOnce() {
        MutableCountingClock clock = new MutableCountingClock(T0);
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence(clock);

        assertEquals(
                PersistenceOutcome.APPLIED,
                observeOnce(
                        clock,
                        () -> persistence.leases().acquire(
                                PersistenceFixtures.PLAN_ID,
                                "worker-a",
                                "lease-token-a",
                                T0.plusSeconds(10)))
                        .outcome());
        assertEquals(
                PersistenceOutcome.REPLAYED,
                observeOnce(
                        clock,
                        () -> persistence.leases().acquire(
                                PersistenceFixtures.PLAN_ID,
                                "worker-a",
                                "lease-token-a",
                                T0.plusSeconds(10)))
                        .outcome());
        assertEquals(
                PersistenceOutcome.FOUND,
                observeOnce(
                        clock,
                        () -> persistence.leases().find(PersistenceFixtures.PLAN_ID))
                        .outcome());
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().acquire(
                                PersistenceFixtures.PLAN_ID,
                                "worker-b",
                                "lease-token-b",
                                T0.plusSeconds(20))),
                PersistenceErrorCode.LEASE_HELD,
                "planId");
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().renew(
                                PersistenceFixtures.PLAN_ID,
                                "wrong-token",
                                T0.plusSeconds(20))),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "leaseToken");

        clock.set(T0.plusSeconds(10));
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().find(PersistenceFixtures.PLAN_ID)),
                PersistenceErrorCode.LEASE_EXPIRED,
                "planId");
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().find(new PlanId("missing-plan"))),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
    }

    @Test
    void businessFailuresRetainFrozenPriorityAndObserveClockOnce() {
        MutableCountingClock clock = new MutableCountingClock(T0);
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence(clock);

        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().acquire(
                                new PlanId("missing-plan"),
                                "worker-a",
                                "lease-token-a",
                                T0.plusSeconds(10))),
                PersistenceErrorCode.NOT_FOUND,
                "planId");
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().renew(
                                PersistenceFixtures.PLAN_ID,
                                "lease-token-a",
                                T0.plusSeconds(10))),
                PersistenceErrorCode.LEASE_NOT_HELD,
                "planId");
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().release(
                                PersistenceFixtures.PLAN_ID,
                                "lease-token-a")),
                PersistenceErrorCode.LEASE_NOT_HELD,
                "planId");

        assertEquals(
                PersistenceOutcome.APPLIED,
                observeOnce(
                        clock,
                        () -> persistence.leases().acquire(
                                PersistenceFixtures.PLAN_ID,
                                "worker-a",
                                "lease-token-a",
                                T0.plusSeconds(10)))
                        .outcome());
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().renew(
                                PersistenceFixtures.PLAN_ID,
                                "lease-token-a",
                                T0.plusSeconds(5))),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "expiresAt");

        clock.set(T0.plusSeconds(10));
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().renew(
                                PersistenceFixtures.PLAN_ID,
                                "wrong-token",
                                T0.plusSeconds(20))),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "leaseToken");
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().release(
                                PersistenceFixtures.PLAN_ID,
                                "wrong-token")),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "leaseToken");
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().acquire(
                                PersistenceFixtures.PLAN_ID,
                                "worker-a",
                                "lease-token-a",
                                T0.plusSeconds(10))),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "expiresAt");
    }

    @Test
    void expiryBoundaryRejectsOldGenerationAndEnablesTakeover() {
        MutableCountingClock clock = new MutableCountingClock(T0);
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence(clock);
        persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "worker-a",
                "lease-token-a",
                T0.plusSeconds(10));

        clock.set(T0.plusSeconds(10));
        assertFailure(
                persistence.leases().renew(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a",
                        T0.plusSeconds(20)),
                PersistenceErrorCode.LEASE_EXPIRED,
                "planId");
        assertFailure(
                persistence.leases().release(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a"),
                PersistenceErrorCode.LEASE_EXPIRED,
                "planId");
        PersistenceResult<LeaseRecord> expired =
                persistence.leases().find(PersistenceFixtures.PLAN_ID);
        assertFailure(expired, PersistenceErrorCode.LEASE_EXPIRED, "planId");
        assertTrue(expired.value().isEmpty());

        LeaseRecord takeover = persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-b",
                        "lease-token-b",
                        T0.plusSeconds(20))
                .value()
                .orElseThrow();
        assertEquals(T0.plusSeconds(10), takeover.acquiredAt());
        assertEquals(2, takeover.fencingToken());
    }

    @Test
    void rollbackCannotReviveExpiredGenerationAndTakeoverRemainsMonotonic() {
        MutableCountingClock clock = new MutableCountingClock(T0);
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence(clock);
        persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "worker-a",
                "lease-token-a",
                T0.plusSeconds(10));

        clock.set(T0.plusSeconds(15));
        assertFailure(
                persistence.leases().find(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.LEASE_EXPIRED,
                "planId");

        clock.set(T0.plusSeconds(5));
        assertFailure(
                persistence.leases().renew(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a",
                        T0.plusSeconds(30)),
                PersistenceErrorCode.LEASE_EXPIRED,
                "planId");
        assertFailure(
                persistence.leases().release(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a"),
                PersistenceErrorCode.LEASE_EXPIRED,
                "planId");
        assertFailure(
                persistence.leases().find(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.LEASE_EXPIRED,
                "planId");

        LeaseRecord takeover = persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-b",
                        "lease-token-b",
                        T0.plusSeconds(30))
                .value()
                .orElseThrow();
        assertEquals(T0.plusSeconds(15), takeover.acquiredAt());
        assertEquals(2, takeover.fencingToken());
        assertFailure(
                persistence.leases().renew(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a",
                        T0.plusSeconds(40)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "leaseToken");
        assertFailure(
                persistence.leases().release(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a"),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "leaseToken");
        LeaseRecord found = persistence.leases()
                .find(PersistenceFixtures.PLAN_ID)
                .value()
                .orElseThrow();
        assertEquals("worker-b", found.ownerId());
        assertEquals(2, found.fencingToken());
    }

    @Test
    void rejectedValidCallAdvancesHighWaterAcrossPlans() {
        MutableCountingClock clock = new MutableCountingClock(T0);
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence(clock);
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.plans().create(PersistenceFixtures.plan(PLAN_2)).outcome());

        clock.set(T0.plusSeconds(20));
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().acquire(
                                PersistenceFixtures.PLAN_ID,
                                "worker-a",
                                "rejected-token",
                                T0.plusSeconds(20))),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "expiresAt");

        clock.set(T0.plusSeconds(5));
        assertFailure(
                observeOnce(
                        clock,
                        () -> persistence.leases().acquire(
                                PLAN_2,
                                "worker-b",
                                "expired-target-token",
                                T0.plusSeconds(15))),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "expiresAt");
        LeaseRecord acquired = observeOnce(
                        clock,
                        () -> persistence.leases().acquire(
                                PLAN_2,
                                "worker-b",
                                "live-target-token",
                                T0.plusSeconds(30)))
                .value()
                .orElseThrow();
        assertEquals(T0.plusSeconds(20), acquired.acquiredAt());
    }

    @Test
    void structuralFailuresHaveStablePriorityAndNeverObserveClock() {
        MutableCountingClock clock = new MutableCountingClock(T0);
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence(clock);

        assertFailure(
                persistence.leases().acquire(null, " ", " ", null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "planId");
        assertFailure(
                persistence.leases().acquire(PersistenceFixtures.PLAN_ID, " ", " ", null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "ownerId");
        assertFailure(
                persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID, "owner", " ", null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "leaseToken");
        assertFailure(
                persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID, "owner", "token", null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "expiresAt");

        assertFailure(
                persistence.leases().renew(null, " ", null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "planId");
        assertFailure(
                persistence.leases().renew(PersistenceFixtures.PLAN_ID, " ", null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "leaseToken");
        assertFailure(
                persistence.leases().renew(PersistenceFixtures.PLAN_ID, "token", null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "expiresAt");

        assertFailure(
                persistence.leases().release(null, " "),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "planId");
        assertFailure(
                persistence.leases().release(PersistenceFixtures.PLAN_ID, " "),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "leaseToken");
        assertFailure(
                persistence.leases().find(null),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "planId");

        assertEquals(0, clock.observationCount());
    }

    @Test
    void releasedAndExpiredTokensStayGloballyUsedAndFencesIncrease() {
        MutableCountingClock clock = new MutableCountingClock(T0);
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence(clock);
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.plans().create(PersistenceFixtures.plan(PLAN_2)).outcome());
        persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "worker-a",
                "lease-token-a",
                T0.plusSeconds(10));

        clock.set(T0.plusSeconds(1));
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.leases()
                        .release(PersistenceFixtures.PLAN_ID, "lease-token-a")
                        .outcome());
        assertFailure(
                persistence.leases().acquire(
                        PLAN_2,
                        "worker-a",
                        "lease-token-a",
                        T0.plusSeconds(20)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "leaseToken");

        LeaseRecord second = persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-b",
                        "lease-token-b",
                        T0.plusSeconds(12))
                .value()
                .orElseThrow();
        assertEquals(2, second.fencingToken());

        clock.set(T0.plusSeconds(12));
        assertFailure(
                persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-b",
                        "lease-token-b",
                        T0.plusSeconds(30)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "leaseToken");
        LeaseRecord third = persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-c",
                        "lease-token-c",
                        T0.plusSeconds(30))
                .value()
                .orElseThrow();
        assertEquals(3, third.fencingToken());
    }

    @Test
    void exactlyOneConcurrentLeaseOwnerAcquires() throws Exception {
        MutableCountingClock clock = new MutableCountingClock(T0);
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence(clock);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<LeaseRecord>> first =
                    executor.submit(() -> acquireAfterBarrier(
                            persistence, barrier, "worker-a", "lease-token-a"));
            Future<PersistenceResult<LeaseRecord>> second =
                    executor.submit(() -> acquireAfterBarrier(
                            persistence, barrier, "worker-b", "lease-token-b"));
            List<PersistenceResult<LeaseRecord>> results = List.of(
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
                                    .map(failure ->
                                            failure.code() == PersistenceErrorCode.LEASE_HELD)
                                    .orElse(false))
                            .count());
            assertEquals(2, clock.observationCount());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void expiryTakeoverWinsRaceWithOldRenew() throws Exception {
        MutableCountingClock clock = new MutableCountingClock(T0);
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence(clock);
        persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "worker-a",
                "lease-token-a",
                T0.plusSeconds(10));
        clock.set(T0.plusSeconds(10));
        int observationsBeforeRace = clock.observationCount();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<LeaseRecord>> takeover =
                    executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return persistence.leases().acquire(
                                PersistenceFixtures.PLAN_ID,
                                "worker-b",
                                "lease-token-b",
                                T0.plusSeconds(20));
                    });
            Future<PersistenceResult<LeaseRecord>> staleRenew =
                    executor.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return persistence.leases().renew(
                                PersistenceFixtures.PLAN_ID,
                                "lease-token-a",
                                T0.plusSeconds(20));
                    });

            PersistenceResult<LeaseRecord> takeoverResult =
                    takeover.get(5, TimeUnit.SECONDS);
            PersistenceResult<LeaseRecord> renewResult =
                    staleRenew.get(5, TimeUnit.SECONDS);
            assertEquals(PersistenceOutcome.APPLIED, takeoverResult.outcome());
            assertEquals(2, takeoverResult.value().orElseThrow().fencingToken());
            assertEquals(PersistenceOutcome.REJECTED, renewResult.outcome());
            assertTrue(
                    Set.of(
                                    PersistenceErrorCode.LEASE_EXPIRED,
                                    PersistenceErrorCode.LEASE_TOKEN_INVALID)
                            .contains(renewResult.failure().orElseThrow().code()));
            assertEquals(observationsBeforeRace + 2, clock.observationCount());

            LeaseRecord authoritative = persistence.leases()
                    .find(PersistenceFixtures.PLAN_ID)
                    .value()
                    .orElseThrow();
            assertEquals("worker-b", authoritative.ownerId());
            assertEquals("lease-token-b", authoritative.leaseToken());
            assertEquals(2, authoritative.fencingToken());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nullClockHasStableFailureMessage() {
        NullPointerException failure =
                assertThrows(NullPointerException.class, () -> new InMemoryPersistence(null));
        assertEquals("leaseClock", failure.getMessage());
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode expectedCode,
            String expectedPath) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertTrue(result.value().isEmpty());
        PersistenceFailure failure = result.failure().orElseThrow();
        assertEquals(expectedCode, failure.code());
        assertEquals(expectedPath, failure.path());
    }

    private static <T> T observeOnce(
            MutableCountingClock clock,
            Supplier<T> operation) {
        int before = clock.observationCount();
        T result = operation.get();
        assertEquals(before + 1, clock.observationCount());
        return result;
    }

    private static PersistenceResult<LeaseRecord> acquireAfterBarrier(
            InMemoryPersistence persistence,
            CyclicBarrier barrier,
            String owner,
            String token) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                owner,
                token,
                T0.plusSeconds(10));
    }

    private static final class MutableCountingClock extends Clock {
        private final AtomicReference<Instant> current;
        private final AtomicInteger observations;
        private final ZoneId zone;

        private MutableCountingClock(Instant initial) {
            this(
                    new AtomicReference<>(initial),
                    new AtomicInteger(),
                    ZoneOffset.UTC);
        }

        private MutableCountingClock(
                AtomicReference<Instant> current,
                AtomicInteger observations,
                ZoneId zone) {
            this.current = current;
            this.observations = observations;
            this.zone = zone;
        }

        void set(Instant instant) {
            current.set(instant);
        }

        int observationCount() {
            return observations.get();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableCountingClock(current, observations, requestedZone);
        }

        @Override
        public Instant instant() {
            observations.incrementAndGet();
            return current.get();
        }
    }
}
