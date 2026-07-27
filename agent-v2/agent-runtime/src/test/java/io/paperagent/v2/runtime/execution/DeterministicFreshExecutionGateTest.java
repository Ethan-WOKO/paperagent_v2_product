package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.VersionedCheckpoint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicFreshExecutionGateTest {
    private static final PlanId PLAN_ID = new PlanId("plan-fresh-gate");

    @Test
    void appliedAndReplayedMapToDistinctMinimalPlanAuthority() {
        PersistedPlanBootstrap bootstrap = bootstrap(PLAN_ID, "primary");
        FreshExecutionGate gate = new DeterministicFreshExecutionGate();

        FreshExecutionDecision applied =
                gate.evaluate(PersistenceResult.applied(bootstrap));
        FreshExecutionDecision replayed =
                gate.evaluate(PersistenceResult.replayed(bootstrap));

        assertEquals(new FreshLeaseAdmissionEligible(PLAN_ID), applied);
        assertEquals(new RecoveryRequired(PLAN_ID), replayed);
        assertEquals(
                List.of("planId"),
                componentNames(FreshLeaseAdmissionEligible.class));
        assertEquals(List.of("planId"), componentNames(RecoveryRequired.class));
    }

    @Test
    void rejectedIsAValueDecisionAndPreservesTheOriginalFailure() {
        PersistenceFailure persistenceFailure = new PersistenceFailure(
                PersistenceErrorCode.CONFLICTING_REPLAY,
                "plan.id");
        PersistenceResult<PersistedPlanBootstrap> rejected =
                new PersistenceResult<>(
                        PersistenceOutcome.REJECTED,
                        Optional.empty(),
                        Optional.of(persistenceFailure));

        BootstrapRejected decision = (BootstrapRejected)
                new DeterministicFreshExecutionGate().evaluate(rejected);

        assertSame(persistenceFailure, decision.failure());
        assertEquals(PersistenceErrorCode.CONFLICTING_REPLAY,
                decision.failure().code());
        assertEquals("plan.id", decision.failure().path());
    }

    @Test
    void foundAndNullFailClosedWithStableCodeAndPath() {
        FreshExecutionGate gate = new DeterministicFreshExecutionGate();

        FreshExecutionGateValidationException foundFailure = assertThrows(
                FreshExecutionGateValidationException.class,
                () -> gate.evaluate(PersistenceResult.found(
                        bootstrap(PLAN_ID, "found"))));
        assertEquals(
                FreshExecutionGateValidationCode.UNEXPECTED_PERSISTENCE_OUTCOME,
                foundFailure.code());
        assertEquals(
                "freshExecutionGate.bootstrapResult.outcome",
                foundFailure.path());

        FreshExecutionGateValidationException nullFailure = assertThrows(
                FreshExecutionGateValidationException.class,
                () -> gate.evaluate(null));
        assertEquals(
                FreshExecutionGateValidationCode.REQUIRED_VALUE_MISSING,
                nullFailure.code());
        assertEquals(
                "freshExecutionGate.bootstrapResult",
                nullFailure.path());
    }

    @Test
    void publicDecisionAndValidationSurfacesRemainExact() {
        assertEquals(
                Set.of(
                        FreshLeaseAdmissionEligible.class,
                        RecoveryRequired.class,
                        BootstrapRejected.class),
                Set.of(FreshExecutionDecision.class.getPermittedSubclasses()));
        assertEquals(
                List.of(
                        FreshExecutionGateValidationCode.REQUIRED_VALUE_MISSING,
                        FreshExecutionGateValidationCode
                                .UNEXPECTED_PERSISTENCE_OUTCOME),
                List.of(FreshExecutionGateValidationCode.values()));

        assertRecordSurface(
                FreshLeaseAdmissionEligible.class,
                "planId",
                PlanId.class);
        assertRecordSurface(RecoveryRequired.class, "planId", PlanId.class);
        assertRecordSurface(
                BootstrapRejected.class,
                "failure",
                PersistenceFailure.class);

        assertNullMessage(
                () -> new FreshLeaseAdmissionEligible(null),
                "planId");
        assertNullMessage(() -> new RecoveryRequired(null), "planId");
        assertNullMessage(() -> new BootstrapRejected(null), "failure");
    }

    @Test
    void repeatedCrossInstanceAndInterleavedEvaluationIsDeterministic() {
        PersistedPlanBootstrap bootstrapA =
                bootstrap(new PlanId("plan-fresh-a"), "a");
        PersistedPlanBootstrap bootstrapB =
                bootstrap(new PlanId("plan-fresh-b"), "b");
        PersistenceResult<PersistedPlanBootstrap> appliedA =
                PersistenceResult.applied(bootstrapA);
        PersistenceResult<PersistedPlanBootstrap> replayedB =
                PersistenceResult.replayed(bootstrapB);
        FreshExecutionGate firstGate = new DeterministicFreshExecutionGate();
        FreshExecutionGate secondGate = new DeterministicFreshExecutionGate();

        FreshExecutionDecision firstA = firstGate.evaluate(appliedA);
        FreshExecutionDecision firstB = firstGate.evaluate(replayedB);
        FreshExecutionDecision secondA = secondGate.evaluate(appliedA);
        FreshExecutionDecision secondB = secondGate.evaluate(replayedB);
        FreshExecutionDecision repeatedA = firstGate.evaluate(appliedA);

        assertEquals(firstA, secondA);
        assertEquals(firstA, repeatedA);
        assertEquals(firstB, secondB);
        assertSame(bootstrapA, appliedA.value().orElseThrow());
        assertSame(bootstrapB, replayedB.value().orElseThrow());
        assertTrue(appliedA.failure().isEmpty());
        assertTrue(replayedB.failure().isEmpty());
    }

    private static void assertRecordSurface(
            Class<?> recordType,
            String componentName,
            Class<?> componentType) {
        assertTrue(recordType.isRecord());
        RecordComponent[] components = recordType.getRecordComponents();
        assertEquals(1, components.length);
        assertEquals(componentName, components[0].getName());
        assertEquals(componentType, components[0].getType());
    }

    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static void assertNullMessage(Runnable constructor, String message) {
        NullPointerException failure =
                assertThrows(NullPointerException.class, constructor::run);
        assertEquals(message, failure.getMessage());
    }

    private static PersistedPlanBootstrap bootstrap(
            PlanId planId,
            String suffix) {
        TaskFrameId taskFrameId = new TaskFrameId("task-fresh-" + suffix);
        Instant createdAt = Instant.parse("2026-07-24T12:00:00Z");
        TaskFrame taskFrame = new TaskFrame(
                taskFrameId,
                "Prepare " + suffix,
                List.of("paper"),
                List.of("workspace diff"),
                List.of(),
                Optional.empty(),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(),
                        NetworkPolicy.DENY_ALL,
                        List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(2),
                                1024,
                                1024,
                                1),
                        Set.of()),
                createdAt);
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-fresh-" + suffix),
                taskFrameId,
                1,
                Optional.empty(),
                "initial " + suffix,
                createdAt.plusSeconds(1),
                List.of(),
                Map.of());
        Plan plan = new Plan(planId, taskFrameId, List.of(revision));
        Checkpoint checkpoint = new Checkpoint(
                taskFrameId,
                planId,
                revision.id(),
                1,
                0,
                PlanExecutionState.NOT_STARTED,
                Map.of(),
                List.of(),
                createdAt.plusSeconds(2));
        return new PersistedPlanBootstrap(
                taskFrame,
                plan,
                new VersionedCheckpoint(1, checkpoint));
    }
}
