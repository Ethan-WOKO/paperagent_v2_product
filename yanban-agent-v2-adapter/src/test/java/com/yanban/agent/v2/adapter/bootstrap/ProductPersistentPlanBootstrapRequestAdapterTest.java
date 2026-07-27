package com.yanban.agent.v2.adapter.bootstrap;

import com.yanban.agent.v2.adapter.taskframe.DeterministicProductTaskFrameAdapter;
import com.yanban.agent.v2.adapter.taskframe.ProductTaskFrameBinding;
import com.yanban.agent.v2.adapter.taskframe.ProductTaskFrameIntakeRequest;
import com.yanban.agent.v2.adapter.taskframe.ProductTaskFramePreparation;
import com.yanban.agent.v2.adapter.taskframe.ProductTaskFrameValidationCode;
import com.yanban.agent.v2.adapter.taskframe.ProductTaskFrameValidationException;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapRequest;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import io.paperagent.v2.runtime.taskframe.DeterministicTaskFrameFreezer;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductPersistentPlanBootstrapRequestAdapterTest {
    private static final Instant TASK_FRAME_TIME = Instant.parse("2026-07-27T09:00:00Z");
    private static final Instant PLAN_TIME = Instant.parse("2026-07-27T09:00:01Z");
    private static final Instant CHECKPOINT_TIME = Instant.parse("2026-07-27T09:00:02Z");

    private final ProductPersistentPlanBootstrapRequestAdapter adapter =
            new ProductPersistentPlanBootstrapRequestAdapter();

    @Test
    void prepareAndBindProduceTheSameCanonicalWorkspaceTaskFrame() {
        AgentRunIdentity identity = identity(null);
        ProductPersistentPlanBootstrapCommand command = command(persistentDecision());
        ProductTaskFrameIntakeRequest intake = intake(identity, Optional.empty(), command);
        DeterministicProductTaskFrameAdapter taskFrames =
                new DeterministicProductTaskFrameAdapter();

        ProductTaskFramePreparation preparation = taskFrames.prepare(intake);
        ProductTaskFrameBinding binding = taskFrames.bind(intake);

        assertSame(identity, preparation.identity());
        assertEquals(
                binding.taskFrame(),
                new DeterministicTaskFrameFreezer().freeze(preparation.freezeRequest()));
        assertEquals(Optional.empty(), binding.taskFrame().sourceProjectVersion());
    }

    @Test
    void projectPreparationPreservesTheOwnerQualifiedVersion() {
        AgentRunIdentity identity = identity(91L);
        ProductPersistentPlanBootstrapCommand command = command(persistentDecision());

        ProductTaskFramePreparation preparation =
                new DeterministicProductTaskFrameAdapter().prepare(
                        intake(identity, Optional.of("manifest-v7"), command));

        assertEquals(
                Optional.of(new ProjectVersionRef("91", "manifest-v7")),
                preparation.freezeRequest().sourceProjectVersion());
    }

    @Test
    void derivesExactDomainSeparatedStablePlanAndRevisionIds() {
        PersistentPlanBootstrapRequest first =
                adapter.adapt(identity(null), Optional.empty(), command(persistentDecision()));
        PersistentPlanBootstrapRequest replay =
                new ProductPersistentPlanBootstrapRequestAdapter().adapt(
                        identity(null), Optional.empty(), command(persistentDecision()));

        assertEquals(first, replay);
        assertEquals(
                "product-plan.c2384435948cc96e3c0f65b75c2bbcc41538416633258f273ddaa1acf41bc0e0",
                first.planId().value());
        assertEquals(
                "product-revision.084ffd444fa6b7cdcaf23e04c335f5d933acda280f576864e061a63e68aef7b8",
                first.initialRevisionId().value());
    }

    @Test
    void mapsEveryCallerOwnedValueExactly() {
        ProductPersistentPlanBootstrapCommand command = command(persistentDecision());

        PersistentPlanBootstrapRequest request =
                adapter.adapt(identity(null), Optional.empty(), command);

        assertSame(command.routingDecision(),
                request.taskFrameFreezeRequest().routingDecision());
        assertSame(command.taskFrameDraft(),
                request.taskFrameFreezeRequest().draft());
        assertSame(command.executionProfile(),
                request.taskFrameFreezeRequest().executionProfile());
        assertSame(command.initialPlanDraft(), request.initialPlanDraft());
        assertEquals(TASK_FRAME_TIME, request.taskFrameFreezeRequest().createdAt());
        assertEquals(PLAN_TIME, request.planCreatedAt());
        assertEquals(CHECKPOINT_TIME, request.checkpointCreatedAt());
    }

    @Test
    void rejectsInconsistentProductFactsBeforeProducingARequest() {
        ProductTaskFrameValidationException failure = assertThrows(
                ProductTaskFrameValidationException.class,
                () -> adapter.adapt(
                        identity(null),
                        Optional.of("untrusted-version"),
                        command(persistentDecision())));

        assertEquals(
                ProductTaskFrameValidationCode.PROJECT_VERSION_UNEXPECTED,
                failure.code());
        assertEquals("request.projectVersionId", failure.path());
    }

    static AgentRunIdentity identity(Long projectId) {
        return new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, projectId);
    }

    static ProductPersistentPlanBootstrapCommand command(RoutingDecision decision) {
        return new ProductPersistentPlanBootstrapCommand(
                decision,
                new TaskFrameDraft(
                        "Summarize verified sources",
                        List.of("manuscript"),
                        List.of("workspace diff"),
                        List.of("preserve citations")),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.READ_PROJECT, Capability.WRITE_WORKSPACE),
                        NetworkPolicy.DENY_ALL,
                        List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(10),
                                Duration.ofMinutes(5),
                                512 * 1024 * 1024L,
                                1024 * 1024L,
                                4),
                        Set.of()),
                new InitialPlanDraft(
                        "initial verified plan",
                        List.of(new PlanStep(
                                new PlanStepId("step-1"),
                                "inspect sources",
                                "verified summary",
                                Set.of(),
                                List.of("citations retained"),
                                new BoundedExecutionHints(
                                        2, Duration.ofMinutes(2))))),
                TASK_FRAME_TIME,
                PLAN_TIME,
                CHECKPOINT_TIME);
    }

    static RoutingDecision persistentDecision() {
        return new RoutingDecision(
                new RoutingRequestId("route-42"),
                Route.PERSISTENT_PLAN_EXECUTE,
                RoutingDecisionReason.DECLARED_REQUIREMENT,
                Set.of(RoutingRequirement.TOOL_USE));
    }

    static RoutingDecision directDecision() {
        return new RoutingDecision(
                new RoutingRequestId("route-direct-42"),
                Route.DIRECT,
                RoutingDecisionReason.DIRECT_ELIGIBLE,
                Set.of());
    }

    private static ProductTaskFrameIntakeRequest intake(
            AgentRunIdentity identity,
            Optional<String> version,
            ProductPersistentPlanBootstrapCommand command) {
        return new ProductTaskFrameIntakeRequest(
                identity,
                version,
                command.routingDecision(),
                command.taskFrameDraft(),
                command.executionProfile(),
                command.taskFrameCreatedAt());
    }
}
