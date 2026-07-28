package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanValidators;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.EffectResultRequest;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistedStepRecoveryReady;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
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
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2_active_replan;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductActiveStepReplanRepositoryAdapter.class,
        ProductActiveStepReplanTransactions.class,
        ProductActiveStepReplanMarkerReader.class,
        ProductActiveStepReplanCodec.class,
        ProductEffectIntentRepositoryAdapter.class,
        ProductEffectIntentTransactions.class,
        ProductEffectOutcomeRepositoryAdapter.class,
        ProductEffectOutcomeTransactions.class,
        ProductStepRecoveryTransactions.class,
        ProductStepRecoveryRepositoryAdapter.class,
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
        ProductActiveStepReplanRepositoryAdapterTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductActiveStepReplanRepositoryAdapterTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        TestTime replanTime() {
            return new TestTime();
        }
    }

    static final class TestTime implements ProductActiveStepReplanTimeSource,
            ProductLeaseTimeSource, ProductEffectOutcomeTimeSource {
        @Override
        public Instant now() {
            return ProductStepActivationTestFixtures.NOW.plusSeconds(5);
        }

        @Override
        public Instant observe() {
            return now();
        }
    }

    @jakarta.annotation.Resource
    private ProductActiveStepReplanRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductStepRecoveryRepositoryAdapter recovery;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapJpaRepository bootstraps;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapCodec bootstrapCodec;
    @jakarta.annotation.Resource
    private ProductLeaseJpaRepository leases;
    @jakarta.annotation.Resource
    private ProductExecutionStartJpaRepository starts;
    @jakarta.annotation.Resource
    private ProductExecutionStartCodec startCodec;
    @jakarta.annotation.Resource
    private ProductStepActivationJpaRepository activations;
    @jakarta.annotation.Resource
    private ProductStepActivationCodec activationCodec;
    @jakarta.annotation.Resource
    private ProductActiveStepReplanJpaRepository replans;
    @jakarta.annotation.Resource
    private ProductEffectIntentRepositoryAdapter effectIntentAdapter;
    @jakarta.annotation.Resource
    private ProductEffectIntentJpaRepository effectIntents;
    @jakarta.annotation.Resource
    private ProductEffectOutcomeRepositoryAdapter effectOutcomeAdapter;
    @jakarta.annotation.Resource
    private ProductEffectOutcomeResultJpaRepository effectResults;
    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        replans.deleteAll();
        effectResults.deleteAll();
        effectIntents.deleteAll();
        activations.deleteAll();
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
    }

    @Test
    void canonicalRequestAndResultRoundTripExactly() {
        var codec = new ProductActiveStepReplanCodec(
                new ObjectMapper());
        var request =
                ProductActiveStepReplanTestSupport.request("codec");
        var requestPayload = codec.encodeRequest(request);
        assertEquals(request, codec.decodeRequest(
                requestPayload.formatVersion(),
                requestPayload.sha256(), requestPayload.json()));
        var result =
                ProductActiveStepReplanTestSupport.result(request);
        var resultPayload = codec.encodeResult(result);
        assertEquals(result, codec.decodeResult(
                resultPayload.formatVersion(),
                resultPayload.sha256(), resultPayload.json()));
    }

    @Test
    void nullRequestFailsBeforeTransactions() {
        var result = adapter.supersedeAndReplan(null);
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(PersistenceErrorCode.INVALID_ARGUMENT,
                result.failure().orElseThrow().code());
    }

    @Test
    void h2ApplyReplayAndRecoveryExposeReplacementReady() {
        Scenario scenario = seedActive("h2");
        var request = scenario.request();

        var applied = adapter.supersedeAndReplan(request);
        assertEquals(PersistenceOutcome.APPLIED,
                applied.outcome());
        assertEquals(PersistenceOutcome.REPLAYED,
                adapter.supersedeAndReplan(request).outcome());
        assertEquals(1, replans.count());
        var cut = recovery.inspect(request.planId());
        assertEquals(PersistenceOutcome.FOUND, cut.outcome());
        var ready = (PersistedStepRecoveryReady)
                cut.value().orElseThrow();
        assertEquals(request.replannedRevision(),
                ready.plan().latestRevision());
        assertEquals(
                new io.paperagent.v2.contracts.PlanStepId(
                        "replacement-h2"),
                ready.readyStepId());
    }

    @Test
    void pendingDurableIntentMakesActiveStepReplanIneligible() {
        Scenario scenario = seedActive("pending-intent");
        var request = scenario.request();
        var intent = new EffectIntent(
                new ToolCallId("tool-pending-intent"),
                request.planId(), request.activeStepId(),
                "literature.search", new ObjectValue(java.util.Map.of()));
        assertEquals(PersistenceOutcome.APPLIED,
                effectIntentAdapter.persist(new EffectIntentRequest(
                        intent, "lease-token", 3,
                        scenario.activation().activationEvent().id()))
                        .outcome());

        var rejected = adapter.supersedeAndReplan(request);

        assertEquals(PersistenceOutcome.REJECTED, rejected.outcome());
        assertEquals(
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_NOT_ELIGIBLE,
                rejected.failure().orElseThrow().code());
        assertEquals(0, replans.count());
        assertEquals(1, effectIntents.count());
    }

    @Test
    void durableOutcomeStillNeedsProgressionBeforeReplan() {
        Scenario scenario = seedActive("durable-outcome");
        var request = scenario.request();
        String toolCallId = "tool-durable-outcome";
        var intent = new EffectIntent(
                new ToolCallId(toolCallId),
                request.planId(), request.activeStepId(),
                "literature.search", new ObjectValue(java.util.Map.of()));
        assertEquals(PersistenceOutcome.APPLIED,
                effectIntentAdapter.persist(new EffectIntentRequest(
                        intent, "lease-token", 3,
                        scenario.activation().activationEvent().id()))
                        .outcome());
        assertEquals(PersistenceOutcome.APPLIED,
                effectOutcomeAdapter.recordResult(
                        new EffectResultRequest(
                                ProductEffectOutcomeCodecTest.receipt(
                                        io.paperagent.v2.contracts
                                                .ReceiptStatus.SUCCESS,
                                        "receipt-durable-outcome",
                                        toolCallId),
                                "lease-token", 3))
                        .outcome());

        var rejected = adapter.supersedeAndReplan(request);

        assertEquals(PersistenceOutcome.REJECTED, rejected.outcome());
        assertEquals(
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_NOT_ELIGIBLE,
                rejected.failure().orElseThrow().code());
        assertEquals(0, replans.count());
        assertEquals(1, effectResults.count());
    }

    @Test
    void exactReplayPrecedesExpiredAndTakenOverLeaseInspection() {
        Scenario scenario = seedActive("permanent-replay");
        PersistedActiveStepReplan applied = adapter
                .supersedeAndReplan(scenario.request())
                .value().orElseThrow();

        ProductLeaseEntity oldLease = leases
                .findFirstByPlanIdOrderByFencingTokenDesc(
                        scenario.request().planId().value())
                .orElseThrow();
        oldLease.renewUntil(ProductStepActivationTestFixtures.NOW
                .plusSeconds(4));
        leases.saveAndFlush(oldLease);
        var expiredReplay = adapter.supersedeAndReplan(
                scenario.request());
        assertEquals(PersistenceOutcome.REPLAYED,
                expiredReplay.outcome());
        assertEquals(applied, expiredReplay.value().orElseThrow());

        oldLease.releaseAt(ProductStepActivationTestFixtures.NOW
                .plusSeconds(4));
        leases.saveAndFlush(oldLease);
        leases.saveAndFlush(new ProductLeaseEntity(
                scenario.request().planId().value(), 4,
                "takeover-owner", "takeover-token",
                ProductStepActivationTestFixtures.NOW.plusSeconds(4),
                ProductStepActivationTestFixtures.NOW.plusSeconds(60)));
        var takeoverReplay = adapter.supersedeAndReplan(
                scenario.request());
        assertEquals(PersistenceOutcome.REPLAYED,
                takeoverReplay.outcome());
        assertEquals(applied, takeoverReplay.value().orElseThrow());
        assertEquals(1, replans.count());
    }

    @Test
    void changedProposalWithSameEventIdentityConflicts() {
        Scenario scenario = seedActive("changed-proposal");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.supersedeAndReplan(
                        scenario.request()).outcome());
        ActiveStepReplanRequest original = scenario.request();
        PlanRevision changedRevision = new PlanRevision(
                original.replannedRevision().id(),
                original.replannedRevision().taskFrameId(),
                original.replannedRevision().number(),
                original.replannedRevision().parentRevisionId(),
                "different proposal",
                original.replannedRevision().createdAt(),
                original.replannedRevision().steps(),
                original.replannedRevision().completedFacts());
        ActiveStepReplanRequest changed =
                new ActiveStepReplanRequest(
                        original.planId(), original.leaseToken(),
                        original.fencingToken(),
                        original.expectedRevisionId(),
                        original.expectedRevisionNumber(),
                        original.expectedCheckpointVersion(),
                        original.expectedEventHeadSequence(),
                        original.activeStepId(),
                        original.supersessionEvent(),
                        original.supersededCheckpoint(),
                        original.replanEvent(), changedRevision,
                        original.replannedCheckpoint());

        var conflict = adapter.supersedeAndReplan(changed);
        assertEquals(PersistenceOutcome.REJECTED,
                conflict.outcome());
        assertEquals(PersistenceErrorCode.CONFLICTING_REPLAY,
                conflict.failure().orElseThrow().code());
        assertEquals(1, replans.count());
        assertEquals(PersistenceOutcome.FOUND,
                recovery.inspect(original.planId()).outcome());
    }

    @Test
    void corruptTornAndCrossBoundMarkersFailClosedWithSanitizedFailure() {
        assertCorruptMarker("digest", """
                update agent_v2_active_step_replans
                   set request_sha256 = ?
                 where supersession_event_id = ?
                """, "0".repeat(64));
        assertCorruptMarker("torn", """
                update agent_v2_active_step_replans
                   set result_json = ?,
                       result_sha256 = ?
                 where supersession_event_id = ?
                """, "{\"storedSecret\":\"C:\\\\private\\\\paper\"",
                "0".repeat(64));
        assertCorruptMarker("cross-bound", """
                update agent_v2_active_step_replans
                   set superseded_step_id = ?
                 where supersession_event_id = ?
                """, "foreign-step");
    }

    @Test
    void recoveryFoldsMultipleSequentialReplans() {
        Scenario scenario = seedActive("sequential-first");
        PersistedActiveStepReplan first = adapter
                .supersedeAndReplan(scenario.request())
                .value().orElseThrow();
        StepActivationRequest secondActivation =
                ProductActiveStepReplanTestSupport.activationAfter(
                        first, "lease-token", "sequential-second");
        PersistedStepActivation secondActivated =
                new PersistedStepActivation(
                        secondActivation.planId(),
                        secondActivation.stepId(), "owner",
                        secondActivation.fencingToken(),
                        secondActivation.activationEvent(),
                        new VersionedCheckpoint(
                                secondActivation
                                        .expectedCheckpointVersion() + 1,
                                secondActivation
                                        .activatedCheckpoint()));
        activations.saveAndFlush(new ProductStepActivationEntity(
                secondActivation.planId().value(),
                secondActivation.stepId().value(),
                secondActivation.activationEvent().id().value(),
                secondActivation.expectedRevisionId().value(),
                secondActivation.expectedRevisionNumber(),
                secondActivation.activatedCheckpoint()
                        .revisionId().value(),
                secondActivation.activatedCheckpoint().revisionNumber(),
                secondActivation.expectedCheckpointVersion(),
                secondActivation.expectedCheckpointVersion() + 1,
                secondActivation.expectedEventHeadSequence(),
                secondActivation.activationEvent().sequence(),
                "owner", secondActivation.fencingToken(),
                activationCodec.encodeRequest(secondActivation),
                activationCodec.encodeResult(secondActivated),
                secondActivation.activationEvent().occurredAt()));
        ActiveStepReplanRequest second =
                ProductActiveStepReplanTestSupport.requestAfter(
                        first, secondActivated,
                        "lease-token", "sequential-second");
        PersistedStepRecoveryActive active =
                (PersistedStepRecoveryActive) recovery
                        .inspect(second.planId()).value().orElseThrow();
        var revisions = new ArrayList<>(
                active.plan().revisions());
        revisions.add(second.replannedRevision());
        assertEquals(java.util.List.of(),
                PlanValidators.validateHistory(
                        active.plan().taskFrameId(), revisions));
        new Plan(active.plan().id(),
                active.plan().taskFrameId(), revisions);
        assertEquals(active.plan().latestRevision().number() + 1,
                second.replannedRevision().number());
        assertEquals(java.util.Optional.of(
                        active.plan().latestRevision().id()),
                second.replannedRevision().parentRevisionId());
        assertFalse(second.replannedRevision().createdAt().isBefore(
                active.plan().latestRevision().createdAt()));
        assertEquals(active.plan().latestRevision().completedFacts(),
                second.replannedRevision().completedFacts());
        assertFalse(second.replannedRevision().steps().stream()
                .anyMatch(step -> step.id().equals(
                        second.activeStepId())));

        var secondResult = adapter.supersedeAndReplan(second);
        assertEquals(PersistenceOutcome.APPLIED,
                secondResult.outcome(), secondResult::toString);
        assertEquals(2, replans.count());
        var recovered = recovery.inspect(second.planId());
        assertEquals(PersistenceOutcome.FOUND,
                recovered.outcome());
        PersistedStepRecoveryReady ready =
                (PersistedStepRecoveryReady)
                        recovered.value().orElseThrow();
        assertEquals(second.replannedRevision(),
                ready.plan().latestRevision());
        assertEquals(
                new io.paperagent.v2.contracts.PlanStepId(
                        "replacement-sequential-second"),
                ready.readyStepId());
    }

    private void assertCorruptMarker(
            String suffix, String update, Object... values) {
        reset();
        Scenario scenario = seedActive("corrupt-" + suffix);
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.supersedeAndReplan(
                        scenario.request()).outcome());
        Object[] arguments =
                java.util.Arrays.copyOf(values, values.length + 1);
        arguments[values.length] = scenario.request()
                .supersessionEvent().id().value();
        assertEquals(1, jdbc.update(update, arguments));

        var result = recovery.inspect(scenario.request().planId());
        assertEquals(PersistenceOutcome.REJECTED,
                result.outcome());
        var failure = result.failure().orElseThrow();
        assertEquals(
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                failure.code());
        assertEquals("stepRecovery", failure.path());
        assertFalse(failure.toString().contains("storedSecret"));
        assertFalse(failure.toString().contains("private"));
    }

    private Scenario seedActive(String suffix) {
        ActiveStepReplanRequest request =
                ProductActiveStepReplanTestSupport.request(suffix);
        var bootstrap = ProductPlanBootstrapTestFixtures.workspace(
                request.planId().value(), "task-" + suffix);
        ProductStepActivationTestFixtures.seedH0(
                bootstrap, "owner", "lease-token", 3,
                bootstraps, bootstrapCodec, leases, starts,
                startCodec);
        StepActivationRequest activation =
                ProductStepActivationTestFixtures.request(
                        bootstrap, "lease-token", 3,
                        "activation-" + suffix);
        PersistedStepActivation activated =
                new PersistedStepActivation(
                        activation.planId(), activation.stepId(),
                        "owner", 3, activation.activationEvent(),
                        new VersionedCheckpoint(
                                3, activation.activatedCheckpoint()));
        activations.saveAndFlush(new ProductStepActivationEntity(
                activation.planId().value(),
                activation.stepId().value(),
                activation.activationEvent().id().value(),
                activation.expectedRevisionId().value(),
                activation.expectedRevisionNumber(),
                activation.activatedCheckpoint()
                        .revisionId().value(),
                activation.activatedCheckpoint().revisionNumber(),
                2, 3, 1, 2, "owner", 3,
                activationCodec.encodeRequest(activation),
                activationCodec.encodeResult(activated),
                ProductStepActivationTestFixtures.NOW.plusSeconds(1)));
        return new Scenario(request, activation, activated);
    }

    private record Scenario(
            ActiveStepReplanRequest request,
            StepActivationRequest activation,
            PersistedStepActivation activated) {
    }
}
