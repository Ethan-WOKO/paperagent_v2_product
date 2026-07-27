package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ToolCallId;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2receipt_concurrency;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.hikari.maximum-pool-size=28"
})
@Import({
        ProductReceiptRepositoryAdapter.class,
        ProductReceiptTransactions.class,
        ProductReceiptCodec.class,
        ProductReceiptMarkerReader.class,
        ProductReceiptEffectIntentMarkerReader.class,
        ProductEffectIntentCodec.class,
        ProductReceiptRepositoryConcurrencyTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductReceiptRepositoryConcurrencyTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        ProductReceiptTimeSource receiptTime() {
            return () -> Instant.parse("2026-07-28T00:00:02Z");
        }
    }

    @jakarta.annotation.Resource
    private ProductReceiptRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductReceiptJpaRepository receipts;
    @jakarta.annotation.Resource
    private ProductReceiptToolCallClaimJpaRepository claims;

    private ExecutorService executor;

    @BeforeEach
    void reset() {
        receipts.deleteAll();
        claims.deleteAll();
        receipts.flush();
        claims.flush();
        executor = Executors.newFixedThreadPool(24);
    }

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void twentyFourExactAppendsApplyOnceAndReplayWinner() throws Exception {
        ExecutionReceipt receipt =
                ProductReceiptRepositoryAdapterTest.receipt("a", "shared");
        List<PersistenceResult<ExecutionReceipt>> results =
                race(24, () -> adapter.append(receipt));
        assertEquals(1, outcomes(results, PersistenceOutcome.APPLIED));
        assertEquals(23, outcomes(results, PersistenceOutcome.REPLAYED));
        assertEquals(1, receipts.count());
        assertEquals(1, claims.count());
        results.forEach(result -> assertEquals(
                receipt, result.value().orElseThrow()));
    }

    @Test
    void conflictingSameIdPreservesOneWinnerWithoutOrphanClaim()
            throws Exception {
        ExecutionReceipt first =
                ProductReceiptRepositoryAdapterTest.receipt("same", "tool-a");
        ExecutionReceipt second = new ExecutionReceipt(
                first.id(), new ToolCallId("tool-b"), first.status(),
                first.startedAt(), first.endedAt(), first.exitCode(),
                first.resultCode(), first.standardOutput(),
                first.standardError(), first.artifactReferences(),
                first.resultingDiff(), first.eventReferences());
        List<PersistenceResult<ExecutionReceipt>> results = race(
                24, new Alternating(first, second));
        assertEquals(1, outcomes(results, PersistenceOutcome.APPLIED));
        assertEquals(23, outcomes(results, PersistenceOutcome.REPLAYED)
                + failures(results,
                PersistenceErrorCode.CONFLICTING_REPLAY));
        assertTrue(failures(results,
                PersistenceErrorCode.CONFLICTING_REPLAY) > 0);
        assertEquals(1, receipts.count());
        assertEquals(1, claims.count());
    }

    @Test
    void differentIdsOnOneToolCallAllApplyUnderOneClaim() throws Exception {
        CountDownLatch ready = new CountDownLatch(24);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PersistenceResult<ExecutionReceipt>>> futures =
                new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String id = Integer.toString(i);
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return adapter.append(
                        ProductReceiptRepositoryAdapterTest.receipt(
                                id, "shared"));
            }));
        }
        ready.await();
        start.countDown();
        List<PersistenceResult<ExecutionReceipt>> results = new ArrayList<>();
        for (Future<PersistenceResult<ExecutionReceipt>> future : futures) {
            results.add(future.get());
        }
        assertEquals(24, outcomes(results, PersistenceOutcome.APPLIED));
        assertEquals(24, receipts.count());
        assertEquals(1, claims.count());
    }

    private List<PersistenceResult<ExecutionReceipt>> race(
            int count, Callable<PersistenceResult<ExecutionReceipt>> task)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PersistenceResult<ExecutionReceipt>>> futures =
                new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return task.call();
            }));
        }
        ready.await();
        start.countDown();
        List<PersistenceResult<ExecutionReceipt>> results = new ArrayList<>();
        for (Future<PersistenceResult<ExecutionReceipt>> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    private static long outcomes(
            List<? extends PersistenceResult<?>> results,
            PersistenceOutcome outcome) {
        return results.stream()
                .filter(result -> result.outcome() == outcome)
                .count();
    }

    private static long failures(
            List<? extends PersistenceResult<?>> results,
            PersistenceErrorCode code) {
        return results.stream()
                .filter(result -> result.failure()
                        .map(failure -> failure.code() == code)
                        .orElse(false))
                .count();
    }

    private final class Alternating
            implements Callable<PersistenceResult<ExecutionReceipt>> {
        private final ExecutionReceipt first;
        private final ExecutionReceipt second;
        private int index;

        private Alternating(
                ExecutionReceipt first, ExecutionReceipt second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public synchronized PersistenceResult<ExecutionReceipt> call() {
            return adapter.append(index++ % 2 == 0 ? first : second);
        }
    }
}
