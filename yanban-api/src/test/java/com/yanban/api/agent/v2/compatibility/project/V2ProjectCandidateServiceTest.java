package com.yanban.api.agent.v2.compatibility.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnFreshExecutionStartComposer;
import com.yanban.api.agent.v2.effect.project.ProjectCandidateCompositionEffect;
import com.yanban.api.agent.v2.loop.AuthenticatedPersistentPlanAgentLoopComposer;
import com.yanban.api.agent.v2.workspace.*;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.*;
import io.paperagent.v2.persistence.StepRecoveryRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class V2ProjectCandidateServiceTest {
    private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectCandidateDeliveryTransactions deliveries =
            mock(ProjectCandidateDeliveryTransactions.class);
    private final AuthenticatedAgentTurnFreshExecutionStartComposer starts =
            mock(AuthenticatedAgentTurnFreshExecutionStartComposer.class);
    private final AuthenticatedAgentTurnPlanExecutionContextComposer contexts =
            mock(AuthenticatedAgentTurnPlanExecutionContextComposer.class);
    private final AuthenticatedPersistentPlanAgentLoopComposer loop =
            mock(AuthenticatedPersistentPlanAgentLoopComposer.class);
    private final StepRecoveryRepository recovery = mock(StepRecoveryRepository.class);
    private final io.paperagent.v2.persistence.LeaseRepository leases =
            mock(io.paperagent.v2.persistence.LeaseRepository.class);
    private final com.yanban.api.agent.v2.AgentTurnProductContextResolver turnContexts =
            mock(com.yanban.api.agent.v2.AgentTurnProductContextResolver.class);
    private final AuthenticatedAgentTurnWorkspacePortFactory workspaces =
            mock(AuthenticatedAgentTurnWorkspacePortFactory.class);
    private final ProjectCandidateCompositionEffect composition =
            mock(ProjectCandidateCompositionEffect.class);
    private final V2ProjectCandidateService service = new V2ProjectCandidateService(
            sessions, projects, deliveries, starts, contexts, loop, recovery,
            leases, turnContexts,
            workspaces, composition, new ObjectMapper());

    @Test
    void invalidObjectivePathsAndRequestIdentityFailBeforeProductLookup() {
        for (V2ProjectCandidateRequest request : List.of(
                new V2ProjectCandidateRequest(
                        " ", List.of("README.md"), "id"),
                new V2ProjectCandidateRequest(
                        "edit", List.of("../secret"), "id"),
                new V2ProjectCandidateRequest(
                        "edit", List.of("a", "a"), "id"),
                new V2ProjectCandidateRequest(
                        "edit", List.of("a"), " "))) {
            assertEquals(HttpStatus.BAD_REQUEST,
                    assertThrows(ResponseStatusException.class,
                            () -> service.execute(
                                    7L, 8L, 9L, request))
                            .getStatusCode());
        }
        verifyNoInteractions(sessions, projects, deliveries);
    }

    @Test
    void crossUserSessionOrProjectFailsBeforeOpeningDelivery() {
        AgentSession session = mock(AgentSession.class);
        when(session.getScope()).thenReturn(AgentSessionScope.PROJECT);
        when(session.getProjectId()).thenReturn(99L);
        when(sessions.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(session));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> service.execute(
                                7L, 8L, 9L,
                                new V2ProjectCandidateRequest(
                                        "edit",
                                        List.of("README.md"), "id")))
                        .getStatusCode());
        verifyNoInteractions(projects, deliveries);
    }

    @Test
    void requestHashUsesUnambiguousEncodingForDelimiterBearingPayloads() {
        AgentSession session = mock(AgentSession.class);
        when(session.getScope()).thenReturn(AgentSessionScope.PROJECT);
        when(session.getProjectId()).thenReturn(8L);
        when(sessions.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(session));
        when(deliveries.findMatching(any(), anyString()))
                .thenThrow(new IllegalStateException("stop after hashing"));

        assertThrows(IllegalStateException.class, () -> service.execute(
                7L, 8L, 9L, new V2ProjectCandidateRequest(
                        "a", List.of("b\0c"), "same-request")));
        assertThrows(IllegalStateException.class, () -> service.execute(
                7L, 8L, 9L, new V2ProjectCandidateRequest(
                        "a\0b", List.of("c"), "same-request")));

        var hashes = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(deliveries, times(2)).findMatching(any(), hashes.capture());
        assertNotEquals(hashes.getAllValues().get(0), hashes.getAllValues().get(1));
        verifyNoInteractions(projects);
    }

    @Test
    void terminalGetReplaysSameCandidateAndFailedGetIsDefinitiveNotCreated() {
        var successKey = new ProjectCandidateDeliveryKey(7L, 8L, 9L, "success");
        var success = new ProjectCandidateDeliveryEntity(successKey, "a".repeat(64),
                "objective", "[\"README.md\"]", "version", 1L, 2L,
                "owner", "token", Instant.now().plusSeconds(60), Instant.now());
        success.bindPlan("plan");
        success.bindCandidate(42L, "b".repeat(64), "c".repeat(64));
        success.complete(3L);
        when(deliveries.find(successKey)).thenReturn(success);
        var replay = service.read(7L, 8L, 9L, "success");
        assertEquals("SUCCEEDED", replay.status());
        assertEquals(42L, replay.candidateArtifactId());
        assertTrue(replay.replayed());

        var failedKey = new ProjectCandidateDeliveryKey(7L, 8L, 9L, "failed");
        var failed = new ProjectCandidateDeliveryEntity(failedKey, "d".repeat(64),
                "objective", "[\"README.md\"]", "version", 4L, 5L,
                "owner", "token", Instant.now().plusSeconds(60), Instant.now());
        failed.fail("PROJECT_CANDIDATE_FAILED");
        when(deliveries.find(failedKey)).thenReturn(failed);
        var failedReplay = service.read(7L, 8L, 9L, "failed");
        assertEquals("FAILED", failedReplay.status());
        assertNull(failedReplay.candidateArtifactId());
        assertTrue(failedReplay.terminal());
    }

    @Test
    void missingRecoveryIdentityIsDefinitiveNotFound() {
        var key = new ProjectCandidateDeliveryKey(7L, 8L, 9L, "missing");
        when(deliveries.find(key)).thenThrow(
                new IllegalArgumentException("delivery was not found"));

        var missing = assertThrows(ResponseStatusException.class,
                () -> service.read(7L, 8L, 9L, "missing"));

        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
        verifyNoInteractions(sessions, projects);
    }

    @Test
    void happyPathRequiresTerminalCutThenPublishesAndDeliversOneCandidate() {
        Instant subMicrosecond =
                Instant.parse("2026-07-29T01:02:03.123456789Z");
        assertEquals(
                Instant.parse("2026-07-29T01:02:03.123456Z"),
                ProjectLeaseAuthorityTime.canonical(subMicrosecond));
        String version = "a".repeat(64);
        var request = new V2ProjectCandidateRequest(
                "improve", List.of("README.md"), "request");
        AgentSession session = mock(AgentSession.class);
        when(session.getScope()).thenReturn(AgentSessionScope.PROJECT);
        when(session.getProjectId()).thenReturn(8L);
        when(sessions.findByIdAndUserId(9L, 7L)).thenReturn(Optional.of(session));
        var manifest = new com.yanban.api.project.ProjectManifestResponse(
                8L, version, List.of(new com.yanban.api.project.ProjectFileEntry(
                "README.md", 8, Instant.now(), "b".repeat(64))));
        when(projects.manifest(7L, 8L)).thenReturn(manifest);
        when(deliveries.findMatching(any(), anyString())).thenReturn(Optional.empty());
        var key = new ProjectCandidateDeliveryKey(7L, 8L, 9L, "request");
        java.util.concurrent.atomic.AtomicReference<Instant> openedExpiry =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(deliveries.open(eq(7L), eq(8L), eq(9L), eq("request"), anyString(),
                eq("improve"), eq(List.of("README.md")), eq(version),
                anyString(), anyString(), any())).thenAnswer(invocation -> {
                    Instant expiry = invocation.getArgument(10);
                    openedExpiry.set(expiry);
                    return new ProjectCandidateDeliveryEntity(
                            key, "c".repeat(64), "improve",
                            "[\"README.md\"]", version, 1L, 2L,
                            "owner", "token", expiry,
                            expiry.minusSeconds(300));
                });
        var identity = new AgentRunIdentity("AGENT_TURN", "turn-2", 7L, 2L, 8L);
        when(turnContexts.resolve(7L, 2L)).thenReturn(
                new com.yanban.api.agent.v2.VerifiedAgentTurnProductContext(
                        identity, Optional.of(version)));
        var planId = new io.paperagent.v2.contracts.PlanId("plan");
        var persistedStart = mock(io.paperagent.v2.persistence.PersistedExecutionStart.class);
        when(persistedStart.planId()).thenReturn(planId);
        when(starts.start(eq(7L), eq(2L), any())).thenReturn(
                new io.paperagent.v2.runtime.execution.start.FreshExecutionStarted(
                        io.paperagent.v2.persistence.PersistenceOutcome.APPLIED,
                        persistedStart));
        when(deliveries.bindPlanAndSteps(eq(key), eq("plan"), any()))
                .thenAnswer(invocation -> {
                    Instant expiry = openedExpiry.get();
                    var bound = new ProjectCandidateDeliveryEntity(
                            key, "c".repeat(64), "improve",
                            "[\"README.md\"]", version, 1L, 2L,
                            "owner", "token", expiry,
                            expiry.minusSeconds(300));
                    bound.bindPlan("plan");
                    return bound;
                });
        var ready = mock(io.paperagent.v2.runtime.execution.context.composition
                .PlanExecutionContextReady.class);
        when(ready.planId()).thenReturn(planId);
        var verified = mock(io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization.class);
        var ref = new io.paperagent.v2.contracts.WorkspaceRef(
                new io.paperagent.v2.contracts.WorkspaceId("workspace"),
                new io.paperagent.v2.contracts.ProjectVersionRef("8", version));
        when(verified.workspace()).thenReturn(ref);
        when(ready.verifiedWorkspace()).thenReturn(verified);
        var persistedContext = mock(io.paperagent.v2.persistence
                .PersistedPlanExecutionContextConfirmed.class);
        var spec = mock(io.paperagent.v2.contracts.WorkspaceMaterializationSpec.class);
        when(persistedContext.materializationSpec()).thenReturn(spec);
        when(ready.persistedContext()).thenReturn(persistedContext);
        when(contexts.compose(eq(7L), eq(2L), any())).thenReturn(ready);
        when(deliveries.bindWorkspace(key, "workspace"))
                .thenAnswer(invocation -> {
                    Instant expiry = openedExpiry.get();
                    var bound = new ProjectCandidateDeliveryEntity(
                            key, "c".repeat(64), "improve",
                            "[\"README.md\"]", version, 1L, 2L,
                            "owner", "token", expiry,
                            expiry.minusSeconds(300));
                    bound.bindPlan("plan");
                    bound.bindWorkspace("workspace");
                    return bound;
                });
        when(loop.execute(eq(7L), eq(2L), any())).thenReturn(
                new com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopOutcome(
                        planId, 2,
                        com.yanban.api.agent.v2.loop.PersistentPlanAgentLoopState.PLAN_SUCCEEDED,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        var terminal = mock(io.paperagent.v2.persistence.PersistedStepRecoverySucceeded.class);
        when(recovery.inspect(planId)).thenReturn(
                io.paperagent.v2.persistence.PersistenceResult.found(terminal));
        var workspace = mock(io.paperagent.v2.workspace.WorkspacePort.class);
        when(workspaces.create(7L, 2L)).thenReturn(workspace);
        when(workspace.inspectMaterialization(spec)).thenReturn(verified);
        when(composition.publish(eq("plan"), eq(7L), eq(2L), eq(workspace),
                eq(ref), any())).thenReturn(
                new ProjectCandidateCompositionEffect.CandidateResult(
                        42L, "d".repeat(64), "e".repeat(64)));
        var delivered = new ProjectCandidateDeliveryEntity(key, "c".repeat(64),
                "improve", "[\"README.md\"]", version, 1L, 2L,
                "owner", "token", Instant.now().plusSeconds(60), Instant.now());
        delivered.bindPlan("plan");
        delivered.bindWorkspace("workspace");
        delivered.bindCandidate(42L, "d".repeat(64), "e".repeat(64));
        delivered.complete(3L);
        when(deliveries.deliver(key)).thenReturn(delivered);

        var response = service.execute(7L, 8L, 9L, request);

        assertEquals("SUCCEEDED", response.status());
        assertEquals(42L, response.candidateArtifactId());
        Instant expiry = openedExpiry.get();
        assertNotNull(expiry);
        assertEquals(0, expiry.getNano() % 1_000);
        var startCommand = org.mockito.ArgumentCaptor.forClass(
                com.yanban.api.agent.v2.bootstrap
                        .AuthenticatedAgentTurnFreshExecutionStartCommand.class);
        verify(starts).start(eq(7L), eq(2L), startCommand.capture());
        assertEquals(expiry, startCommand.getValue().attempt()
                .orElseThrow().leaseExpiresAt());
        var contextCommand = org.mockito.ArgumentCaptor.forClass(
                AuthenticatedAgentTurnPlanExecutionContextCommand.class);
        verify(contexts).compose(eq(7L), eq(2L), contextCommand.capture());
        assertEquals(expiry, contextCommand.getValue().attempt()
                .orElseThrow().leaseExpiresAt());
        var loopCommand = org.mockito.ArgumentCaptor.forClass(
                com.yanban.api.agent.v2.loop
                        .PersistentPlanAgentLoopCommand.class);
        verify(loop).execute(eq(7L), eq(2L), loopCommand.capture());
        assertEquals(expiry, loopCommand.getValue()
                .currentRecoveryAttempt().leaseExpiresAt());
        assertEquals(expiry, loopCommand.getValue()
                .readyActivationAttempt().leaseExpiresAt());
        assertEquals(expiry, loopCommand.getValue()
                .nextStepActivationAttempt().leaseExpiresAt());
        verify(composition).publish(eq("plan"), eq(7L), eq(2L), eq(workspace),
                eq(ref), any());
        verify(deliveries).deliver(key);
    }

    @Test
    void loopFailureDiagnosticContainsOnlyStableStageAndFailureType() {
        var failure = mock(com.yanban.api.agent.v2.loop
                .PersistentPlanAgentLoopException.class);
        when(failure.stage()).thenReturn("kernel");
        when(failure.getMessage()).thenReturn("owner-api-key");

        var diagnostic =
                V2ProjectCandidateService.failureDiagnostic(failure);

        assertEquals("loop.kernel", diagnostic.stage());
        assertEquals(
                com.yanban.api.agent.v2.loop
                        .PersistentPlanAgentLoopException.class.getName(),
                diagnostic.failureType());
        assertFalse(diagnostic.toString().contains("owner-api-key"));
    }
}
