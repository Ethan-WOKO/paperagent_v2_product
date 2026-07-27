package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.EffectProgress;
import io.paperagent.v2.contracts.EffectProgressId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.EffectProgressRequest;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistedEffectProgress;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2effect_outcome_behavior;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
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
        ProductStepRecoveryRepositoryAdapter.class,
        ProductStepRecoveryTransactions.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductStepActivationCodec.class,
        ProductEffectOutcomeRepositoryAdapterTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductEffectOutcomeRepositoryAdapterTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        MutableTime outcomeTime() {
            return new MutableTime();
        }
    }

    static final class MutableTime implements ProductLeaseTimeSource,
            ProductEffectOutcomeTimeSource, ProductReceiptTimeSource {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(ProductStepActivationTestFixtures.NOW);
        private final AtomicInteger observations = new AtomicInteger();
        private volatile boolean fail;

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
    private ProductEffectOutcomeRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductEffectIntentRepositoryAdapter intentAdapter;
    @jakarta.annotation.Resource
    private ProductReceiptRepositoryAdapter receiptAdapter;
    @jakarta.annotation.Resource
    private ProductEffectOutcomeProgressJpaRepository progress;
    @jakarta.annotation.Resource
    private ProductEffectOutcomeResultJpaRepository results;
    @jakarta.annotation.Resource
    private ProductEffectIntentJpaRepository intents;
    @jakarta.annotation.Resource
    private ProductReceiptJpaRepository receipts;
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
    private EffectIntentRequest intent;

    @BeforeEach
    void reset() {
        time.fail = false;
        time.now.set(ProductStepActivationTestFixtures.NOW);
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
                "plan-outcome", "task-outcome", "owner-outcome",
                "token-outcome", 1, bootstraps, bootstrapCodec, leases,
                starts, startCodec, activations, activationCodec);
        intent = ProductEffectIntentTestFixtures.request(
                scenario, "tool-outcome", "token-outcome", 1);
        assertEquals(PersistenceOutcome.APPLIED,
                intentAdapter.persist(intent).outcome());
        time.observations.set(0);
        time.fail = false;
    }

    @Test
    void nullAndMissingInputsAreTypedWithoutClockOrWrites() {
        failure(adapter.appendProgress(null),
                PersistenceErrorCode.INVALID_ARGUMENT, "request");
        failure(adapter.readProgress(null),
                PersistenceErrorCode.INVALID_ARGUMENT, "toolCallId");
        failure(adapter.recordResult(null),
                PersistenceErrorCode.INVALID_ARGUMENT, "request");
        failure(adapter.findResult(null),
                PersistenceErrorCode.INVALID_ARGUMENT, "toolCallId");
        failure(adapter.readProgress(new ToolCallId("missing")),
                PersistenceErrorCode.NOT_FOUND, "toolCallId");
        failure(adapter.findResult(new ToolCallId("missing")),
                PersistenceErrorCode.NOT_FOUND, "toolCallId");
        assertEquals(0, time.observations.get());
        assertEquals(0, progress.count());
        assertEquals(0, results.count());
        assertEquals(0, receipts.count());
    }

    @Test
    void progressIsContiguousImmutableAndFinalResultIsAtomic() {
        PersistedEffectProgress first = applied(
                adapter.appendProgress(progress("progress-1", 1, "one")));
        applied(adapter.appendProgress(progress("progress-2", 2, "two")));
        assertEquals(List.of(first,
                        adapter.readProgress(intent.intent().toolCallId())
                                .value().orElseThrow().get(1)),
                adapter.readProgress(intent.intent().toolCallId())
                        .value().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () ->
                adapter.readProgress(intent.intent().toolCallId())
                        .value().orElseThrow().add(first));
        failure(adapter.appendProgress(
                        progress("progress-gap", 4, "gap")),
                PersistenceErrorCode.EFFECT_PROGRESS_OUT_OF_SEQUENCE,
                "request.progress.sequence");

        EffectResultRequest result = result("receipt-outcome");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.recordResult(result).outcome());
        assertEquals(result.receipt(),
                adapter.findResult(intent.intent().toolCallId())
                        .value().orElseThrow().receipt());
        assertEquals(result.receipt(),
                receiptAdapter.find(result.receipt().id())
                        .value().orElseThrow());
        assertEquals(1, results.count());
        assertEquals(1, receipts.count());
        failure(adapter.appendProgress(
                        progress("progress-after", 3, "after")),
                PersistenceErrorCode.EFFECT_OUTCOME_FINALIZED,
                "request.progress.toolCallId");
    }

    @Test
    void exactReplayIsPermanentBeforeRecoveryLeaseAndClock() {
        EffectProgressRequest progressRequest =
                progress("progress-replay", 1, "same");
        EffectResultRequest resultRequest = result("receipt-replay");
        var progressResult = applied(
                adapter.appendProgress(progressRequest));
        var result = applied(adapter.recordResult(resultRequest));
        time.fail = true;
        jdbc.update("DELETE FROM agent_v2_plan_leases");

        assertEquals(progressResult,
                replayed(adapter.appendProgress(progressRequest)));
        assertEquals(result, replayed(adapter.recordResult(resultRequest)));
        assertEquals(PersistenceOutcome.FOUND,
                adapter.readProgress(intent.intent().toolCallId()).outcome());
        assertEquals(PersistenceOutcome.FOUND,
                adapter.findResult(intent.intent().toolCallId()).outcome());
    }

    @Test
    void changedReplaysConflictAtExactPathsWithoutNewWrites() {
        EffectProgressRequest original =
                progress("progress-conflict", 1, "original");
        applied(adapter.appendProgress(original));
        failure(adapter.appendProgress(
                        progress("progress-conflict", 1, "changed")),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.progress.details");
        EffectResultRequest result = result("receipt-conflict");
        applied(adapter.recordResult(result));
        EffectResultRequest changed = new EffectResultRequest(
                ProductEffectOutcomeCodecTest.receipt(
                        ReceiptStatus.FAILURE, "receipt-other",
                        intent.intent().toolCallId().value()),
                "token-outcome", 1);
        failure(adapter.recordResult(changed),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.receipt.id");
        assertEquals(1, progress.count());
        assertEquals(1, results.count());
        assertEquals(1, receipts.count());
    }

    @Test
    void newWritesRequireCurrentLeaseAndLeaveAuthorityUnchanged() {
        Authority before = authority();
        EffectProgressRequest wrongToken = new EffectProgressRequest(
                progress("wrong", 1, "wrong").progress(),
                "other-token", 1);
        failure(adapter.appendProgress(wrongToken),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");
        EffectResultRequest wrongFence = new EffectResultRequest(
                result("wrong-fence").receipt(), "token-outcome", 2);
        failure(adapter.recordResult(wrongFence),
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");
        time.now.set(ProductStepActivationTestFixtures.NOW.plusSeconds(61));
        failure(adapter.appendProgress(
                        progress("expired", 1, "expired")),
                PersistenceErrorCode.LEASE_EXPIRED,
                "effectIntent.planId");
        assertEquals(before, authority());
        assertEquals(0, progress.count());
        assertEquals(0, results.count());
        assertEquals(0, receipts.count());
    }

    @Test
    void corruptIntentFailsClosedWithoutOutcomeWritesOrClock() {
        jdbc.update("""
                UPDATE agent_v2_effect_intents
                   SET result_sha256 = ?
                 WHERE tool_call_id = ?
                """, "0".repeat(64), intent.intent().toolCallId().value());

        failure(adapter.appendProgress(
                        progress("corrupt-intent", 1, "corrupt")),
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");
        assertEquals(0, time.observations.get());
        assertEquals(0, progress.count());
        assertEquals(0, results.count());
        assertEquals(0, receipts.count());
    }

    @Test
    void ownershipAndTornCutsFailClosedWithoutTakeover() {
        ExecutionReceipt ordinary =
                ProductEffectOutcomeCodecTest.receipt(
                        ReceiptStatus.FAILURE, "shared-receipt",
                        "ordinary-tool");
        applied(receiptAdapter.append(ordinary));
        EffectResultRequest collision = new EffectResultRequest(
                ProductEffectOutcomeCodecTest.receipt(
                        ReceiptStatus.FAILURE, "shared-receipt",
                        intent.intent().toolCallId().value()),
                "token-outcome", 1);
        failure(adapter.recordResult(collision),
                PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                "request.receipt.id");
        assertEquals(0, results.count());

        EffectIntentRequest secondIntent =
                ProductEffectIntentTestFixtures.request(
                        scenario, "tool-second-effect",
                        "token-outcome", 1);
        applied(intentAdapter.persist(secondIntent));
        EffectResultRequest secondEffect = new EffectResultRequest(
                ProductEffectOutcomeCodecTest.receipt(
                        ReceiptStatus.FAILURE, "cross-effect-receipt",
                        secondIntent.intent().toolCallId().value()),
                "token-outcome", 1);
        applied(adapter.recordResult(secondEffect));
        failure(adapter.recordResult(new EffectResultRequest(
                        ProductEffectOutcomeCodecTest.receipt(
                                ReceiptStatus.FAILURE,
                                "cross-effect-receipt",
                                intent.intent().toolCallId().value()),
                        "token-outcome", 1)),
                PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                "request.receipt.id");

        EffectResultRequest effect = result("effect-owned");
        applied(adapter.recordResult(effect));
        failure(receiptAdapter.append(effect.receipt()),
                PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                "receipt.id");
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("DELETE FROM agent_v2_receipts "
                        + "WHERE receipt_id = 'effect-owned'"));
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        receipts.deleteById(effect.receipt().id().value());
        receipts.flush();
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        failure(adapter.findResult(intent.intent().toolCallId()),
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");
        failure(receiptAdapter.append(effect.receipt()),
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");
    }

    @Test
    void corruptProgressCannotBeFinalized() {
        applied(adapter.appendProgress(
                progress("progress-corrupt", 1, "detail")));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.update("DELETE FROM agent_v2_effect_intents "
                        + "WHERE tool_call_id = 'tool-outcome'"));
        jdbc.update("""
                UPDATE agent_v2_effect_progress
                   SET request_sha256 = ?
                 WHERE effect_progress_id = ?
                """, "0".repeat(64), "progress-corrupt");

        failure(adapter.recordResult(result("receipt-blocked")),
                PersistenceErrorCode.EFFECT_OUTCOME_PARTIAL_STATE,
                "effectOutcome.source");
        assertEquals(0, results.count());
        assertEquals(0, receipts.count());
    }

    private EffectProgressRequest progress(
            String id, long sequence, String detail) {
        return new EffectProgressRequest(new EffectProgress(
                new EffectProgressId(id), intent.intent().toolCallId(),
                sequence,
                ProductStepActivationTestFixtures.NOW.plusSeconds(sequence),
                new ObjectValue(Map.of(
                        "detail", new TextValue(detail)))),
                "token-outcome", 1);
    }

    private EffectResultRequest result(String receiptId) {
        return new EffectResultRequest(
                ProductEffectOutcomeCodecTest.receipt(
                        ReceiptStatus.FAILURE, receiptId,
                        intent.intent().toolCallId().value()),
                "token-outcome", 1);
    }

    private Authority authority() {
        return new Authority(
                bootstraps.count(), leases.count(), starts.count(),
                contexts.count(), activations.count(),
                interruptions.count(), intents.count(), claims.count());
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
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(),
                result.toString());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    private record Authority(
            long bootstraps,
            long leases,
            long starts,
            long contexts,
            long activations,
            long interruptions,
            long intents,
            long claims) {
    }
}
