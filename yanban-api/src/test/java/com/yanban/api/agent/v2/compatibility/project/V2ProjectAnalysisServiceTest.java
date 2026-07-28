package com.yanban.api.agent.v2.compatibility.project;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnFreshExecutionStartCommand;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class V2ProjectAnalysisServiceTest {
    @Test
    void rejectsWrongScopeTraversalDuplicatesAndBoundsBeforeDelivery() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        ProjectService projects = mock(ProjectService.class);
        ProjectAnalysisDeliveryTransactions deliveries =
                mock(ProjectAnalysisDeliveryTransactions.class);
        AgentSession workspace = mock(AgentSession.class);
        when(workspace.getScope()).thenReturn(AgentSessionScope.WORKSPACE);
        when(sessions.findByIdAndUserId(9L, 7L))
                .thenReturn(Optional.of(workspace));
        V2ProjectAnalysisService service =
                service(sessions, projects, deliveries);

        assertThrows(IllegalArgumentException.class, () -> service.execute(
                7L, 8L, 9L, request(List.of("paper.md"))));
        verifyNoInteractions(projects, deliveries);

        AgentSession project = mock(AgentSession.class);
        when(project.getScope()).thenReturn(AgentSessionScope.PROJECT);
        when(project.getProjectId()).thenReturn(8L);
        when(sessions.findByIdAndUserId(9L, 7L))
                .thenReturn(Optional.of(project));
        when(projects.manifest(7L, 8L)).thenReturn(new ProjectManifestResponse(
                8L, "version", List.of(new ProjectFileEntry(
                        "paper.md", 10, Instant.EPOCH, "a".repeat(64)))));

        assertThrows(IllegalArgumentException.class, () -> service.execute(
                7L, 8L, 9L, request(List.of("../paper.md"))));
        assertThrows(IllegalArgumentException.class, () -> service.execute(
                7L, 8L, 9L, request(
                        List.of("paper.md", "paper.md"))));
        assertThrows(IllegalArgumentException.class, () -> service.execute(
                7L, 8L, 9L, new V2ProjectAnalysisRequest(
                        "objective", List.of("paper.md"),
                        "x".repeat(257), 21, "request")));
        verifyNoInteractions(deliveries);
    }

    @Test
    void deterministicFailureBecomesPermanentReplayWithoutManifestRefresh() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        ProjectService projects = mock(ProjectService.class);
        ProjectAnalysisDeliveryTransactions deliveries =
                mock(ProjectAnalysisDeliveryTransactions.class);
        var contexts = mock(com.yanban.api.agent.v2
                .AgentTurnProductContextResolver.class);
        AgentSession project = mock(AgentSession.class);
        when(project.getScope()).thenReturn(AgentSessionScope.PROJECT);
        when(project.getProjectId()).thenReturn(8L);
        when(sessions.findByIdAndUserId(9L, 7L))
                .thenReturn(Optional.of(project));
        ProjectAnalysisDeliveryKey key =
                new ProjectAnalysisDeliveryKey(7L, 8L, 9L, "request");
        ProjectAnalysisDeliveryEntity running = delivery(key, "RUNNING");
        ProjectAnalysisDeliveryEntity failed = delivery(key, "FAILED");
        when(failed.errorCode()).thenReturn("PROJECT_ANALYSIS_FAILED");
        when(deliveries.paths(failed)).thenReturn(List.of("paper.md"));
        when(deliveries.findMatching(any(), any()))
                .thenReturn(Optional.of(running))
                .thenReturn(Optional.of(failed));
        when(deliveries.find(key)).thenReturn(failed);
        when(deliveries.fail(any(), any())).thenReturn(failed);
        when(contexts.resolve(7L, 42L))
                .thenThrow(new IllegalStateException("provider detail"));
        V2ProjectAnalysisService service = service(
                sessions, projects, deliveries, contexts,
                mock(io.paperagent.v2.persistence
                        .FinalSynthesisRepository.class),
                mock(com.yanban.api.agent.v2.bootstrap
                        .AuthenticatedAgentTurnFreshExecutionStartComposer.class),
                mock(io.paperagent.v2.persistence.LeaseRepository.class));

        var first = service.execute(7L, 8L, 9L,
                request(List.of("paper.md")));
        var replay = service.read(7L, 8L, 9L, "request");

        assertEquals("FAILED", first.status());
        assertTrue(first.terminal());
        assertEquals("PROJECT_ANALYSIS_FAILED", first.errorCode());
        assertEquals("FAILED", replay.status());
        assertTrue(replay.terminal());
        assertTrue(replay.replayed());
        verify(contexts, times(1)).resolve(7L, 42L);
        verifyNoInteractions(projects);
    }

    @Test
    void staleFrozenProjectVersionFailsBeforePlanOrWorkspace() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        ProjectService projects = mock(ProjectService.class);
        ProjectAnalysisDeliveryTransactions deliveries =
                mock(ProjectAnalysisDeliveryTransactions.class);
        var contexts = mock(com.yanban.api.agent.v2
                .AgentTurnProductContextResolver.class);
        var starts = mock(com.yanban.api.agent.v2.bootstrap
                .AuthenticatedAgentTurnFreshExecutionStartComposer.class);
        AgentSession project = mock(AgentSession.class);
        when(project.getScope()).thenReturn(AgentSessionScope.PROJECT);
        when(project.getProjectId()).thenReturn(8L);
        when(sessions.findByIdAndUserId(9L, 7L))
                .thenReturn(Optional.of(project));
        ProjectAnalysisDeliveryKey key =
                new ProjectAnalysisDeliveryKey(7L, 8L, 9L, "request");
        ProjectAnalysisDeliveryEntity running = delivery(key, "RUNNING");
        ProjectAnalysisDeliveryEntity failed = delivery(key, "FAILED");
        when(failed.errorCode()).thenReturn("PROJECT_ANALYSIS_FAILED");
        when(deliveries.findMatching(any(), any()))
                .thenReturn(Optional.of(running));
        when(deliveries.fail(any(), any())).thenReturn(failed);
        when(contexts.resolve(7L, 42L)).thenReturn(
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN", "42", 7L, 9L, 8L),
                        Optional.of("changed-version")));
        V2ProjectAnalysisService service = service(
                sessions, projects, deliveries, contexts,
                mock(io.paperagent.v2.persistence
                        .FinalSynthesisRepository.class),
                starts, mock(io.paperagent.v2.persistence
                        .LeaseRepository.class));

        var response = service.execute(
                7L, 8L, 9L, request(List.of("paper.md")));

        assertEquals("FAILED", response.status());
        verifyNoInteractions(starts, projects);
    }

    @Test
    void expiredBoundDeliveryRotatesAndAcquiresDurableLease() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        ProjectService projects = mock(ProjectService.class);
        ProjectAnalysisDeliveryTransactions deliveries =
                mock(ProjectAnalysisDeliveryTransactions.class);
        var contexts = mock(com.yanban.api.agent.v2
                .AgentTurnProductContextResolver.class);
        var leases = mock(io.paperagent.v2.persistence
                .LeaseRepository.class);
        AgentSession project = mock(AgentSession.class);
        when(project.getScope()).thenReturn(AgentSessionScope.PROJECT);
        when(project.getProjectId()).thenReturn(8L);
        when(sessions.findByIdAndUserId(9L, 7L))
                .thenReturn(Optional.of(project));
        ProjectAnalysisDeliveryKey key =
                new ProjectAnalysisDeliveryKey(7L, 8L, 9L, "request");
        ProjectAnalysisDeliveryEntity expired = delivery(key, "RUNNING");
        when(expired.leaseExpiresAt()).thenReturn(
                Instant.now().minusSeconds(1));
        ProjectAnalysisDeliveryEntity rotated = delivery(key, "RUNNING");
        when(rotated.planId()).thenReturn("plan");
        when(rotated.leaseOwnerId()).thenReturn("owner");
        when(rotated.leaseToken()).thenReturn("new-token");
        Instant newExpiry = Instant.now().plusSeconds(300);
        when(rotated.leaseExpiresAt()).thenReturn(newExpiry);
        ProjectAnalysisDeliveryEntity failed = delivery(key, "FAILED");
        when(failed.errorCode()).thenReturn("PROJECT_ANALYSIS_FAILED");
        when(deliveries.findMatching(any(), any()))
                .thenReturn(Optional.of(expired));
        when(deliveries.rotateExpiredLease(
                any(), any(), any(), any())).thenReturn(rotated);
        when(leases.acquire(
                new io.paperagent.v2.contracts.PlanId("plan"),
                "owner", "new-token", newExpiry))
                .thenReturn(io.paperagent.v2.persistence.PersistenceResult
                        .applied(new io.paperagent.v2.persistence.LeaseRecord(
                                new io.paperagent.v2.contracts.PlanId("plan"),
                                "owner", "new-token", 2L,
                                Instant.now().minusSeconds(1), newExpiry)));
        when(contexts.resolve(7L, 42L))
                .thenThrow(new IllegalStateException("stop after takeover"));
        when(deliveries.fail(any(), any())).thenReturn(failed);
        V2ProjectAnalysisService service = service(
                sessions, projects, deliveries, contexts,
                mock(io.paperagent.v2.persistence
                        .FinalSynthesisRepository.class),
                mock(com.yanban.api.agent.v2.bootstrap
                        .AuthenticatedAgentTurnFreshExecutionStartComposer.class),
                leases);

        assertEquals("FAILED", service.execute(
                7L, 8L, 9L, request(List.of("paper.md"))).status());
        verify(leases).acquire(
                new io.paperagent.v2.contracts.PlanId("plan"),
                "owner", "new-token", newExpiry);
        verifyNoInteractions(projects);
    }

    @Test
    void fourReadsThenOptionalSearchAreFrozenInExactStepOrder() {
        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        ProjectService projects = mock(ProjectService.class);
        ProjectAnalysisDeliveryTransactions deliveries =
                mock(ProjectAnalysisDeliveryTransactions.class);
        var contexts = mock(com.yanban.api.agent.v2
                .AgentTurnProductContextResolver.class);
        var starts = mock(com.yanban.api.agent.v2.bootstrap
                .AuthenticatedAgentTurnFreshExecutionStartComposer.class);
        AgentSession project = mock(AgentSession.class);
        when(project.getScope()).thenReturn(AgentSessionScope.PROJECT);
        when(project.getProjectId()).thenReturn(8L);
        when(sessions.findByIdAndUserId(9L, 7L))
                .thenReturn(Optional.of(project));
        ProjectAnalysisDeliveryKey key =
                new ProjectAnalysisDeliveryKey(7L, 8L, 9L, "request");
        ProjectAnalysisDeliveryEntity running = delivery(key, "RUNNING");
        ProjectAnalysisDeliveryEntity failed = delivery(key, "FAILED");
        when(failed.errorCode()).thenReturn("PROJECT_ANALYSIS_FAILED");
        when(deliveries.findMatching(any(), any()))
                .thenReturn(Optional.of(running));
        when(deliveries.fail(any(), any())).thenReturn(failed);
        when(contexts.resolve(7L, 42L)).thenReturn(
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN", "42", 7L, 9L, 8L),
                        Optional.of("version")));
        when(starts.start(any(), any(), any())).thenThrow(
                new IllegalStateException("inspect frozen command"));
        V2ProjectAnalysisService service = service(
                sessions, projects, deliveries, contexts,
                mock(io.paperagent.v2.persistence
                        .FinalSynthesisRepository.class),
                starts, mock(io.paperagent.v2.persistence
                        .LeaseRepository.class));

        service.execute(7L, 8L, 9L, new V2ProjectAnalysisRequest(
                "Analyze evidence",
                List.of("a.md", "b.md", "c.md", "d.md"),
                "needle", 7, "request"));

        ArgumentCaptor<AuthenticatedAgentTurnFreshExecutionStartCommand>
                command = ArgumentCaptor.forClass(
                        AuthenticatedAgentTurnFreshExecutionStartCommand.class);
        verify(starts).start(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(42L), command.capture());
        var steps = command.getValue().bootstrapCommand()
                .initialPlanDraft().steps();
        assertEquals(List.of(
                        "project-read-01", "project-read-02",
                        "project-read-03", "project-read-04",
                        "project-search-01"),
                steps.stream().map(step -> step.id().value()).toList());
        assertEquals("Call project.search exactly once with "
                        + "{\"maxResults\":7,\"query\":\"needle\"}",
                steps.get(4).intent());
    }

    private static V2ProjectAnalysisRequest request(List<String> paths) {
        return new V2ProjectAnalysisRequest(
                "Analyze evidence", paths, null, 10, "request");
    }

    private static V2ProjectAnalysisService service(
            AgentSessionRepository sessions, ProjectService projects,
            ProjectAnalysisDeliveryTransactions deliveries) {
        return service(sessions, projects, deliveries,
                mock(com.yanban.api.agent.v2
                        .AgentTurnProductContextResolver.class),
                mock(io.paperagent.v2.persistence
                        .FinalSynthesisRepository.class),
                mock(com.yanban.api.agent.v2.bootstrap
                        .AuthenticatedAgentTurnFreshExecutionStartComposer.class),
                mock(io.paperagent.v2.persistence.LeaseRepository.class));
    }

    private static V2ProjectAnalysisService service(
            AgentSessionRepository sessions, ProjectService projects,
            ProjectAnalysisDeliveryTransactions deliveries,
            com.yanban.api.agent.v2.AgentTurnProductContextResolver contexts,
            io.paperagent.v2.persistence.FinalSynthesisRepository syntheses,
            com.yanban.api.agent.v2.bootstrap
                    .AuthenticatedAgentTurnFreshExecutionStartComposer starts,
            io.paperagent.v2.persistence.LeaseRepository leases) {
        return new V2ProjectAnalysisService(
                sessions, projects, deliveries,
                starts,
                mock(com.yanban.api.agent.v2.workspace
                        .AuthenticatedAgentTurnPlanExecutionContextComposer.class),
                mock(com.yanban.api.agent.v2.loop
                        .AuthenticatedPersistentPlanAgentLoopComposer.class),
                mock(io.paperagent.v2.persistence.StepRecoveryRepository.class),
                leases,
                mock(com.yanban.api.agent.v2.workspace
                        .AuthenticatedAgentTurnWorkspacePortFactory.class),
                contexts, syntheses,
                mock(io.paperagent.v2.persistence.ReceiptRepository.class),
                mock(io.paperagent.v2.persistence.EffectIntentRepository.class),
                mock(io.paperagent.v2.providers.ModelProvider.class),
                new ObjectMapper());
    }

    private static ProjectAnalysisDeliveryEntity delivery(
            ProjectAnalysisDeliveryKey key, String status) {
        ProjectAnalysisDeliveryEntity value =
                mock(ProjectAnalysisDeliveryEntity.class);
        when(value.id()).thenReturn(key);
        when(value.status()).thenReturn(status);
        when(value.turnId()).thenReturn(42L);
        when(value.planId()).thenReturn("plan");
        when(value.objective()).thenReturn("Analyze evidence");
        when(value.maxSearchResults()).thenReturn(10);
        when(value.projectVersionId()).thenReturn("version");
        when(value.leaseOwnerId()).thenReturn("owner");
        when(value.leaseToken()).thenReturn("token");
        when(value.createdAt()).thenReturn(Instant.EPOCH);
        when(value.leaseExpiresAt()).thenReturn(
                Instant.now().plusSeconds(60));
        return value;
    }
}
