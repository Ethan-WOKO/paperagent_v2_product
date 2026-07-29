package com.yanban.api.agent.v2.compatibility.project;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void newDeliveryPassesMicrosecondCanonicalExpiryIntoFreshStart() {
        Instant subMicrosecond =
                Instant.parse("2026-07-29T01:02:03.123456789Z");
        assertEquals(
                Instant.parse("2026-07-29T01:02:03.123456Z"),
                ProjectLeaseAuthorityTime.canonical(subMicrosecond));

        AgentSessionRepository sessions = mock(AgentSessionRepository.class);
        ProjectService projects = mock(ProjectService.class);
        ProjectAnalysisDeliveryTransactions deliveries =
                mock(ProjectAnalysisDeliveryTransactions.class);
        var contexts = mock(com.yanban.api.agent.v2
                .AgentTurnProductContextResolver.class);
        var starts = mock(com.yanban.api.agent.v2.bootstrap
                .AuthenticatedAgentTurnFreshExecutionStartComposer.class);
        var planContexts = mock(com.yanban.api.agent.v2.workspace
                .AuthenticatedAgentTurnPlanExecutionContextComposer.class);
        var planLoop = mock(com.yanban.api.agent.v2.loop
                .AuthenticatedPersistentPlanAgentLoopComposer.class);
        AgentSession session = mock(AgentSession.class);
        when(session.getScope()).thenReturn(AgentSessionScope.PROJECT);
        when(session.getProjectId()).thenReturn(8L);
        when(sessions.findByIdAndUserId(9L, 7L))
                .thenReturn(Optional.of(session));
        when(projects.manifest(7L, 8L)).thenReturn(
                new ProjectManifestResponse(
                        8L, "version", List.of(new ProjectFileEntry(
                                "paper.md", 10, Instant.EPOCH,
                                "a".repeat(64)))));
        when(deliveries.findMatching(any(), any()))
                .thenReturn(Optional.empty());
        java.util.concurrent.atomic.AtomicReference<
                ProjectAnalysisDeliveryEntity> opened =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(deliveries.open(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(Integer.class), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Instant expiry = invocation.getArgument(12);
                    ProjectAnalysisDeliveryEntity value =
                            new ProjectAnalysisDeliveryEntity(
                            new ProjectAnalysisDeliveryKey(
                                    7L, 8L, 9L, "request"),
                            "b".repeat(64), "Analyze evidence",
                            "[\"paper.md\"]", null, 10, "version",
                            41L, 42L, "owner", "token", expiry,
                            expiry.minusSeconds(600));
                    opened.set(value);
                    return value;
                });
        when(contexts.resolve(7L, 42L)).thenReturn(
                new VerifiedAgentTurnProductContext(
                        new AgentRunIdentity(
                                "AGENT_TURN", "42", 7L, 9L, 8L),
                        Optional.of("version")));
        var planId = new io.paperagent.v2.contracts.PlanId("plan");
        var persistedStart = mock(
                io.paperagent.v2.persistence.PersistedExecutionStart.class);
        when(persistedStart.planId()).thenReturn(planId);
        when(starts.start(any(), any(), any())).thenReturn(
                new io.paperagent.v2.runtime.execution.start
                        .FreshExecutionStarted(
                        io.paperagent.v2.persistence.PersistenceOutcome.APPLIED,
                        persistedStart));
        when(deliveries.bindPlanAndSteps(any(), eq("plan"), any()))
                .thenAnswer(invocation -> {
                    opened.get().bindPlan("plan");
                    return opened.get();
                });
        var ready = mock(io.paperagent.v2.runtime.execution.context
                .composition.PlanExecutionContextReady.class);
        when(ready.planId()).thenReturn(planId);
        var verified = mock(io.paperagent.v2.workspace
                .VerifiedWorkspaceMaterialization.class);
        when(verified.workspace()).thenReturn(
                new io.paperagent.v2.contracts.WorkspaceRef(
                        new io.paperagent.v2.contracts.WorkspaceId("workspace"),
                        new io.paperagent.v2.contracts.ProjectVersionRef(
                                "8", "version")));
        when(ready.verifiedWorkspace()).thenReturn(verified);
        when(planContexts.compose(any(), any(), any())).thenReturn(ready);
        when(deliveries.bindWorkspace(any(), eq("workspace")))
                .thenAnswer(invocation -> {
                    opened.get().bindWorkspace("workspace");
                    return opened.get();
                });
        when(planLoop.execute(any(), any(), any())).thenThrow(
                new IllegalStateException("expected test boundary"));
        ProjectAnalysisDeliveryEntity failed = delivery(
                new ProjectAnalysisDeliveryKey(7L, 8L, 9L, "request"),
                "FAILED");
        when(failed.errorCode()).thenReturn("PROJECT_ANALYSIS_FAILED");
        when(deliveries.fail(any(), any())).thenReturn(failed);
        V2ProjectAnalysisService service = new V2ProjectAnalysisService(
                sessions, projects, deliveries, starts, planContexts, planLoop,
                mock(io.paperagent.v2.persistence.StepRecoveryRepository.class),
                mock(io.paperagent.v2.persistence.LeaseRepository.class),
                mock(com.yanban.api.agent.v2.workspace
                        .AuthenticatedAgentTurnWorkspacePortFactory.class),
                contexts,
                mock(io.paperagent.v2.persistence
                        .FinalSynthesisRepository.class),
                mock(io.paperagent.v2.persistence.ReceiptRepository.class),
                mock(io.paperagent.v2.persistence
                        .EffectIntentRepository.class),
                mock(io.paperagent.v2.providers.ModelProvider.class),
                new ObjectMapper());

        assertEquals("FAILED", service.execute(
                7L, 8L, 9L, request(List.of("paper.md"))).status());

        ArgumentCaptor<Instant> openedExpiry =
                ArgumentCaptor.forClass(Instant.class);
        verify(deliveries).open(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(Integer.class), any(), any(), any(),
                openedExpiry.capture());
        ArgumentCaptor<AuthenticatedAgentTurnFreshExecutionStartCommand>
                command = ArgumentCaptor.forClass(
                        AuthenticatedAgentTurnFreshExecutionStartCommand.class);
        verify(starts).start(eq(7L), eq(42L), command.capture());
        Instant expiry = openedExpiry.getValue();
        assertEquals(0, expiry.getNano() % 1_000);
        assertEquals(
                expiry,
                command.getValue().attempt().orElseThrow()
                        .leaseExpiresAt());
        var contextCommand = ArgumentCaptor.forClass(
                com.yanban.api.agent.v2.workspace
                        .AuthenticatedAgentTurnPlanExecutionContextCommand.class);
        verify(planContexts).compose(
                eq(7L), eq(42L), contextCommand.capture());
        assertEquals(
                expiry,
                contextCommand.getValue().attempt().orElseThrow()
                        .leaseExpiresAt());
        var loopCommand = ArgumentCaptor.forClass(
                com.yanban.api.agent.v2.loop
                        .PersistentPlanAgentLoopCommand.class);
        verify(planLoop).execute(eq(7L), eq(42L), loopCommand.capture());
        assertEquals(
                expiry,
                loopCommand.getValue().currentRecoveryAttempt()
                        .leaseExpiresAt());
        assertEquals(
                expiry,
                loopCommand.getValue().readyActivationAttempt()
                        .leaseExpiresAt());
        assertEquals(
                expiry,
                loopCommand.getValue().nextStepActivationAttempt()
                        .leaseExpiresAt());
    }

    @Test
    void loopFailureDiagnosticContainsOnlyStableStageAndFailureType() {
        var failure = mock(com.yanban.api.agent.v2.loop
                .PersistentPlanAgentLoopException.class);
        when(failure.stage()).thenReturn("kernel");
        when(failure.getMessage()).thenReturn("owner-api-key");

        var diagnostic =
                V2ProjectAnalysisService.failureDiagnostic(failure);

        assertEquals("loop.kernel", diagnostic.stage());
        assertEquals(
                com.yanban.api.agent.v2.loop
                        .PersistentPlanAgentLoopException.class.getName(),
                diagnostic.failureType());
        assertFalse(diagnostic.toString().contains("owner-api-key"));
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
