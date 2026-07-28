package com.yanban.api.agent.v2.compatibility.literature;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;

import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnFreshExecutionStartComposer;
import com.yanban.api.agent.v2.loop.AuthenticatedPersistentPlanAgentLoopComposer;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.runtime.synthesis.DefaultFinalSynthesisComposer;
import java.util.Optional;
import java.time.Instant;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class V2LiteratureTurnServiceTest {
    @Test
    void rejectsProjectSessionBeforeCreatingAnyTurnOrPlan() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        LiteratureDeliveryTransactions deliveries =
                mock(LiteratureDeliveryTransactions.class);
        var starts = mock(
                AuthenticatedAgentTurnFreshExecutionStartComposer.class);
        var loop = mock(AuthenticatedPersistentPlanAgentLoopComposer.class);
        var recovery = mock(StepRecoveryRepository.class);
        var synthesis = mock(DefaultFinalSynthesisComposer.class);
        when(sessions.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(
                new AgentSession(
                        7L, "project", "mock", "model", 4, false,
                        AgentSessionScope.PROJECT, 91L)));
        var service = new V2LiteratureTurnService(
                sessions, deliveries, starts, loop, recovery, synthesis);

        assertThrows(IllegalArgumentException.class, () -> service.execute(
                7L, 9L, new V2LiteratureTurnRequest(
                        "query", 10, null, false, "request-1")));

        verifyNoInteractions(deliveries, starts, loop, recovery, synthesis);
    }

    @Test
    void rejectsMissingOwnerQualifiedSessionBeforeCreatingFacts() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        LiteratureDeliveryTransactions deliveries =
                mock(LiteratureDeliveryTransactions.class);
        var service = new V2LiteratureTurnService(
                sessions, deliveries,
                mock(AuthenticatedAgentTurnFreshExecutionStartComposer.class),
                mock(AuthenticatedPersistentPlanAgentLoopComposer.class),
                mock(StepRecoveryRepository.class),
                mock(DefaultFinalSynthesisComposer.class));
        when(sessions.findByIdAndUserId(9L, 7L))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.execute(
                7L, 9L, new V2LiteratureTurnRequest(
                        "query", 10, null, false, "request-1")));
        verifyNoInteractions(deliveries);
    }

    @Test
    void runningCrashGapReusesStableBootstrapAndEventFacts() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        LiteratureDeliveryTransactions deliveries =
                mock(LiteratureDeliveryTransactions.class);
        var starts = mock(
                AuthenticatedAgentTurnFreshExecutionStartComposer.class);
        var service = new V2LiteratureTurnService(
                sessions, deliveries, starts,
                mock(AuthenticatedPersistentPlanAgentLoopComposer.class),
                mock(StepRecoveryRepository.class),
                mock(DefaultFinalSynthesisComposer.class));
        when(sessions.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(
                new AgentSession(
                        7L, "workspace", "mock", "model", 4, false)));
        LiteratureDeliveryEntity running =
                mock(LiteratureDeliveryEntity.class);
        Instant created = Instant.parse("2026-07-28T01:00:00Z");
        when(running.status()).thenReturn("RUNNING");
        when(running.requestSha256()).thenReturn(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        when(running.turnId()).thenReturn(42L);
        when(running.createdAt()).thenReturn(created);
        when(running.leaseExpiresAt())
                .thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
        when(running.leaseOwnerId()).thenReturn("owner");
        when(running.leaseToken()).thenReturn("token");
        when(deliveries.open(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(running);
        when(starts.start(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("crash gap"));
        var request = new V2LiteratureTurnRequest(
                "stable query", 10, null, false, "stable-request");

        assertThrows(IllegalStateException.class,
                () -> service.execute(7L, 9L, request));
        assertThrows(IllegalStateException.class,
                () -> service.execute(7L, 9L, request));

        ArgumentCaptor<com.yanban.api.agent.v2.bootstrap
                .AuthenticatedAgentTurnFreshExecutionStartCommand> captured =
                ArgumentCaptor.forClass(com.yanban.api.agent.v2.bootstrap
                        .AuthenticatedAgentTurnFreshExecutionStartCommand.class);
        org.mockito.Mockito.verify(starts, times(2))
                .start(org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.eq(42L),
                        captured.capture());
        assertEquals(captured.getAllValues().get(0),
                captured.getAllValues().get(1));
    }
}
