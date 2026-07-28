package com.yanban.api.agent.v2.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2effect_claim_concurrency;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductEffectExecutionClaimRepository.class,
        ProductEffectExecutionClaimTransactions.class,
        ProductEffectOutcomeCodec.class,
        ProductEffectOutcomeMarkerReader.class,
        ProductEffectIntentRepositoryAdapter.class,
        ProductEffectIntentTransactions.class,
        ProductEffectIntentCodec.class,
        ProductReceiptCodec.class,
        ProductReceiptMarkerReader.class,
        ProductStepRecoveryTransactions.class,
        ProductActiveStepReplanMarkerReader.class,
        ProductActiveStepReplanCodec.class,
        ProductStepInterruptionMarkerReader.class,
        ProductStepInterruptionCodec.class,
        ProductStepCompletionMarkerReader.class,
        ProductStepCompletionCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartCodec.class,
        ProductStepActivationCodec.class,
        ProductEffectExecutionClaimRepositoryTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductEffectExecutionClaimConcurrencyTest {
    @jakarta.annotation.Resource
    private org.springframework.context.ApplicationContext context;

    @Test
    void concurrentApplicationCallsExecuteOneRealTaskAndReplayOneOutcome()
            throws Exception {
        ProductEffectExecutionClaimRepositoryTest harness =
                ProductEffectExecutionClaimRepositoryTest.harness(context);
        harness.clearDatabase();
        var scenario = harness.scenario("application-race");
        var invocations = new AtomicInteger();
        var request = harness.request(
                scenario, scenario.lease().expiresAt().minusSeconds(1),
                invocations);
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                gate.await();
                return harness.repository.execute(request);
            });
            var second = pool.submit(() -> {
                gate.await();
                return harness.repository.execute(request);
            });
            gate.countDown();
            var outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS));
            assertEquals(1, invocations.get());
            assertEquals(1, outcomes.stream()
                    .filter(ProductEffectExecutionClaimResult::replayed)
                    .count());
            assertEquals(outcomes.get(0).result(), outcomes.get(1).result());
            assertEquals(1, harness.literatureTasks.count());
            assertEquals(1, harness.claimRows.count());
            assertEquals(1, harness.receiptRows.count());
            assertEquals(1, harness.resultRows.count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoContendersCanCommitOnlyOneToolCallClaim() throws Exception {
        String url = ProductEffectExecutionClaimRepositoryTest.schema(
                "claim_concurrency");
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute(
                    ProductEffectExecutionClaimRepositoryTest.intent(
                            "tool-race"));
        }
        var gate = new CountDownLatch(1);
        var committed = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        gate.await();
                        try (var connection =
                                     DriverManager.getConnection(url, "sa", "");
                             var statement = connection.createStatement()) {
                            statement.execute(
                                    ProductEffectExecutionClaimRepositoryTest
                                            .claim("tool-race"));
                            committed.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // The unique-key loser is the expected replay contender.
                    }
                });
            }
            gate.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, committed.get());
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            assertEquals(1,
                    ProductEffectExecutionClaimRepositoryTest.count(
                            statement));
        }
    }
}
