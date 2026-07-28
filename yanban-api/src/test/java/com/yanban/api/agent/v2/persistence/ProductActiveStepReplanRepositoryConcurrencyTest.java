package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.persistence.ActiveStepReplanRequest;
import io.paperagent.v2.persistence.EffectIntentRequest;
import io.paperagent.v2.persistence.PersistedActiveStepReplan;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepCancelRequest;
import io.paperagent.v2.persistence.StepFailRequest;
import io.paperagent.v2.persistence.StepPauseRequest;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.StepCompletionRequest;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2_active_replan_concurrency;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductActiveStepReplanRepositoryAdapter.class,
        ProductActiveStepReplanTransactions.class,
        ProductActiveStepReplanMarkerReader.class,
        ProductActiveStepReplanCodec.class,
        ProductEffectIntentRepositoryAdapter.class,
        ProductEffectIntentTransactions.class,
        ProductStepActivationRepositoryAdapter.class,
        ProductStepActivationTransactions.class,
        ProductStepInterruptionRepositoryAdapter.class,
        ProductStepInterruptionTransactions.class,
        ProductStepCompletionRepositoryAdapter.class,
        ProductStepCompletionTransactions.class,
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
class ProductActiveStepReplanRepositoryConcurrencyTest {
    @jakarta.annotation.Resource
    private ProductActiveStepReplanRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductStepInterruptionRepositoryAdapter interruptionAdapter;
    @jakarta.annotation.Resource
    private ProductStepActivationRepositoryAdapter activationAdapter;
    @jakarta.annotation.Resource
    private ProductStepCompletionRepositoryAdapter completionAdapter;
    @jakarta.annotation.Resource
    private ProductStepRecoveryRepositoryAdapter recoveryAdapter;
    @jakarta.annotation.Resource
    private ProductEffectIntentRepositoryAdapter effectIntentAdapter;
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
    private ProductStepInterruptionJpaRepository interruptions;
    @jakarta.annotation.Resource
    private ProductStepCompletionJpaRepository completions;
    @jakarta.annotation.Resource
    private ProductStepCompletionEvidenceJpaRepository completionEvidence;
    @jakarta.annotation.Resource
    private ProductEffectIntentJpaRepository effectIntents;

    @BeforeEach
    void reset() {
        completionEvidence.deleteAll();
        completions.deleteAll();
        replans.deleteAll();
        effectIntents.deleteAll();
        interruptions.deleteAll();
        activations.deleteAll();
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
    }

    @Test
    void eightCanonicalReadersConvergeOnOneImmutableFact()
            throws Exception {
        var codec = new ProductActiveStepReplanCodec(
                new ObjectMapper());
        var request =
                ProductActiveStepReplanTestSupport.request(
                        "concurrent");
        var payload = codec.encodeRequest(request);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> pool.submit(() ->
                            codec.decodeRequest(
                                    payload.formatVersion(),
                                    payload.sha256(),
                                    payload.json())))
                    .toList();
            for (var future : futures) {
                assertEquals(request, future.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void eightDatabaseContendersCommitOneMarker() throws Exception {
        Scenario scenario = seedActive("race");
        var request = scenario.request();

        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(ignored -> pool.submit(() -> {
                        start.await();
                        return adapter.supersedeAndReplan(request)
                                .outcome();
                    })).toList();
            start.countDown();
            int applied = 0;
            int replayed = 0;
            for (var future : futures) {
                PersistenceOutcome outcome = future.get();
                applied += outcome == PersistenceOutcome.APPLIED
                        ? 1 : 0;
                replayed += outcome == PersistenceOutcome.REPLAYED
                        ? 1 : 0;
            }
            assertEquals(1, applied);
            assertEquals(7, replayed);
            assertEquals(1, replans.count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replanFirstMakesObsoleteInterruptionIneligible() {
        Scenario scenario = seedActive("replan-first-interruption");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.supersedeAndReplan(
                        scenario.request()).outcome());

        var obsolete = interruptionAdapter.cancel(
                cancellation(scenario, "obsolete-interruption"));
        assertEquals(PersistenceOutcome.REJECTED,
                obsolete.outcome(),
                "an obsolete Step interruption must not commit after replan");
        assertEquals(1, replans.count());
        assertEquals(0, interruptions.count());
    }

    @Test
    void replacementActiveStepSupportsPauseFailAndCancel() {
        for (InterruptionKind kind : InterruptionKind.values()) {
            reset();
            Scenario scenario = seedActive(
                    "replacement-interruption-"
                            + kind.name().toLowerCase());
            PersistedActiveStepReplan replan = adapter
                    .supersedeAndReplan(scenario.request())
                    .value().orElseThrow();
            StepActivationRequest activation =
                    ProductActiveStepReplanTestSupport.activationAfter(
                            replan, "lease-token",
                            "after-replan-interruption-"
                                    + kind.name().toLowerCase());
            var activationResult = activationAdapter.activate(activation);
            assertEquals(PersistenceOutcome.APPLIED,
                    activationResult.outcome(),
                    () -> "replacement activation rejected: "
                            + activationResult.failure());
            PersistedStepActivation active =
                    activationResult.value().orElseThrow();

            PersistenceResult<?> result =
                    interruptReplacement(
                            kind, replan, active,
                            "replacement-" + kind.name().toLowerCase());

            assertEquals(PersistenceOutcome.APPLIED, result.outcome());
            assertEquals(PersistenceOutcome.REPLAYED,
                    interruptReplacement(
                            kind, replan, active,
                            "replacement-"
                                    + kind.name().toLowerCase())
                            .outcome());
            var terminal = recoveryAdapter.inspect(replan.planId());
            assertEquals(PersistenceOutcome.REJECTED,
                    terminal.outcome());
            assertEquals(
                    io.paperagent.v2.persistence.PersistenceErrorCode
                            .STEP_RECOVERY_NOT_ELIGIBLE,
                    terminal.failure().orElseThrow().code());
            assertEquals(1, interruptions.count());
        }
    }

    @Test
    void interruptionAndReplanRaceCommitOneAuthorityChain()
            throws Exception {
        Scenario scenario = seedActive("interruption-race");
        List<PersistenceResult<?>> outcomes = race(List.of(
                () -> adapter.supersedeAndReplan(
                        scenario.request()),
                () -> interruptionAdapter.cancel(
                        cancellation(scenario,
                                "raced-interruption"))));
        assertEquals(1, outcomes.stream()
                .filter(result -> result.outcome()
                        == PersistenceOutcome.APPLIED)
                .count());
        assertEquals(1, outcomes.stream()
                .filter(result -> result.outcome()
                        == PersistenceOutcome.REJECTED)
                .count());
        assertEquals(1, replans.count() + interruptions.count());
        assertTrue(replans.count() == 0
                || interruptions.count() == 0);
    }

    @Test
    void completionAndReplanRaceCommitOneAuthorityChain()
            throws Exception {
        Scenario scenario = seedActive("completion-race");
        List<PersistenceResult<?>> outcomes = race(List.of(
                () -> adapter.supersedeAndReplan(
                        scenario.request()),
                () -> completionAdapter.complete(completion(
                        scenario, "raced-completion"))));
        assertEquals(1, outcomes.stream()
                .filter(result -> result.outcome()
                        == PersistenceOutcome.APPLIED)
                .count());
        assertEquals(1, outcomes.stream()
                .filter(result -> result.outcome()
                        == PersistenceOutcome.REJECTED)
                .count());
        assertEquals(1, replans.count() + completions.count());
        assertTrue(replans.count() == 0
                || completions.count() == 0);
    }

    @Test
    void effectIntentAndReplanRaceCommitOneAuthorityChain()
            throws Exception {
        Scenario scenario = seedActive("intent-replan-race");
        EffectIntentRequest intent = new EffectIntentRequest(
                new EffectIntent(
                        new ToolCallId("tool-intent-replan-race"),
                        scenario.request().planId(),
                        scenario.request().activeStepId(),
                        "literature.search",
                        new ObjectValue(Map.of())),
                "lease-token", 3,
                scenario.activation().activationEvent().id());

        var outcomes = race(List.of(
                () -> effectIntentAdapter.persist(intent),
                () -> adapter.supersedeAndReplan(
                        scenario.request())));

        assertEquals(2, outcomes.size());
        assertEquals(1, effectIntents.count() + replans.count());
        assertTrue(effectIntents.count() == 0
                || replans.count() == 0);
    }

    @Test
    void v54EventIdCannotBeReusedByActivationWriter() {
        Scenario replan = seedActive("v54-id-owner");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.supersedeAndReplan(
                        replan.request()).outcome());
        String v54EventId = replan.request()
                .supersessionEvent().id().value();

        var activationBootstrap =
                ProductPlanBootstrapTestFixtures.workspace(
                        "plan-v54-activation-collision",
                        "task-v54-activation-collision");
        ProductStepActivationTestFixtures.seedH0(
                activationBootstrap, "activation-owner",
                "activation-token", 8, bootstraps,
                bootstrapCodec, leases, starts, startCodec);
        StepActivationRequest collidingActivation =
                ProductStepActivationTestFixtures.request(
                        activationBootstrap, "activation-token", 8,
                        v54EventId);
        assertEquals(PersistenceOutcome.REJECTED,
                activationAdapter.activate(
                        collidingActivation).outcome(),
                "activation must reject a V54-owned event ID");
    }

    @Test
    void v54EventIdCannotBeReusedByInterruptionWriter() {
        Scenario replan = seedActive("v54-interruption-owner");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.supersedeAndReplan(
                        replan.request()).outcome());
        String v54EventId = replan.request()
                .supersessionEvent().id().value();
        leases.deleteAll();
        leases.flush();
        Scenario interruption =
                seedActive("v54-interruption-collision");
        assertEquals(PersistenceOutcome.REJECTED,
                interruptionAdapter.cancel(cancellation(
                        interruption, v54EventId)).outcome(),
                "interruption must reject a V54-owned event ID");
    }

    @Test
    void v54EventIdCannotBeReusedByCompletionWriter() {
        Scenario replan = seedActive("v54-completion-owner");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.supersedeAndReplan(
                        replan.request()).outcome());
        String v54EventId = replan.request()
                .supersessionEvent().id().value();
        var completion = ProductEffectIntentTestFixtures.seed(
                "plan-v54-completion-collision",
                "task-v54-completion-collision",
                "completion-owner", "completion-token", 9,
                bootstraps, bootstrapCodec, leases, starts,
                startCodec, activations, activationCodec);
        StepCompletionRequest collidingCompletion =
                ProductStepCompletionTestFixtures.request(
                        completion, "completion-token", 9,
                        v54EventId, List.of());
        assertEquals(PersistenceOutcome.REJECTED,
                completionAdapter.complete(
                        collidingCompletion).outcome(),
                "completion must reject a V54-owned event ID");
    }

    @Test
    void v54WriterRejectsLifecycleOwnedEventIds() {
        Scenario interruption =
                seedActive("reverse-interruption-owner");
        String interruptionEventId =
                "reverse-owned-interruption";
        assertEquals(PersistenceOutcome.APPLIED,
                interruptionAdapter.cancel(cancellation(
                        interruption,
                        interruptionEventId)).outcome());

        leases.deleteAll();
        leases.flush();
        Scenario replan = seedActive("reverse-v54");
        ActiveStepReplanRequest collision =
                withSupersessionEventId(
                        replan.request(), interruptionEventId);
        assertEquals(PersistenceOutcome.REJECTED,
                adapter.supersedeAndReplan(collision).outcome());
        assertEquals(0, replans
                .findAllByPlanIdOrderBySourceEventSequenceAsc(
                        collision.planId().value()).size());
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
        return new Scenario(request, bootstrap, activation);
    }

    private static StepCancelRequest cancellation(
            Scenario scenario, String eventId) {
        Checkpoint active =
                scenario.activation().activatedCheckpoint();
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(active.stepStates());
        states.put(scenario.activation().stepId(),
                StepExecutionState.CANCELLED);
        EventEnvelope event = new EventEnvelope(
                new EventId(eventId),
                active.taskFrameId(), active.planId(), 3,
                active.createdAt().plusSeconds(1),
                new EventType("STEP_CANCELLED"),
                Optional.of(
                        scenario.activation()
                                .activationEvent().id()),
                "interruption-race",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint checkpoint = new Checkpoint(
                active.taskFrameId(), active.planId(),
                active.revisionId(), active.revisionNumber(),
                3, PlanExecutionState.CANCELLED,
                states, active.receiptReferences(),
                active.createdAt().plusSeconds(1));
        return new StepCancelRequest(
                scenario.request().planId(), "lease-token", 3,
                scenario.request().expectedRevisionId(),
                scenario.request().expectedRevisionNumber(),
                3, 2, scenario.activation().stepId(),
                event, checkpoint);
    }

    private static StepCompletionRequest completion(
            Scenario scenario, String eventId) {
        PlanRevision source =
                scenario.bootstrap().plan().latestRevision();
        CompletionFact fact = new CompletionFact(
                scenario.activation().stepId(),
                "completion-outcome", ProductStepActivationTestFixtures.NOW
                        .plusSeconds(2),
                List.of());
        PlanRevision completed = new PlanRevision(
                new PlanRevisionId("revision-" + eventId),
                source.taskFrameId(), source.number() + 1,
                Optional.of(source.id()), "step completed",
                ProductStepActivationTestFixtures.NOW.plusSeconds(2),
                source.steps(),
                Map.of(scenario.activation().stepId(), fact));
        Checkpoint active =
                scenario.activation().activatedCheckpoint();
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(active.stepStates());
        states.put(scenario.activation().stepId(),
                StepExecutionState.SUCCEEDED);
        EventEnvelope event = new EventEnvelope(
                new EventId(eventId), active.taskFrameId(),
                active.planId(), 3,
                ProductStepActivationTestFixtures.NOW.plusSeconds(2),
                new EventType("STEP_COMPLETED"),
                Optional.of(
                        scenario.activation()
                                .activationEvent().id()),
                "completion-race",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint checkpoint = new Checkpoint(
                active.taskFrameId(), active.planId(),
                completed.id(), completed.number(), 3,
                PlanExecutionState.SUCCEEDED,
                states, List.of(),
                ProductStepActivationTestFixtures.NOW.plusSeconds(2));
        return new StepCompletionRequest(
                scenario.request().planId(), "lease-token", 3,
                source.id(), source.number(), 3, 2,
                scenario.activation().stepId(), fact,
                event, completed, checkpoint);
    }

    private PersistenceResult<?> interruptReplacement(
            InterruptionKind kind,
            PersistedActiveStepReplan replan,
            PersistedStepActivation active,
            String suffix) {
        Checkpoint source = active.activatedCheckpoint().checkpoint();
        Map<PlanStepId, StepExecutionState> states =
                new LinkedHashMap<>(source.stepStates());
        states.put(active.stepId(), switch (kind) {
            case PAUSE -> StepExecutionState.PAUSED;
            case FAIL -> StepExecutionState.FAILED;
            case CANCEL -> StepExecutionState.CANCELLED;
        });
        EventEnvelope event = new EventEnvelope(
                new EventId("event-" + suffix),
                source.taskFrameId(), source.planId(),
                source.lastEventSequence() + 1,
                source.createdAt().plusSeconds(1),
                new EventType("STEP_" + kind.name() + "D"),
                Optional.of(active.activationEvent().id()),
                "replacement-interruption",
                new InlineEventPayload(new ObjectValue(Map.of())));
        Checkpoint target = new Checkpoint(
                source.taskFrameId(), source.planId(),
                source.revisionId(), source.revisionNumber(),
                event.sequence(), switch (kind) {
                    case PAUSE -> PlanExecutionState.PAUSED;
                    case FAIL -> PlanExecutionState.FAILED;
                    case CANCEL -> PlanExecutionState.CANCELLED;
                }, states, source.receiptReferences(),
                source.createdAt().plusSeconds(1));
        return switch (kind) {
            case PAUSE -> interruptionAdapter.pause(
                    new StepPauseRequest(
                            replan.planId(), "lease-token",
                            active.fencingToken(),
                            source.revisionId(),
                            source.revisionNumber(),
                            active.activatedCheckpoint().version(),
                            source.lastEventSequence(),
                            active.stepId(), event, target));
            case FAIL -> interruptionAdapter.fail(
                    new StepFailRequest(
                            replan.planId(), "lease-token",
                            active.fencingToken(),
                            source.revisionId(),
                            source.revisionNumber(),
                            active.activatedCheckpoint().version(),
                            source.lastEventSequence(),
                            active.stepId(), event, target));
            case CANCEL -> interruptionAdapter.cancel(
                    new StepCancelRequest(
                            replan.planId(), "lease-token",
                            active.fencingToken(),
                            source.revisionId(),
                            source.revisionNumber(),
                            active.activatedCheckpoint().version(),
                            source.lastEventSequence(),
                            active.stepId(), event, target));
        };
    }

    private static ActiveStepReplanRequest withSupersessionEventId(
            ActiveStepReplanRequest source, String eventId) {
        EventEnvelope supersession = new EventEnvelope(
                new EventId(eventId),
                source.supersessionEvent().taskFrameId(),
                source.supersessionEvent().planId(),
                source.supersessionEvent().sequence(),
                source.supersessionEvent().occurredAt(),
                source.supersessionEvent().type(),
                source.supersessionEvent().causationId(),
                source.supersessionEvent().correlationId(),
                source.supersessionEvent().payload());
        EventEnvelope replan = new EventEnvelope(
                source.replanEvent().id(),
                source.replanEvent().taskFrameId(),
                source.replanEvent().planId(),
                source.replanEvent().sequence(),
                source.replanEvent().occurredAt(),
                source.replanEvent().type(),
                Optional.of(supersession.id()),
                source.replanEvent().correlationId(),
                source.replanEvent().payload());
        return new ActiveStepReplanRequest(
                source.planId(), source.leaseToken(),
                source.fencingToken(),
                source.expectedRevisionId(),
                source.expectedRevisionNumber(),
                source.expectedCheckpointVersion(),
                source.expectedEventHeadSequence(),
                source.activeStepId(), supersession,
                source.supersededCheckpoint(), replan,
                source.replannedRevision(),
                source.replannedCheckpoint());
    }

    private static List<PersistenceResult<?>> race(
            List<Callable<? extends PersistenceResult<?>>> calls)
            throws Exception {
        var pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<? extends PersistenceResult<?>>> futures =
                    new java.util.ArrayList<>();
            for (Callable<? extends PersistenceResult<?>> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "race start timed out");
                    }
                    return call.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<PersistenceResult<?>> outcomes =
                    new java.util.ArrayList<>();
            for (Future<? extends PersistenceResult<?>> future
                    : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private enum InterruptionKind {
        PAUSE,
        FAIL,
        CANCEL
    }

    private record Scenario(
            ActiveStepReplanRequest request,
            PersistedPlanBootstrap bootstrap,
            StepActivationRequest activation) {
    }
}
