package com.yanban.api.agent.v2.workspace;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.agent.v2.adapter.bootstrap.ProductWorkspaceIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.project.ProjectStorageProperties;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceMaterializationLimits;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;
import io.paperagent.v2.persistence.ExecutionStartRecoveryRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextReserved;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PlanExecutionContextRepository;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextLeaseAttempt;
import io.paperagent.v2.workspace.WorkspacePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthenticatedAgentTurnPlanExecutionContextComposerTest {
    private AgentTurnProductContextResolver contexts;
    private ProductPlanIdDerivation planIds;
    private ProductWorkspaceIdDerivation workspaceIds;
    private AuthenticatedAgentTurnWorkspacePortFactory workspaces;
    private ExecutionStartRecoveryRepository executionStarts;
    private PlanExecutionContextRepository executionContexts;
    private LeaseRepository leases;
    private WorkspacePort workspace;
    private AuthenticatedAgentTurnPlanExecutionContextComposer composer;

    @BeforeEach
    void setUp() {
        contexts = mock(AgentTurnProductContextResolver.class);
        planIds = spy(new ProductPlanIdDerivation());
        workspaceIds = spy(new ProductWorkspaceIdDerivation());
        workspaces = mock(AuthenticatedAgentTurnWorkspacePortFactory.class);
        executionStarts = mock(ExecutionStartRecoveryRepository.class);
        executionContexts = mock(PlanExecutionContextRepository.class);
        leases = mock(LeaseRepository.class);
        workspace = mock(WorkspacePort.class);
        ProjectStorageProperties limits = new ProjectStorageProperties();
        limits.setMaxFileBytes(101);
        limits.setMaxTotalBytes(303);
        limits.setMaxFiles(7);
        composer = new AuthenticatedAgentTurnPlanExecutionContextComposer(
                contexts,
                planIds,
                workspaceIds,
                limits,
                workspaces,
                executionStarts,
                executionContexts,
                leases);
    }

    @Test
    void resolverIsExactlyOnceAndFirstAndTheSameContextBindsWorkspace() {
        VerifiedAgentTurnProductContext context = projectContext();
        PlanId planId = new ProductPlanIdDerivation().derive(context.identity());
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        doReturn(
                PersistenceResult.rejected(
                        PersistenceErrorCode.NOT_FOUND,
                        "planExecutionContext"))
                .when(executionContexts).inspect(planId);
        when(workspaces.create(context)).thenReturn(workspace);
        when(executionStarts.inspect(planId)).thenReturn(
                PersistenceResult.rejected(
                        PersistenceErrorCode.NOT_FOUND,
                        "planId"));

        var outcome = composer.compose(7L, 42L, command());

        assertThat(outcome.planId()).isEqualTo(planId);
        InOrder order = inOrder(
                contexts,
                planIds,
                workspaceIds,
                executionContexts,
                workspaces,
                executionStarts);
        order.verify(contexts).resolve(7L, 42L);
        order.verify(planIds).derive(context.identity());
        order.verify(workspaceIds).derive(context.identity());
        order.verify(executionContexts).inspect(planId);
        order.verify(workspaces).create(context);
        order.verify(executionStarts).inspect(planId);
        verify(contexts).resolve(7L, 42L);
    }

    @Test
    void existingContextOmitsInitializationPermissionAndStillDelegates() {
        VerifiedAgentTurnProductContext context = projectContext();
        PlanId planId = new ProductPlanIdDerivation().derive(context.identity());
        WorkspaceMaterializationSpec persisted = spec(
                "persisted",
                context,
                new WorkspaceMaterializationLimits(1, 2, 3));
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(executionContexts.inspect(planId)).thenReturn(
                PersistenceResult.found(
                        new PersistedPlanExecutionContextReserved(
                                planId, persisted, "old-owner", 1)));
        when(workspaces.create(context)).thenReturn(workspace);
        when(executionStarts.inspect(planId)).thenReturn(
                PersistenceResult.rejected(
                        PersistenceErrorCode.NOT_FOUND,
                        "planId"));

        assertThat(composer.compose(7L, 42L, command()).planId())
                .isEqualTo(planId);
        verify(executionContexts).inspect(planId);
        verify(workspaces).create(context);
    }

    @Test
    void canonicalMissingPlanAlsoSuppliesInitializationProposal() {
        VerifiedAgentTurnProductContext context = projectContext();
        PlanId planId = new ProductPlanIdDerivation().derive(context.identity());
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(executionContexts.inspect(planId)).thenReturn(
                PersistenceResult.rejected(
                        PersistenceErrorCode.NOT_FOUND,
                        "planId"));
        when(workspaces.create(context)).thenReturn(workspace);
        when(executionStarts.inspect(planId)).thenReturn(
                PersistenceResult.rejected(
                        PersistenceErrorCode.NOT_FOUND,
                        "planId"));

        assertThat(composer.compose(7L, 42L, command()).planId())
                .isEqualTo(planId);
        verify(workspaces).create(context);
    }

    @Test
    void resolverFailureHasZeroDownstreamEffectsAndPropagatesUnchanged() {
        RuntimeException failure =
                new AgentTurnProductContextResolutionException(
                        AgentTurnProductContextResolutionCode.TURN_NOT_FOUND,
                        "turnId");
        when(contexts.resolve(7L, 404L)).thenThrow(failure);

        assertSame(failure, org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> composer.compose(7L, 404L, command())));
        verifyNoInteractions(
                planIds,
                workspaceIds,
                workspaces,
                executionStarts,
                executionContexts,
                leases);
    }

    @Test
    void malformedOrSourceLessVerifiedContextFailsBeforePreflightOrWorkspace() {
        VerifiedAgentTurnProductContext sourceLess =
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN", "42", 7L, 11L, null),
                        Optional.empty());
        when(contexts.resolve(7L, 42L)).thenReturn(sourceLess);

        assertThatThrownBy(() -> composer.compose(7L, 42L, command()))
                .isInstanceOfSatisfying(
                        AuthenticatedPlanExecutionContextCompositionException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                AuthenticatedPlanExecutionContextCompositionCode
                                        .PROJECT_CONTEXT_REQUIRED));
        verifyNoInteractions(
                planIds,
                workspaceIds,
                workspaces,
                executionStarts,
                executionContexts,
                leases);
    }

    @Test
    void nullCommandFailsAfterResolutionButBeforeAnyOtherEffect() {
        VerifiedAgentTurnProductContext context = projectContext();
        when(contexts.resolve(7L, 42L)).thenReturn(context);

        assertThatThrownBy(() -> composer.compose(7L, 42L, null))
                .isInstanceOfSatisfying(
                        AuthenticatedPlanExecutionContextCompositionException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                AuthenticatedPlanExecutionContextCompositionCode
                                        .INVALID_COMMAND));
        verify(contexts).resolve(7L, 42L);
        verifyNoInteractions(
                planIds,
                workspaceIds,
                workspaces,
                executionStarts,
                executionContexts,
                leases);
    }

    @Test
    void invalidCurrentLimitsFailBeforePreflightOrWorkspaceCreation() {
        ProjectStorageProperties invalid = new ProjectStorageProperties();
        invalid.setMaxFileBytes(-1);
        composer = new AuthenticatedAgentTurnPlanExecutionContextComposer(
                contexts,
                planIds,
                workspaceIds,
                invalid,
                workspaces,
                executionStarts,
                executionContexts,
                leases);
        VerifiedAgentTurnProductContext context = projectContext();
        when(contexts.resolve(7L, 42L)).thenReturn(context);

        assertThatThrownBy(() -> composer.compose(7L, 42L, command()))
                .isInstanceOfSatisfying(
                        AuthenticatedPlanExecutionContextCompositionException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                AuthenticatedPlanExecutionContextCompositionCode
                                        .INVALID_WORKSPACE_LIMITS));
        verify(executionContexts, never()).inspect(any());
        verify(workspaces, never()).create(any());
        verifyNoInteractions(executionStarts, leases);
    }

    @Test
    void partialOrProtocolInvalidPreflightCannotInitializeWorkspace() {
        VerifiedAgentTurnProductContext context = projectContext();
        PlanId planId = new ProductPlanIdDerivation().derive(context.identity());
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(executionContexts.inspect(planId)).thenReturn(
                PersistenceResult.rejected(
                        PersistenceErrorCode
                                .PLAN_EXECUTION_CONTEXT_PARTIAL_STATE,
                        "planExecutionContext"));

        assertThatThrownBy(() -> composer.compose(7L, 42L, command()))
                .isInstanceOfSatisfying(
                        AuthenticatedPlanExecutionContextCompositionException.class,
                        failure -> {
                            assertThat(failure.code()).isEqualTo(
                                    AuthenticatedPlanExecutionContextCompositionCode
                                            .INVALID_CONTEXT_PREFLIGHT);
                            assertThat(failure.getMessage())
                                    .doesNotContain("old-owner")
                                    .doesNotContain("product-workspace");
                            assertThat(failure.getCause()).isNull();
                        });
        verify(workspaces, never()).create(any());
        verifyNoInteractions(executionStarts, leases);
    }

    @Test
    void repositoryAndWorkspaceFactoryExceptionsPropagateUnchanged() {
        VerifiedAgentTurnProductContext context = projectContext();
        PlanId planId = new ProductPlanIdDerivation().derive(context.identity());
        RuntimeException repositoryFailure =
                new IllegalStateException("synthetic repository");
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(executionContexts.inspect(planId)).thenThrow(repositoryFailure);

        assertSame(repositoryFailure, org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> composer.compose(7L, 42L, command())));
        verify(workspaces, never()).create(any());

        RuntimeException workspaceFailure =
                new IllegalArgumentException("synthetic workspace");
        doReturn(
                PersistenceResult.rejected(
                        PersistenceErrorCode.NOT_FOUND,
                        "planExecutionContext"))
                .when(executionContexts).inspect(planId);
        when(workspaces.create(context)).thenThrow(workspaceFailure);
        assertSame(workspaceFailure, org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> composer.compose(7L, 42L, command())));
        verifyNoInteractions(executionStarts, leases);
    }

    @Test
    void commandAndTrustedOverloadsExposeOnlyFrozenAuthority() throws Exception {
        assertThat(Arrays.stream(
                        AuthenticatedAgentTurnPlanExecutionContextCommand.class
                                .getRecordComponents())
                .map(component -> component.getName())
                .toList()).containsExactly("attempt");

        Method sourceTrusted =
                AuthenticatedAgentTurnProjectVersionSourceFactory.class
                        .getDeclaredMethod(
                                "create",
                                VerifiedAgentTurnProductContext.class);
        Method workspaceTrusted =
                AuthenticatedAgentTurnWorkspacePortFactory.class
                        .getDeclaredMethod(
                                "create",
                                VerifiedAgentTurnProductContext.class);
        assertThat(Modifier.isPublic(sourceTrusted.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(workspaceTrusted.getModifiers())).isFalse();
    }

    private static VerifiedAgentTurnProductContext projectContext() {
        return new VerifiedAgentTurnProductContext(
                new AgentRunIdentity(
                        "AGENT_TURN", "42", 7L, 11L, 83L),
                Optional.of("a".repeat(64)));
    }

    private static AuthenticatedAgentTurnPlanExecutionContextCommand command() {
        return new AuthenticatedAgentTurnPlanExecutionContextCommand(
                Optional.of(new PlanExecutionContextLeaseAttempt(
                        "owner", "token", Instant.parse("2030-01-01T00:00:00Z"))));
    }

    private static WorkspaceMaterializationSpec spec(
            String workspaceId,
            VerifiedAgentTurnProductContext context,
            WorkspaceMaterializationLimits limits) {
        return new WorkspaceMaterializationSpec(
                new io.paperagent.v2.contracts.WorkspaceId(workspaceId),
                new ProjectVersionRef(
                        String.valueOf(context.identity().projectId()),
                        context.projectVersionId().orElseThrow()),
                limits);
    }
}
