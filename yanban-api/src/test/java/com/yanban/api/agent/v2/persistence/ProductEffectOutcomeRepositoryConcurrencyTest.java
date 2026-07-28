package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.EffectProgress;
import io.paperagent.v2.contracts.EffectProgressId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.EffectProgressRequest;
import io.paperagent.v2.persistence.EffectResultRequest;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2effect_outcome_concurrency;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.hikari.maximum-pool-size=28"
})
@Import({
        ProductEffectOutcomeRepositoryAdapter.class,
        ProductEffectOutcomeTransactions.class,
        ProductEffectOutcomeCodec.class,
        ProductEffectOutcomeMarkerReader.class,
        ProductEffectOutcomeReceiptInspector.class,
        ProductEffectIntentRepositoryAdapter.class,
        ProductEffectIntentTransactions.class,
        ProductEffectIntentCodec.class,
        ProductReceiptRepositoryAdapter.class,
        ProductReceiptTransactions.class,
        ProductReceiptCodec.class,
        ProductReceiptMarkerReader.class,
        ProductReceiptEffectIntentMarkerReader.class,
        ProductStepRecoveryRepositoryAdapter.class,
        ProductStepRecoveryTransactions.class,
        ProductActiveStepReplanMarkerReader.class,
        ProductActiveStepReplanCodec.class,
        ProductStepInterruptionMarkerReader.class,
        ProductStepInterruptionCodec.class,
        ProductStepCompletionMarkerReader.class,
        ProductStepCompletionCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductStepActivationCodec.class,
        ProductEffectOutcomeRepositoryConcurrencyTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductEffectOutcomeRepositoryConcurrencyTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        FixedTime outcomeTime() {
            return new FixedTime();
        }
    }

    static final class FixedTime implements ProductLeaseTimeSource,
            ProductEffectOutcomeTimeSource, ProductReceiptTimeSource {
        @Override
        public java.time.Instant observe() {
            return ProductStepActivationTestFixtures.NOW;
        }
    }

    @jakarta.annotation.Resource
    private ProductEffectOutcomeRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductEffectIntentRepositoryAdapter intentAdapter;
    @jakarta.annotation.Resource
    private ProductReceiptRepositoryAdapter receiptAdapter;
    @jakarta.annotation.Resource
    private ProductEffectOutcomeProgressJpaRepository progress;
    @jakarta.annotation.Resource
    private ProductEffectOutcomeResultJpaRepository results;
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
    private EffectIntentRequest intent;

    @BeforeEach
    void reset() {
        pool = Executors.newFixedThreadPool(24);
        results.deleteAll();
        progress.deleteAll();
        receipts.deleteAll();
        intents.deleteAll();
        claims.deleteAll();
        interruptions.deleteAll();
        activations.deleteAll();
        contexts.deleteAll();
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
        ProductEffectIntentTestFixtures.Scenario scenario =
                ProductEffectIntentTestFixtures.seed(
                        "plan-outcome-race", "task-outcome-race",
                        "owner-race", "token-race", 1,
                        bootstraps, bootstrapCodec, leases, starts,
                        startCodec, activations, activationCodec);
        intent = ProductEffectIntentTestFixtures.request(
                scenario, "tool-outcome-race", "token-race", 1);
        assertEquals(PersistenceOutcome.APPLIED,
                intentAdapter.persist(intent).outcome());
    }

    @AfterEach
    void stopPool() throws Exception {
        pool.shutdownNow();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    void twentyFourExactProgressAndResultCallsApplyOnceThenReplay()
            throws Exception {
        EffectProgressRequest progressRequest = progress();
        List<PersistenceResult<?>> progressRace =
                race(24, () -> adapter.appendProgress(progressRequest));
        assertEquals(1, count(progressRace, PersistenceOutcome.APPLIED));
        assertEquals(23, count(progressRace, PersistenceOutcome.REPLAYED));
        assertEquals(1, progress.count());

        EffectResultRequest resultRequest = result("receipt-exact-race");
        List<PersistenceResult<?>> resultRace =
                race(24, () -> adapter.recordResult(resultRequest));
        assertEquals(1, count(resultRace, PersistenceOutcome.APPLIED));
        assertEquals(23, count(resultRace, PersistenceOutcome.REPLAYED));
        assertEquals(1, results.count());
        assertEquals(1, receipts.count());
    }

    @Test
    void conflictingResultRacePreservesOneImmutableWinner()
            throws Exception {
        List<Callable<PersistenceResult<?>>> calls = List.of(
                () -> adapter.recordResult(result("receipt-left")),
                () -> adapter.recordResult(result("receipt-right")));
        List<PersistenceResult<?>> raced = race(calls);
        assertEquals(1, count(raced, PersistenceOutcome.APPLIED));
        assertEquals(1, count(raced, PersistenceOutcome.REJECTED));
        PersistenceResult<?> rejected = raced.stream()
                .filter(value -> value.outcome()
                        == PersistenceOutcome.REJECTED)
                .findFirst().orElseThrow();
        assertEquals(PersistenceErrorCode.CONFLICTING_REPLAY,
                rejected.failure().orElseThrow().code());
        assertEquals("request.receipt.id",
                rejected.failure().orElseThrow().path());
        assertEquals(1, results.count());
        assertEquals(1, receipts.count());
    }

    @Test
    void progressFinalizationAndReceiptOwnershipRacesNeverExposeTornState()
            throws Exception {
        EffectProgressRequest progressRequest = progress();
        EffectResultRequest resultRequest = result("receipt-final-race");
        List<Callable<PersistenceResult<?>>> finalization = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            finalization.add(() -> adapter.appendProgress(progressRequest));
            finalization.add(() -> adapter.recordResult(resultRequest));
        }
        List<PersistenceResult<?>> finalized = race(finalization);
        assertEquals(1, results.count());
        assertEquals(1, receipts.count());
        assertTrue(progress.count() == 0 || progress.count() == 1);
        assertTrue(finalized.stream().noneMatch(value ->
                value.failure().map(failure ->
                        failure.code()
                                == PersistenceErrorCode
                                .EFFECT_OUTCOME_PARTIAL_STATE)
                        .orElse(false)));

        resetForOwnership();
        EffectResultRequest effect = result("receipt-ownership-race");
        ExecutionReceipt ordinary =
                ProductEffectOutcomeCodecTest.receipt(
                        ReceiptStatus.FAILURE,
                        "receipt-ownership-race", "ordinary-race-call");
        List<PersistenceResult<?>> ownership = race(List.of(
                () -> adapter.recordResult(effect),
                () -> receiptAdapter.append(ordinary)));
        assertEquals(1, count(ownership, PersistenceOutcome.APPLIED));
        assertEquals(1, count(ownership, PersistenceOutcome.REJECTED));
        assertEquals(PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                ownership.stream()
                        .filter(value -> value.outcome()
                                == PersistenceOutcome.REJECTED)
                        .findFirst().orElseThrow()
                        .failure().orElseThrow().code());
        assertEquals(1, receipts.count());
        assertTrue(results.count() == 0 || results.count() == 1);
    }

    private void resetForOwnership() {
        results.deleteAll();
        progress.deleteAll();
        receipts.deleteAll();
        claims.findById("ordinary-race-call")
                .ifPresent(claims::delete);
        results.flush();
        progress.flush();
        receipts.flush();
        claims.flush();
    }

    private EffectProgressRequest progress() {
        return new EffectProgressRequest(new EffectProgress(
                new EffectProgressId("progress-race"),
                intent.intent().toolCallId(), 1,
                ProductStepActivationTestFixtures.NOW.plusSeconds(1),
                new ObjectValue(Map.of(
                        "detail", new TextValue("safe")))),
                "token-race", 1);
    }

    private EffectResultRequest result(String receiptId) {
        return new EffectResultRequest(
                ProductEffectOutcomeCodecTest.receipt(
                        ReceiptStatus.FAILURE, receiptId,
                        intent.intent().toolCallId().value()),
                "token-race", 1);
    }

    private List<PersistenceResult<?>> race(
            int count,
            Callable<? extends PersistenceResult<?>> call)
            throws Exception {
        List<Callable<PersistenceResult<?>>> calls = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            calls.add(call::call);
        }
        return race(calls);
    }

    private List<PersistenceResult<?>> race(
            List<Callable<PersistenceResult<?>>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PersistenceResult<?>>> futures = new ArrayList<>();
        for (Callable<PersistenceResult<?>> call : calls) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("race start timed out");
                }
                return call.call();
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        List<PersistenceResult<?>> values = new ArrayList<>();
        for (Future<PersistenceResult<?>> future : futures) {
            values.add(future.get(30, TimeUnit.SECONDS));
        }
        return values;
    }

    private static long count(
            List<PersistenceResult<?>> results,
            PersistenceOutcome outcome) {
        return results.stream()
                .filter(value -> value.outcome() == outcome).count();
    }
}
