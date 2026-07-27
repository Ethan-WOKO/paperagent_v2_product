package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPlanIdDerivation;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceFailure;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoverer;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseAttempt;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseDisposition;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryLeaseRejected;
import io.paperagent.v2.runtime.execution.recovery.composition.StepRecoveryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class AuthenticatedAgentTurnStepRecoveryComposerTest {
    private AgentTurnProductContextResolver contexts;
    private ProductPlanIdDerivation planIds;
    private StepRecoverer recoverer;
    private AuthenticatedAgentTurnStepRecoveryComposer composer;

    @BeforeEach
    void setUp() {
        contexts = mock(AgentTurnProductContextResolver.class);
        planIds = spy(new ProductPlanIdDerivation());
        recoverer = mock(StepRecoverer.class);
        composer = new AuthenticatedAgentTurnStepRecoveryComposer(
                contexts, planIds, recoverer);
    }

    @Test
    void resolvesDerivesValidatesAndDelegatesExactAuthorityOnceInOrder() {
        var context =
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext();
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        PlanId expectedPlanId = new ProductPlanIdDerivation()
                .derive(context.identity());
        var command = command();
        var outcome = outcome(expectedPlanId);
        when(recoverer.recover(any())).thenReturn(outcome);

        assertSame(outcome, composer.recover(7L, 42L, command));

        InOrder order = inOrder(contexts, planIds, recoverer);
        order.verify(contexts).resolve(7L, 42L);
        order.verify(planIds).derive(context.identity());
        ArgumentCaptor<StepRecoveryRequest> request =
                ArgumentCaptor.forClass(StepRecoveryRequest.class);
        order.verify(recoverer).recover(request.capture());
        assertEquals(expectedPlanId, request.getValue().planId());
        assertSame(command.attempt(), request.getValue().leaseAttempt());
    }

    @Test
    void resolverAndDerivationRemainFirstForNullAndInvalidCommand() {
        var context =
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext();
        when(contexts.resolve(7L, 42L)).thenReturn(context);

        assertFailure(
                () -> composer.recover(7L, 42L, null),
                "authenticatedStepRecovery.command");
        verify(contexts).resolve(7L, 42L);
        verify(planIds).derive(context.identity());
        verifyNoInteractions(recoverer);

        setUp();
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        assertFailure(
                () -> composer.recover(
                        7L, 42L,
                        new AuthenticatedAgentTurnStepRecoveryCommand(null)),
                "authenticatedStepRecovery.command.attempt");
        verify(contexts).resolve(7L, 42L);
        verify(planIds).derive(context.identity());
        verifyNoInteractions(recoverer);
    }

    @Test
    void ownershipFailurePropagatesUnchangedBeforeOtherAuthority() {
        var failure = new AgentTurnProductContextResolutionException(
                AgentTurnProductContextResolutionCode.TURN_NOT_FOUND,
                "turnId");
        when(contexts.resolve(7L, 404L)).thenThrow(failure);

        assertSame(failure, assertThrows(
                AgentTurnProductContextResolutionException.class,
                () -> composer.recover(7L, 404L, command())));
        verifyNoInteractions(planIds, recoverer);
    }

    @Test
    void stableOutcomeAndFailurePropagateUnchangedWithoutRetry() {
        var context =
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext();
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        PlanId planId = new ProductPlanIdDerivation().derive(context.identity());
        var outcome = outcome(planId);
        when(recoverer.recover(any())).thenReturn(outcome);
        assertSame(outcome, composer.recover(7L, 42L, command()));

        RuntimeException failure =
                new IllegalStateException("stable protocol failure");
        when(recoverer.recover(any())).thenThrow(failure);
        assertSame(failure, assertThrows(
                IllegalStateException.class,
                () -> composer.recover(7L, 42L, command())));
        verify(recoverer, org.mockito.Mockito.times(2)).recover(any());
    }

    @Test
    void commandAndDiagnosticsNeverRevealOwnerOrToken() {
        String owner = "must-not-appear-owner";
        String token = "must-not-appear-token";
        var command = new AuthenticatedAgentTurnStepRecoveryCommand(
                attempt(owner, token));
        assertFalse(command.toString().contains(owner));
        assertFalse(command.toString().contains(token));

        when(contexts.resolve(7L, 42L)).thenReturn(
                AuthenticatedAgentTurnExecutionStartRecoveryComposerTest
                        .workspaceContext());
        var failure = assertFailure(
                () -> composer.recover(
                        7L, 42L,
                        new AuthenticatedAgentTurnStepRecoveryCommand(null)),
                "authenticatedStepRecovery.command.attempt");
        assertFalse(failure.toString().contains(owner));
        assertFalse(failure.toString().contains(token));
        assertArrayEquals(
                new String[]{"attempt"},
                Arrays.stream(
                                AuthenticatedAgentTurnStepRecoveryCommand.class
                                        .getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
        verify(recoverer, never()).recover(any());
    }

    static AuthenticatedAgentTurnStepRecoveryCommand command() {
        return new AuthenticatedAgentTurnStepRecoveryCommand(
                attempt("synthetic-owner", "synthetic-token"));
    }

    static StepRecoveryLeaseAttempt attempt(String owner, String token) {
        return new StepRecoveryLeaseAttempt(
                owner,
                token,
                Instant.parse("2099-07-27T10:10:00Z"));
    }

    private static StepRecoveryLeaseRejected outcome(PlanId planId) {
        return new StepRecoveryLeaseRejected(
                planId,
                new PersistenceFailure(
                        PersistenceErrorCode.LEASE_HELD, "lease"),
                StepRecoveryLeaseDisposition.NOT_ACQUIRED);
    }

    private static AuthenticatedAgentTurnStepRecoveryCompositionException
            assertFailure(
                    org.junit.jupiter.api.function.Executable executable,
                    String path) {
        var failure = assertThrows(
                AuthenticatedAgentTurnStepRecoveryCompositionException.class,
                executable);
        assertEquals(
                AuthenticatedAgentTurnStepRecoveryCompositionCode
                        .REQUIRED_VALUE_MISSING,
                failure.code());
        assertEquals(path, failure.path());
        return failure;
    }
}
