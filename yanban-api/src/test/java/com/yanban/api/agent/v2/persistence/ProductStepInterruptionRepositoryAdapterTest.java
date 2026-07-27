package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepInterruption;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepInterruptionRepository;
import io.paperagent.v2.persistence.StepPauseRequest;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2step_interruption_behavior;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductStepInterruptionRepositoryAdapter.class,
        ProductStepInterruptionTransactions.class,
        ProductStepInterruptionMarkerReader.class,
        ProductStepInterruptionCodec.class,
        ProductStepActivationCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductStepInterruptionRepositoryAdapterTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductStepInterruptionRepositoryAdapterTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        MutableTime timeSource() {
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

        void set(Instant value) {
            now.set(value);
        }

        void reset() {
            now.set(ProductStepActivationTestFixtures.NOW);
            observations.set(0);
        }
    }

    @jakarta.annotation.Resource
    private ProductStepInterruptionRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private StepInterruptionRepository repository;
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
    private ProductPlanExecutionContextCodec contextCodec;
    @jakarta.annotation.Resource
    private ProductLeaseJpaRepository leases;
    @jakarta.annotation.Resource
    private ProductStepActivationJpaRepository activations;
    @jakarta.annotation.Resource
    private ProductStepActivationCodec activationCodec;
    @jakarta.annotation.Resource
    private ProductStepInterruptionJpaRepository interruptions;
    @jakarta.annotation.Resource
    private MutableTime time;
    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        interruptions.deleteAll();
        activations.deleteAll();
        contexts.deleteAll();
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
        interruptions.flush();
        activations.flush();
        contexts.flush();
        starts.flush();
        leases.flush();
        bootstraps.flush();
        time.reset();
    }

    @Test
    void springExposesTheProductAdapterAsTheStableRepository() {
        assertSame(adapter, repository);
    }

    @Test
    void appliesPauseFailAndCancelAsExactVersionFourFacts() {
        assertApplied(seed("pause"), Kind.PAUSE);
        reset();
        assertApplied(seed("fail"), Kind.FAIL);
        reset();
        assertApplied(seed("cancel"), Kind.CANCEL);
    }

    @Test
    void exactReplayIsPermanentBeforeLeaseAndTimeValidation() {
        Scenario scenario = seed("replay");
        PersistenceResult<PersistedStepInterruption> applied =
                invoke(scenario, Kind.PAUSE);
        time.set(ProductStepActivationTestFixtures.NOW.plusSeconds(600));
        leases.saveAndFlush(new ProductLeaseEntity(
                scenario.bootstrap().plan().id().value(), 2,
                "replacement-owner", "replacement-token",
                ProductStepActivationTestFixtures.NOW.plusSeconds(10),
                ProductStepActivationTestFixtures.NOW.plusSeconds(1200)));

        PersistenceResult<PersistedStepInterruption> replayed =
                invoke(scenario, Kind.PAUSE);

        assertEquals(PersistenceOutcome.APPLIED, applied.outcome());
        assertEquals(PersistenceOutcome.REPLAYED, replayed.outcome());
        assertEquals(applied.value(), replayed.value());
        assertEquals(1, time.observations.get());
        assertEquals(1, interruptions.count());
    }

    @Test
    void sameEventWithDifferentRequestOrKindConflictsBeforeTime() {
        Scenario scenario = seed("conflict");
        invoke(scenario, Kind.PAUSE);
        int observed = time.observations.get();
        StepPauseRequest pause = pause(scenario);
        StepPauseRequest changed = new StepPauseRequest(
                pause.planId(), "different", 99,
                pause.expectedRevisionId(), pause.expectedRevisionNumber(),
                pause.expectedCheckpointVersion(),
                pause.expectedEventHeadSequence(), pause.stepId(),
                pause.pauseEvent(), pause.pausedCheckpoint());
        assertFailure(adapter.pause(changed),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.pauseEvent.id");
        assertFailure(adapter.fail(fail(scenario)),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.failureEvent.id");
        assertEquals(observed, time.observations.get());
        assertEquals(1, interruptions.count());
    }

    @Test
    void leaseFailuresUseOneTrustedObservationAndNeverWrite() {
        Scenario scenario = seed("lease");
        leases.deleteAll();
        leases.flush();
        assertFailure(adapter.pause(pause(scenario)),
                PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");
        replaceLease(scenario, "other", 1,
                ProductStepActivationTestFixtures.NOW.plusSeconds(60));
        assertFailure(adapter.pause(pause(scenario)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");
        replaceLease(scenario, scenario.token(), 2,
                ProductStepActivationTestFixtures.NOW.plusSeconds(60));
        assertFailure(adapter.pause(pause(scenario)),
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");
        replaceLease(scenario, scenario.token(), 1,
                ProductStepActivationTestFixtures.NOW);
        assertFailure(adapter.pause(pause(scenario)),
                PersistenceErrorCode.LEASE_EXPIRED, "request.planId");
        assertEquals(4, time.observations.get());
        assertEquals(0, interruptions.count());
        assertEquals(1, activations.count());
    }

    @Test
    void staleEventCheckpointAndStepEligibilityRejectWithoutMutation() {
        Scenario scenario = seed("invalid");
        StepPauseRequest base = pause(scenario);
        assertFailure(adapter.pause(new StepPauseRequest(
                        base.planId(), base.leaseToken(), base.fencingToken(),
                        new PlanRevisionId("other"), 1, 3, 2,
                        base.stepId(), base.pauseEvent(),
                        base.pausedCheckpoint())),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedRevisionId");
        assertFailure(adapter.pause(new StepPauseRequest(
                        base.planId(), base.leaseToken(), base.fencingToken(),
                        base.expectedRevisionId(), 1, 4, 2,
                        base.stepId(), base.pauseEvent(),
                        base.pausedCheckpoint())),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedCheckpointVersion");
        EventEnvelope wrongTask = new EventEnvelope(
                base.pauseEvent().id(), new TaskFrameId("other"),
                base.planId(), 3, base.pauseEvent().occurredAt(),
                base.pauseEvent().type(), base.pauseEvent().causationId(),
                base.pauseEvent().correlationId(),
                base.pauseEvent().payload());
        assertFailure(adapter.pause(new StepPauseRequest(
                        base.planId(), base.leaseToken(), base.fencingToken(),
                        base.expectedRevisionId(), 1, 3, 2,
                        base.stepId(), wrongTask,
                        base.pausedCheckpoint())),
                PersistenceErrorCode.TASK_FRAME_MISMATCH,
                "request.pauseEvent.taskFrameId");
        assertFailure(adapter.pause(new StepPauseRequest(
                        base.planId(), base.leaseToken(), base.fencingToken(),
                        base.expectedRevisionId(), 1, 3, 2,
                        new PlanStepId("step-b"), base.pauseEvent(),
                        base.pausedCheckpoint())),
                PersistenceErrorCode.STEP_INTERRUPTION_NOT_ELIGIBLE,
                "stepInterruption");
        Checkpoint active = scenario.activation().activatedCheckpoint();
        Checkpoint wrongTransition = new Checkpoint(
                active.taskFrameId(), active.planId(), active.revisionId(),
                active.revisionNumber(), 3, PlanExecutionState.PAUSED,
                active.stepStates(), active.receiptReferences(),
                active.createdAt().plusSeconds(1));
        assertFailure(adapter.pause(new StepPauseRequest(
                        base.planId(), base.leaseToken(), base.fencingToken(),
                        base.expectedRevisionId(), 1, 3, 2,
                        base.stepId(), base.pauseEvent(), wrongTransition)),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.pausedCheckpoint");
        assertEquals(0, interruptions.count());
        assertEquals(1, activations.count());
    }

    @Test
    void missingPartialAndCorruptedAuthoritiesFailClosed() {
        PersistedPlanBootstrap missing =
                ProductPlanBootstrapTestFixtures.workspace(
                        "missing", "task-m");
        Scenario absent = scenarioOnly(missing, "missing");
        assertFailure(adapter.pause(pause(absent)),
                PersistenceErrorCode.NOT_FOUND, "request.planId");
        leases.saveAndFlush(new ProductLeaseEntity(
                "orphan", 1, "owner", "token",
                ProductStepActivationTestFixtures.NOW.minusSeconds(1),
                ProductStepActivationTestFixtures.NOW.plusSeconds(60)));
        PersistedPlanBootstrap orphanBootstrap =
                ProductPlanBootstrapTestFixtures.workspace(
                        "orphan", "task-o");
        assertFailure(adapter.pause(pause(
                        scenarioOnly(orphanBootstrap, "orphan"))),
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                "stepInterruption");
        leases.deleteAll();
        leases.flush();

        Scenario corruptActivation = seed("corrupt-activation");
        jdbc.update("update agent_v2_step_activations "
                        + "set result_sha256 = ? where plan_id = ?",
                "0".repeat(64),
                corruptActivation.bootstrap().plan().id().value());
        assertFailure(adapter.pause(pause(corruptActivation)),
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                "stepInterruption");
        assertEquals(0, interruptions.count());

        reset();
        Scenario corruptFact = seed("corrupt-fact");
        invoke(corruptFact, Kind.PAUSE);
        int observed = time.observations.get();
        jdbc.update("update agent_v2_step_interruptions "
                        + "set result_sha256 = ? where plan_id = ?",
                "0".repeat(64),
                corruptFact.bootstrap().plan().id().value());
        assertFailure(adapter.pause(pause(corruptFact)),
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                "stepInterruption");
        assertEquals(observed, time.observations.get());
        assertEquals(1, activations.count());
        assertEquals(1, interruptions.count());
    }

    @Test
    void nonCanonicalDocumentWithMatchingDigestAndCrossBoundActivationFail()
            throws Exception {
        Scenario nonCanonical = seed("noncanonical");
        invoke(nonCanonical, Kind.FAIL);
        ProductStepInterruptionEntity row = interruptions.findAllByPlanId(
                nonCanonical.bootstrap().plan().id().value()).get(0);
        String padded = row.requestJson() + " ";
        jdbc.update("update agent_v2_step_interruptions "
                        + "set request_json = ?, request_sha256 = ? "
                        + "where plan_id = ?",
                padded, sha256(padded),
                nonCanonical.bootstrap().plan().id().value());
        assertFailure(adapter.fail(fail(nonCanonical)),
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                "stepInterruption");

        reset();
        Scenario crossBound = seed("cross-bound");
        jdbc.update("update agent_v2_step_activations "
                        + "set step_id = ? where plan_id = ?",
                "step-b", crossBound.bootstrap().plan().id().value());
        assertFailure(adapter.cancel(cancel(crossBound)),
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                "stepInterruption");
        assertEquals(0, interruptions.count());
        assertEquals(1, activations.count());
    }

    @Test
    void corruptedExtractedColumnsAndOccupiedAdvancedCutArePartial() {
        Scenario scenario = seed("columns");
        invoke(scenario, Kind.PAUSE);
        int observed = time.observations.get();
        jdbc.update("update agent_v2_step_interruptions "
                        + "set source_revision_id = ? where plan_id = ?",
                "other", scenario.bootstrap().plan().id().value());
        assertFailure(adapter.pause(pause(scenario)),
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                "stepInterruption");
        assertEquals(observed, time.observations.get());

        StepPauseRequest original = pause(scenario);
        EventEnvelope other = new EventEnvelope(
                new EventId("other-event"),
                original.pauseEvent().taskFrameId(),
                original.pauseEvent().planId(), 3,
                original.pauseEvent().occurredAt(),
                original.pauseEvent().type(),
                original.pauseEvent().causationId(),
                original.pauseEvent().correlationId(),
                original.pauseEvent().payload());
        StepPauseRequest advanced = new StepPauseRequest(
                original.planId(), original.leaseToken(),
                original.fencingToken(), original.expectedRevisionId(),
                original.expectedRevisionNumber(),
                original.expectedCheckpointVersion(),
                original.expectedEventHeadSequence(), original.stepId(),
                other, original.pausedCheckpoint());
        assertFailure(adapter.pause(advanced),
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                "stepInterruption");
        assertEquals(1, interruptions.count());
    }

    @Test
    void concurrentSamePlanAttemptsSerializeToOneImmutableWinner()
            throws Exception {
        Scenario scenario = seed("race");
        List<PersistenceResult<PersistedStepInterruption>> same =
                runTogether(
                        () -> adapter.pause(pause(scenario)),
                        () -> adapter.pause(pause(scenario)));
        assertEquals(1, same.stream().filter(result ->
                result.outcome() == PersistenceOutcome.APPLIED).count());
        assertEquals(1, same.stream().filter(result ->
                result.outcome() == PersistenceOutcome.REPLAYED).count());
        assertEquals(1, interruptions.count());

        reset();
        Scenario competing = seed("competing");
        List<PersistenceResult<PersistedStepInterruption>> different =
                runTogether(
                        () -> adapter.pause(pause(competing)),
                        () -> adapter.fail(fail(competing)));
        assertEquals(1, different.stream().filter(result ->
                result.outcome() == PersistenceOutcome.APPLIED).count());
        assertEquals(1, different.stream().filter(result ->
                result.failure().map(failure ->
                        failure.code()
                                == PersistenceErrorCode.CONFLICTING_REPLAY)
                        .orElse(false)).count());
        assertEquals(1, interruptions.count());
    }

    @Test
    void globalEventIdentityCannotAuthorizeAnotherPlan() {
        Scenario first = seed("global-a");
        invoke(first, Kind.CANCEL);
        Scenario second = seed("global-b");
        StepCancelRequest base = cancel(second);
        EventEnvelope reused = new EventEnvelope(
                cancel(first).cancellationEvent().id(),
                base.cancellationEvent().taskFrameId(),
                base.cancellationEvent().planId(), 3,
                base.cancellationEvent().occurredAt(),
                base.cancellationEvent().type(),
                base.cancellationEvent().causationId(),
                base.cancellationEvent().correlationId(),
                base.cancellationEvent().payload());
        StepCancelRequest collision = new StepCancelRequest(
                base.planId(), base.leaseToken(), base.fencingToken(),
                base.expectedRevisionId(), base.expectedRevisionNumber(),
                3, 2, base.stepId(), reused,
                base.cancelledCheckpoint());
        assertFailure(adapter.cancel(collision),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.cancellationEvent.id");
        assertEquals(1, interruptions.count());
        assertEquals(2, activations.count());
    }

    @Test
    void projectAuthorityRequiresItsExactConfirmedExecutionContext() {
        String suffix = "project-missing";
        Scenario missing = seedBootstrap(
                ProductPlanBootstrapTestFixtures.project(
                        "plan-" + suffix, "task-" + suffix),
                suffix, false);
        assertFailure(adapter.pause(pause(missing)),
                PersistenceErrorCode.STEP_INTERRUPTION_PARTIAL_STATE,
                "stepInterruption");
        assertEquals(0, interruptions.count());

        reset();
        suffix = "project-confirmed";
        Scenario confirmed = seedBootstrap(
                ProductPlanBootstrapTestFixtures.project(
                        "plan-" + suffix, "task-" + suffix),
                suffix, true);
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.cancel(cancel(confirmed)).outcome());
        assertEquals(1, interruptions.count());
    }

    private void assertApplied(Scenario scenario, Kind kind) {
        PersistenceResult<PersistedStepInterruption> result =
                invoke(scenario, kind);
        assertEquals(PersistenceOutcome.APPLIED, result.outcome());
        PersistedStepInterruption persisted =
                result.value().orElseThrow();
        assertEquals(4, persisted.interruptedCheckpoint().version());
        assertEquals(3, persisted.interruptionEvent().sequence());
        assertEquals(kind.name(), persisted.kind().name());
        ProductStepInterruptionEntity row = interruptions.findAllByPlanId(
                scenario.bootstrap().plan().id().value()).get(0);
        assertEquals(3, row.sourceCheckpointVersion());
        assertEquals(4, row.resultCheckpointVersion());
        assertEquals(2, row.sourceEventSequence());
        assertEquals(3, row.resultEventSequence());
        assertEquals(64, row.requestSha256().length());
        assertEquals(64, row.resultSha256().length());
        assertEquals(1, activations.count());
    }

    private Scenario seed(String suffix) {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.workspace(
                        "plan-" + suffix, "task-" + suffix);
        return seedBootstrap(bootstrap, suffix, false);
    }

    private Scenario seedBootstrap(
            PersistedPlanBootstrap bootstrap, String suffix,
            boolean confirmedContext) {
        String owner = "owner-" + suffix;
        String token = "token-" + suffix;
        ProductStepActivationTestFixtures.seedH0(
                bootstrap, owner, token, 1, bootstraps,
                bootstrapCodec, leases, starts, startCodec);
        if (confirmedContext) {
            ProductStepActivationTestFixtures.seedConfirmedContext(
                    bootstrap, owner, token, 1, contexts, contextCodec);
        }
        StepActivationRequest activation =
                ProductStepActivationTestFixtures.request(
                        bootstrap, token, 1,
                        "activation-" + suffix);
        PersistedStepActivation result = new PersistedStepActivation(
                bootstrap.plan().id(), activation.stepId(), owner, 1,
                activation.activationEvent(),
                new VersionedCheckpoint(
                        3, activation.activatedCheckpoint()));
        Checkpoint h0 = starts.findById(
                bootstrap.plan().id().value()).map(row ->
                startCodec.decodeResult(
                        row.resultFormatVersion(),
                        row.resultSha256(),
                        row.resultJson()).startedCheckpoint().checkpoint())
                .orElseThrow();
        activations.saveAndFlush(new ProductStepActivationEntity(
                bootstrap.plan().id().value(),
                activation.stepId().value(),
                activation.activationEvent().id().value(),
                h0.revisionId().value(), h0.revisionNumber(),
                activation.activatedCheckpoint().revisionId().value(),
                activation.activatedCheckpoint().revisionNumber(),
                2, 3, 1, 2, owner, 1,
                activationCodec.encodeRequest(activation),
                activationCodec.encodeResult(result),
                ProductStepActivationTestFixtures.NOW.plusSeconds(1)));
        return new Scenario(
                bootstrap, owner, token, activation,
                "interruption-" + suffix);
    }

    private Scenario scenarioOnly(
            PersistedPlanBootstrap bootstrap, String suffix) {
        StepActivationRequest activation =
                ProductStepActivationTestFixtures.request(
                        bootstrap, "token-" + suffix, 1,
                        "activation-" + suffix);
        return new Scenario(
                bootstrap, "owner-" + suffix, "token-" + suffix,
                activation, "interruption-" + suffix);
    }

    private StepPauseRequest pause(Scenario scenario) {
        Target target = target(scenario, Kind.PAUSE);
        return new StepPauseRequest(
                scenario.bootstrap().plan().id(), scenario.token(), 1,
                scenario.bootstrap().plan().latestRevision().id(), 1,
                3, 2, scenario.activation().stepId(),
                target.event(), target.checkpoint());
    }

    private StepFailRequest fail(Scenario scenario) {
        Target target = target(scenario, Kind.FAIL);
        return new StepFailRequest(
                scenario.bootstrap().plan().id(), scenario.token(), 1,
                scenario.bootstrap().plan().latestRevision().id(), 1,
                3, 2, scenario.activation().stepId(),
                target.event(), target.checkpoint());
    }

    private StepCancelRequest cancel(Scenario scenario) {
        Target target = target(scenario, Kind.CANCEL);
        return new StepCancelRequest(
                scenario.bootstrap().plan().id(), scenario.token(), 1,
                scenario.bootstrap().plan().latestRevision().id(), 1,
                3, 2, scenario.activation().stepId(),
                target.event(), target.checkpoint());
    }

    private Target target(Scenario scenario, Kind kind) {
        Checkpoint active = scenario.activation().activatedCheckpoint();
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(active.stepStates());
        states.put(scenario.activation().stepId(), switch (kind) {
            case PAUSE -> StepExecutionState.PAUSED;
            case FAIL -> StepExecutionState.FAILED;
            case CANCEL -> StepExecutionState.CANCELLED;
        });
        EventEnvelope event = new EventEnvelope(
                new EventId(scenario.interruptionEventId()),
                scenario.bootstrap().taskFrame().id(),
                scenario.bootstrap().plan().id(), 3,
                ProductStepActivationTestFixtures.NOW.plusSeconds(2),
                new EventType("STEP_" + kind.name() + "D"),
                Optional.of(scenario.activation().activationEvent().id()),
                "interruption-correlation",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint checkpoint = new Checkpoint(
                active.taskFrameId(), active.planId(), active.revisionId(),
                active.revisionNumber(), 3, switch (kind) {
                    case PAUSE -> PlanExecutionState.PAUSED;
                    case FAIL -> PlanExecutionState.FAILED;
                    case CANCEL -> PlanExecutionState.CANCELLED;
                }, states, active.receiptReferences(),
                active.createdAt().plusSeconds(1));
        return new Target(event, checkpoint);
    }

    private PersistenceResult<PersistedStepInterruption> invoke(
            Scenario scenario, Kind kind) {
        return switch (kind) {
            case PAUSE -> adapter.pause(pause(scenario));
            case FAIL -> adapter.fail(fail(scenario));
            case CANCEL -> adapter.cancel(cancel(scenario));
        };
    }

    private void replaceLease(
            Scenario scenario, String token, long fence, Instant expiry) {
        leases.deleteAll();
        leases.flush();
        leases.saveAndFlush(new ProductLeaseEntity(
                scenario.bootstrap().plan().id().value(), fence,
                scenario.owner(), token,
                ProductStepActivationTestFixtures.NOW.minusSeconds(1),
                expiry));
    }

    @SafeVarargs
    private static List<PersistenceResult<PersistedStepInterruption>>
            runTogether(ThrowingSupplier<PersistenceResult<
                    PersistedStepInterruption>>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch ready = new CountDownLatch(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PersistenceResult<PersistedStepInterruption>>> futures =
                    java.util.Arrays.stream(calls).map(call -> pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        return call.get();
                    })).toList();
            ready.await();
            start.countDown();
            java.util.ArrayList<PersistenceResult<PersistedStepInterruption>>
                    results = new java.util.ArrayList<>();
            for (Future<PersistenceResult<PersistedStepInterruption>> future
                    : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code, String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
        assertTrue(result.value().isEmpty());
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                        value.getBytes(StandardCharsets.UTF_8)));
    }

    private enum Kind {
        PAUSE,
        FAIL,
        CANCEL
    }

    private record Scenario(
            PersistedPlanBootstrap bootstrap,
            String owner,
            String token,
            StepActivationRequest activation,
            String interruptionEventId) {
    }

    private record Target(EventEnvelope event, Checkpoint checkpoint) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
