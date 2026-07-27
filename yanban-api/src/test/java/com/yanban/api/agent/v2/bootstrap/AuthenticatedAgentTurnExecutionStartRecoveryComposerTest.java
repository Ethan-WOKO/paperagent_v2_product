package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryAdvancedUnsupported;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryOutcome;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryRejected;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryRequest;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryRetryRequired;
import io.paperagent.v2.runtime.execution.recovery.composition.ExecutionStartRecoveryStage;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredExecutionStart;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthenticatedAgentTurnExecutionStartRecoveryComposerTest {
    private AgentTurnProductContextResolver contexts;
    private ProductPlanIdDerivation planIds;
    private ExecutionStartRecoverer recoverer;
    private AuthenticatedAgentTurnExecutionStartRecoveryComposer composer;

    @BeforeEach
    void setUp() {
        contexts = mock(AgentTurnProductContextResolver.class);
        planIds = spy(new ProductPlanIdDerivation());
        recoverer = mock(ExecutionStartRecoverer.class);
        composer = new AuthenticatedAgentTurnExecutionStartRecoveryComposer(
                contexts, planIds, recoverer);
    }

    @Test
    void resolvesDerivesAndForwardsTheExactAttemptInOrder() {
        VerifiedAgentTurnProductContext context = workspaceContext();
        FreshExecutionStartAttempt attempt =
                AuthenticatedAgentTurnFreshExecutionStartComposerTest.attempt();
        var command = command(Optional.of(attempt));
        ExecutionStartRecoveryOutcome outcome =
                mock(RecoveredExecutionStart.class);
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(recoverer.recover(any())).thenReturn(outcome);

        assertSame(outcome, composer.recover(7L, 42L, command));

        InOrder order = inOrder(contexts, planIds, recoverer);
        order.verify(contexts).resolve(7L, 42L);
        order.verify(planIds).derive(context.identity());
        ArgumentCaptor<ExecutionStartRecoveryRequest> request =
                ArgumentCaptor.forClass(ExecutionStartRecoveryRequest.class);
        order.verify(recoverer).recover(request.capture());
        assertSame(attempt, request.getValue().attempt().orElseThrow());
    }

    @Test
    void recoveryAndBootstrapUseTheSamePlanIdImplementation() {
        VerifiedAgentTurnProductContext context = workspaceContext();
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(recoverer.recover(any())).thenReturn(
                mock(RecoveredExecutionStart.class));

        composer.recover(7L, 42L, command(Optional.empty()));

        ArgumentCaptor<ExecutionStartRecoveryRequest> recovery =
                ArgumentCaptor.forClass(ExecutionStartRecoveryRequest.class);
        verify(recoverer).recover(recovery.capture());
        PlanId bootstrapPlanId =
                new ProductPersistentPlanBootstrapRequestAdapter(planIds)
                        .adapt(
                                context.identity(),
                                context.projectVersionId(),
                                AuthenticatedAgentTurnPlanBootstrapComposerTest
                                        .command())
                        .planId();
        assertEquals(bootstrapPlanId, recovery.getValue().planId());
        assertEquals(
                "product-plan.c2384435948cc96e3c0f65b75c2bbcc41538416633258f273ddaa1acf41bc0e0",
                recovery.getValue().planId().value());
    }

    @Test
    void ownershipFailurePreventsDerivationInspectionLeaseAndStart() {
        AgentTurnProductContextResolutionException failure =
                new AgentTurnProductContextResolutionException(
                        AgentTurnProductContextResolutionCode.TURN_NOT_FOUND,
                        "turnId");
        when(contexts.resolve(7L, 404L)).thenThrow(failure);

        assertSame(failure, assertThrows(
                AgentTurnProductContextResolutionException.class,
                () -> composer.recover(
                        7L, 404L, command(Optional.empty()))));
        verify(planIds, never()).derive(any());
        verifyNoInteractions(recoverer);
    }

    @Test
    void stableRecoveryOutcomesAreReturnedWithoutRemapping() {
        PlanId planId = new PlanId("synthetic-plan");
        ExecutionStartRecoveryOutcome[] outcomes = {
                mock(RecoveredExecutionStart.class),
                new ExecutionStartRecoveryRejected(
                        planId,
                        ExecutionStartRecoveryStage.INITIAL_INSPECT,
                        new PersistenceFailure(
                                PersistenceErrorCode
                                        .EXECUTION_RECOVERY_PARTIAL_STATE,
                                "executionRecovery"),
                        ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION),
                new ExecutionStartRecoveryAdvancedUnsupported(
                        planId,
                        ExecutionStartRecoveryStage.INITIAL_INSPECT,
                        new PersistenceFailure(
                                PersistenceErrorCode
                                        .EXECUTION_RECOVERY_ADVANCED_STATE,
                                "executionRecovery"),
                        ExecutionStartRecoveryLeaseDisposition.NO_LEASE_ACTION),
                new ExecutionStartRecoveryRejected(
                        planId,
                        ExecutionStartRecoveryStage.LEASE_ACQUIRE,
                        new PersistenceFailure(
                                PersistenceErrorCode.LEASE_HELD,
                                "lease"),
                        ExecutionStartRecoveryLeaseDisposition.NOT_ACQUIRED),
                new ExecutionStartRecoveryRejected(
                        planId,
                        ExecutionStartRecoveryStage.ATOMIC_START,
                        new PersistenceFailure(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "start"),
                        ExecutionStartRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY),
                new ExecutionStartRecoveryRetryRequired(
                        planId,
                        ExecutionStartRecoveryLeaseDisposition
                                .RETAINED_FOR_RECOVERY)
        };
        when(contexts.resolve(7L, 42L)).thenReturn(workspaceContext());

        for (ExecutionStartRecoveryOutcome outcome : outcomes) {
            when(recoverer.recover(any())).thenReturn(outcome);
            assertSame(outcome,
                    composer.recover(
                            7L, 42L, command(Optional.empty())));
        }
    }

    @Test
    void derivationAndRecovererExceptionsPropagateUnchanged() {
        RuntimeException derivationFailure =
                new IllegalStateException("synthetic derivation");
        when(contexts.resolve(7L, 42L)).thenReturn(workspaceContext());
        doThrow(derivationFailure).when(planIds).derive(any());
        assertSame(derivationFailure, assertThrows(
                IllegalStateException.class,
                () -> composer.recover(
                        7L, 42L, command(Optional.empty()))));
        verifyNoInteractions(recoverer);

        ProductPlanIdDerivation workingPlanIds =
                new ProductPlanIdDerivation();
        composer = new AuthenticatedAgentTurnExecutionStartRecoveryComposer(
                contexts, workingPlanIds, recoverer);
        RuntimeException recoveryFailure =
                new IllegalArgumentException("synthetic recovery");
        when(recoverer.recover(any())).thenThrow(recoveryFailure);
        assertSame(recoveryFailure, assertThrows(
                IllegalArgumentException.class,
                () -> composer.recover(
                        7L, 42L, command(Optional.empty()))));
    }

    @Test
    void commandHasOnlyTheFrozenOptionalAttemptField() {
        assertArrayEquals(
                new String[]{"attempt"},
                Arrays.stream(
                                AuthenticatedAgentTurnExecutionStartRecoveryCommand
                                        .class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
    }

    static AuthenticatedAgentTurnExecutionStartRecoveryCommand command(
            Optional<FreshExecutionStartAttempt> attempt) {
        return new AuthenticatedAgentTurnExecutionStartRecoveryCommand(attempt);
    }

    static VerifiedAgentTurnProductContext workspaceContext() {
        return new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, null),
                Optional.empty());
    }
}
