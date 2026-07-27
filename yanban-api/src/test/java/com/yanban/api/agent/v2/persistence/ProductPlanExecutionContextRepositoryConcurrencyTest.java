package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextConfirmationRequest;
import io.paperagent.v2.persistence.PlanExecutionContextReservationRequest;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2context_concurrency;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductPlanExecutionContextRepositoryAdapter.class,
        ProductPlanExecutionContextTransactions.class,
        ProductPlanExecutionContextCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanExecutionContextRepositoryConcurrencyTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductPlanExecutionContextRepositoryConcurrencyTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() { return new ObjectMapper(); }

        @Bean
        @Primary
        ProductLeaseTimeSource time() {
            return () -> ProductPlanExecutionContextTestFixtures.NOW;
        }
    }

    @jakarta.annotation.Resource
    private ProductPlanExecutionContextRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductPlanExecutionContextJpaRepository contexts;
    @jakarta.annotation.Resource
    private ProductExecutionStartJpaRepository starts;
    @jakarta.annotation.Resource
    private ProductLeaseJpaRepository leases;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapJpaRepository bootstraps;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapCodec bootstrapCodec;
    @jakarta.annotation.Resource
    private ProductExecutionStartCodec startCodec;

    private ExecutorService pool;

    @BeforeEach
    void reset() {
        pool = Executors.newFixedThreadPool(8);
        contexts.deleteAll();
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
        contexts.flush();
        starts.flush();
        leases.flush();
        bootstraps.flush();
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    @Test
    void sameReservationHasOneAppliedAndRestPermanentReplays()
            throws Exception {
        Scenario scenario = seed("a");
        List<PersistenceResult<?>> results =
                race(8, () -> adapter.reserve(scenario.reservation()));
        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(7, count(results, PersistenceOutcome.REPLAYED));
        assertEquals(1, contexts.count());
    }

    @Test
    void changedSamePlanReservationsHaveOneWinnerAndOneConflict()
            throws Exception {
        Scenario scenario = seed("a");
        var changed = ProductPlanExecutionContextTestFixtures.reservation(
                scenario.bootstrap(), "token-a", 1,
                ProductPlanExecutionContextTestFixtures.spec("changed"));
        List<PersistenceResult<?>> results = race(
                () -> adapter.reserve(scenario.reservation()),
                () -> adapter.reserve(changed));
        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(1, failures(results,
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.planId"));
        assertEquals(1, contexts.count());
    }

    @Test
    void competingPlansForWorkspaceHaveOneDurableOwner()
            throws Exception {
        Scenario first = seed("a");
        Scenario second = seed("b");
        var shared = ProductPlanExecutionContextTestFixtures.reservation(
                second.bootstrap(), "token-b", 1, first.spec());
        List<PersistenceResult<?>> results = race(
                () -> adapter.reserve(first.reservation()),
                () -> adapter.reserve(shared));
        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(1, failures(results,
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.materializationSpec.workspaceId"));
        assertEquals(1, contexts.count());
    }

    @Test
    void confirmRaceHasOneAppliedAndPermanentReplays()
            throws Exception {
        Scenario scenario = seed("a");
        adapter.reserve(scenario.reservation());
        PlanExecutionContextConfirmationRequest confirmation =
                ProductPlanExecutionContextTestFixtures.confirmation(
                        scenario.bootstrap(), "token-a", 1, scenario.spec());
        List<PersistenceResult<?>> results =
                race(8, () -> adapter.confirm(confirmation));
        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(7, count(results, PersistenceOutcome.REPLAYED));
        assertEquals(1, contexts.count());
        assertEquals(PersistenceOutcome.REPLAYED,
                adapter.confirm(confirmation).outcome());
    }

    private Scenario seed(String suffix) {
        PersistedPlanBootstrap bootstrap =
                ProductPlanExecutionContextTestFixtures.bootstrap(
                        "plan-" + suffix, "task-" + suffix);
        ProductPlanExecutionContextTestFixtures.seedStarted(
                bootstrap, "owner-" + suffix, "token-" + suffix, 1,
                bootstraps, bootstrapCodec, leases, starts, startCodec);
        var spec = ProductPlanExecutionContextTestFixtures.spec(suffix);
        return new Scenario(
                bootstrap, spec,
                ProductPlanExecutionContextTestFixtures.reservation(
                        bootstrap, "token-" + suffix, 1, spec));
    }

    private List<PersistenceResult<?>> race(
            int count, Callable<PersistenceResult<?>> call)
            throws Exception {
        List<Callable<PersistenceResult<?>>> calls = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            calls.add(call);
        }
        return race(calls);
    }

    private List<PersistenceResult<?>> race(
            Callable<PersistenceResult<?>> first,
            Callable<PersistenceResult<?>> second) throws Exception {
        return race(List.of(first, second));
    }

    private List<PersistenceResult<?>> race(
            List<Callable<PersistenceResult<?>>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PersistenceResult<?>>> futures = new ArrayList<>();
        for (Callable<PersistenceResult<?>> call : calls) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return call.call();
            }));
        }
        ready.await();
        go.countDown();
        List<PersistenceResult<?>> results = new ArrayList<>();
        for (Future<PersistenceResult<?>> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    private static long count(
            List<PersistenceResult<?>> results,
            PersistenceOutcome outcome) {
        return results.stream()
                .filter(result -> result.outcome() == outcome).count();
    }

    private static long failures(
            List<PersistenceResult<?>> results,
            PersistenceErrorCode code, String path) {
        return results.stream()
                .filter(result -> result.failure().isPresent())
                .filter(result -> result.failure().orElseThrow().code() == code)
                .filter(result -> result.failure().orElseThrow()
                        .path().equals(path))
                .count();
    }

    private record Scenario(
            PersistedPlanBootstrap bootstrap,
            io.paperagent.v2.contracts.WorkspaceMaterializationSpec spec,
            PlanExecutionContextReservationRequest reservation) {
    }
}
