package com.yanban.api.agent.v2.compatibility.project;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.agent.AgentSessionRepository;
import com.yanban.core.agent.AgentSessionScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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

    private static V2ProjectAnalysisRequest request(List<String> paths) {
        return new V2ProjectAnalysisRequest(
                "Analyze evidence", paths, null, 10, "request");
    }

    private static V2ProjectAnalysisService service(
            AgentSessionRepository sessions, ProjectService projects,
            ProjectAnalysisDeliveryTransactions deliveries) {
        return new V2ProjectAnalysisService(
                sessions, projects, deliveries,
                mock(com.yanban.api.agent.v2.bootstrap
                        .AuthenticatedAgentTurnFreshExecutionStartComposer.class),
                mock(com.yanban.api.agent.v2.workspace
                        .AuthenticatedAgentTurnPlanExecutionContextComposer.class),
                mock(com.yanban.api.agent.v2.loop
                        .AuthenticatedPersistentPlanAgentLoopComposer.class),
                mock(io.paperagent.v2.persistence.StepRecoveryRepository.class),
                mock(io.paperagent.v2.persistence.LeaseRepository.class),
                mock(com.yanban.api.agent.v2.workspace
                        .AuthenticatedAgentTurnWorkspacePortFactory.class),
                mock(com.yanban.api.agent.v2
                        .AgentTurnProductContextResolver.class),
                mock(io.paperagent.v2.persistence.FinalSynthesisRepository.class),
                mock(io.paperagent.v2.persistence.ReceiptRepository.class),
                mock(io.paperagent.v2.persistence.EffectIntentRepository.class),
                mock(io.paperagent.v2.providers.ModelProvider.class),
                new ObjectMapper());
    }
}
