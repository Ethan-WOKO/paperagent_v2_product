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
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepCompletionRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2step_completion_race;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductStepCompletionRepositoryAdapter.class,
        ProductStepCompletionTransactions.class,
        ProductStepCompletionCodec.class,
        ProductStepInterruptionRepositoryAdapter.class,
        ProductStepInterruptionTransactions.class,
        ProductStepInterruptionCodec.class,
        ProductStepRecoveryTransactions.class,
        ProductEffectOutcomeMarkerReader.class,
        ProductEffectOutcomeCodec.class,
        ProductReceiptMarkerReader.class,
        ProductReceiptCodec.class,
        ProductEffectIntentCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductStepActivationCodec.class,
        ProductStepCompletionRepositoryConcurrencyTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductStepCompletionRepositoryConcurrencyTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        RaceTime raceTime() {
            return new RaceTime();
        }
    }

    static final class RaceTime implements ProductLeaseTimeSource,
            ProductEffectOutcomeTimeSource {
        final AtomicReference<Instant> now = new AtomicReference<>(
                ProductStepCompletionTestFixtures.NOW);

        @Override
        public Instant observe() {
            return now.get();
        }
    }

    @jakarta.annotation.Resource
    private ProductStepCompletionRepositoryAdapter completionAdapter;
    @jakarta.annotation.Resource
    private ProductStepInterruptionRepositoryAdapter interruptionAdapter;
    @jakarta.annotation.Resource
    private ProductStepCompletionJpaRepository completions;
    @jakarta.annotation.Resource
    private ProductStepCompletionEvidenceJpaRepository evidence;
    @jakarta.annotation.Resource
    private ProductEffectOutcomeResultJpaRepository results;
    @jakarta.annotation.Resource
    private ProductEffectOutcomeProgressJpaRepository progress;
    @jakarta.annotation.Resource
    private ProductReceiptJpaRepository receipts;
    @jakarta.annotation.Resource
    private ProductEffectIntentJpaRepository intents;
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

    private final ExecutorService pool = Executors.newFixedThreadPool(24);
    private ProductEffectIntentTestFixtures.Scenario scenario;

    @BeforeEach
    void reset() {
        evidence.deleteAll();
        completions.deleteAll();
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
        scenario = ProductEffectIntentTestFixtures.seed(
                "plan-completion-race", "task-completion-race",
                "owner-race", "token-race", 1,
                bootstraps, bootstrapCodec, leases, starts, startCodec,
                activations, activationCodec);
    }

    @AfterEach
    void stopPool() throws Exception {
        pool.shutdownNow();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    void twentyFourExactCallsApplyOnceThenReplay() throws Exception {
        StepCompletionRequest request = completion("completion-race");
        List<PersistenceResult<?>> raced = race(24,
                () -> completionAdapter.complete(request));
        assertEquals(1, count(raced, PersistenceOutcome.APPLIED));
        assertEquals(23, count(raced, PersistenceOutcome.REPLAYED));
        assertEquals(1, completions.count());
    }

    @Test
    void conflictingIdentityPreservesOneImmutableWinner() throws Exception {
        StepCompletionRequest left = completion("completion-conflict");
        StepCompletionRequest originalRight =
                ProductStepCompletionTestFixtures.request(
                        scenario, "token-race", 1,
                        "completion-conflict", List.of());
        StepCompletionRequest right = new StepCompletionRequest(
                originalRight.planId(), originalRight.leaseToken(),
                originalRight.fencingToken(),
                originalRight.expectedRevisionId(),
                originalRight.expectedRevisionNumber(),
                originalRight.expectedCheckpointVersion(),
                originalRight.expectedEventHeadSequence(),
                originalRight.stepId(),
                new io.paperagent.v2.contracts.CompletionFact(
                        originalRight.stepId(), "different",
                        originalRight.completionFact().completedAt(),
                        List.of()),
                originalRight.completionEvent(),
                originalRight.completedRevision(),
                originalRight.completedCheckpoint());
        List<PersistenceResult<?>> raced = race(List.of(
                () -> completionAdapter.complete(left),
                () -> completionAdapter.complete(right)));
        assertEquals(1, count(raced, PersistenceOutcome.APPLIED));
        assertEquals(1, count(raced, PersistenceOutcome.REJECTED));
        assertEquals(1, completions.count());
    }

    @Test
    void completionAndInterruptionCannotBothCommitInEitherOrder()
            throws Exception {
        List<PersistenceResult<?>> first = race(List.of(
                () -> completionAdapter.complete(
                        completion("completion-vs-interruption")),
                () -> interruptionAdapter.cancel(
                        cancellation("cancel-vs-completion"))));
        assertEquals(1, count(first, PersistenceOutcome.APPLIED));
        assertEquals(1, count(first, PersistenceOutcome.REJECTED));
        assertEquals(1, completions.count() + interruptions.count());
        assertTrue(completions.count() == 0 || interruptions.count() == 0);
    }

    private StepCompletionRequest completion(String id) {
        return ProductStepCompletionTestFixtures.request(
                scenario, "token-race", 1, id, List.of());
    }

    private StepCancelRequest cancellation(String id) {
        var activation = scenario.persistedActivation();
        Checkpoint active = activation.activatedCheckpoint().checkpoint();
        Map<io.paperagent.v2.contracts.PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(active.stepStates());
        states.put(activation.stepId(), StepExecutionState.CANCELLED);
        EventEnvelope event = new EventEnvelope(
                new EventId(id), scenario.bootstrap().taskFrame().id(),
                scenario.bootstrap().plan().id(), 3,
                ProductStepCompletionTestFixtures.NOW.plusSeconds(2),
                new EventType("STEP_CANCELLED"),
                Optional.of(activation.activationEvent().id()),
                "cancel-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint checkpoint = new Checkpoint(
                active.taskFrameId(), active.planId(), active.revisionId(),
                active.revisionNumber(), 3, PlanExecutionState.CANCELLED,
                states, active.receiptReferences(),
                ProductStepCompletionTestFixtures.NOW.plusSeconds(2));
        return new StepCancelRequest(
                scenario.bootstrap().plan().id(), "token-race", 1,
                scenario.bootstrap().plan().latestRevision().id(),
                scenario.bootstrap().plan().latestRevision().number(),
                3, 2, activation.stepId(), event, checkpoint);
    }

    private List<PersistenceResult<?>> race(
            int count, Callable<? extends PersistenceResult<?>> call)
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
            List<PersistenceResult<?>> results, PersistenceOutcome outcome) {
        return results.stream()
                .filter(value -> value.outcome() == outcome).count();
    }
}
