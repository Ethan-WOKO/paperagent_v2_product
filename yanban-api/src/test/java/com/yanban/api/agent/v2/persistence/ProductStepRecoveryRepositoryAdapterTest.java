package com.yanban.api.agent.v2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.persistence.StepRecoverySnapshot;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:v2step_recovery_behavior;"
                + "MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({
        ProductStepRecoveryRepositoryAdapter.class,
        ProductStepRecoveryTransactions.class,
        ProductStepActivationCodec.class,
        ProductExecutionStartCodec.class,
        ProductPlanBootstrapCodec.class,
        ProductPlanExecutionContextCodec.class,
        ProductStepRecoveryRepositoryAdapterTest.Configuration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductStepRecoveryRepositoryAdapterTest {
    static class Configuration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        CountingTimeSource countingTimeSource() {
            return new CountingTimeSource();
        }
    }

    static final class CountingTimeSource implements ProductLeaseTimeSource {
        private final AtomicInteger observations = new AtomicInteger();

        @Override
        public Instant observe() {
            observations.incrementAndGet();
            return ProductStepActivationTestFixtures.NOW;
        }
    }

    @jakarta.annotation.Resource
    private ProductStepRecoveryRepositoryAdapter adapter;
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
    private ProductStepActivationJpaRepository activations;
    @jakarta.annotation.Resource
    private ProductStepActivationCodec activationCodec;
    @jakarta.annotation.Resource
    private ProductLeaseJpaRepository leases;
    @jakarta.annotation.Resource
    private CountingTimeSource timeSource;
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
    }

    @Test
    void nullAbsentAndOrphanOccupancyHaveFrozenPriority() {
        assertFailure(adapter.inspect(null),
                PersistenceErrorCode.INVALID_ARGUMENT, "planId");
        assertFailure(adapter.inspect(new PlanId("absent")),
                PersistenceErrorCode.NOT_FOUND, "planId");

        PersistedPlanBootstrap orphan =
                ProductPlanBootstrapTestFixtures.workspace(
                        "orphan", "task-orphan");
        seedActivation(orphan, "owner", 1);
        assertFailure(adapter.inspect(orphan.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");
    }

    @Test
    void missingOrCorruptSourceAndCrossBoundActivationArePartial() {
        PersistedPlanBootstrap missingStart =
                ProductPlanBootstrapTestFixtures.workspace(
                        "missing-start", "task-a");
        seedBootstrap(missingStart);
        assertFailure(adapter.inspect(missingStart.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");

        reset();
        PersistedPlanBootstrap corrupt =
                ProductPlanBootstrapTestFixtures.workspace(
                        "corrupt", "task-b");
        seedH0(corrupt);
        jdbc.update("update agent_v2_execution_starts "
                        + "set result_sha256 = ? where plan_id = ?",
                "0".repeat(64), "corrupt");
        assertFailure(adapter.inspect(corrupt.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");

        reset();
        PersistedPlanBootstrap cross =
                ProductPlanBootstrapTestFixtures.workspace(
                        "cross", "task-c");
        seedH0(cross);
        seedActivation(cross, "owner", 1);
        jdbc.update("update agent_v2_step_activations "
                        + "set source_revision_id = ? where plan_id = ?",
                "other-revision", "cross");
        assertFailure(adapter.inspect(cross.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");
    }

    @Test
    void canonicalSourceWithoutActivationIsNotEligible() {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.workspace(
                        "not-active", "task-a");
        seedH0(bootstrap);
        assertFailure(adapter.inspect(bootstrap.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_NOT_ELIGIBLE,
                "stepRecovery");
    }

    @Test
    void sourceLessFoundReturnsExactImmutableV3Head() {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.workspace(
                        "found", "task-found");
        seedH0(bootstrap);
        StepActivationRequest base =
                ProductStepActivationTestFixtures.request(
                        bootstrap, "token", 7, "activation-found");
        EventEnvelope event = base.activationEvent();
        EventEnvelope noCausation = new EventEnvelope(
                event.id(), event.taskFrameId(), event.planId(),
                event.sequence(), event.occurredAt(), event.type(),
                Optional.empty(), event.correlationId(), event.payload());
        StepActivationRequest canonicalNoCausation =
                new StepActivationRequest(
                        base.planId(), base.leaseToken(), base.fencingToken(),
                        base.expectedRevisionId(),
                        base.expectedRevisionNumber(),
                        base.expectedCheckpointVersion(),
                        base.expectedEventHeadSequence(), base.stepId(),
                        noCausation, base.activatedCheckpoint());
        PersistedStepActivation activation = saveActivation(
                canonicalNoCausation, "activation-owner", 7,
                ProductStepActivationTestFixtures.NOW.plusSeconds(1));

        PersistedStepRecoveryActive found = found(
                adapter.inspect(bootstrap.plan().id()));
        assertEquals(bootstrap.taskFrame(), found.taskFrame());
        assertEquals(bootstrap.plan(), found.plan());
        assertEquals(activation.activatedCheckpoint(), found.checkpoint());
        assertEquals(activation, found.activation());
        assertEquals(Optional.empty(), found.executionContext());
        assertEquals(3, found.checkpoint().version());
        assertEquals(2, found.checkpoint().checkpoint().lastEventSequence());
        assertEquals(PersistenceOutcome.FOUND,
                adapter.inspect(bootstrap.plan().id()).outcome());
    }

    @Test
    void projectRequiresOneFullyConfirmedMatchingContext() {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.project(
                        "project", "task-project");
        seedH0(bootstrap);
        seedActivation(bootstrap, "owner", 1);
        assertFailure(adapter.inspect(bootstrap.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");

        var spec = ProductPlanExecutionContextTestFixtures.spec("project");
        var reservation =
                ProductPlanExecutionContextTestFixtures.reservation(
                        bootstrap, "token", 1, spec);
        var reserved = new io.paperagent.v2.persistence
                .PersistedPlanExecutionContextReserved(
                        bootstrap.plan().id(), spec, "owner", 1);
        contexts.saveAndFlush(new ProductPlanExecutionContextEntity(
                "project", spec.workspaceId().value(), "owner", 1,
                contextCodec.encodeReservationRequest(reservation),
                contextCodec.encodeReservationResult(reserved)));
        assertFailure(adapter.inspect(bootstrap.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");
        contexts.deleteAll();
        contexts.flush();
        ProductStepActivationTestFixtures.seedConfirmedContext(
                bootstrap, "owner", "token", 1, contexts, contextCodec);
        PersistedStepRecoveryActive found = found(
                adapter.inspect(bootstrap.plan().id()));
        assertEquals(bootstrap.taskFrame().sourceProjectVersion().orElseThrow(),
                found.executionContext().orElseThrow()
                        .materializationSpec().sourceProjectVersion());

        jdbc.update("update agent_v2_plan_execution_contexts "
                        + "set confirmation_result_sha256 = ? "
                        + "where plan_id = ?",
                "0".repeat(64), "project");
        assertFailure(adapter.inspect(bootstrap.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");
    }

    @Test
    void unexpectedContextMalformedAndMultipleActivationsArePartial() {
        PersistedPlanBootstrap workspace =
                ProductPlanBootstrapTestFixtures.workspace(
                        "unexpected-context", "task-u");
        seedH0(workspace);
        seedActivation(workspace, "owner", 1);
        PersistedPlanBootstrap project =
                ProductPlanBootstrapTestFixtures.project(
                        "unexpected-context", "task-u");
        ProductStepActivationTestFixtures.seedConfirmedContext(
                project, "owner", "token", 1, contexts, contextCodec);
        assertFailure(adapter.inspect(workspace.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");

        reset();
        PersistedPlanBootstrap malformed =
                ProductPlanBootstrapTestFixtures.workspace(
                        "malformed", "task-m");
        seedH0(malformed);
        seedActivation(malformed, "owner", 1);
        jdbc.update("update agent_v2_step_activations "
                        + "set request_sha256 = ? where plan_id = ?",
                "0".repeat(64), "malformed");
        assertFailure(adapter.inspect(malformed.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");

        reset();
        PersistedPlanBootstrap multiple =
                ProductPlanBootstrapTestFixtures.workspace(
                        "multiple", "task-x");
        seedH0(multiple);
        seedActivation(multiple, "owner", 1);
        StepActivationRequest second =
                ProductStepActivationTestFixtures.request(
                        multiple, "token", 1, "activation-second");
        saveActivation(second, "owner", 1,
                ProductStepActivationTestFixtures.NOW.plusSeconds(2));
        assertFailure(adapter.inspect(multiple.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");
    }

    @Test
    void canonicalDigestCannotHideInvalidTransitionOrEventCollision() {
        PersistedPlanBootstrap transition =
                ProductPlanBootstrapTestFixtures.workspace(
                        "bad-transition", "task-t");
        seedH0(transition);
        StepActivationRequest base =
                ProductStepActivationTestFixtures.request(
                        transition, "token", 1, "activation-transition");
        Checkpoint target = base.activatedCheckpoint();
        Checkpoint withInjectedReceipt = new Checkpoint(
                target.taskFrameId(), target.planId(), target.revisionId(),
                target.revisionNumber(), target.lastEventSequence(),
                target.planState(), target.stepStates(),
                List.of(new ReceiptId("injected-receipt")),
                target.createdAt());
        StepActivationRequest altered = new StepActivationRequest(
                base.planId(), base.leaseToken(), base.fencingToken(),
                base.expectedRevisionId(), base.expectedRevisionNumber(),
                base.expectedCheckpointVersion(),
                base.expectedEventHeadSequence(), base.stepId(),
                base.activationEvent(), withInjectedReceipt);
        saveActivation(altered, "owner", 1,
                ProductStepActivationTestFixtures.NOW.plusSeconds(1));
        assertFailure(adapter.inspect(transition.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");

        reset();
        PersistedPlanBootstrap collision =
                ProductPlanBootstrapTestFixtures.workspace(
                        "collision", "task-collision");
        seedH0(collision);
        StepActivationRequest collisionBase =
                ProductStepActivationTestFixtures.request(
                        collision, "token", 1, "activation-placeholder");
        EventEnvelope collisionEvent = collisionBase.activationEvent();
        EventEnvelope reusedEvent = new EventEnvelope(
                new io.paperagent.v2.contracts.EventId("start-collision"),
                collisionEvent.taskFrameId(), collisionEvent.planId(),
                collisionEvent.sequence(), collisionEvent.occurredAt(),
                collisionEvent.type(), Optional.empty(),
                collisionEvent.correlationId(), collisionEvent.payload());
        StepActivationRequest reusedStartId = new StepActivationRequest(
                collisionBase.planId(), collisionBase.leaseToken(),
                collisionBase.fencingToken(),
                collisionBase.expectedRevisionId(),
                collisionBase.expectedRevisionNumber(),
                collisionBase.expectedCheckpointVersion(),
                collisionBase.expectedEventHeadSequence(),
                collisionBase.stepId(), reusedEvent,
                collisionBase.activatedCheckpoint());
        saveActivation(reusedStartId, "owner", 1,
                ProductStepActivationTestFixtures.NOW.plusSeconds(1));
        assertFailure(adapter.inspect(collision.plan().id()),
                PersistenceErrorCode.STEP_RECOVERY_PARTIAL_STATE,
                "stepRecovery");
    }

    @Test
    void inspectionIgnoresExpiredDeletedAndReplacementLeasesAndNeverWrites() {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.workspace(
                        "lease-independent", "task-l");
        seedH0(bootstrap);
        seedActivation(bootstrap, "activation-owner", 1);
        long bootstrapCount = bootstraps.count();
        long startCount = starts.count();
        long activationCount = activations.count();

        leases.deleteAll();
        leases.flush();
        PersistedStepRecoveryActive withoutLease = found(
                adapter.inspect(bootstrap.plan().id()));
        leases.saveAndFlush(new ProductLeaseEntity(
                bootstrap.plan().id().value(), 99, "replacement-owner",
                "replacement-token",
                ProductStepActivationTestFixtures.NOW.plusSeconds(100),
                ProductStepActivationTestFixtures.NOW.plusSeconds(200)));
        PersistedStepRecoveryActive replacement = found(
                adapter.inspect(bootstrap.plan().id()));

        assertEquals(withoutLease, replacement);
        assertEquals("activation-owner",
                replacement.activation().leaseOwnerId());
        assertEquals(1, replacement.activation().fencingToken());
        assertEquals(bootstrapCount, bootstraps.count());
        assertEquals(startCount, starts.count());
        assertEquals(activationCount, activations.count());
        assertEquals(1, leases.count());
        assertEquals(0, timeSource.observations.get());
    }

    @Test
    void repeatedInspectionsReturnEqualFactsWithoutWrites() {
        PersistedPlanBootstrap bootstrap =
                ProductPlanBootstrapTestFixtures.workspace(
                        "repeat", "task-repeat");
        seedH0(bootstrap);
        seedActivation(bootstrap, "owner", 3);
        long rows = totalAuthorityRows();

        PersistedStepRecoveryActive first = found(
                adapter.inspect(bootstrap.plan().id()));
        PersistedStepRecoveryActive second = found(
                adapter.inspect(bootstrap.plan().id()));
        assertEquals(first, second);
        assertEquals(rows, totalAuthorityRows());
    }

    private void seedBootstrap(PersistedPlanBootstrap bootstrap) {
        ProductPlanBootstrapCodec.EncodedPayload payload =
                bootstrapCodec.encode(bootstrap);
        bootstraps.saveAndFlush(new ProductPlanBootstrapEntity(
                bootstrap.plan().id().value(),
                bootstrap.taskFrame().id().value(),
                payload.formatVersion(), payload.sha256(), payload.json(),
                ProductStepActivationTestFixtures.NOW));
    }

    private void seedH0(PersistedPlanBootstrap bootstrap) {
        ProductStepActivationTestFixtures.seedH0(
                bootstrap, "owner", "token", 1,
                bootstraps, bootstrapCodec, leases, starts, startCodec);
    }

    private PersistedStepActivation seedActivation(
            PersistedPlanBootstrap bootstrap, String owner, long fence) {
        StepActivationRequest request =
                ProductStepActivationTestFixtures.request(
                        bootstrap, "token", fence,
                        "activation-" + bootstrap.plan().id().value());
        return saveActivation(request, owner, fence,
                ProductStepActivationTestFixtures.NOW.plusSeconds(1));
    }

    private PersistedStepActivation saveActivation(
            StepActivationRequest request, String owner, long fence,
            Instant committedAt) {
        PersistedStepActivation result = new PersistedStepActivation(
                request.planId(), request.stepId(), owner, fence,
                request.activationEvent(), new VersionedCheckpoint(
                        3, request.activatedCheckpoint()));
        var target = result.activatedCheckpoint().checkpoint();
        activations.saveAndFlush(new ProductStepActivationEntity(
                request.planId().value(), request.stepId().value(),
                request.activationEvent().id().value(),
                request.expectedRevisionId().value(),
                request.expectedRevisionNumber(),
                target.revisionId().value(), target.revisionNumber(),
                request.expectedCheckpointVersion(), 3,
                request.expectedEventHeadSequence(),
                request.activationEvent().sequence(), owner, fence,
                activationCodec.encodeRequest(request),
                activationCodec.encodeResult(result), committedAt));
        return result;
    }

    private long totalAuthorityRows() {
        return bootstraps.count() + starts.count() + contexts.count()
                + activations.count() + leases.count();
    }

    private static PersistedStepRecoveryActive found(
            PersistenceResult<StepRecoverySnapshot> result) {
        assertEquals(PersistenceOutcome.FOUND, result.outcome());
        return (PersistedStepRecoveryActive) result.value().orElseThrow();
    }

    private static void assertFailure(
            PersistenceResult<?> result, PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
    }
}
