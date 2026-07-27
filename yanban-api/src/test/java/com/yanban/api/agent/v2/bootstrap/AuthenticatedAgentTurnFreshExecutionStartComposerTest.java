package com.yanban.api.agent.v2.bootstrap;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import io.paperagent.v2.runtime.execution.ExecutionStartEventDraft;
import io.paperagent.v2.runtime.execution.start.FreshAtomicExecutionStartRejected;
import io.paperagent.v2.runtime.execution.start.FreshExecutionBootstrapRejected;
import io.paperagent.v2.runtime.execution.start.FreshExecutionLeaseDisposition;
import io.paperagent.v2.runtime.execution.start.FreshExecutionRecoveryRequired;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartAttempt;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartOutcome;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStartRequest;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarted;
import io.paperagent.v2.runtime.execution.start.FreshExecutionStarter;
import io.paperagent.v2.runtime.execution.start.FreshLeaseAcquisitionRejected;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthenticatedAgentTurnFreshExecutionStartComposerTest {
    private AgentTurnProductContextResolver contexts;
    private ProductPersistentPlanBootstrapRequestAdapter requests;
    private PersistentPlanBootstrapper bootstrapper;
    private FreshExecutionStarter starter;
    private AuthenticatedAgentTurnFreshExecutionStartComposer composer;

    @BeforeEach
    void setUp() {
        contexts = mock(AgentTurnProductContextResolver.class);
        requests = spy(new ProductPersistentPlanBootstrapRequestAdapter());
        bootstrapper = mock(PersistentPlanBootstrapper.class);
        starter = mock(FreshExecutionStarter.class);
        composer = new AuthenticatedAgentTurnFreshExecutionStartComposer(
                contexts, requests, bootstrapper, starter);
    }

    @Test
    void resolvesThenAdaptsBootstrapsAndForwardsExactResultAndAttempt() {
        VerifiedAgentTurnProductContext context = workspaceContext();
        FreshExecutionStartAttempt attempt = attempt();
        var command = command(Optional.of(attempt));
        PersistenceResult<PersistedPlanBootstrap> bootstrap =
                PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION, "synthetic");
        FreshExecutionStartOutcome outcome =
                new FreshExecutionBootstrapRejected(
                        bootstrap.failure().orElseThrow());
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(bootstrapper.bootstrap(any())).thenReturn(bootstrap);
        when(starter.start(any())).thenReturn(outcome);

        assertSame(outcome, composer.start(7L, 42L, command));

        InOrder order = inOrder(contexts, requests, bootstrapper, starter);
        order.verify(contexts).resolve(7L, 42L);
        order.verify(requests).adapt(
                context.identity(),
                context.projectVersionId(),
                command.bootstrapCommand());
        order.verify(bootstrapper).bootstrap(any());
        ArgumentCaptor<FreshExecutionStartRequest> start =
                ArgumentCaptor.forClass(FreshExecutionStartRequest.class);
        order.verify(starter).start(start.capture());
        assertSame(bootstrap, start.getValue().bootstrapResult());
        assertSame(attempt, start.getValue().attempt().orElseThrow());
    }

    @Test
    void ownershipFailurePropagatesBeforeEveryOtherCollaborator() {
        AgentTurnProductContextResolutionException failure =
                new AgentTurnProductContextResolutionException(
                        AgentTurnProductContextResolutionCode.TURN_NOT_FOUND,
                        "turnId");
        when(contexts.resolve(7L, 404L)).thenThrow(failure);

        assertSame(failure, assertThrows(
                AgentTurnProductContextResolutionException.class,
                () -> composer.start(7L, 404L, command(Optional.empty()))));
        verify(requests, never()).adapt(any(), any(), any());
        verifyNoInteractions(bootstrapper, starter);
    }

    @Test
    void emptyAttemptIsForwardedForReplayRecovery() {
        PersistenceResult<PersistedPlanBootstrap> bootstrap =
                PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY, "synthetic");
        FreshExecutionRecoveryRequired outcome =
                new FreshExecutionRecoveryRequired(new PlanId("plan-replay"));
        when(contexts.resolve(7L, 42L)).thenReturn(workspaceContext());
        when(bootstrapper.bootstrap(any())).thenReturn(bootstrap);
        when(starter.start(any())).thenReturn(outcome);

        assertSame(outcome,
                composer.start(7L, 42L, command(Optional.empty())));
        ArgumentCaptor<FreshExecutionStartRequest> request =
                ArgumentCaptor.forClass(FreshExecutionStartRequest.class);
        verify(starter).start(request.capture());
        assertSame(bootstrap, request.getValue().bootstrapResult());
        assertSame(Optional.empty(), request.getValue().attempt());
    }

    @Test
    void stableStarterOutcomesAreReturnedWithoutRemapping() {
        FreshExecutionStartOutcome[] outcomes = {
                mock(FreshExecutionStarted.class),
                new FreshExecutionBootstrapRejected(
                        PersistenceResult.rejected(
                                PersistenceErrorCode.INVALID_ARGUMENT,
                                "bootstrap").failure().orElseThrow()),
                new FreshLeaseAcquisitionRejected(
                        new PlanId("plan-lease"),
                        PersistenceResult.rejected(
                                PersistenceErrorCode.LEASE_HELD,
                                "lease").failure().orElseThrow()),
                new FreshAtomicExecutionStartRejected(
                        new PlanId("plan-start"),
                        PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "start").failure().orElseThrow(),
                        FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY)
        };
        when(contexts.resolve(7L, 42L)).thenReturn(workspaceContext());
        when(bootstrapper.bootstrap(any())).thenReturn(
                PersistenceResult.rejected(
                        PersistenceErrorCode.INVALID_ARGUMENT, "synthetic"));
        for (FreshExecutionStartOutcome outcome : outcomes) {
            when(starter.start(any())).thenReturn(outcome);
            assertSame(outcome,
                    composer.start(7L, 42L, command(Optional.of(attempt()))));
        }
    }

    @Test
    void bootstrapAndStarterExceptionsPropagateUnchanged() {
        RuntimeException bootstrapFailure =
                new IllegalStateException("synthetic bootstrap");
        when(contexts.resolve(7L, 42L)).thenReturn(workspaceContext());
        when(bootstrapper.bootstrap(any())).thenThrow(bootstrapFailure);
        assertSame(bootstrapFailure, assertThrows(
                IllegalStateException.class,
                () -> composer.start(
                        7L, 42L, command(Optional.of(attempt())))));
        verifyNoInteractions(starter);

        RuntimeException starterFailure =
                new IllegalArgumentException("synthetic starter");
        PersistenceResult<PersistedPlanBootstrap> rejected =
                PersistenceResult.rejected(
                        PersistenceErrorCode.INVALID_ARGUMENT, "synthetic");
        doReturn(rejected).when(bootstrapper).bootstrap(any());
        when(starter.start(any())).thenThrow(starterFailure);
        assertSame(starterFailure, assertThrows(
                IllegalArgumentException.class,
                () -> composer.start(
                        7L, 42L, command(Optional.of(attempt())))));
    }

    @Test
    void commandHasOnlyFrozenBootstrapAndAttemptFields() {
        assertArrayEquals(
                new String[]{"bootstrapCommand", "attempt"},
                Arrays.stream(
                                AuthenticatedAgentTurnFreshExecutionStartCommand
                                        .class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toArray(String[]::new));
    }

    static AuthenticatedAgentTurnFreshExecutionStartCommand command(
            Optional<FreshExecutionStartAttempt> attempt) {
        return new AuthenticatedAgentTurnFreshExecutionStartCommand(
                AuthenticatedAgentTurnPlanBootstrapComposerTest.command(),
                attempt);
    }

    static FreshExecutionStartAttempt attempt() {
        return new FreshExecutionStartAttempt(
                "synthetic-owner",
                "synthetic-token",
                Instant.parse("2099-07-27T10:10:00Z"),
                new ExecutionStartEventDraft(
                        new EventId("synthetic-start-event"),
                        Instant.parse("2026-07-27T10:00:03Z"),
                        new EventType("PLAN_STARTED"),
                        Optional.empty(),
                        "synthetic-correlation",
                        new InlineEventPayload(new ObjectValue(Map.of()))),
                Instant.parse("2026-07-27T10:00:04Z"));
    }

    private static VerifiedAgentTurnProductContext workspaceContext() {
        return new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, null),
                Optional.empty());
    }
}
