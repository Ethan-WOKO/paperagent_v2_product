package io.paperagent.v2.runtime.execution;

import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.InMemoryPersistence;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.bootstrap.DefaultPersistentPlanBootstrapper;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapRequest;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import io.paperagent.v2.runtime.checkpoint.DeterministicInitialCheckpointFreezer;
import io.paperagent.v2.runtime.planning.DeterministicInitialPlanFreezer;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import io.paperagent.v2.runtime.taskframe.DeterministicTaskFrameFreezer;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import io.paperagent.v2.runtime.taskframe.TaskFrameFreezeRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FreshExecutionGateBootstrapIntegrationTest {
    private static final PlanId PLAN_ID =
            new PlanId("plan-fresh-gate-integration");
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-24T13:00:00Z");

    @Test
    void realBootstrapMapsApplyReplayAndAuthorityConflictWithoutSnapshotLeakage() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        PersistentPlanBootstrapper bootstrapper =
                new DefaultPersistentPlanBootstrapper(
                        new DeterministicTaskFrameFreezer(),
                        new DeterministicInitialPlanFreezer(),
                        new DeterministicInitialCheckpointFreezer(),
                        persistence.planBootstraps());
        FreshExecutionGate gate = new DeterministicFreshExecutionGate();
        PersistentPlanBootstrapRequest original =
                request("original", new TaskFrameId("task-fresh-original"));

        PersistenceResult<PersistedPlanBootstrap> first =
                bootstrapper.bootstrap(original);
        FreshExecutionDecision freshDecision = gate.evaluate(first);
        PersistenceResult<PersistedPlanBootstrap> retry =
                bootstrapper.bootstrap(original);
        FreshExecutionDecision recoveryDecision = gate.evaluate(retry);
        PersistenceResult<PersistedPlanBootstrap> conflict =
                bootstrapper.bootstrap(
                        request(
                                "conflict",
                                new TaskFrameId("task-fresh-conflict")));
        FreshExecutionDecision rejectedDecision = gate.evaluate(conflict);

        assertEquals(PersistenceOutcome.APPLIED, first.outcome());
        assertEquals(
                new FreshLeaseAdmissionEligible(PLAN_ID),
                freshDecision);
        assertEquals(PersistenceOutcome.REPLAYED, retry.outcome());
        assertEquals(new RecoveryRequired(PLAN_ID), recoveryDecision);

        assertEquals(PersistenceOutcome.REJECTED, conflict.outcome());
        BootstrapRejected rejected = (BootstrapRejected) rejectedDecision;
        assertSame(conflict.failure().orElseThrow(), rejected.failure());
        assertEquals(
                PersistenceErrorCode.CONFLICTING_REPLAY,
                rejected.failure().code());
        assertEquals("plan.id", rejected.failure().path());
    }

    private static PersistentPlanBootstrapRequest request(
            String suffix,
            TaskFrameId taskFrameId) {
        RoutingDecision routingDecision = new RoutingDecision(
                new RoutingRequestId("route-fresh-" + suffix),
                Route.PERSISTENT_PLAN_EXECUTE,
                RoutingDecisionReason.DECLARED_REQUIREMENT,
                Set.of(RoutingRequirement.PROJECT_FILE_ACCESS));
        TaskFrameFreezeRequest taskFrameRequest = new TaskFrameFreezeRequest(
                routingDecision,
                taskFrameId,
                new TaskFrameDraft(
                        "Prepare " + suffix,
                        List.of("paper"),
                        List.of("workspace diff"),
                        List.of("preserve citations")),
                Optional.empty(),
                executionProfile(),
                CREATED_AT);
        return new PersistentPlanBootstrapRequest(
                taskFrameRequest,
                PLAN_ID,
                new PlanRevisionId("revision-fresh-" + suffix),
                new InitialPlanDraft("initial " + suffix, List.of()),
                CREATED_AT.plusSeconds(1),
                CREATED_AT.plusSeconds(2));
    }

    private static ExecutionProfile executionProfile() {
        return new ExecutionProfile(
                ExecutionTier.SANDBOX_STANDARD,
                Set.of(Capability.READ_PROJECT),
                NetworkPolicy.DENY_ALL,
                List.of(),
                new ResourceLimits(
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(2),
                        1024,
                        1024,
                        1),
                Set.of());
    }
}
