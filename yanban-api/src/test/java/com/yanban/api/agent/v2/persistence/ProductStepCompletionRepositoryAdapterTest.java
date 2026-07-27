package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCompletionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void intentRelationalStepMismatchFailsBeforeApplyAndOnReplay() {
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
        assertEquals(1, jdbc.update("""
                UPDATE agent_v2_effect_intents
                   SET step_id = ?
                 WHERE tool_call_id = ?
                """, "other-step", "tool-misbound-after"));
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
