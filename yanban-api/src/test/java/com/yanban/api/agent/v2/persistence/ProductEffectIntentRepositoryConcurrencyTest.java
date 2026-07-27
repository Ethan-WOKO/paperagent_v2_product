package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.PersistedEffectIntent;
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
import java.util.Map;
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
        "spring.datasource.url=jdbc:h2:mem:v2effect_intent_concurrency;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.hikari.maximum-pool-size=28"
})
@Import({
        ProductEffectIntentRepositoryAdapter.class,
        ProductEffectIntentTransactions.class,
        ProductEffectIntentCodec.class,
        ProductReceiptMarkerReader.class,
        ProductReceiptEffectIntentMarkerReader.class,
        ProductStepRecoveryRepositoryAdapter.class,
        ProductStepRecoveryTransactions.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductStepActivationCodec.class,
        ProductReceiptRepositoryAdapter.class,
        ProductReceiptTransactions.class,
        ProductReceiptCodec.class,
        ProductEffectIntentRepositoryConcurrencyTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductEffectIntentRepositoryConcurrencyTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        ProductLeaseTimeSource effectIntentTime() {
            return () -> ProductStepActivationTestFixtures.NOW;
        }

        @Bean
        @Primary
        ProductReceiptTimeSource receiptTime() {
            return () -> ProductStepActivationTestFixtures.NOW;
        }
    }

    @jakarta.annotation.Resource
    private ProductEffectIntentRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductReceiptRepositoryAdapter receiptAdapter;
    @jakarta.annotation.Resource
    private ProductEffectIntentJpaRepository intents;
    @jakarta.annotation.Resource
    private ProductReceiptJpaRepository receipts;
    @jakarta.annotation.Resource
    private ProductReceiptToolCallClaimJpaRepository claims;
    @jakarta.annotation.Resource
    private ProductStepInterruptionJpaRepository interruptions;
    @jakarta.annotation.Resource
    private ProductStepActivationJpaRepository activations;
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
    @jakarta.annotation.Resource
    private ProductStepActivationCodec activationCodec;

    private ExecutorService pool;
    private ProductEffectIntentTestFixtures.Scenario scenario;

    @BeforeEach
    void reset() {
        pool = Executors.newFixedThreadPool(24);
        intents.deleteAll();
        receipts.deleteAll();
        claims.deleteAll();
        interruptions.deleteAll();
        activations.deleteAll();
        contexts.deleteAll();
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
        intents.flush();
        interruptions.flush();
        activations.flush();
        contexts.flush();
        starts.flush();
        leases.flush();
        bootstraps.flush();
        scenario = ProductEffectIntentTestFixtures.seed(
                "plan-a", "task-a", "owner-a", "token-a", 1,
                bootstraps, bootstrapCodec, leases, starts, startCodec,
                activations, activationCodec);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    @Test
    void twentyFourExactContendersProduceOneAppliedAndExactReplays()
            throws Exception {
        EffectIntentRequest request =
                ProductEffectIntentTestFixtures.request(
                        scenario, "tool-a", "token-a", 1);
        List<PersistenceResult<PersistedEffectIntent>> results =
                race(24, () -> adapter.persist(request));
        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(23, count(results, PersistenceOutcome.REPLAYED));
        assertEquals(1, intents.count());
        assertEquals(request.intent(),
                adapter.find(request.intent().toolCallId())
                        .value().orElseThrow().intent());
    }

    @Test
    void conflictingContendersPreserveOneImmutableWinner()
            throws Exception {
        EffectIntentRequest first =
                ProductEffectIntentTestFixtures.request(
                        scenario, "tool-a", "token-a", 1, "search",
                        new ObjectValue(Map.of(
                                "query", new TextValue("first"))));
        EffectIntentRequest second =
                ProductEffectIntentTestFixtures.request(
                        scenario, "tool-a", "token-a", 1, "search",
                        new ObjectValue(Map.of(
                                "query", new TextValue("second"))));
        List<PersistenceResult<PersistedEffectIntent>> results =
                race(List.of(
                        () -> adapter.persist(first),
                        () -> adapter.persist(second)));
        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(1, results.stream()
                .filter(result -> result.outcome()
                        == PersistenceOutcome.REJECTED)
                .filter(result -> result.failure().orElseThrow().code()
                        == PersistenceErrorCode.CONFLICTING_REPLAY)
                .filter(result -> result.failure().orElseThrow().path()
                        .equals("request.intent.arguments"))
                .count());
        assertEquals(1, intents.count());
        var winner = adapter.find(first.intent().toolCallId())
                .value().orElseThrow().intent();
        assertEquals(true, winner.equals(first.intent())
                || winner.equals(second.intent()));
    }

    @Test
    void firstEffectIntentAndOrdinaryReceiptYieldOneOwnershipWinner()
            throws Exception {
        EffectIntentRequest effect = ProductEffectIntentTestFixtures.request(
                scenario, "shared-tool", "token-a", 1);
        ExecutionReceipt receipt =
                ProductReceiptRepositoryAdapterTest.receipt(
                        "shared-receipt", "shared-tool");
        List<PersistenceResult<?>> results = raceAny(List.of(
                () -> adapter.persist(effect),
                () -> receiptAdapter.append(receipt)));
        assertEquals(1, count(results, PersistenceOutcome.APPLIED));
        assertEquals(1, results.stream()
                .filter(result -> result.failure()
                        .map(failure -> failure.code()
                                == PersistenceErrorCode
                                .EFFECT_RECEIPT_OWNERSHIP_REQUIRED)
                        .orElse(false))
                .count());
        assertEquals(1, intents.count() + receipts.count());
        assertEquals(1, claims.count());
    }

    private <T> List<PersistenceResult<T>> race(
            int count, Callable<PersistenceResult<T>> call) throws Exception {
        List<Callable<PersistenceResult<T>>> calls = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            calls.add(call);
        }
        return race(calls);
    }

    private <T> List<PersistenceResult<T>> race(
            List<Callable<PersistenceResult<T>>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PersistenceResult<T>>> futures = new ArrayList<>();
        for (Callable<PersistenceResult<T>> call : calls) {
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

    private List<PersistenceResult<?>> raceAny(
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
            List<? extends PersistenceResult<?>> results,
            PersistenceOutcome outcome) {
        return results.stream()
                .filter(result -> result.outcome() == outcome)
                .count();
    }
}
