package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedStepActivation;
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
        "spring.datasource.url=jdbc:h2:mem:v2step_activation_concurrency;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductStepActivationRepositoryAdapter.class,
        ProductStepActivationTransactions.class,
        ProductStepActivationCodec.class,
        ProductStepRecoveryTransactions.class,
        ProductActiveStepReplanMarkerReader.class,
        ProductActiveStepReplanCodec.class,
        ProductStepCompletionMarkerReader.class,
        ProductStepCompletionCodec.class,
        ProductStepInterruptionMarkerReader.class,
        ProductStepInterruptionCodec.class,
        ProductEffectOutcomeMarkerReader.class,
        ProductEffectOutcomeCodec.class,
        ProductEffectIntentCodec.class,
        ProductReceiptMarkerReader.class,
        ProductReceiptCodec.class,
        ProductReceiptEffectIntentMarkerReader.class,
        ProductExecutionStartCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductStepActivationRepositoryConcurrencyTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductStepActivationRepositoryConcurrencyTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        ProductLeaseTimeSource timeSource() {
            return () -> ProductStepActivationTestFixtures.NOW;
        }
    }

    @jakarta.annotation.Resource
    private ProductStepActivationRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapJpaRepository bootstraps;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapCodec bootstrapCodec;
    @jakarta.annotation.Resource
    private ProductExecutionStartJpaRepository starts;
    @jakarta.annotation.Resource
    private ProductExecutionStartCodec startCodec;
    @jakarta.annotation.Resource
    private ProductPlanExecutionContextJpaRepository contexts;
    @jakarta.annotation.Resource
    private ProductLeaseJpaRepository leases;
    @jakarta.annotation.Resource
    private ProductStepActivationJpaRepository activations;

    private ExecutorService pool;

    @BeforeEach
    void reset() {
        pool = Executors.newFixedThreadPool(8);
        activations.deleteAll();
        contexts.deleteAll();
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
        activations.flush();
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
    void identicalContendersProduceOneImmutableRowAndReplays()
            throws Exception {
        Scenario scenario = seed("plan-a", "task-a", "owner-a", "token-a",
                "activation-a");
        List<PersistenceResult<PersistedStepActivation>> results =
                race(8, () -> adapter.activate(scenario.request()));
        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(7, count(results, PersistenceOutcome.REPLAYED));
        assertEquals(1, activations.count());
    }

    @Test
    void conflictingSamePlanContendersHaveOneAppliedAndStableLoser()
            throws Exception {
        Scenario scenario = seed("plan-a", "task-a", "owner-a", "token-a",
                "activation-a");
        var different = ProductStepActivationTestFixtures.request(
                scenario.bootstrap(), "token-a", 1, "activation-b");
        List<PersistenceResult<PersistedStepActivation>> results = race(
                () -> adapter.activate(scenario.request()),
                () -> adapter.activate(different));
        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(1, results.stream()
                .filter(result -> result.outcome() == PersistenceOutcome.REJECTED)
                .filter(result -> result.failure().orElseThrow().code()
                        == PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE)
                .count());
        assertEquals(1, activations.count());
    }

    @Test
    void crossPlanSameEventHasOneAppliedAndOneEventConflict()
            throws Exception {
        Scenario first = seed("plan-a", "task-a", "owner-a", "token-a",
                "shared-activation");
        Scenario second = seed("plan-b", "task-b", "owner-b", "token-b",
                "shared-activation");
        List<PersistenceResult<PersistedStepActivation>> results = race(
                () -> adapter.activate(first.request()),
                () -> adapter.activate(second.request()));
        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(1, results.stream()
                .filter(result -> result.failure().isPresent())
                .filter(result -> result.failure().orElseThrow().code()
                        == PersistenceErrorCode.CONFLICTING_REPLAY)
                .filter(result -> result.failure().orElseThrow().path()
                        .equals("request.activationEvent.id"))
                .count());
        assertEquals(1, activations.count());
    }

    private Scenario seed(
            String plan, String task, String owner, String token,
            String eventId) {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.workspace(plan, task);
        ProductStepActivationTestFixtures.seedH0(
                bootstrap, owner, token, 1, bootstraps, bootstrapCodec,
                leases, starts, startCodec);
        return new Scenario(bootstrap,
                ProductStepActivationTestFixtures.request(
                        bootstrap, token, 1, eventId));
    }

    private <T> List<PersistenceResult<T>> race(
            int count, Callable<PersistenceResult<T>> call) throws Exception {
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
        List<PersistenceResult<T>> result = new ArrayList<>();
        for (Future<PersistenceResult<T>> future : futures) {
            result.add(future.get());
        }
        return result;
    }

    private <T> List<PersistenceResult<T>> race(
            Callable<PersistenceResult<T>> left,
            Callable<PersistenceResult<T>> right) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<PersistenceResult<T>> first = pool.submit(() -> {
            ready.countDown();
            go.await();
            return left.call();
        });
        Future<PersistenceResult<T>> second = pool.submit(() -> {
            ready.countDown();
            go.await();
            return right.call();
        });
        ready.await();
        go.countDown();
        return List.of(first.get(), second.get());
    }

    private static long count(
            List<? extends PersistenceResult<?>> results,
            PersistenceOutcome outcome) {
        return results.stream()
                .filter(result -> result.outcome() == outcome).count();
    }

    private record Scenario(
            PersistedPlanBootstrap bootstrap,
            io.paperagent.v2.persistence.StepActivationRequest request) {
    }
}
