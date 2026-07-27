package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepActivationRequest;
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
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2step_activation_behavior;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductStepActivationRepositoryAdapter.class,
        ProductStepActivationTransactions.class,
        ProductStepActivationCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductStepActivationRepositoryAdapterTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductStepActivationRepositoryAdapterTest {
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
    private ProductStepActivationRepositoryAdapter adapter;
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
    private MutableTime time;
    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        activations.deleteAll();
        contexts.deleteAll();
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
        activations.flush();
        contexts.flush();
        starts.flush();
        leases.flush();
        bootstraps.flush();
        time.reset();
    }

    @Test
    void nullMissingAndOrphanSourcesFailWithoutTimeOrWrites() {
        assertFailure(adapter.activate(null),
                PersistenceErrorCode.INVALID_ARGUMENT, "request");
        var absent = ProductPlanBootstrapTestFixtures.workspace(
                "missing", "task-m");
        assertFailure(adapter.activate(
                        ProductStepActivationTestFixtures.request(
                                absent, "token", 1, "event")),
                PersistenceErrorCode.NOT_FOUND, "request.planId");
        leases.saveAndFlush(new ProductLeaseEntity(
                "orphan", 1, "owner", "token",
                ProductStepActivationTestFixtures.NOW.minusSeconds(1),
                ProductStepActivationTestFixtures.NOW.plusSeconds(60)));
        var orphan = ProductPlanBootstrapTestFixtures.workspace(
                "orphan", "task-o");
        assertFailure(adapter.activate(
                        ProductStepActivationTestFixtures.request(
                                orphan, "token", 1, "event-o")),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertEquals(0, time.observations.get());
        assertEquals(0, activations.count());
    }

    @Test
    void projectRequiresMatchingFullyConfirmedContext() {
        Scenario scenario = seedProject(
                "plan-a", "task-a", "owner-a", "token-a", 1);
        assertFailure(adapter.activate(scenario.request()),
                PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                "stepActivation.source");
        ProductStepActivationTestFixtures.seedConfirmedContext(
                scenario.bootstrap(), "owner-a", "token-a", 1,
                contexts, contextCodec);
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.activate(scenario.request()).outcome());
        assertEquals(1, activations.count());
    }

    @Test
    void sourceLessTaskRejectsUnexpectedContextAsPartial() {
        Scenario scenario = seedWorkspace(
                "plan-a", "task-a", "owner-a", "token-a", 1);
        var project = ProductPlanBootstrapTestFixtures.project(
                "plan-a", "task-a");
        ProductStepActivationTestFixtures.seedConfirmedContext(
                project, "owner-a", "token-a", 1, contexts, contextCodec);
        assertFailure(adapter.activate(scenario.request()),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertEquals(0, time.observations.get());
    }

    @Test
    void leaseFailuresHaveFrozenPriorityAndOneObservationEach() {
        Scenario scenario = seedWorkspace(
                "plan-a", "task-a", "owner-a", "token-a", 1);
        leases.deleteAll();
        leases.flush();
        assertFailure(adapter.activate(scenario.request()),
                PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");
        replaceLease("plan-a", "owner-a", "other", 1,
                ProductStepActivationTestFixtures.NOW.plusSeconds(60), null);
        assertFailure(adapter.activate(scenario.request()),
                PersistenceErrorCode.LEASE_TOKEN_INVALID,
                "request.leaseToken");
        replaceLease("plan-a", "owner-a", "token-a", 2,
                ProductStepActivationTestFixtures.NOW.plusSeconds(60), null);
        assertFailure(adapter.activate(scenario.request()),
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");
        replaceLease("plan-a", "owner-a", "token-a", 1,
                ProductStepActivationTestFixtures.NOW, null);
        assertFailure(adapter.activate(scenario.request()),
                PersistenceErrorCode.LEASE_EXPIRED, "request.planId");
        assertEquals(4, time.observations.get());
        assertEquals(0, activations.count());
    }

    @Test
    void staleAndIneligibleH0FieldsNeverWrite() {
        Scenario scenario = seedWorkspace(
                "plan-a", "task-a", "owner-a", "token-a", 1);
        StepActivationRequest base = scenario.request();
        assertFailure(adapter.activate(copy(base,
                        new PlanRevisionId("other"), 1, 2, 1,
                        base.activationEvent(), base.activatedCheckpoint())),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedRevisionId");
        assertFailure(adapter.activate(copy(base,
                        base.expectedRevisionId(), 2, 2, 1,
                        base.activationEvent(), base.activatedCheckpoint())),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedRevisionNumber");
        assertFailure(adapter.activate(copy(base,
                        base.expectedRevisionId(), 1, 3, 1,
                        base.activationEvent(), base.activatedCheckpoint())),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedCheckpointVersion");
        assertFailure(adapter.activate(copy(base,
                        base.expectedRevisionId(), 1, 2, 2,
                        base.activationEvent(), base.activatedCheckpoint())),
                PersistenceErrorCode.STALE_VERSION,
                "request.expectedEventHeadSequence");
        var blockedStates = new LinkedHashMap<>(
                base.activatedCheckpoint().stepStates());
        blockedStates.put(new io.paperagent.v2.contracts.PlanStepId("step-a"),
                StepExecutionState.NOT_STARTED);
        blockedStates.put(new io.paperagent.v2.contracts.PlanStepId("step-b"),
                StepExecutionState.ACTIVE);
        Checkpoint blockedCheckpoint = new Checkpoint(
                base.activatedCheckpoint().taskFrameId(),
                base.activatedCheckpoint().planId(),
                base.activatedCheckpoint().revisionId(),
                base.activatedCheckpoint().revisionNumber(),
                base.activatedCheckpoint().lastEventSequence(),
                base.activatedCheckpoint().planState(), blockedStates,
                base.activatedCheckpoint().receiptReferences(),
                base.activatedCheckpoint().createdAt());
        StepActivationRequest blockedDependency = new StepActivationRequest(
                base.planId(), base.leaseToken(), base.fencingToken(),
                base.expectedRevisionId(), base.expectedRevisionNumber(),
                base.expectedCheckpointVersion(),
                base.expectedEventHeadSequence(),
                new io.paperagent.v2.contracts.PlanStepId("step-b"),
                base.activationEvent(), blockedCheckpoint);
        assertFailure(adapter.activate(blockedDependency),
                PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                "stepActivation.source");
        assertEquals(0, activations.count());
    }

    @Test
    void eventAndCheckpointValidationFailWithoutPartialWrite() {
        Scenario scenario = seedWorkspace(
                "plan-a", "task-a", "owner-a", "token-a", 1);
        StepActivationRequest base = scenario.request();
        EventEnvelope wrongTask = new EventEnvelope(
                base.activationEvent().id(), new TaskFrameId("other"),
                base.activationEvent().planId(),
                base.activationEvent().sequence(),
                base.activationEvent().occurredAt(),
                base.activationEvent().type(),
                base.activationEvent().causationId(),
                base.activationEvent().correlationId(),
                base.activationEvent().payload());
        assertFailure(adapter.activate(copy(base,
                        base.expectedRevisionId(), 1, 2, 1,
                        wrongTask, base.activatedCheckpoint())),
                PersistenceErrorCode.TASK_FRAME_MISMATCH,
                "request.activationEvent.taskFrameId");
        Checkpoint cp = base.activatedCheckpoint();
        var states = new LinkedHashMap<>(cp.stepStates());
        states.put(base.stepId(), StepExecutionState.NOT_STARTED);
        Checkpoint unchanged = new Checkpoint(
                cp.taskFrameId(), cp.planId(), cp.revisionId(),
                cp.revisionNumber(), cp.lastEventSequence(), cp.planState(),
                states, cp.receiptReferences(), cp.createdAt());
        assertFailure(adapter.activate(copy(base,
                        base.expectedRevisionId(), 1, 2, 1,
                        base.activationEvent(), unchanged)),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.activatedCheckpoint");
        assertEquals(0, activations.count());
    }

    @Test
    void appliesThenPermanentReplayAfterExpiryWithoutSecondTimeRead() {
        Scenario scenario = seedWorkspace(
                "plan-a", "task-a", "owner-a", "token-a", 1);
        PersistenceResult<PersistedStepActivation> applied =
                adapter.activate(scenario.request());
        time.set(ProductStepActivationTestFixtures.NOW.plusSeconds(600));
        leases.deleteAll();
        leases.flush();
        PersistenceResult<PersistedStepActivation> replayed =
                adapter.activate(scenario.request());

        assertEquals(PersistenceOutcome.APPLIED, applied.outcome());
        assertEquals(PersistenceOutcome.REPLAYED, replayed.outcome());
        assertEquals(applied.value(), replayed.value());
        assertEquals("owner-a",
                applied.value().orElseThrow().leaseOwnerId());
        assertEquals(1, applied.value().orElseThrow().fencingToken());
        assertEquals(1, time.observations.get());
        assertEquals(1, activations.count());
        ProductStepActivationEntity row = activations.findById(
                "activation-plan-a").orElseThrow();
        assertEquals(2, row.sourceCheckpointVersion());
        assertEquals(3, row.resultCheckpointVersion());
        assertEquals(1, row.sourceEventSequence());
        assertEquals(2, row.resultEventSequence());
        assertEquals(64, row.requestSha256().length());
        assertEquals(64, row.resultSha256().length());
    }

    @Test
    void conflictingReplayAndCorruptRowFailBeforeLeaseOrTime() {
        Scenario scenario = seedWorkspace(
                "plan-a", "task-a", "owner-a", "token-a", 1);
        adapter.activate(scenario.request());
        int observed = time.observations.get();
        StepActivationRequest different =
                ProductStepActivationTestFixtures.request(
                        scenario.bootstrap(), "other-token", 99,
                        "activation-plan-a");
        assertFailure(adapter.activate(different),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.activationEvent.id");
        assertEquals(observed, time.observations.get());
        jdbc.update("update agent_v2_step_activations "
                        + "set result_sha256 = ? where activation_event_id = ?",
                "0".repeat(64), "activation-plan-a");
        assertFailure(adapter.activate(scenario.request()),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertEquals(observed, time.observations.get());
        assertEquals(1, activations.count());
    }

    @Test
    void occupiedDifferentEventAndExtractedCrossLinkCorruptionArePartial() {
        Scenario scenario = seedWorkspace(
                "plan-a", "task-a", "owner-a", "token-a", 1);
        adapter.activate(scenario.request());
        int observed = time.observations.get();
        StepActivationRequest different =
                ProductStepActivationTestFixtures.request(
                        scenario.bootstrap(), "token-a", 1,
                        "other-activation");
        assertFailure(adapter.activate(different),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertEquals(observed, time.observations.get());

        jdbc.update("update agent_v2_step_activations "
                        + "set source_revision_id = ? "
                        + "where activation_event_id = ?",
                "other-revision", "activation-plan-a");
        assertFailure(adapter.activate(scenario.request()),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        assertEquals(observed, time.observations.get());
        assertEquals(1, activations.count());
    }

    @Test
    void corruptSourceAndExistingStartEventIdFailClosedWithoutWrite() {
        Scenario corrupt = seedWorkspace(
                "plan-a", "task-a", "owner-a", "token-a", 1);
        jdbc.update("update agent_v2_execution_starts "
                        + "set result_sha256 = ? where plan_id = ?",
                "0".repeat(64), "plan-a");
        assertFailure(adapter.activate(corrupt.request()),
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                "stepActivation");
        reset();
        Scenario collision = seedWorkspace(
                "plan-b", "task-b", "owner-b", "token-b", 1);
        StepActivationRequest base = collision.request();
        EventEnvelope reused = new EventEnvelope(
                new io.paperagent.v2.contracts.EventId("start-plan-b"),
                base.activationEvent().taskFrameId(),
                base.activationEvent().planId(),
                base.activationEvent().sequence(),
                base.activationEvent().occurredAt(),
                base.activationEvent().type(), java.util.Optional.empty(),
                base.activationEvent().correlationId(),
                base.activationEvent().payload());
        StepActivationRequest sameAsStart = copy(
                base, base.expectedRevisionId(),
                base.expectedRevisionNumber(),
                base.expectedCheckpointVersion(),
                base.expectedEventHeadSequence(),
                reused, base.activatedCheckpoint());
        assertFailure(adapter.activate(sameAsStart),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.activationEvent.id");
        assertEquals(0, activations.count());
    }

    private Scenario seedWorkspace(
            String plan, String task, String owner, String token, long fence) {
        return seed(ProductPlanBootstrapTestFixtures.workspace(plan, task),
                owner, token, fence);
    }

    private Scenario seedProject(
            String plan, String task, String owner, String token, long fence) {
        return seed(ProductPlanBootstrapTestFixtures.project(plan, task),
                owner, token, fence);
    }

    private Scenario seed(
            PersistedPlanBootstrap bootstrap,
            String owner, String token, long fence) {
        ProductStepActivationTestFixtures.seedH0(
                bootstrap, owner, token, fence, bootstraps, bootstrapCodec,
                leases, starts, startCodec);
        return new Scenario(bootstrap,
                ProductStepActivationTestFixtures.request(
                        bootstrap, token, fence,
                        "activation-" + bootstrap.plan().id().value()));
    }

    private void replaceLease(
            String plan, String owner, String token, long fence,
            Instant expiry, Instant released) {
        leases.deleteAll();
        leases.flush();
        ProductLeaseEntity row = new ProductLeaseEntity(
                plan, fence, owner, token,
                ProductStepActivationTestFixtures.NOW.minusSeconds(1),
                expiry);
        if (released != null) {
            row.releaseAt(released);
        }
        leases.saveAndFlush(row);
    }

    private static StepActivationRequest copy(
            StepActivationRequest source, PlanRevisionId revisionId,
            long revisionNumber, long checkpointVersion, long eventSequence,
            EventEnvelope event, Checkpoint checkpoint) {
        return new StepActivationRequest(
                source.planId(), source.leaseToken(), source.fencingToken(),
                revisionId, revisionNumber, checkpointVersion, eventSequence,
                source.stepId(), event, checkpoint);
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code, String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    private record Scenario(
            PersistedPlanBootstrap bootstrap,
            StepActivationRequest request) {
    }
}
