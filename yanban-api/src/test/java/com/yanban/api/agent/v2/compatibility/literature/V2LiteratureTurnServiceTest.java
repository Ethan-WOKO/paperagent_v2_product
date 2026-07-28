package com.yanban.api.agent.v2.compatibility.literature;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.times;

import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnFreshExecutionStartComposer;
import com.yanban.api.agent.v2.loop.AuthenticatedPersistentPlanAgentLoopComposer;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.runtime.execution.start.FreshExecutionRecoveryRequired;
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
                sessions, deliveries, starts, loop, recovery, synthesis,
                mock(LeaseRepository.class));

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
                mock(DefaultFinalSynthesisComposer.class),
                mock(LeaseRepository.class));
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
                mock(DefaultFinalSynthesisComposer.class),
                mock(LeaseRepository.class));
        when(sessions.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(
                new AgentSession(
                        7L, "workspace", "mock", "model", 4, false)));
        LiteratureDeliveryEntity running =
                mock(LiteratureDeliveryEntity.class);
        Instant created = Instant.parse("2026-07-28T01:00:00Z");
        when(running.status()).thenReturn("RUNNING");
        when(running.id()).thenReturn(
                new LiteratureDeliveryKey(7L, 9L, "stable-request"));
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

    @Test
    void expiredBoundDeliveryTakesOverPersistentLeaseBeforeLoop() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        LiteratureDeliveryTransactions deliveries =
                mock(LiteratureDeliveryTransactions.class);
        var starts = mock(
                AuthenticatedAgentTurnFreshExecutionStartComposer.class);
        var loop = mock(AuthenticatedPersistentPlanAgentLoopComposer.class);
        LeaseRepository leases = mock(LeaseRepository.class);
        var service = new V2LiteratureTurnService(
                sessions, deliveries, starts, loop,
                mock(StepRecoveryRepository.class),
                mock(DefaultFinalSynthesisComposer.class), leases);
        when(sessions.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(
                new AgentSession(
                        7L, "workspace", "mock", "model", 4, false)));
        LiteratureDeliveryEntity expired =
                mock(LiteratureDeliveryEntity.class);
        LiteratureDeliveryEntity rotated =
                mock(LiteratureDeliveryEntity.class);
        var key = new LiteratureDeliveryKey(
                7L, 9L, "expired-request");
        when(expired.id()).thenReturn(key);
        when(expired.status()).thenReturn("RUNNING");
        when(expired.leaseExpiresAt()).thenReturn(
                Instant.parse("2020-01-01T00:00:00Z"));
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
                .thenReturn(expired);
        when(deliveries.rotateExpiredLease(
                org.mockito.ArgumentMatchers.eq(key),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(rotated);
        Instant created = Instant.parse("2026-07-28T01:00:00Z");
        Instant newExpiry = Instant.parse("2030-01-01T00:00:00Z");
        when(rotated.id()).thenReturn(key);
        when(rotated.status()).thenReturn("RUNNING");
        when(rotated.planId()).thenReturn("bound-plan");
        when(rotated.turnId()).thenReturn(42L);
        when(rotated.createdAt()).thenReturn(created);
        when(rotated.requestSha256()).thenReturn(
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                        + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        when(rotated.leaseOwnerId()).thenReturn("owner");
        when(rotated.leaseToken()).thenReturn("new-token");
        when(rotated.leaseExpiresAt()).thenReturn(newExpiry);
        when(leases.acquire(
                new PlanId("bound-plan"), "owner", "new-token", newExpiry))
                .thenReturn(PersistenceResult.applied(new LeaseRecord(
                        new PlanId("bound-plan"), "owner", "new-token",
                        2, created, newExpiry)));
        when(starts.start(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new FreshExecutionRecoveryRequired(
                        new PlanId("bound-plan")));
        when(deliveries.bindPlan(key, "bound-plan"))
                .thenReturn(rotated);
        when(loop.execute(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("stop after loop input"));

        assertThrows(IllegalStateException.class, () -> service.execute(
                7L, 9L, new V2LiteratureTurnRequest(
                        "query", 10, null, false,
                        "expired-request")));
        verify(leases).acquire(
                new PlanId("bound-plan"), "owner", "new-token", newExpiry);
        ArgumentCaptor<com.yanban.api.agent.v2.loop
                .PersistentPlanAgentLoopCommand> command =
                ArgumentCaptor.forClass(com.yanban.api.agent.v2.loop
                        .PersistentPlanAgentLoopCommand.class);
        org.mockito.Mockito.verify(loop).execute(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L),
                command.capture());
        assertEquals("new-token",
                command.getValue().currentRecoveryAttempt().leaseToken());
    }

    @Test
    void samePayloadAcrossSessionsHasDistinctLeaseAndEventIds() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        LiteratureDeliveryTransactions deliveries =
                mock(LiteratureDeliveryTransactions.class);
        var starts = mock(
                AuthenticatedAgentTurnFreshExecutionStartComposer.class);
        when(sessions.findByIdAndUserId(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(7L)))
                .thenAnswer(invocation -> Optional.of(new AgentSession(
                        7L, "workspace", "mock", "model", 4, false)));
        LiteratureDeliveryEntity first =
                runningDelivery(9L, 41L, "same-request", "token-a");
        LiteratureDeliveryEntity second =
                runningDelivery(10L, 42L, "same-request", "token-b");
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
                .thenReturn(first, second);
        when(starts.start(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("capture"));
        var service = new V2LiteratureTurnService(
                sessions, deliveries, starts,
                mock(AuthenticatedPersistentPlanAgentLoopComposer.class),
                mock(StepRecoveryRepository.class),
                mock(DefaultFinalSynthesisComposer.class),
                mock(LeaseRepository.class));
        var request = new V2LiteratureTurnRequest(
                "same query", 10, null, false, "same-request");
        assertThrows(IllegalStateException.class,
                () -> service.execute(7L, 9L, request));
        assertThrows(IllegalStateException.class,
                () -> service.execute(7L, 10L, request));

        ArgumentCaptor<String> initialTokens =
                ArgumentCaptor.forClass(String.class);
        verify(deliveries, times(2)).open(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("same-request"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("same query"),
                org.mockito.ArgumentMatchers.eq(10),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.anyString(),
                initialTokens.capture(),
                org.mockito.ArgumentMatchers.any());
        assertNotEquals(initialTokens.getAllValues().get(0),
                initialTokens.getAllValues().get(1));
        ArgumentCaptor<com.yanban.api.agent.v2.bootstrap
                .AuthenticatedAgentTurnFreshExecutionStartCommand> commands =
                ArgumentCaptor.forClass(com.yanban.api.agent.v2.bootstrap
                        .AuthenticatedAgentTurnFreshExecutionStartCommand.class);
        verify(starts, times(2)).start(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.anyLong(),
                commands.capture());
        assertNotEquals(
                commands.getAllValues().get(0).attempt().orElseThrow()
                        .eventDraft().id(),
                commands.getAllValues().get(1).attempt().orElseThrow()
                        .eventDraft().id());
    }

    private static LiteratureDeliveryEntity runningDelivery(
            long sessionId, long turnId, String requestId, String token) {
        LiteratureDeliveryEntity value =
                mock(LiteratureDeliveryEntity.class);
        when(value.id()).thenReturn(
                new LiteratureDeliveryKey(7L, sessionId, requestId));
        when(value.status()).thenReturn("RUNNING");
        when(value.turnId()).thenReturn(turnId);
        when(value.createdAt()).thenReturn(
                Instant.parse("2026-07-28T01:00:00Z"));
        when(value.requestSha256()).thenReturn(
                "cccccccccccccccccccccccccccccccc"
                        + "cccccccccccccccccccccccccccccccc");
        when(value.leaseOwnerId()).thenReturn("owner-" + sessionId);
        when(value.leaseToken()).thenReturn(token);
        when(value.leaseExpiresAt()).thenReturn(
                Instant.parse("2030-01-01T00:00:00Z"));
        return value;
    }
}
