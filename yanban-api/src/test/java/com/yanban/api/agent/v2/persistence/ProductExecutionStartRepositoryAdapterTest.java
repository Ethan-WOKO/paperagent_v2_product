package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.ExecutionStartRequest;
import io.paperagent.v2.persistence.PersistedExecutionStart;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2execution_start_behavior;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductExecutionStartRepositoryAdapter.class,
        ProductExecutionStartTransactions.class,
        ProductExecutionStartCodec.class,
        ProductPlanBootstrapCodec.class,
        SystemProductExecutionStartTimeSource.class,
        ProductExecutionStartRepositoryAdapterTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductExecutionStartRepositoryAdapterTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        @Primary
        MutableTime time() {
            return new MutableTime();
        }
    }

    static final class MutableTime implements ProductExecutionStartTimeSource {
        private final AtomicReference<Instant> now = new AtomicReference<>(
                ProductExecutionStartTestFixtures.NOW.plusNanos(999));
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
            now.set(ProductExecutionStartTestFixtures.NOW.plusNanos(999));
            observations.set(0);
        }
    }

    @jakarta.annotation.Resource
    private ProductExecutionStartRepositoryAdapter adapter;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapJpaRepository bootstraps;
    @jakarta.annotation.Resource
    private ProductPlanBootstrapCodec bootstrapCodec;
    @jakarta.annotation.Resource
    private ProductLeaseJpaRepository leases;
    @jakarta.annotation.Resource
    private ProductExecutionStartJpaRepository starts;
    @jakarta.annotation.Resource
    private MutableTime time;
    @jakarta.annotation.Resource
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        starts.deleteAll();
        leases.deleteAll();
        bootstraps.deleteAll();
        starts.flush();
        leases.flush();
        bootstraps.flush();
        time.reset();
    }

    @Test
    void nullAndTrueMissingPlanFailWithoutTimeOrWrites() {
        assertFailure(adapter.start(null),
                PersistenceErrorCode.INVALID_ARGUMENT, "request");
        PersistedPlanBootstrap absent =
                ProductExecutionStartTestFixtures.bootstrap("missing", "task-m");
        assertFailure(adapter.start(ProductExecutionStartTestFixtures.request(
                        absent, "token", 1, "event")),
                PersistenceErrorCode.NOT_FOUND, "request.planId");
        assertEquals(0, time.observations.get());
        assertEquals(0, starts.count());
    }

    @Test
    void corruptBootstrapIsPartialBeforeTimeAndWrites() {
        Scenario scenario = seed("plan-a", "task-a", "owner-a", "token-a", 1);
        jdbc.update("update agent_v2_plan_bootstraps "
                + "set payload_sha256 = ? where plan_id = ?",
                "0".repeat(64), "plan-a");

        assertFailure(adapter.start(scenario.request()),
                PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                "executionStart");
        assertEquals(0, time.observations.get());
        assertEquals(0, starts.count());
    }

    @Test
    void impossibleLeaseOccupancyWithoutBootstrapIsPartialWithoutTime() {
        PersistedPlanBootstrap absent =
                ProductExecutionStartTestFixtures.bootstrap("orphan", "task-o");
        ProductLeaseEntity orphan = new ProductLeaseEntity(
                "orphan", 1, "owner-o", "token-o",
                ProductExecutionStartTestFixtures.NOW.minusSeconds(1),
                ProductExecutionStartTestFixtures.NOW.plusSeconds(60));
        leases.saveAndFlush(orphan);

        assertFailure(adapter.start(ProductExecutionStartTestFixtures.request(
                        absent, "token-o", 1, "event-o")),
                PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                "executionStart");
        assertEquals(0, time.observations.get());
        assertEquals(0, starts.count());
    }

    @Test
    void leaseFailuresHaveFrozenPriorityAndExactlyOneObservation() {
        PersistedPlanBootstrap bootstrap = seedBootstrap("plan-a", "task-a");
        ExecutionStartRequest request =
                ProductExecutionStartTestFixtures.request(
                        bootstrap, "token-a", 1, "event-a");

        assertFailure(adapter.start(request),
                PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");
        seedLease("plan-a", "owner-a", "token-real", 1,
                ProductExecutionStartTestFixtures.NOW.plusSeconds(60), null);
        assertFailure(adapter.start(request),
                PersistenceErrorCode.LEASE_TOKEN_INVALID, "request.leaseToken");
        replaceLease("plan-a", "owner-a", "token-a", 2,
                ProductExecutionStartTestFixtures.NOW.plusSeconds(60), null);
        assertFailure(adapter.start(request),
                PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                "request.fencingToken");
        replaceLease("plan-a", "owner-a", "token-a", 1,
                ProductExecutionStartTestFixtures.NOW, null);
        assertFailure(adapter.start(request),
                PersistenceErrorCode.LEASE_EXPIRED, "request.planId");
        replaceLease("plan-a", "owner-a", "token-a", 1,
                ProductExecutionStartTestFixtures.NOW.plusSeconds(60),
                ProductExecutionStartTestFixtures.NOW.minusSeconds(1));
        assertFailure(adapter.start(request),
                PersistenceErrorCode.LEASE_NOT_HELD, "request.planId");

        assertEquals(5, time.observations.get());
        assertEquals(0, starts.count());
    }

    @Test
    void appliesCanonicalResultThenPermanentReplayIgnoresLeaseAndTime() {
        Scenario scenario = seed("plan-a", "task-a", "owner-a", "token-a", 1);
        PersistenceResult<PersistedExecutionStart> applied =
                adapter.start(scenario.request());
        leases.findById(new ProductLeaseId("plan-a", 1)).orElseThrow()
                .releaseAt(ProductExecutionStartTestFixtures.NOW.plusSeconds(1));
        leases.flush();
        time.set(ProductExecutionStartTestFixtures.NOW.plusSeconds(999));
        PersistenceResult<PersistedExecutionStart> replayed =
                adapter.start(scenario.request());

        assertEquals(PersistenceOutcome.APPLIED, applied.outcome());
        assertEquals(PersistenceOutcome.REPLAYED, replayed.outcome());
        assertEquals(applied.value(), replayed.value());
        PersistedExecutionStart value = applied.value().orElseThrow();
        assertEquals("owner-a", value.leaseOwnerId());
        assertEquals(1, value.fencingToken());
        assertEquals(2, value.startedCheckpoint().version());
        assertEquals(1, time.observations.get());
        assertEquals(1, starts.count());
        assertEquals(ProductExecutionStartTestFixtures.NOW,
                starts.findById("plan-a").orElseThrow().committedAt());
    }

    @Test
    void differentReplayConflictsBeforeTimeOrLease() {
        Scenario scenario = seed("plan-a", "task-a", "owner-a", "token-a", 1);
        adapter.start(scenario.request());
        ExecutionStartRequest different =
                ProductExecutionStartTestFixtures.request(
                        scenario.bootstrap(), "other-token", 99, "other-event");
        int before = time.observations.get();

        assertFailure(adapter.start(different),
                PersistenceErrorCode.CONFLICTING_REPLAY, "request.planId");
        assertEquals(before, time.observations.get());
        assertEquals(1, starts.count());
    }

    @Test
    void corruptPermanentMarkerIsPartialWithoutTimeOrLeaseConsultation() {
        Scenario scenario = seed("plan-a", "task-a", "owner-a", "token-a", 1);
        adapter.start(scenario.request());
        int before = time.observations.get();
        jdbc.update("update agent_v2_execution_starts "
                        + "set request_sha256 = ? where plan_id = ?",
                "0".repeat(64), "plan-a");
        leases.deleteAll();
        leases.flush();

        assertFailure(adapter.start(scenario.request()),
                PersistenceErrorCode.EXECUTION_START_PARTIAL_STATE,
                "executionStart");
        assertEquals(before, time.observations.get());
        assertEquals(1, starts.count());
    }

    @Test
    void transitionFailuresExposeStableCodesAndPathsWithoutWrites() {
        Scenario scenario = seed("plan-a", "task-a", "owner-a", "token-a", 1);
        ExecutionStartRequest base = scenario.request();
        assertRejectedWithoutWrite(withEvent(base, new EventEnvelope(
                        base.startEvent().id(), base.startEvent().taskFrameId(),
                        new PlanId("other"), 1, base.startEvent().occurredAt(),
                        base.startEvent().type(), base.startEvent().causationId(),
                        base.startEvent().correlationId(), base.startEvent().payload())),
                PersistenceErrorCode.INVALID_ARGUMENT,
                "request.startEvent.planId");
        assertRejectedWithoutWrite(withEvent(base, new EventEnvelope(
                        base.startEvent().id(), new TaskFrameId("other"),
                        base.startEvent().planId(), 1,
                        base.startEvent().occurredAt(), base.startEvent().type(),
                        base.startEvent().causationId(),
                        base.startEvent().correlationId(), base.startEvent().payload())),
                PersistenceErrorCode.TASK_FRAME_MISMATCH,
                "request.startEvent.taskFrameId");
        assertRejectedWithoutWrite(withEvent(base, new EventEnvelope(
                        base.startEvent().id(), base.startEvent().taskFrameId(),
                        base.startEvent().planId(), 2,
                        base.startEvent().occurredAt(), base.startEvent().type(),
                        base.startEvent().causationId(),
                        base.startEvent().correlationId(), base.startEvent().payload())),
                PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                "request.startEvent.sequence");
        Checkpoint cp = base.startedCheckpoint();
        assertRejectedWithoutWrite(withCheckpoint(base, checkpoint(cp, 0,
                        cp.planState(), cp.stepStates(), cp.createdAt())),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.startedCheckpoint.lastEventSequence");
        Map<io.paperagent.v2.contracts.PlanStepId, StepExecutionState> bad =
                new LinkedHashMap<>(cp.stepStates());
        bad.replaceAll((id, state) -> StepExecutionState.ACTIVE);
        assertRejectedWithoutWrite(withCheckpoint(base, checkpoint(cp, 1,
                        PlanExecutionState.ACTIVE, bad, cp.createdAt())),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.startedCheckpoint");
        assertRejectedWithoutWrite(withCheckpoint(base, checkpoint(cp, 1,
                        PlanExecutionState.ACTIVE, cp.stepStates(),
                        scenario.bootstrap().initialCheckpoint().checkpoint()
                                .createdAt().minusNanos(1))),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.startedCheckpoint");
        assertEquals(6, time.observations.get());
    }

    @Test
    void globallyReusedEventIdConflictsForOtherPlan() {
        Scenario first = seed("plan-a", "task-a", "owner-a", "token-a", 1);
        Scenario second = seed("plan-b", "task-b", "owner-b", "token-b", 1,
                "event-a");
        assertEquals(PersistenceOutcome.APPLIED,
                adapter.start(first.request()).outcome());

        assertFailure(adapter.start(second.request()),
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "request.startEvent.id");
        assertEquals(1, starts.count());
    }

    @Test
    void unrelatedDatabaseFailurePropagatesUnchanged() {
        ProductExecutionStartTransactions transactions =
                org.mockito.Mockito.mock(ProductExecutionStartTransactions.class);
        ProductExecutionStartRepositoryAdapter isolated =
                new ProductExecutionStartRepositoryAdapter(transactions);
        Scenario scenario = seed("plan-a", "task-a", "owner-a", "token-a", 1);
        IllegalStateException failure = new IllegalStateException("synthetic database");
        org.mockito.Mockito.when(transactions.start(scenario.request()))
                .thenThrow(failure);
        assertSame(failure,
                assertThrows(IllegalStateException.class,
                        () -> isolated.start(scenario.request())));
    }

    private void assertRejectedWithoutWrite(
            ExecutionStartRequest request,
            PersistenceErrorCode code,
            String path) {
        assertFailure(adapter.start(request), code, path);
        assertEquals(0, starts.count());
    }

    private Scenario seed(
            String plan, String task, String owner, String token, long fence) {
        return seed(plan, task, owner, token, fence, "event-a");
    }

    private Scenario seed(
            String plan, String task, String owner, String token, long fence,
            String eventId) {
        PersistedPlanBootstrap bootstrap = seedBootstrap(plan, task);
        seedLease(plan, owner, token, fence,
                ProductExecutionStartTestFixtures.NOW.plusSeconds(60), null);
        return new Scenario(bootstrap,
                ProductExecutionStartTestFixtures.request(
                        bootstrap, token, fence, eventId));
    }

    private PersistedPlanBootstrap seedBootstrap(String plan, String task) {
        PersistedPlanBootstrap bootstrap =
                ProductExecutionStartTestFixtures.bootstrap(plan, task);
        var encoded = bootstrapCodec.encode(bootstrap);
        bootstraps.saveAndFlush(new ProductPlanBootstrapEntity(
                plan, task, encoded.formatVersion(), encoded.sha256(),
                encoded.json(), ProductExecutionStartTestFixtures.NOW));
        return bootstrap;
    }

    private void seedLease(
            String plan, String owner, String token, long fence,
            Instant expiry, Instant released) {
        ProductLeaseEntity lease = new ProductLeaseEntity(
                plan, fence, owner, token,
                ProductExecutionStartTestFixtures.NOW.minusSeconds(1), expiry);
        if (released != null) {
            lease.releaseAt(released);
        }
        leases.saveAndFlush(lease);
    }

    private void replaceLease(
            String plan, String owner, String token, long fence,
            Instant expiry, Instant released) {
        leases.deleteAll();
        leases.flush();
        seedLease(plan, owner, token, fence, expiry, released);
    }

    private static ExecutionStartRequest withEvent(
            ExecutionStartRequest source, EventEnvelope event) {
        return new ExecutionStartRequest(
                source.planId(), source.leaseToken(), source.fencingToken(),
                event, source.startedCheckpoint());
    }

    private static ExecutionStartRequest withCheckpoint(
            ExecutionStartRequest source, Checkpoint checkpoint) {
        return new ExecutionStartRequest(
                source.planId(), source.leaseToken(), source.fencingToken(),
                source.startEvent(), checkpoint);
    }

    private static Checkpoint checkpoint(
            Checkpoint source, long sequence, PlanExecutionState planState,
            Map<io.paperagent.v2.contracts.PlanStepId, StepExecutionState> states,
            Instant createdAt) {
        return new Checkpoint(
                source.taskFrameId(), source.planId(), source.revisionId(),
                source.revisionNumber(), sequence, planState, states,
                source.receiptReferences(), createdAt);
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }

    private record Scenario(
            PersistedPlanBootstrap bootstrap, ExecutionStartRequest request) {
    }
}
