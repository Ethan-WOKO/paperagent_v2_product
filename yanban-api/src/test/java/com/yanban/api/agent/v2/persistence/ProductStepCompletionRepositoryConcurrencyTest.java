package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EffectProgress;
import io.paperagent.v2.contracts.EffectProgressId;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.EffectProgressRequest;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistenceErrorCode;
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
        ProductStepCompletionMarkerReader.class,
        ProductStepCompletionCodec.class,
        ProductStepInterruptionRepositoryAdapter.class,
        ProductStepInterruptionTransactions.class,
        ProductStepInterruptionMarkerReader.class,
        ProductStepInterruptionCodec.class,
        ProductStepRecoveryTransactions.class,
        ProductStepRecoveryRepositoryAdapter.class,
        ProductEffectIntentRepositoryAdapter.class,
        ProductEffectIntentTransactions.class,
        ProductEffectOutcomeRepositoryAdapter.class,
        ProductEffectOutcomeTransactions.class,
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
    private ProductEffectIntentRepositoryAdapter intentAdapter;
    @jakarta.annotation.Resource
    private ProductEffectOutcomeRepositoryAdapter outcomeAdapter;
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

        resetScenario();
        applied(completionAdapter.complete(
                completion("completion-first")));
        assertEquals(PersistenceOutcome.REJECTED,
                interruptionAdapter.cancel(
                        cancellation("cancel-after-completion")).outcome());
        assertEquals(1, completions.count());
        assertEquals(0, interruptions.count());

        resetScenario();
        applied(interruptionAdapter.cancel(
                cancellation("cancel-first")));
        assertEquals(PersistenceOutcome.REJECTED,
                completionAdapter.complete(
                        completion("completion-after-cancel")).outcome());
        assertEquals(0, completions.count());
        assertEquals(1, interruptions.count());
    }

    @Test
    void completionFirstRejectsNewEffectWritesAndKeepsExactReplays() {
        EffectIntentRequest intentRequest = intent("tool-before-completion");
        applied(intentAdapter.persist(intentRequest));
        EffectProgressRequest progressRequest = progress(
                intentRequest, "progress-before-completion", 1);
        applied(outcomeAdapter.appendProgress(progressRequest));
        EffectResultRequest resultRequest = result(
                intentRequest, "receipt-before-completion");
        applied(outcomeAdapter.recordResult(resultRequest));
        StepCompletionRequest complete = ProductStepCompletionTestFixtures
                .request(scenario, "token-race", 1,
                        "completion-freezes-effects",
                        List.of(resultRequest.receipt().id()));
        applied(completionAdapter.complete(complete));

        replayed(intentAdapter.persist(intentRequest));
        replayed(outcomeAdapter.appendProgress(progressRequest));
        replayed(outcomeAdapter.recordResult(resultRequest));
        failure(intentAdapter.persist(intent("tool-after-completion")),
                PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                "effectIntent.source");
        failure(outcomeAdapter.appendProgress(progress(
                        intentRequest, "progress-after-completion", 2)),
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");
        EffectResultRequest changed = result(
                intentRequest, "receipt-after-completion");
        failure(outcomeAdapter.recordResult(changed),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.receipt.id");
        replayed(completionAdapter.complete(complete));
        assertEquals(1, intents.count());
        assertEquals(1, progress.count());
        assertEquals(1, results.count());
    }

    @Test
    void completionLockSerializesNewIntentProgressAndResult()
            throws Exception {
        List<PersistenceResult<?>> intentRace = race(List.of(
                () -> completionAdapter.complete(
                        completion("completion-vs-intent")),
                () -> intentAdapter.persist(intent("tool-raced-intent"))));
        assertEquals(1, count(intentRace, PersistenceOutcome.APPLIED));
        assertEquals(1, count(intentRace, PersistenceOutcome.REJECTED));
        assertEquals(1, completions.count() + intents.count());

        resetScenario();
        EffectIntentRequest progressIntent = intent("tool-raced-progress");
        applied(intentAdapter.persist(progressIntent));
        EffectResultRequest finalResult =
                result(progressIntent, "receipt-raced-progress");
        applied(outcomeAdapter.recordResult(finalResult));
        StepCompletionRequest progressCompletion =
                ProductStepCompletionTestFixtures.request(
                        scenario, "token-race", 1,
                        "completion-vs-progress",
                        List.of(finalResult.receipt().id()));
        List<PersistenceResult<?>> progressRace = race(List.of(
                () -> completionAdapter.complete(progressCompletion),
                () -> outcomeAdapter.appendProgress(progress(
                        progressIntent, "progress-raced", 1))));
        assertEquals(1, count(progressRace, PersistenceOutcome.APPLIED));
        assertEquals(1, count(progressRace, PersistenceOutcome.REJECTED));
        assertEquals(1, completions.count());
        assertEquals(0, progress.count());

        resetScenario();
        EffectIntentRequest resultIntent = intent("tool-raced-result");
        applied(intentAdapter.persist(resultIntent));
        EffectResultRequest racedResult =
                result(resultIntent, "receipt-raced-result");
        StepCompletionRequest resultCompletion =
                ProductStepCompletionTestFixtures.request(
                        scenario, "token-race", 1,
                        "completion-vs-result",
                        List.of(racedResult.receipt().id()));
        List<PersistenceResult<?>> resultRace = race(List.of(
                () -> completionAdapter.complete(resultCompletion),
                () -> outcomeAdapter.recordResult(racedResult)));
        assertEquals(PersistenceOutcome.APPLIED,
                resultRace.get(1).outcome());
        assertEquals(1, results.count());
        assertTrue(completions.count() == 0 || completions.count() == 1);
        if (completions.count() == 1) {
            replayed(completionAdapter.complete(resultCompletion));
            assertEquals(1, evidence.count());
        }
    }

    private StepCompletionRequest completion(String id) {
        return ProductStepCompletionTestFixtures.request(
                scenario, "token-race", 1, id, List.of());
    }

    private EffectIntentRequest intent(String toolCallId) {
        return ProductEffectIntentTestFixtures.request(
                scenario, toolCallId, "token-race", 1);
    }

    private EffectProgressRequest progress(
            EffectIntentRequest intent, String id, long sequence) {
        return new EffectProgressRequest(new EffectProgress(
                new EffectProgressId(id), intent.intent().toolCallId(),
                sequence,
                ProductStepCompletionTestFixtures.NOW.plusSeconds(sequence),
                new ObjectValue(Map.of(
                        "detail", new TextValue(id)))),
                "token-race", 1);
    }

    private EffectResultRequest result(
            EffectIntentRequest intent, String receiptId) {
        return new EffectResultRequest(
                ProductEffectOutcomeCodecTest.receipt(
                        ReceiptStatus.FAILURE, receiptId,
                        intent.intent().toolCallId().value()),
                "token-race", 1);
    }

    private void resetScenario() {
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

    private static <T> T applied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(),
                result.toString());
        return result.value().orElseThrow();
    }

    private static <T> T replayed(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.REPLAYED, result.outcome(),
                result.toString());
        return result.value().orElseThrow();
    }

    private static void failure(
            PersistenceResult<?> result, PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(),
                result.toString());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }
}
