package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.StepInterruptionKind;
import io.paperagent.v2.persistence.StepPauseRequest;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        ProductActiveStepReplanMarkerReader.class,
        ProductActiveStepReplanCodec.class,
        ProductStepInterruptionMarkerReader.class,
        ProductStepCompletionMarkerReader.class,
        ProductEffectOutcomeMarkerReader.class,
        ProductReceiptMarkerReader.class,
        ProductReceiptEffectIntentMarkerReader.class,
        ProductStepActivationCodec.class,
        ProductStepInterruptionCodec.class,
        ProductStepCompletionCodec.class,
        ProductEffectIntentCodec.class,
        ProductEffectOutcomeCodec.class,
        ProductReceiptCodec.class,
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
    @jakarta.annotation.Resource
    private ProductStepInterruptionJpaRepository interruptions;
    @jakarta.annotation.Resource
    private ProductStepInterruptionCodec interruptionCodec;
    @jakarta.annotation.Resource
    private PlatformTransactionManager transactionManager;

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

    @Test
    void inspectionRacingCommittedInterruptionSeesTerminalCutNotHybrid()
            throws Exception {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.workspace(
                        "terminal-race", "task-terminal-race");
        ProductStepActivationTestFixtures.seedH0(
                bootstrap, "owner", "token-terminal-race", 1,
                bootstraps, bootstrapCodec, leases, starts, startCodec);
        StepActivationRequest request =
                ProductStepActivationTestFixtures.request(
                        bootstrap, "token-terminal-race", 1,
                        "activation-terminal-race");
        PersistedStepActivation activation = saveActivation(request);
        ProductStepInterruptionEntity terminal =
                interruption(bootstrap, activation);
        long rows = authorityRows();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> writer = pool.submit(() -> {
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            bootstraps.lockByPlanId(
                                    bootstrap.plan().id().value())
                                    .orElseThrow();
                            locked.countDown();
                            try {
                                commit.await();
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(exception);
                            }
                            interruptions.saveAndFlush(terminal);
                        });
                return null;
            });
            locked.await();
            Future<io.paperagent.v2.persistence.PersistenceResult<
                    StepRecoverySnapshot>> reader = pool.submit(
                    () -> adapter.inspect(bootstrap.plan().id()));
            commit.countDown();
            writer.get();
            var inspected = reader.get();
            assertEquals(PersistenceOutcome.REJECTED, inspected.outcome());
            assertEquals(PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE,
                    inspected.failure().orElseThrow().code());
            assertEquals("stepRecovery",
                    inspected.failure().orElseThrow().path());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(rows + 1, authorityRows());
    }

    private PersistedStepActivation saveActivation(
            StepActivationRequest request) {
        PersistedStepActivation result = new PersistedStepActivation(
                request.planId(), request.stepId(), "owner", 1,
                request.activationEvent(), new VersionedCheckpoint(
                        3, request.activatedCheckpoint()));
        var target = result.activatedCheckpoint().checkpoint();
        activations.saveAndFlush(new ProductStepActivationEntity(
                request.planId().value(), request.stepId().value(),
                request.activationEvent().id().value(),
                request.expectedRevisionId().value(),
                request.expectedRevisionNumber(),
                target.revisionId().value(), target.revisionNumber(),
                2, 3, 1, 2, "owner", 1,
                activationCodec.encodeRequest(request),
                activationCodec.encodeResult(result),
                ProductStepActivationTestFixtures.NOW.plusSeconds(1)));
        return result;
    }

    private ProductStepInterruptionEntity interruption(
            PersistedPlanBootstrap bootstrap,
            PersistedStepActivation activation) {
        Checkpoint active = activation.activatedCheckpoint().checkpoint();
        Map<io.paperagent.v2.contracts.PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(active.stepStates());
        states.put(activation.stepId(), StepExecutionState.PAUSED);
        EventEnvelope event = new EventEnvelope(
                new EventId("interruption-terminal-race"),
                bootstrap.taskFrame().id(), bootstrap.plan().id(), 3,
                ProductStepActivationTestFixtures.NOW.plusSeconds(2),
                new EventType("STEP_PAUSED"),
                Optional.of(activation.activationEvent().id()),
                "interruption-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint checkpoint = new Checkpoint(
                active.taskFrameId(), active.planId(), active.revisionId(),
                active.revisionNumber(), 3, PlanExecutionState.PAUSED,
                states, active.receiptReferences(),
                active.createdAt().plusSeconds(1));
        StepPauseRequest request = new StepPauseRequest(
                bootstrap.plan().id(), "token-terminal-race", 1,
                active.revisionId(), active.revisionNumber(), 3, 2,
                activation.stepId(), event, checkpoint);
        PersistedStepInterruption result = new PersistedStepInterruption(
                request.planId(), request.stepId(),
                StepInterruptionKind.PAUSE, "owner", 1, event,
                new VersionedCheckpoint(4, checkpoint));
        return new ProductStepInterruptionEntity(
                request.planId().value(), request.stepId().value(),
                event.id().value(), StepInterruptionKind.PAUSE.name(),
                active.revisionId().value(), active.revisionNumber(),
                checkpoint.revisionId().value(),
                checkpoint.revisionNumber(), 3, 4, 2, 3, "owner", 1,
                interruptionCodec.encodeRequest(
                        ProductStepInterruptionCodec.Candidate.from(
                                StepInterruptionKind.PAUSE, request)),
                interruptionCodec.encodeResult(result),
                ProductStepActivationTestFixtures.NOW.plusSeconds(2));
    }

    private long authorityRows() {
        return bootstraps.count() + starts.count() + contexts.count()
                + activations.count() + leases.count()
                + interruptions.count();
    }
}
