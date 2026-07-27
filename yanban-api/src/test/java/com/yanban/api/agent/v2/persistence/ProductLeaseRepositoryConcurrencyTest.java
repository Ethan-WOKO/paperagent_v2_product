package com.yanban.api.agent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2lease_concurrency;MODE=MySQL;"
                + "DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductLeaseRepositoryAdapter.class,
        ProductLeaseTransactions.class,
        SystemProductLeaseTimeSource.class,
        ProductLeaseRepositoryAdapterTest.TimeConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductLeaseRepositoryConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-07-27T11:00:00Z");

    @jakarta.annotation.Resource
    private ProductLeaseRepositoryAdapter adapter;

    @jakarta.annotation.Resource
    private ProductPlanBootstrapJpaRepository bootstraps;

    @jakarta.annotation.Resource
    private ProductLeaseJpaRepository leases;

    @jakarta.annotation.Resource
    private ProductLeaseRepositoryAdapterTest.MutableProductLeaseTimeSource time;

    @BeforeEach
    void reset() {
        leases.deleteAll();
        bootstraps.deleteAll();
        leases.flush();
        bootstraps.flush();
        time.reset();
        time.set(NOW);
    }

    @Test
    void equivalentSamePlanAcquisitionsSerializeToAppliedAndReplayed()
            throws Exception {
        seed("plan-1");

        List<PersistenceResult<LeaseRecord>> results = race(
                () -> acquire("plan-1", "owner", "token", NOW.plusSeconds(60)),
                () -> acquire("plan-1", "owner", "token", NOW.plusSeconds(60)));

        assertOutcomeCount(results, PersistenceOutcome.APPLIED, 1);
        assertOutcomeCount(results, PersistenceOutcome.REPLAYED, 1);
        assertEquals(1, leases.count());
        assertEquals(1, leases.findAll().get(0).fencingToken());
    }

    @Test
    void conflictingSamePlanAcquisitionsSerializeToAppliedAndHeld()
            throws Exception {
        seed("plan-1");

        List<PersistenceResult<LeaseRecord>> results = race(
                () -> acquire("plan-1", "owner-a", "token-a", NOW.plusSeconds(60)),
                () -> acquire("plan-1", "owner-b", "token-b", NOW.plusSeconds(60)));

        assertOutcomeCount(results, PersistenceOutcome.APPLIED, 1);
        assertFailureCount(results, PersistenceErrorCode.LEASE_HELD, "planId", 1);
        assertEquals(1, leases.count());
        assertEquals(1, leases.findAll().get(0).fencingToken());
    }

    @Test
    void crossPlanSameTokenConvergesToAppliedAndTokenInvalid()
            throws Exception {
        seed("plan-1");
        seed("plan-2");

        List<PersistenceResult<LeaseRecord>> results = race(
                () -> acquire("plan-1", "owner-a", "shared-token", NOW.plusSeconds(60)),
                () -> acquire("plan-2", "owner-b", "shared-token", NOW.plusSeconds(60)));

        assertOutcomeCount(results, PersistenceOutcome.APPLIED, 1);
        assertFailureCount(
                results, PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken", 1);
        assertEquals(1, leases.count());
        assertEquals("shared-token", leases.findAll().get(0).leaseToken());
        assertEquals(1, leases.findAll().get(0).fencingToken());
    }

    private PersistenceResult<LeaseRecord> acquire(
            String plan, String owner, String token, Instant expiry) {
        return adapter.acquire(new PlanId(plan), owner, token, expiry);
    }

    private List<PersistenceResult<LeaseRecord>> race(
            Operation first, Operation second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PersistenceResult<LeaseRecord>> left =
                    pool.submit(() -> awaitAndRun(ready, start, first));
            Future<PersistenceResult<LeaseRecord>> right =
                    pool.submit(() -> awaitAndRun(ready, start, second));
            ready.await();
            start.countDown();
            return List.of(left.get(), right.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static PersistenceResult<LeaseRecord> awaitAndRun(
            CountDownLatch ready, CountDownLatch start, Operation operation)
            throws Exception {
        ready.countDown();
        start.await();
        return operation.run();
    }

    private void seed(String planId) {
        bootstraps.saveAndFlush(new ProductPlanBootstrapEntity(
                planId,
                "task-" + planId,
                1,
                "0".repeat(64),
                "{}",
                NOW.minusSeconds(1)));
    }

    private static void assertOutcomeCount(
            List<PersistenceResult<LeaseRecord>> results,
            PersistenceOutcome outcome,
            long count) {
        assertEquals(count, results.stream()
                .filter(result -> result.outcome() == outcome)
                .count());
    }

    private static void assertFailureCount(
            List<PersistenceResult<LeaseRecord>> results,
            PersistenceErrorCode code,
            String path,
            long count) {
        assertEquals(count, results.stream()
                .filter(result -> result.failure()
                        .map(failure -> failure.code() == code
                                && failure.path().equals(path))
                        .orElse(false))
                .count());
    }

    @FunctionalInterface
    private interface Operation {
        PersistenceResult<LeaseRecord> run() throws Exception;
    }
}
