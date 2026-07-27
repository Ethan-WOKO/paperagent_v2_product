package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2step_recovery_concurrency;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductStepRecoveryRepositoryAdapter.class,
        ProductStepRecoveryTransactions.class,
        ProductStepActivationCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductStepRecoveryRepositoryConcurrencyTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductStepRecoveryRepositoryConcurrencyTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @jakarta.annotation.Resource
    private ProductStepRecoveryRepositoryAdapter adapter;
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
    @jakarta.annotation.Resource
    private ProductStepActivationCodec activationCodec;

    @Test
    void concurrentReadOnlyInspectionsReturnOneEqualImmutableCut()
            throws Exception {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.workspace(
                        "concurrent", "task-concurrent");
        ProductStepActivationTestFixtures.seedH0(
                bootstrap, "owner", "token", 1,
                bootstraps, bootstrapCodec, leases, starts, startCodec);
        StepActivationRequest request =
                ProductStepActivationTestFixtures.request(
                        bootstrap, "token", 1, "activation-concurrent");
        PersistedStepActivation activation = new PersistedStepActivation(
                request.planId(), request.stepId(), "owner", 1,
                request.activationEvent(), new VersionedCheckpoint(
                        3, request.activatedCheckpoint()));
        var target = activation.activatedCheckpoint().checkpoint();
        activations.saveAndFlush(new ProductStepActivationEntity(
                request.planId().value(), request.stepId().value(),
                request.activationEvent().id().value(),
                request.expectedRevisionId().value(),
                request.expectedRevisionNumber(),
                target.revisionId().value(), target.revisionNumber(),
                2, 3, 1, 2, "owner", 1,
                activationCodec.encodeRequest(request),
                activationCodec.encodeResult(activation),
                ProductStepActivationTestFixtures.NOW.plusSeconds(1)));
        long rows = authorityRows();

        int readers = 8;
        CountDownLatch ready = new CountDownLatch(readers);
        CountDownLatch go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(readers);
        try {
            List<Future<PersistedStepRecoveryActive>> futures =
                    new ArrayList<>();
            Callable<PersistedStepRecoveryActive> inspection = () -> {
                ready.countDown();
                go.await();
                var result = adapter.inspect(bootstrap.plan().id());
                assertEquals(PersistenceOutcome.FOUND, result.outcome());
                StepRecoverySnapshot snapshot =
                        result.value().orElseThrow();
                return (PersistedStepRecoveryActive) snapshot;
            };
            for (int index = 0; index < readers; index++) {
                futures.add(pool.submit(inspection));
            }
            ready.await();
            go.countDown();
            PersistedStepRecoveryActive expected = futures.get(0).get();
            for (Future<PersistedStepRecoveryActive> future : futures) {
                assertEquals(expected, future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(rows, authorityRows());
    }

    private long authorityRows() {
        return bootstraps.count() + starts.count() + contexts.count()
                + activations.count() + leases.count();
    }
}
