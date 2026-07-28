package com.yanban.api.agent.v2.execution;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistedStepRecoveryActive;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnNoEffect;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernel;
import io.paperagent.v2.runtime.execution.kernel.SingleTurnStepKernelRequest;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredActiveStep;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseRejected;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

class AuthenticatedAgentTurnStepTurnComposerTest {
    private AgentTurnProductContextResolver contexts;
    private ProductPlanIdDerivation planIds;
    private StepRecoverer recoverer;
    private SingleTurnStepKernel kernel;
    private AuthenticatedAgentTurnStepTurnComposer composer;

    @BeforeEach
    void setUp() {
        contexts = mock(AgentTurnProductContextResolver.class);
        planIds = spy(new ProductPlanIdDerivation());
        recoverer = mock(StepRecoverer.class);
        kernel = mock(SingleTurnStepKernel.class);
        composer = new AuthenticatedAgentTurnStepTurnComposer(
                contexts, planIds, recoverer, kernel);
    }

    @Test
    void ownerRecoveryAndExactlyOneKernelTurnRunInOrder() {
        VerifiedAgentTurnProductContext context = context();
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        PlanId planId = new ProductPlanIdDerivation()
                .derive(context.identity());
        RecoveredActiveStep active = active(planId);
        when(recoverer.recover(any())).thenReturn(active);
        SingleTurnNoEffect noEffect =
                new SingleTurnNoEffect(planId, new PlanStepId("step-a"));
        when(kernel.run(any())).thenReturn(noEffect);

        AuthenticatedAgentTurnStepTurnExecuted executed = assertInstanceOf(
                AuthenticatedAgentTurnStepTurnExecuted.class,
                composer.execute(7L, 42L, command()));
        assertSame(noEffect, executed.outcome());

        InOrder order = inOrder(contexts, planIds, recoverer, kernel);
        order.verify(contexts).resolve(7L, 42L);
        order.verify(planIds).derive(context.identity());
        order.verify(recoverer).recover(any());
        ArgumentCaptor<SingleTurnStepKernelRequest> request =
                ArgumentCaptor.forClass(SingleTurnStepKernelRequest.class);
        order.verify(kernel).run(request.capture());
        assertSame(active, request.getValue().recoveredStep());
    }

    @Test
    void stableRecoveryRejectionIsReturnedWithoutProviderKernel() {
        VerifiedAgentTurnProductContext context = context();
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        PlanId planId = new ProductPlanIdDerivation()
                .derive(context.identity());
        StepRecoveryLeaseRejected rejected = new StepRecoveryLeaseRejected(
                planId,
                new PersistenceFailure(
                        PersistenceErrorCode.LEASE_HELD, "lease"),
                StepRecoveryLeaseDisposition.NOT_ACQUIRED);
        when(recoverer.recover(any())).thenReturn(rejected);

        var outcome = assertInstanceOf(
                AuthenticatedAgentTurnStepTurnRecoveryRejected.class,
                composer.execute(7L, 42L, command()));
        assertSame(rejected, outcome.recovery());
        verifyNoInteractions(kernel);
    }

    @Test
    void wrongOwnerFailsBeforeRecoveryOrKernel() {
        var failure = new AgentTurnProductContextResolutionException(
                AgentTurnProductContextResolutionCode.TURN_NOT_FOUND,
                "turnId");
        when(contexts.resolve(7L, 404L)).thenThrow(failure);

        assertSame(failure, assertThrows(
                AgentTurnProductContextResolutionException.class,
                () -> composer.execute(7L, 404L, command())));
        verifyNoInteractions(planIds, recoverer, kernel);
    }

    @Test
    void nullCommandIsValidatedAfterOwnershipAndBeforeRecovery() {
        VerifiedAgentTurnProductContext context = context();
        when(contexts.resolve(7L, 42L)).thenReturn(context);

        var failure = assertThrows(
                AuthenticatedAgentTurnStepTurnCompositionException.class,
                () -> composer.execute(7L, 42L, null));
        assertEquals(
                AuthenticatedAgentTurnStepTurnCompositionCode
                        .REQUIRED_VALUE_MISSING,
                failure.code());
        assertEquals("authenticatedStepTurn.command", failure.path());
        verify(contexts).resolve(7L, 42L);
        verify(planIds).derive(context.identity());
        verifyNoInteractions(recoverer, kernel);
    }

    @Test
    void throwingCollaboratorsAreBoundedAndSecretsAreRedacted() {
        String secret = "lease-token-and-provider-payload";
        when(contexts.resolve(7L, 42L)).thenReturn(context());
        when(recoverer.recover(any()))
                .thenThrow(new IllegalStateException(secret));

        var failure = assertThrows(
                AuthenticatedAgentTurnStepTurnCompositionException.class,
                () -> composer.execute(7L, 42L, command()));
        assertEquals(
                AuthenticatedAgentTurnStepTurnCompositionCode
                        .RECOVERY_COLLABORATOR_FAILURE,
                failure.code());
        assertFalse(failure.getMessage().contains(secret));
        assertFalse(failure.toString().contains(secret));
        verify(kernel, never()).run(any());
        assertFalse(command().toString().contains("token"));
    }

    private static AuthenticatedAgentTurnStepTurnCommand command() {
        return new AuthenticatedAgentTurnStepTurnCommand(
                new StepRecoveryLeaseAttempt(
                        "owner",
                        "opaque-secret",
                        Instant.parse("2099-07-28T00:00:00Z")));
    }

    private static VerifiedAgentTurnProductContext context() {
        return new VerifiedAgentTurnProductContext(
                new AgentRunIdentity(
                        "AGENT_TURN", "turn-42", 7L, 11L, null),
                Optional.empty());
    }

    private static RecoveredActiveStep active(PlanId planId) {
        PersistedStepRecoveryActive recovery =
                mock(PersistedStepRecoveryActive.class);
        LeaseRecord lease = mock(LeaseRecord.class);
        when(recovery.planId()).thenReturn(planId);
        when(lease.planId()).thenReturn(planId);
        return new RecoveredActiveStep(
                recovery,
                lease,
                StepRecoveryLeaseDisposition.RETAINED_FOR_RECOVERY);
    }
}
