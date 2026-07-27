package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.persistence.ExecutionStartRecoverySnapshot;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;
import io.paperagent.v2.persistence.PersistedExecutionStartReady;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2execution_recovery_concurrency;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductExecutionStartRecoveryRepositoryAdapter.class,
        ProductExecutionStartRecoveryTransactions.class,
        ProductExecutionStartRepositoryAdapter.class,
        ProductExecutionStartTransactions.class,
        ProductExecutionStartCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartRecoveryRepositoryConcurrencyTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductExecutionStartRecoveryRepositoryConcurrencyTest {
    private static final int INSPECTIONS = 24;

    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        ProductExecutionStartTimeSource timeSource() {
            return () -> ProductExecutionStartTestFixtures.NOW.plusNanos(999);
        }
    }

    @jakarta.annotation.Resource
    private ProductExecutionStartRecoveryRepositoryAdapter recovery;
    @jakarta.annotation.Resource
    private ProductExecutionStartRepositoryAdapter startAdapter;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapJpaRepository bootstraps;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapCodec bootstrapCodec;
    @jakarta.annotation.Resource
    private ProductLeaseJpaRepository leases;
    @jakarta.annotation.Resource
    private ProductExecutionStartJpaRepository starts;

    private ExecutorService pool;

    @BeforeEach
    void reset() {
        pool = Executors.newFixedThreadPool(INSPECTIONS + 1);
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
        starts.flush();
        leases.flush();
        bootstraps.flush();
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void concurrentAtomicStartInspectionSeesOnlyReadyOrCommitted()
            throws Exception {
        Scenario scenario = seed();
        CountDownLatch ready = new CountDownLatch(INSPECTIONS + 1);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PersistenceResult<ExecutionStartRecoverySnapshot>>>
                inspections = new ArrayList<>();
        for (int index = 0; index < INSPECTIONS; index++) {
            inspections.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return recovery.inspect(scenario.bootstrap().plan().id());
            }));
        }
        Future<PersistenceResult<PersistedExecutionStart>> start =
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return startAdapter.start(scenario.request());
                });
        ready.await();
        go.countDown();

        assertEquals(PersistenceOutcome.APPLIED, start.get().outcome());
        long readyCount = 0;
        long committedCount = 0;
        for (Future<PersistenceResult<ExecutionStartRecoverySnapshot>>
                inspection : inspections) {
            PersistenceResult<ExecutionStartRecoverySnapshot> result =
                    inspection.get();
            assertEquals(PersistenceOutcome.FOUND, result.outcome());
            ExecutionStartRecoverySnapshot snapshot =
                    result.value().orElseThrow();
            assertTrue(snapshot instanceof PersistedExecutionStartReady
                    || snapshot instanceof PersistedExecutionStartCommitted);
            if (snapshot instanceof PersistedExecutionStartReady) {
                readyCount++;
            } else {
                committedCount++;
            }
        }
        assertEquals(INSPECTIONS, readyCount + committedCount);
        System.out.printf(
                "Recovery inspection race outcomes: ready=%d, committed=%d%n",
                readyCount,
                committedCount);
        assertEquals(1, bootstraps.count());
        assertEquals(1, leases.count());
        assertEquals(1, starts.count());

        ExecutionStartRecoverySnapshot finalSnapshot =
                recovery.inspect(scenario.bootstrap().plan().id())
                        .value().orElseThrow();
        assertTrue(finalSnapshot instanceof PersistedExecutionStartCommitted);
    }

    private Scenario seed() {
        PersistedPlanBootstrap bootstrap =
                ProductExecutionStartTestFixtures.bootstrap("plan-a", "task-a");
        ProductPlanBootstrapCodec.EncodedPayload encoded =
                bootstrapCodec.encode(bootstrap);
        bootstraps.saveAndFlush(new ProductPlanBootstrapEntity(
                "plan-a",
                "task-a",
                encoded.formatVersion(),
                encoded.sha256(),
                encoded.json(),
                ProductExecutionStartTestFixtures.NOW));
        Instant now = ProductExecutionStartTestFixtures.NOW;
        leases.saveAndFlush(new ProductLeaseEntity(
                "plan-a",
                1,
                "owner-a",
                "token-a",
                now.minusSeconds(1),
                now.plusSeconds(60)));
        return new Scenario(
                bootstrap,
                ProductExecutionStartTestFixtures.request(
                        bootstrap, "token-a", 1, "event-a"));
    }

    private record Scenario(
            PersistedPlanBootstrap bootstrap,
            ExecutionStartRequest request) {
    }
}
