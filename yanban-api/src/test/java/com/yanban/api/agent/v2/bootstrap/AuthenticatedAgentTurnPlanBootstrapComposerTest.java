package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapCommand;
import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
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
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapRequest;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.routing.RoutingDecision;
import io.paperagent.v2.runtime.routing.RoutingDecisionReason;
import io.paperagent.v2.runtime.routing.RoutingRequestId;
import io.paperagent.v2.runtime.routing.RoutingRequirement;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthenticatedAgentTurnPlanBootstrapComposerTest {
    private AgentTurnProductContextResolver contexts;
    private ProductPersistentPlanBootstrapRequestAdapter requests;
    private PersistentPlanBootstrapper bootstrapper;
    private AuthenticatedAgentTurnPlanBootstrapComposer composer;

    @BeforeEach
    void setUp() {
        contexts = mock(AgentTurnProductContextResolver.class);
        requests = spy(new ProductPersistentPlanBootstrapRequestAdapter());
        bootstrapper = mock(PersistentPlanBootstrapper.class);
        composer = new AuthenticatedAgentTurnPlanBootstrapComposer(
                contexts, requests, bootstrapper);
    }

    @Test
    void resolvesOwnershipBeforeAdaptationAndPersistence() {
        VerifiedAgentTurnProductContext context = workspaceContext();
        ProductPersistentPlanBootstrapCommand command = command();
        PersistenceResult<PersistedPlanBootstrap> result =
                PersistenceResult.rejected(PersistenceErrorCode.INVALID_ARGUMENT, "synthetic");
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(bootstrapper.bootstrap(any())).thenReturn(result);

        PersistenceResult<PersistedPlanBootstrap> actual =
                composer.bootstrap(7L, 42L, command);

        assertSame(result, actual);
        InOrder order = inOrder(contexts, requests, bootstrapper);
        order.verify(contexts).resolve(7L, 42L);
        order.verify(requests).adapt(context.identity(), Optional.empty(), command);
        order.verify(bootstrapper).bootstrap(any());
    }

    @Test
    void ownershipFailurePropagatesWithoutAdaptationOrBootstrap() {
        AgentTurnProductContextResolutionException failure =
                new AgentTurnProductContextResolutionException(
                        AgentTurnProductContextResolutionCode.TURN_NOT_FOUND,
                        "turnId");
        when(contexts.resolve(7L, 404L)).thenThrow(failure);

        AgentTurnProductContextResolutionException actual = assertThrows(
                AgentTurnProductContextResolutionException.class,
                () -> composer.bootstrap(7L, 404L, command()));

        assertSame(failure, actual);
        verify(requests, never()).adapt(any(), any(), any());
        verifyNoInteractions(bootstrapper);
    }

    @Test
    void mapsVerifiedWorkspaceContextAndCallerValuesExactly() {
        VerifiedAgentTurnProductContext context = workspaceContext();
        ProductPersistentPlanBootstrapCommand command = command();
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        PersistenceResult<PersistedPlanBootstrap> result =
                PersistenceResult.rejected(PersistenceErrorCode.STALE_VERSION, "synthetic");
        when(bootstrapper.bootstrap(any())).thenReturn(result);

        assertSame(result, composer.bootstrap(7L, 42L, command));

        PersistentPlanBootstrapRequest request = capturedRequest();
        assertEquals(Optional.empty(),
                request.taskFrameFreezeRequest().sourceProjectVersion());
        assertSame(command.routingDecision(),
                request.taskFrameFreezeRequest().routingDecision());
        assertSame(command.taskFrameDraft(),
                request.taskFrameFreezeRequest().draft());
        assertSame(command.executionProfile(),
                request.taskFrameFreezeRequest().executionProfile());
        assertSame(command.initialPlanDraft(), request.initialPlanDraft());
        assertEquals(command.taskFrameCreatedAt(),
                request.taskFrameFreezeRequest().createdAt());
        assertEquals(command.planCreatedAt(), request.planCreatedAt());
        assertEquals(command.checkpointCreatedAt(), request.checkpointCreatedAt());
    }

    @Test
    void mapsOnlyTheOwnerQualifiedProjectVersion() {
        AgentRunIdentity identity =
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, 91L);
        VerifiedAgentTurnProductContext context =
                new VerifiedAgentTurnProductContext(
                        identity, Optional.of("owner-manifest-v8"));
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(bootstrapper.bootstrap(any())).thenReturn(
                PersistenceResult.rejected(
                        PersistenceErrorCode.INVALID_ARGUMENT, "synthetic"));

        composer.bootstrap(7L, 42L, command());

        assertEquals(
                Optional.of(new ProjectVersionRef("91", "owner-manifest-v8")),
                capturedRequest().taskFrameFreezeRequest().sourceProjectVersion());
    }

    private PersistentPlanBootstrapRequest capturedRequest() {
        ArgumentCaptor<PersistentPlanBootstrapRequest> captor =
                ArgumentCaptor.forClass(PersistentPlanBootstrapRequest.class);
        verify(bootstrapper).bootstrap(captor.capture());
        return captor.getValue();
    }

    private static VerifiedAgentTurnProductContext workspaceContext() {
        return new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, null),
                Optional.empty());
    }

    static ProductPersistentPlanBootstrapCommand command() {
        RoutingDecision decision = new RoutingDecision(
                new RoutingRequestId("route-42"),
                Route.PERSISTENT_PLAN_EXECUTE,
                RoutingDecisionReason.DECLARED_REQUIREMENT,
                Set.of(RoutingRequirement.TOOL_USE));
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
                Instant.parse("2026-07-27T09:00:00Z"),
                Instant.parse("2026-07-27T09:00:01Z"),
                Instant.parse("2026-07-27T09:00:02Z"));
    }
}
