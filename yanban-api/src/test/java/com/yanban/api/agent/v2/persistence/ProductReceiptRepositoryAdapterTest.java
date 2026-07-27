package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.PersistedEffectIntent;
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
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2receipt_behavior;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductReceiptRepositoryAdapter.class,
        ProductReceiptTransactions.class,
        ProductReceiptCodec.class,
        ProductReceiptMarkerReader.class,
        ProductReceiptEffectIntentMarkerReader.class,
        ProductEffectIntentCodec.class,
        ProductReceiptRepositoryAdapterTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductReceiptRepositoryAdapterTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        CountingTime receiptTime() {
            return new CountingTime();
        }
    }

    static final class CountingTime implements ProductReceiptTimeSource {
        private final AtomicInteger observations = new AtomicInteger();

        @Override
        public Instant observe() {
            observations.incrementAndGet();
            return Instant.parse("2026-07-28T00:00:02Z");
        }
    }

    @jakarta.annotation.Resource
    private ProductReceiptRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductReceiptJpaRepository receipts;
    @jakarta.annotation.Resource
    private ProductReceiptToolCallClaimJpaRepository claims;
    @jakarta.annotation.Resource
    private ProductEffectIntentJpaRepository intents;
    @jakarta.annotation.Resource
    private ProductEffectIntentCodec effectIntentCodec;
    @jakarta.annotation.Resource
    private CountingTime time;
    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        receipts.deleteAll();
        intents.deleteAll();
        claims.deleteAll();
        jdbc.update("DELETE FROM agent_v2_plan_bootstraps");
        receipts.flush();
        intents.flush();
        claims.flush();
        time.observations.set(0);
    }

    @Test
    void nullAndAbsentInputsAreTypedWithoutTimeOrWrites() {
        failure(adapter.append(null), PersistenceErrorCode.INVALID_ARGUMENT,
                "receipt");
        failure(adapter.find(null), PersistenceErrorCode.INVALID_ARGUMENT,
                "receiptId");
        failure(adapter.find(new ReceiptId("missing")),
                PersistenceErrorCode.NOT_FOUND, "receiptId");
        assertEquals(0, time.observations.get());
        assertEquals(0, receipts.count());
        assertEquals(0, claims.count());
    }

    @Test
    void applyFindReplayAndMultipleIdsShareOneOrdinaryClaim() {
        ExecutionReceipt first = receipt("a", "shared");
        ExecutionReceipt second = receipt("b", "shared");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.append(first).outcome());
        assertEquals(PersistenceOutcome.FOUND,
                adapter.find(first.id()).outcome());
        assertEquals(PersistenceOutcome.REPLAYED,
                adapter.append(first).outcome());
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.append(second).outcome());
        assertEquals(2, receipts.count());
        assertEquals(1, claims.count());
        assertEquals(2, time.observations.get());
    }

    @Test
    void changedSameIdConflictsBeforeCreatingAnotherClaim() {
        ExecutionReceipt first = receipt("a", "first");
        ExecutionReceipt changed = copyToolCall(first, "second");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.append(first).outcome());
        failure(adapter.append(changed),
                PersistenceErrorCode.CONFLICTING_REPLAY, "receipt.id");
        assertEquals(1, receipts.count());
        assertEquals(1, claims.count());
        assertEquals(1, time.observations.get());
    }

    @Test
    void effectOwnershipRejectsWithoutReceiptOrTime() {
        EffectIntent intent = new EffectIntent(
                new ToolCallId("effect-owned"),
                new PlanId("plan-a"),
                new PlanStepId("step-a"),
                "search",
                new ObjectValue(Map.of()));
        EffectIntentRequest request = new EffectIntentRequest(
                intent, "token-a", 1, new EventId("activation-a"));
        PersistedEffectIntent result = new PersistedEffectIntent(
                intent, "owner-a", 1, new EventId("activation-a"));
        jdbc.update("""
                INSERT INTO agent_v2_plan_bootstraps (
                  plan_id, task_frame_id, payload_format_version,
                  payload_sha256, payload_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, "plan-a", "task-a", 1, "0".repeat(64), "{}",
                Instant.parse("2026-07-28T00:00:00Z"));
        claims.saveAndFlush(new ProductReceiptToolCallClaimEntity(
                "effect-owned", ProductReceiptOwnership.EFFECT_INTENT));
        intents.saveAndFlush(new ProductEffectIntentEntity(
                "effect-owned", "plan-a", "step-a", "activation-a",
                "search", "owner-a", 1,
                effectIntentCodec.encodeRequest(request),
                effectIntentCodec.encodeResult(result),
                Instant.parse("2026-07-28T00:00:02Z")));
        failure(adapter.append(receipt("a", "effect-owned")),
                PersistenceErrorCode.EFFECT_RECEIPT_OWNERSHIP_REQUIRED,
                "receipt.toolCallId");
        assertEquals(0, receipts.count());
        assertEquals(1, claims.count());
        assertEquals(0, time.observations.get());
    }

    @Test
    void orphanEffectClaimFailsAsEffectPartialState() {
        claims.saveAndFlush(new ProductReceiptToolCallClaimEntity(
                "effect-orphan", ProductReceiptOwnership.EFFECT_INTENT));
        failure(adapter.append(receipt("a", "effect-orphan")),
                PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                "effectIntent.source");
        assertEquals(0, receipts.count());
        assertEquals(0, time.observations.get());
    }

    @Test
    void orphanOrdinaryClaimFailsClosedWithoutRepairOrWrite() {
        claims.saveAndFlush(new ProductReceiptToolCallClaimEntity(
                "orphan", ProductReceiptOwnership.ORDINARY_RECEIPT));
        failure(adapter.append(receipt("a", "orphan")),
                PersistenceErrorCode.RECEIPT_PARTIAL_STATE,
                "receipt.source");
        assertEquals(0, receipts.count());
        assertEquals(1, claims.count());
        assertEquals(0, time.observations.get());
    }

    @Test
    void tamperedMarkerAndClaimMismatchAreSanitizedPartialState() {
        ExecutionReceipt first = receipt("a", "tool-a");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.append(first).outcome());
        jdbc.update("""
                UPDATE agent_v2_receipts
                   SET payload_sha256 = ?
                 WHERE receipt_id = ?
                """, "0".repeat(64), first.id().value());
        failure(adapter.find(first.id()),
                PersistenceErrorCode.RECEIPT_PARTIAL_STATE,
                "receipt.source");
        failure(adapter.append(first),
                PersistenceErrorCode.RECEIPT_PARTIAL_STATE,
                "receipt.source");
        assertEquals(1, time.observations.get());
    }

    @Test
    void corruptExistingOrdinaryFactCannotAuthorizeAnotherReceipt() {
        ExecutionReceipt first = receipt("a", "shared");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.append(first).outcome());
        jdbc.update("""
                UPDATE agent_v2_receipts
                   SET payload_sha256 = ?
                 WHERE receipt_id = ?
                """, "0".repeat(64), first.id().value());
        failure(adapter.append(receipt("b", "shared")),
                PersistenceErrorCode.RECEIPT_PARTIAL_STATE,
                "receipt.source");
        assertEquals(1, receipts.count());
        assertEquals(1, time.observations.get());
    }

    static ExecutionReceipt receipt(String id, String toolCall) {
        ExecutionReceipt base = ProductReceiptCodecTest.receipt(
                id, ReceiptStatus.FAILURE, Optional.of(2),
                Optional.of("FAILED"), true);
        return copyToolCall(base, toolCall);
    }

    private static ExecutionReceipt copyToolCall(
            ExecutionReceipt source, String toolCall) {
        return new ExecutionReceipt(
                source.id(), new ToolCallId(toolCall), source.status(),
                source.startedAt(), source.endedAt(), source.exitCode(),
                source.resultCode(), source.standardOutput(),
                source.standardError(), source.artifactReferences(),
                source.resultingDiff(), source.eventReferences());
    }

    private static void failure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }
}
