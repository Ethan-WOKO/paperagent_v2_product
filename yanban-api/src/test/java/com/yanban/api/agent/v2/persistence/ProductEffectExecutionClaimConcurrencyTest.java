package com.yanban.api.agent.v2.persistence;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductEffectExecutionClaimConcurrencyTest {
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
