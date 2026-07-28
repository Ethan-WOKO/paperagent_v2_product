package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2effect_intent_behavior;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductEffectIntentRepositoryAdapter.class,
        ProductEffectIntentTransactions.class,
        ProductEffectIntentCodec.class,
        ProductReceiptCodec.class,
        ProductReceiptMarkerReader.class,
        ProductStepRecoveryRepositoryAdapter.class,
        ProductStepRecoveryTransactions.class,
        ProductActiveStepReplanMarkerReader.class,
        ProductActiveStepReplanCodec.class,
        ProductStepInterruptionMarkerReader.class,
        ProductStepInterruptionCodec.class,
        ProductStepCompletionMarkerReader.class,
        ProductStepCompletionCodec.class,
        ProductEffectOutcomeMarkerReader.class,
        ProductEffectOutcomeCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductStepActivationCodec.class,
        ProductEffectIntentRepositoryAdapterTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductEffectIntentRepositoryAdapterTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        MutableTime effectIntentTime() {
            return new MutableTime();
        }
    }

    static final class MutableTime implements ProductLeaseTimeSource {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(ProductStepActivationTestFixtures.NOW);
        private final AtomicInteger observations = new AtomicInteger();

        @Override
        public Instant observe() {
            observations.incrementAndGet();
            return now.get();
        }

        void resetObservations() {
            observations.set(0);
        }
    }

    @jakarta.annotation.Resource
    private ProductEffectIntentRepositoryAdapter adapter;
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

    @BeforeEach
    void reset() {
        intents.deleteAll();
        receipts.deleteAll();
        claims.deleteAll();
        interruptions.deleteAll();
        activations.deleteAll();
        contexts.deleteAll();
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
        intents.flush();
        receipts.flush();
        claims.flush();
        interruptions.flush();
        activations.flush();
        contexts.flush();
        starts.flush();
        leases.flush();
        bootstraps.flush();
        time.resetObservations();
    }

    @Test
    void nullAndAbsentInputsAreTypedAndDoNotObserveTime() {
        failure(adapter.persist(null), PersistenceErrorCode.INVALID_ARGUMENT,
                "request");
        failure(adapter.find(null), PersistenceErrorCode.INVALID_ARGUMENT,
                "toolCallId");
        failure(adapter.find(new ToolCallId("missing")),
                PersistenceErrorCode.NOT_FOUND, "toolCallId");
        EffectIntentRequest absent = new EffectIntentRequest(new EffectIntent(
                new ToolCallId("tool-m"), new PlanId("plan-m"),
                new PlanStepId("step-a"), "search",
                new ObjectValue(Map.of())), "token", 1,
                new EventId("activation-m"));
        failure(adapter.persist(absent), PersistenceErrorCode.NOT_FOUND,
                "planId");
        assertEquals(0, time.observations.get());
        assertEquals(0, intents.count());
    }

    @Test
    void appliedFindAndMultipleToolCallsPreserveExecutionAuthority() {
        var scenario = seed();
        EffectIntentRequest first = request(scenario, "tool-a");
        EffectIntentRequest second = request(scenario, "tool-b");
        long bootstrapCount = bootstraps.count();
        long activationCount = activations.count();
        long leaseCount = leases.count();
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.persist(first).outcome());
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.persist(second).outcome());
        assertEquals(PersistenceOutcome.FOUND,
                adapter.find(first.intent().toolCallId()).outcome());
        assertEquals(2, intents.count());
        assertEquals(bootstrapCount, bootstraps.count());
        assertEquals(activationCount, activations.count());
        assertEquals(leaseCount, leases.count());
        assertEquals(0, interruptions.count());
    }

    @Test
    void exactReplayPrecedesClockAndLeaseTakeover() {
        var scenario = seed();
        EffectIntentRequest request = request(scenario, "tool-a");
        var applied = adapter.persist(request).value().orElseThrow();
        leases.saveAndFlush(new ProductLeaseEntity(
                "plan-a", 2, "owner-b", "token-b",
                ProductStepActivationTestFixtures.NOW,
                ProductStepActivationTestFixtures.NOW.plusSeconds(60)));
        time.resetObservations();
        var replay = adapter.persist(request);
        assertEquals(PersistenceOutcome.REPLAYED, replay.outcome());
        assertEquals(applied, replay.value().orElseThrow());
        assertEquals(PersistenceOutcome.FOUND,
                adapter.find(request.intent().toolCallId()).outcome());
        assertEquals(0, time.observations.get());
    }

    @Test
    void changedDurableFieldsConflictAtExactStablePaths() {
        var scenario = seed();
        EffectIntentRequest original = request(scenario, "tool-a");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.persist(original).outcome());
        List<Changed> changes = List.of(
                new Changed(copy(original, new PlanId("other"),
                        original.intent().stepId(), original.intent().kind(),
                        original.intent().arguments(), original.leaseToken(),
                        original.fencingToken(),
                        original.expectedActivationEventId()),
                        "request.intent.planId"),
                new Changed(copy(original, original.intent().planId(),
                        new PlanStepId("other"), original.intent().kind(),
                        original.intent().arguments(), original.leaseToken(),
                        original.fencingToken(),
                        original.expectedActivationEventId()),
                        "request.intent.stepId"),
                new Changed(copy(original, original.intent().planId(),
                        original.intent().stepId(), "write",
                        original.intent().arguments(), original.leaseToken(),
                        original.fencingToken(),
                        original.expectedActivationEventId()),
                        "request.intent.kind"),
                new Changed(copy(original, original.intent().planId(),
                        original.intent().stepId(), original.intent().kind(),
                        new ObjectValue(Map.of(
                                "query", new TextValue("different"))),
                        original.leaseToken(), original.fencingToken(),
                        original.expectedActivationEventId()),
                        "request.intent.arguments"),
                new Changed(copy(original, original.intent().planId(),
                        original.intent().stepId(), original.intent().kind(),
                        original.intent().arguments(), "other-token",
                        original.fencingToken(),
                        original.expectedActivationEventId()),
                        "request.leaseToken"),
                new Changed(copy(original, original.intent().planId(),
                        original.intent().stepId(), original.intent().kind(),
                        original.intent().arguments(), original.leaseToken(), 2,
                        original.expectedActivationEventId()),
                        "request.fencingToken"),
                new Changed(copy(original, original.intent().planId(),
                        original.intent().stepId(), original.intent().kind(),
                        original.intent().arguments(), original.leaseToken(),
                        original.fencingToken(), new EventId("other-event")),
                        "request.expectedActivationEventId"));
        for (Changed changed : changes) {
            failure(adapter.persist(changed.request()),
                    PersistenceErrorCode.CONFLICTING_REPLAY, changed.path());
        }
        assertEquals(1, intents.count());
    }

    @Test
    void missingWrongFencedAndExpiredLeasesUseStableFailures() {
        var scenario = seed();
        EffectIntentRequest request = request(scenario, "tool-a");
        leases.deleteAll();
        leases.flush();
        failure(adapter.persist(request), PersistenceErrorCode.LEASE_NOT_HELD,
                "request.intent.planId");
        replaceLease("other", 1,
                ProductStepActivationTestFixtures.NOW.plusSeconds(60));
        failure(adapter.persist(request),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");
        replaceLease("token-a", 2,
                ProductStepActivationTestFixtures.NOW.plusSeconds(60));
        failure(adapter.persist(request),
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");
        replaceLease("token-a", 1,
                ProductStepActivationTestFixtures.NOW);
        failure(adapter.persist(request), PersistenceErrorCode.LEASE_EXPIRED,
                "request.intent.planId");
        replaceLease("token-a", 1,
                ProductStepActivationTestFixtures.NOW.plusSeconds(60));
        ProductLeaseEntity released = leases.findById(
                new ProductLeaseId("plan-a", 1)).orElseThrow();
        released.releaseAt(ProductStepActivationTestFixtures.NOW);
        leases.saveAndFlush(released);
        failure(adapter.persist(request), PersistenceErrorCode.LEASE_NOT_HELD,
                "request.intent.planId");
        assertEquals(0, intents.count());
    }

    @Test
    void inactiveOrInterruptedAuthorityFailsBeforeTimeAndWrite() {
        var scenario = seed();
        EffectIntentRequest request = request(scenario, "tool-a");
        activations.deleteAll();
        activations.flush();
        failure(adapter.persist(request),
                PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE,
                "stepRecovery");
        assertEquals(0, time.observations.get());
        assertEquals(0, intents.count());
    }

    @Test
    void wrongActivationEventAndStepAreRejectedAtStablePaths() {
        var scenario = seed();
        EffectIntentRequest request = request(scenario, "tool-a");
        failure(adapter.persist(copy(
                        request, request.intent().planId(),
                        request.intent().stepId(), request.intent().kind(),
                        request.intent().arguments(), request.leaseToken(),
                        request.fencingToken(), new EventId("wrong-event"))),
                PersistenceErrorCode.NOT_FOUND,
                "request.expectedActivationEventId");
        failure(adapter.persist(copy(
                        request, request.intent().planId(),
                        new PlanStepId("wrong-step"), request.intent().kind(),
                        request.intent().arguments(), request.leaseToken(),
                        request.fencingToken(),
                        request.expectedActivationEventId())),
                PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                "request.intent.stepId");
        assertEquals(0, time.observations.get());
        assertEquals(0, intents.count());
    }

    @Test
    void corruptInterruptionOccupancyFailsClosedBeforeIntentWrite() {
        var scenario = seed();
        EffectIntentRequest request = request(scenario, "tool-a");
        jdbc.update("""
                INSERT INTO agent_v2_step_interruptions (
                  plan_id,step_id,interruption_event_id,interruption_kind,
                  source_revision_id,source_revision_number,
                  result_revision_id,result_revision_number,
                  source_checkpoint_version,result_checkpoint_version,
                  source_event_sequence,result_event_sequence,
                  lease_owner_id,fencing_token,
                  request_format_version,request_sha256,request_json,
                  result_format_version,result_sha256,result_json,committed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                "plan-a", "step-a", "interruption-a", "PAUSE",
                "revision-1", 1, "revision-1", 1,
                3, 4, 2, 3, "owner-a", 1, 1,
                "0".repeat(64), "{}", 1, "0".repeat(64), "{}",
                ProductStepActivationTestFixtures.NOW);
        failure(adapter.persist(request),
                PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                "effectIntent.source");
        assertEquals(0, time.observations.get());
        assertEquals(0, intents.count());
    }

    @Test
    void tamperedDurableMarkerFailsClosedForFindAndReplay() {
        var scenario = seed();
        EffectIntentRequest request = request(scenario, "tool-a");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.persist(request).outcome());
        jdbc.update("""
                UPDATE agent_v2_effect_intents
                   SET result_sha256 = ?
                 WHERE tool_call_id = ?
                """, "0".repeat(64), "tool-a");
        failure(adapter.find(new ToolCallId("tool-a")),
                PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                "effectIntent.source");
        failure(adapter.persist(request),
                PersistenceErrorCode.EFFECT_INTENT_PARTIAL_STATE,
                "effectIntent.source");
        assertTrue(time.observations.get() >= 1);
    }

    @Test
    void orphanOrdinaryClaimFailsAsReceiptPartialBeforeRecovery() {
        var scenario = seed();
        EffectIntentRequest request = request(scenario, "tool-a");
        claims.saveAndFlush(new ProductReceiptToolCallClaimEntity(
                "tool-a", ProductReceiptOwnership.ORDINARY_RECEIPT));
        failure(adapter.persist(request),
                PersistenceErrorCode.RECEIPT_PARTIAL_STATE,
                "receipt.source");
        assertEquals(0, intents.count());
        assertEquals(0, time.observations.get());
    }

    private ProductEffectIntentTestFixtures.Scenario seed() {
        return ProductEffectIntentTestFixtures.seed(
                "plan-a", "task-a", "owner-a", "token-a", 1,
                bootstraps, bootstrapCodec, leases, starts, startCodec,
                activations, activationCodec);
    }

    private EffectIntentRequest request(
            ProductEffectIntentTestFixtures.Scenario scenario, String id) {
        return ProductEffectIntentTestFixtures.request(
                scenario, id, "token-a", 1);
    }

    private void replaceLease(String token, long fence, Instant expiry) {
        leases.deleteAll();
        leases.flush();
        leases.saveAndFlush(new ProductLeaseEntity(
                "plan-a", fence, "owner-a", token,
                ProductStepActivationTestFixtures.NOW.minusSeconds(1),
                expiry));
    }

    private static EffectIntentRequest copy(
            EffectIntentRequest source, PlanId planId, PlanStepId stepId,
            String kind, ObjectValue arguments, String token, long fence,
            EventId activation) {
        return new EffectIntentRequest(new EffectIntent(
                source.intent().toolCallId(), planId, stepId, kind, arguments),
                token, fence, activation);
    }

    private static void failure(
            PersistenceResult<?> result, PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    private record Changed(EffectIntentRequest request, String path) {
    }
}
