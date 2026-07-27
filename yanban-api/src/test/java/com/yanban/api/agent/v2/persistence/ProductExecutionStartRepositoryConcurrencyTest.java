package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2execution_start_concurrency;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductExecutionStartRepositoryAdapter.class,
        ProductExecutionStartTransactions.class,
        ProductExecutionStartCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductLeaseRepositoryAdapter.class,
        ProductLeaseTransactions.class,
        ProductExecutionStartRepositoryConcurrencyTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductExecutionStartRepositoryConcurrencyTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        SharedTime time() {
            return new SharedTime();
        }
    }

    static final class SharedTime
            implements ProductExecutionStartTimeSource, ProductLeaseTimeSource {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(ProductExecutionStartTestFixtures.NOW);

        @Override
        public Instant observe() {
            return now.get();
        }

        void set(Instant instant) {
            now.set(instant);
        }
    }

    @jakarta.annotation.Resource
    private ProductExecutionStartRepositoryAdapter startsAdapter;
    @jakarta.annotation.Resource
    private ProductLeaseRepositoryAdapter leaseAdapter;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapJpaRepository bootstraps;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapCodec bootstrapCodec;
    @jakarta.annotation.Resource
    private ProductLeaseJpaRepository leases;
    @jakarta.annotation.Resource
    private ProductExecutionStartJpaRepository starts;
    @jakarta.annotation.Resource
    private SharedTime time;

    private ExecutorService pool;

    @BeforeEach
    void reset() {
        pool = Executors.newFixedThreadPool(8);
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
        starts.flush();
        leases.flush();
        bootstraps.flush();
        time.set(ProductExecutionStartTestFixtures.NOW);
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void identicalSamePlanContendersConvergeToOneAppliedAndReplays()
            throws Exception {
        Scenario scenario = seed("plan-a", "task-a", "owner-a", "token-a",
                "event-a");
        List<PersistenceResult<PersistedExecutionStart>> results =
                race(8, () -> startsAdapter.start(scenario.request()));

        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(7, count(results, PersistenceOutcome.REPLAYED));
        assertEquals(1, starts.count());
        assertTrue(results.stream().map(result -> result.value().orElseThrow())
                .allMatch(results.get(0).value().orElseThrow()::equals));
    }

    @Test
    void differentSamePlanContendersConvergeToAppliedAndPlanConflict()
            throws Exception {
        Scenario scenario = seed("plan-a", "task-a", "owner-a", "token-a",
                "event-a");
        ExecutionStartRequest other =
                ProductExecutionStartTestFixtures.request(
                        scenario.bootstrap(), "token-a", 1, "event-other");
        List<PersistenceResult<PersistedExecutionStart>> results = race(
                () -> startsAdapter.start(scenario.request()),
                () -> startsAdapter.start(other));

        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(1, failures(results,
                PersistenceErrorCode.CONFLICTING_REPLAY, "request.planId"));
        assertEquals(1, starts.count());
    }

    @Test
    void crossPlanSameEventIdHasOneAppliedAndOneEventConflict()
            throws Exception {
        Scenario first = seed("plan-a", "task-a", "owner-a", "token-a",
                "shared-event");
        Scenario second = seed("plan-b", "task-b", "owner-b", "token-b",
                "shared-event");
        List<PersistenceResult<PersistedExecutionStart>> results = race(
                () -> startsAdapter.start(first.request()),
                () -> startsAdapter.start(second.request()));

        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(1, failures(results,
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.startEvent.id"));
        assertEquals(1, starts.count());
    }

    @Test
    void startVersusReleaseSerializesWithoutPartialAuthority()
            throws Exception {
        Scenario scenario = seed("plan-a", "task-a", "owner-a", "token-a",
                "event-a");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<PersistenceResult<PersistedExecutionStart>> start =
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return startsAdapter.start(scenario.request());
                });
        Future<PersistenceResult<LeaseRecord>> release = pool.submit(() -> {
            ready.countDown();
            go.await();
            return leaseAdapter.release(
                    scenario.bootstrap().plan().id(), "token-a");
        });
        ready.await();
        go.countDown();

        PersistenceResult<PersistedExecutionStart> startResult = start.get();
        PersistenceResult<LeaseRecord> releaseResult = release.get();
        assertEquals(PersistenceOutcome.APPLIED, releaseResult.outcome());
        if (startResult.outcome() == PersistenceOutcome.APPLIED) {
            assertEquals(1, starts.count());
        } else {
            assertFailure(startResult,
                    PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");
            assertEquals(0, starts.count());
        }
    }

    @Test
    void expiryTakeoverAlwaysFencesOldStartAndNewFenceCanStart()
            throws Exception {
        Scenario old = seed("plan-a", "task-a", "owner-a", "token-a",
                "event-old");
        time.set(ProductExecutionStartTestFixtures.NOW.plusSeconds(61));
        List<Object> results = raceObjects(
                () -> startsAdapter.start(old.request()),
                () -> leaseAdapter.acquire(
                        old.bootstrap().plan().id(), "owner-b", "token-b",
                        ProductExecutionStartTestFixtures.NOW.plusSeconds(120)));
        @SuppressWarnings("unchecked")
        PersistenceResult<PersistedExecutionStart> stale =
                (PersistenceResult<PersistedExecutionStart>) results.get(0);
        @SuppressWarnings("unchecked")
        PersistenceResult<LeaseRecord> acquired =
                (PersistenceResult<LeaseRecord>) results.get(1);

        assertEquals(PersistenceOutcome.REJECTED, stale.outcome());
        assertTrue(stale.failure().orElseThrow().code()
                == PersistenceErrorCode.LEASE_EXPIRED
                || stale.failure().orElseThrow().code()
                == PersistenceErrorCode.LEASE_TOKEN_INVALID);
        assertEquals(PersistenceOutcome.APPLIED, acquired.outcome());
        assertEquals(0, starts.count());

        ExecutionStartRequest current =
                ProductExecutionStartTestFixtures.request(
                        old.bootstrap(), "token-b",
                        acquired.value().orElseThrow().fencingToken(),
                        "event-new");
        assertEquals(PersistenceOutcome.APPLIED,
                startsAdapter.start(current).outcome());
        assertEquals("owner-b",
                starts.findById("plan-a").orElseThrow().leaseOwnerId());
        assertEquals(2,
                starts.findById("plan-a").orElseThrow().fencingToken());
    }

    private Scenario seed(
            String plan, String task, String owner, String token,
            String eventId) {
        PersistedPlanBootstrap bootstrap =
                ProductExecutionStartTestFixtures.bootstrap(plan, task);
        var encoded = bootstrapCodec.encode(bootstrap);
        bootstraps.saveAndFlush(new ProductPlanBootstrapEntity(
                plan, task, encoded.formatVersion(), encoded.sha256(),
                encoded.json(), ProductExecutionStartTestFixtures.NOW));
        leases.saveAndFlush(new ProductLeaseEntity(
                plan, 1, owner, token,
                ProductExecutionStartTestFixtures.NOW.minusSeconds(1),
                ProductExecutionStartTestFixtures.NOW.plusSeconds(60)));
        return new Scenario(bootstrap,
                ProductExecutionStartTestFixtures.request(
                        bootstrap, token, 1, eventId));
    }

    private <T> List<PersistenceResult<T>> race(
            int count, java.util.concurrent.Callable<PersistenceResult<T>> call)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PersistenceResult<T>>> futures = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return call.call();
            }));
        }
        ready.await();
        go.countDown();
        List<PersistenceResult<T>> results = new ArrayList<>();
        for (Future<PersistenceResult<T>> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    private <T> List<PersistenceResult<T>> race(
            java.util.concurrent.Callable<PersistenceResult<T>> first,
            java.util.concurrent.Callable<PersistenceResult<T>> second)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<PersistenceResult<T>> left = pool.submit(() -> {
            ready.countDown();
            go.await();
            return first.call();
        });
        Future<PersistenceResult<T>> right = pool.submit(() -> {
            ready.countDown();
            go.await();
            return second.call();
        });
        ready.await();
        go.countDown();
        return List.of(left.get(), right.get());
    }

    private List<Object> raceObjects(
            java.util.concurrent.Callable<?> first,
            java.util.concurrent.Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> left = pool.submit(() -> {
            ready.countDown();
            go.await();
            return first.call();
        });
        Future<?> right = pool.submit(() -> {
            ready.countDown();
            go.await();
            return second.call();
        });
        ready.await();
        go.countDown();
        return List.of(left.get(), right.get());
    }

    private static long count(
            List<? extends PersistenceResult<?>> results,
            PersistenceOutcome outcome) {
        return results.stream()
                .filter(result -> result.outcome() == outcome)
                .count();
    }

    private static long failures(
            List<? extends PersistenceResult<?>> results,
            PersistenceErrorCode code,
            String path) {
        return results.stream()
                .filter(result -> result.failure().isPresent())
                .filter(result -> result.failure().orElseThrow().code() == code)
                .filter(result -> result.failure().orElseThrow().path().equals(path))
                .count();
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    private record Scenario(
            PersistedPlanBootstrap bootstrap, ExecutionStartRequest request) {
    }
}
