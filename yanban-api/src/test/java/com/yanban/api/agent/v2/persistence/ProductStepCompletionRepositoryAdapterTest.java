package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistedStepRecoverySucceeded;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2step_completion_behavior;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductStepCompletionRepositoryAdapter.class,
        ProductStepCompletionTransactions.class,
        ProductStepActivationRepositoryAdapter.class,
        ProductStepActivationTransactions.class,
        ProductStepCompletionMarkerReader.class,
        ProductStepCompletionCodec.class,
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
        ProductStepRecoveryTransactions.class,
        ProductActiveStepReplanMarkerReader.class,
        ProductActiveStepReplanCodec.class,
        ProductStepInterruptionMarkerReader.class,
        ProductStepInterruptionCodec.class,
        ProductStepRecoveryRepositoryAdapter.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductStepActivationCodec.class,
        ProductStepCompletionRepositoryAdapterTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductStepCompletionRepositoryAdapterTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        MutableTime completionTime() {
            return new MutableTime();
        }
    }

    static final class MutableTime implements ProductLeaseTimeSource,
            ProductEffectOutcomeTimeSource, ProductReceiptTimeSource {
        final AtomicReference<Instant> now = new AtomicReference<>(
                ProductStepCompletionTestFixtures.NOW);
        final AtomicInteger observations = new AtomicInteger();
        volatile boolean fail;

        @Override
        public Instant observe() {
            if (fail) {
                throw new AssertionError("time must not be observed");
            }
            observations.incrementAndGet();
            return now.get();
        }
    }

    @jakarta.annotation.Resource
    private ProductStepCompletionRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductStepActivationRepositoryAdapter activationAdapter;
    @jakarta.annotation.Resource
    private ProductStepRecoveryRepositoryAdapter recoveryAdapter;
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
    @jakarta.annotation.Resource
    private MutableTime time;
    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    private ProductEffectIntentTestFixtures.Scenario scenario;

    @BeforeEach
    void reset() {
        evidence.deleteAll();
        completions.deleteAll();
        results.deleteAll();
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
                "plan-completion", "task-completion",
                "owner-completion", "token-completion", 1,
                bootstraps, bootstrapCodec, leases, starts, startCodec,
                activations, activationCodec);
        time.now.set(ProductStepCompletionTestFixtures.NOW);
        time.fail = false;
        time.observations.set(0);
    }

    @Test
    void effectFreeCompletionAppliesAndReplaysPermanently() {
        StepCompletionRequest request = request("completion-free", List.of());
        var applied = applied(adapter.complete(request));
        assertEquals(1, completions.count());
        assertEquals(0, evidence.count());
        time.fail = true;
        jdbc.update("DELETE FROM agent_v2_plan_leases");
        assertEquals(applied, replayed(adapter.complete(request)));
    }

    @Test
    void persistedTwoStepLifecycleRecoversReadyActiveAndSucceeded() {
        StepCompletionRequest completeA =
                request("completion-step-a", List.of());
        var completedA = applied(adapter.complete(completeA));

        var readyResult = recoveryAdapter.inspect(
                scenario.bootstrap().plan().id());
        assertEquals(PersistenceOutcome.FOUND, readyResult.outcome());
        var ready = (PersistedStepRecoveryReady)
                readyResult.value().orElseThrow();
        assertEquals(new PlanStepId("step-b"), ready.readyStepId());
        assertEquals(4, ready.checkpoint().version());
        assertEquals(3,
                ready.checkpoint().checkpoint().lastEventSequence());

        StepActivationRequest activateB = activateB(ready);
        var activatedB = applied(activationAdapter.activate(activateB));
        var activeResult = recoveryAdapter.inspect(
                scenario.bootstrap().plan().id());
        assertEquals(
                PersistenceOutcome.FOUND, activeResult.outcome(),
                activeResult.toString());
        var active = (PersistedStepRecoveryActive)
                activeResult.value().orElseThrow();
        assertEquals(new PlanStepId("step-b"),
                active.activation().stepId());
        assertEquals(5, active.checkpoint().version());
        assertEquals(4,
                active.checkpoint().checkpoint().lastEventSequence());

        StepCompletionRequest completeB = completeB(active);
        var completedB = applied(adapter.complete(completeB));
        var succeededResult = recoveryAdapter.inspect(
                scenario.bootstrap().plan().id());
        assertEquals(PersistenceOutcome.FOUND, succeededResult.outcome());
        var succeeded = (PersistedStepRecoverySucceeded)
                succeededResult.value().orElseThrow();
        assertEquals(6, succeeded.checkpoint().version());
        assertEquals(5,
                succeeded.checkpoint().checkpoint().lastEventSequence());
        assertEquals(PlanExecutionState.SUCCEEDED,
                succeeded.checkpoint().checkpoint().planState());
        assertEquals(2,
                succeeded.plan().latestRevision().completedFacts().size());

        jdbc.update("DELETE FROM agent_v2_plan_leases");
        assertEquals(activatedB,
                replayed(activationAdapter.activate(activateB)));
        assertEquals(completedA, replayed(adapter.complete(completeA)));
        assertEquals(completedB, replayed(adapter.complete(completeB)));
    }

    @Test
    void concurrentReadyStepActivationProducesOneAuthoritativeResult()
            throws Exception {
        applied(adapter.complete(request("completion-step-a", List.of())));
        var ready = (PersistedStepRecoveryReady) recoveryAdapter.inspect(
                scenario.bootstrap().plan().id()).value().orElseThrow();
        StepActivationRequest activateB = activateB(ready);
        CountDownLatch waiting = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var contenders = Executors.newFixedThreadPool(2);
        try {
            var first = contenders.submit(() -> {
                waiting.countDown();
                start.await();
                return activationAdapter.activate(activateB);
            });
            var second = contenders.submit(() -> {
                waiting.countDown();
                start.await();
                return activationAdapter.activate(activateB);
            });
            waiting.await();
            start.countDown();
            var results = List.of(first.get(), second.get());

            assertEquals(1, results.stream()
                    .filter(result -> result.outcome()
                            == PersistenceOutcome.APPLIED)
                    .count());
            assertEquals(1, results.stream()
                    .filter(result -> result.outcome()
                            == PersistenceOutcome.REPLAYED)
                    .count());
            assertEquals(2, activations.count());
            assertEquals(1, activations.findAllByPlanId(
                            scenario.bootstrap().plan().id().value()).stream()
                    .filter(row -> row.stepId().equals("step-b"))
                    .count());
        } finally {
            contenders.shutdownNow();
        }
    }

    private StepActivationRequest activateB(PersistedStepRecoveryReady ready) {
        PlanStepId step = ready.readyStepId();
        Checkpoint source = ready.checkpoint().checkpoint();
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(source.stepStates());
        states.put(step, StepExecutionState.ACTIVE);
        EventEnvelope event = new EventEnvelope(
                new EventId("activation-step-b"),
                ready.taskFrame().id(), ready.planId(),
                source.lastEventSequence() + 1,
                ProductStepCompletionTestFixtures.NOW.plusSeconds(3),
                new EventType("STEP_ACTIVATED"),
                Optional.of(new EventId("completion-step-a")),
                "activation-b-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint checkpoint = new Checkpoint(
                source.taskFrameId(), source.planId(), source.revisionId(),
                source.revisionNumber(), event.sequence(),
                PlanExecutionState.ACTIVE, states,
                source.receiptReferences(),
                ProductStepCompletionTestFixtures.NOW.plusSeconds(3));
        return new StepActivationRequest(
                ready.planId(), "token-completion", 1,
                ready.plan().latestRevision().id(),
                ready.plan().latestRevision().number(),
                ready.checkpoint().version(), source.lastEventSequence(),
                step, event, checkpoint);
    }

    private StepCompletionRequest completeB(PersistedStepRecoveryActive active) {
        Checkpoint source = active.checkpoint().checkpoint();
        Plan current = active.plan();
        PlanRevision previous = current.latestRevision();
        PlanStepId step = active.activation().stepId();
        CompletionFact fact = new CompletionFact(
                step, "outcome-hash-b",
                ProductStepCompletionTestFixtures.NOW.plusSeconds(4),
                List.of());
        Map<PlanStepId, CompletionFact> facts =
                new LinkedHashMap<>(previous.completedFacts());
        facts.put(step, fact);
        PlanRevision completed = new PlanRevision(
                new PlanRevisionId("revision-completed-step-b"),
                previous.taskFrameId(), previous.number() + 1,
                Optional.of(previous.id()), "step b completed",
                ProductStepCompletionTestFixtures.NOW.plusSeconds(4),
                previous.steps(), facts);
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(source.stepStates());
        states.put(step, StepExecutionState.SUCCEEDED);
        EventEnvelope event = new EventEnvelope(
                new EventId("completion-step-b"),
                active.taskFrame().id(), active.planId(),
                source.lastEventSequence() + 1,
                ProductStepCompletionTestFixtures.NOW.plusSeconds(4),
                new EventType("STEP_COMPLETED"),
                Optional.of(active.activation().activationEvent().id()),
                "completion-b-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint checkpoint = new Checkpoint(
                source.taskFrameId(), source.planId(), completed.id(),
                completed.number(), event.sequence(),
                PlanExecutionState.SUCCEEDED, states,
                source.receiptReferences(),
                ProductStepCompletionTestFixtures.NOW.plusSeconds(4));
        return new StepCompletionRequest(
                active.planId(), "token-completion", 1,
                previous.id(), previous.number(),
                active.checkpoint().version(),
                source.lastEventSequence(), step, fact, event,
                completed, checkpoint);
    }

    @Test
    void effectBackedCompletionRequiresEveryCanonicalOutcomeInToolOrder() {
        var first = ProductEffectIntentTestFixtures.request(
                scenario, "tool-b", "token-completion", 1);
        var second = ProductEffectIntentTestFixtures.request(
                scenario, "tool-a", "token-completion", 1);
        applied(intentAdapter.persist(first));
        applied(intentAdapter.persist(second));
        failure(adapter.complete(request("completion-missing", List.of())),
                PersistenceErrorCode.STEP_COMPLETION_NOT_ELIGIBLE,
                "stepCompletion.effectOutcomes");
        record(first, "receipt-b");
        record(second, "receipt-a");
        StepCompletionRequest complete = request(
                "completion-effects",
                List.of(new ReceiptId("receipt-a"),
                        new ReceiptId("receipt-b")));
        applied(adapter.complete(complete));
        assertEquals(List.of("tool-a", "tool-b"),
                evidence.findAllByCompletionEventIdOrderByOrdinal(
                                "completion-effects").stream()
                        .map(ProductStepCompletionEvidenceEntity::toolCallId)
                        .toList());
    }

    @Test
    void staleLeaseAndConflictingReplayUseStablePaths() {
        StepCompletionRequest request =
                request("completion-conflict", List.of());
        applied(adapter.complete(request));
        StepCompletionRequest changed = new StepCompletionRequest(
                request.planId(), request.leaseToken(),
                request.fencingToken(), request.expectedRevisionId(),
                request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(),
                request.expectedEventHeadSequence(), request.stepId(),
                new io.paperagent.v2.contracts.CompletionFact(
                        request.stepId(), "changed",
                        request.completionFact().completedAt(), List.of()),
                request.completionEvent(), request.completedRevision(),
                request.completedCheckpoint());
        failure(adapter.complete(changed),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.completionEvent.id");

        reset();
        StepCompletionRequest wrongToken =
                request("completion-wrong-token", List.of());
        wrongToken = new StepCompletionRequest(
                wrongToken.planId(), "wrong", wrongToken.fencingToken(),
                wrongToken.expectedRevisionId(),
                wrongToken.expectedRevisionNumber(),
                wrongToken.expectedCheckpointVersion(),
                wrongToken.expectedEventHeadSequence(), wrongToken.stepId(),
                wrongToken.completionFact(), wrongToken.completionEvent(),
                wrongToken.completedRevision(),
                wrongToken.completedCheckpoint());
        failure(adapter.complete(wrongToken),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");
        assertEquals(0, completions.count());
    }

    @Test
    void corruptOutcomeAndMarkerFailClosedWithoutLeakingPayload() {
        var intent = ProductEffectIntentTestFixtures.request(
                scenario, "tool-corrupt", "token-completion", 1);
        applied(intentAdapter.persist(intent));
        record(intent, "receipt-corrupt");
        jdbc.update("""
                UPDATE agent_v2_effect_results
                   SET result_sha256 = ?
                 WHERE tool_call_id = ?
                """, "0".repeat(64), "tool-corrupt");
        failure(adapter.complete(request(
                        "completion-corrupt",
                        List.of(new ReceiptId("receipt-corrupt")))),
                PersistenceErrorCode.STEP_COMPLETION_PARTIAL_STATE,
                "stepCompletion");
        assertEquals(0, completions.count());
        assertEquals(0, evidence.count());
    }

    @Test
    void intentRelationalStepMismatchFailsBeforeApplyAndCannotCorruptReplay() {
        var before = ProductEffectIntentTestFixtures.request(
                scenario, "tool-misbound-before", "token-completion", 1);
        applied(intentAdapter.persist(before));
        assertEquals(1, jdbc.update("""
                UPDATE agent_v2_effect_intents
                   SET step_id = ?
                 WHERE tool_call_id = ?
                """, "other-step", "tool-misbound-before"));
        failure(adapter.complete(request(
                        "completion-misbound-before", List.of())),
                PersistenceErrorCode.STEP_COMPLETION_PARTIAL_STATE,
                "stepCompletion");
        assertEquals(0, completions.count());

        reset();
        var after = ProductEffectIntentTestFixtures.request(
                scenario, "tool-misbound-after", "token-completion", 1);
        applied(intentAdapter.persist(after));
        record(after, "receipt-misbound-after");
        StepCompletionRequest complete = request(
                "completion-misbound-after",
                List.of(new ReceiptId("receipt-misbound-after")));
        applied(adapter.complete(complete));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE agent_v2_effect_intents
                   SET step_id = ?
                 WHERE tool_call_id = ?
                """, "other-step", "tool-misbound-after"));
        assertEquals(1, jdbc.update("""
                UPDATE agent_v2_effect_intents
                   SET result_sha256 = ?
                 WHERE tool_call_id = ?
                """, "0".repeat(64), "tool-misbound-after"));
        failure(adapter.complete(complete),
                PersistenceErrorCode.STEP_COMPLETION_PARTIAL_STATE,
                "stepCompletion");
        assertEquals(1, completions.count());
        assertEquals(1, evidence.count());
    }

    @Test
    void stableSourceEventPlanAndCheckpointFailuresKeepExactPaths() {
        StepCompletionRequest base =
                request("completion-validation", List.of());
        failure(adapter.complete(new StepCompletionRequest(
                        base.planId(), base.leaseToken(),
                        base.fencingToken(),
                        new PlanRevisionId("stale-revision"),
                        base.expectedRevisionNumber(),
                        base.expectedCheckpointVersion(),
                        base.expectedEventHeadSequence(), base.stepId(),
                        base.completionFact(), base.completionEvent(),
                        base.completedRevision(),
                        base.completedCheckpoint())),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedRevisionId");

        var event = base.completionEvent();
        var wrongTaskEvent = new io.paperagent.v2.contracts.EventEnvelope(
                event.id(), new TaskFrameId("wrong-task"),
                event.planId(), event.sequence(), event.occurredAt(),
                event.type(), event.causationId(), event.correlationId(),
                event.payload());
        failure(adapter.complete(new StepCompletionRequest(
                        base.planId(), base.leaseToken(),
                        base.fencingToken(), base.expectedRevisionId(),
                        base.expectedRevisionNumber(),
                        base.expectedCheckpointVersion(),
                        base.expectedEventHeadSequence(), base.stepId(),
                        base.completionFact(), wrongTaskEvent,
                        base.completedRevision(),
                        base.completedCheckpoint())),
                PersistenceErrorCode.TASK_FRAME_MISMATCH,
                "request.completionEvent.taskFrameId");

        PlanRevision invalidPlan = new PlanRevision(
                base.completedRevision().id(),
                base.completedRevision().taskFrameId(),
                base.completedRevision().number(),
                base.completedRevision().parentRevisionId(),
                base.completedRevision().reason(),
                base.completedRevision().createdAt(),
                base.completedRevision().steps(), java.util.Map.of());
        failure(adapter.complete(new StepCompletionRequest(
                        base.planId(), base.leaseToken(),
                        base.fencingToken(), base.expectedRevisionId(),
                        base.expectedRevisionNumber(),
                        base.expectedCheckpointVersion(),
                        base.expectedEventHeadSequence(), base.stepId(),
                        base.completionFact(), base.completionEvent(),
                        invalidPlan, base.completedCheckpoint())),
                PersistenceErrorCode.PLAN_VALIDATION_FAILED,
                "request.completedRevision");

        var checkpoint = base.completedCheckpoint();
        var wrongHead = new io.paperagent.v2.contracts.Checkpoint(
                checkpoint.taskFrameId(), checkpoint.planId(),
                checkpoint.revisionId(), checkpoint.revisionNumber(),
                4, checkpoint.planState(), checkpoint.stepStates(),
                checkpoint.receiptReferences(), checkpoint.createdAt());
        failure(adapter.complete(new StepCompletionRequest(
                        base.planId(), base.leaseToken(),
                        base.fencingToken(), base.expectedRevisionId(),
                        base.expectedRevisionNumber(),
                        base.expectedCheckpointVersion(),
                        base.expectedEventHeadSequence(), base.stepId(),
                        base.completionFact(), base.completionEvent(),
                        base.completedRevision(), wrongHead)),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.completedCheckpoint.lastEventSequence");
        assertEquals(0, completions.count());
    }

    private StepCompletionRequest request(
            String event, List<ReceiptId> receiptIds) {
        return ProductStepCompletionTestFixtures.request(
                scenario, "token-completion", 1, event, receiptIds);
    }

    private void record(
            io.paperagent.v2.persistence.EffectIntentRequest intent,
            String receiptId) {
        applied(outcomeAdapter.recordResult(new EffectResultRequest(
                ProductEffectOutcomeCodecTest.receipt(
                        ReceiptStatus.FAILURE, receiptId,
                        intent.intent().toolCallId().value()),
                "token-completion", 1)));
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
