package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProjectAgentRuntimeStreamingTest {

    @Test
    void streamsOnlyCanonicalCompletionAndProjectsSafeCurrentEvidence() {
        ProjectService projects = mock(ProjectService.class);
        AgentService agentService = mock(AgentService.class);
        PlanAgentService planService = mock(PlanAgentService.class);
        ProjectAgentRuntimeService service = new ProjectAgentRuntimeService(projects, agentService, planService);
        ProjectManifestResponse manifest = new ProjectManifestResponse(18L, "manifest-hash", List.of(
                new ProjectFileEntry("src/Main.java", 10L, Instant.EPOCH, "h1")
        ));
        when(projects.manifest(7L, 18L)).thenReturn(manifest);
        String canonicalAnswer = "verified canonical answer: " + "分析🙂".repeat(80);

        SendMessageRequest request = new SendMessageRequest("inspect", true, null, "request-1", null);
        when(agentService.sendProjectMessageStreaming(
                eq(7L),
                eq(34L),
                eq(request),
                any(ProjectRuntimeContext.class),
                org.mockito.Mockito.<Consumer<String>>any(),
                org.mockito.Mockito.<Consumer<String>>any()
        )).thenAnswer(invocation -> {
            Consumer<String> attemptTokens = invocation.getArgument(4);
            Consumer<String> process = invocation.getArgument(5);
            process.accept("attempt one inspected manifest");
            attemptTokens.accept("unverified first answer");
            attemptTokens.accept("unverified repair answer");
            return new SendMessageResponse(true, canonicalAnswer, 2, null, null, List.of(), null,
                    List.of(
                            new ProjectEvidenceResponse("safe", "src/Main.java", "h1", "h1", "tool:1", true, false),
                            new ProjectEvidenceResponse("unsafe", "C:\\private\\secret.txt", "h2", "h2", "tool:2", true, false)
                    ));
        });

        List<String> clientTokens = new ArrayList<>();
        List<String> clientProcess = new ArrayList<>();
        SendMessageResponse response = service.sendStreaming(
                7L, 18L, 34L, request, clientTokens::add, clientProcess::add);

        assertThat(clientTokens).hasSizeGreaterThan(1);
        assertThat(String.join("", clientTokens)).isEqualTo(canonicalAnswer);
        assertThat(clientTokens).noneMatch(chunk -> chunk.contains("unverified"));
        assertThat(clientProcess).containsExactly("attempt one inspected manifest");
        assertThat(response.projectEvidence()).singleElement().satisfies(item -> {
            assertThat(item.relativePath()).isEqualTo("src/Main.java");
            assertThat(item.current()).isTrue();
        });
        assertThat(response.projectEvidence()).extracting(ProjectEvidenceResponse::relativePath)
                .noneMatch(path -> path.contains(":") || path.contains("\\"));

        ArgumentCaptor<ProjectRuntimeContext> context = ArgumentCaptor.forClass(ProjectRuntimeContext.class);
        verify(agentService).sendProjectMessageStreaming(
                eq(7L), eq(34L), eq(request), context.capture(),
                org.mockito.Mockito.<Consumer<String>>any(), org.mockito.Mockito.<Consumer<String>>any());
        assertThat(context.getValue()).isEqualTo(new ProjectRuntimeContext(7L, 18L, "manifest-hash"));
    }

    @Test
    void ownershipFailureStopsBeforeRuntimeInvocation() {
        ProjectService projects = mock(ProjectService.class);
        AgentService agentService = mock(AgentService.class);
        ProjectAgentRuntimeService service = new ProjectAgentRuntimeService(
                projects, agentService, mock(PlanAgentService.class));
        when(projects.manifest(7L, 99L)).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        assertThatThrownBy(() -> service.sendStreaming(
                7L,
                99L,
                34L,
                new SendMessageRequest("inspect", true, null, "request-2", null),
                ignored -> { },
                ignored -> { }
        )).isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(agentService);
    }

    @Test
    void controlledBudgetPartialStreamsCanonicalAnswerAndPreservesTypedCompletion() {
        ProjectService projects = mock(ProjectService.class);
        AgentService agentService = mock(AgentService.class);
        ProjectAgentRuntimeService service = new ProjectAgentRuntimeService(
                projects, agentService, mock(PlanAgentService.class));
        when(projects.manifest(7L, 18L)).thenReturn(
                new ProjectManifestResponse(18L, "manifest-hash", List.of()));
        SendMessageRequest request = new SendMessageRequest("inspect broadly", true, null, "request-partial", null);
        when(agentService.sendProjectMessageStreaming(
                eq(7L), eq(34L), eq(request), any(ProjectRuntimeContext.class),
                org.mockito.Mockito.<Consumer<String>>any(), org.mockito.Mockito.<Consumer<String>>any()))
                .thenReturn(new SendMessageResponse(true, "Useful bounded partial answer.", 3, null, null,
                        List.of(), null, List.of(), CompletionStatus.PARTIAL,
                        AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED, "PARTIAL"));

        List<String> chunks = new ArrayList<>();
        SendMessageResponse response = service.sendStreaming(
                7L, 18L, 34L, request, chunks::add, ignored -> { });

        assertThat(String.join("", chunks)).isEqualTo("Useful bounded partial answer.");
        assertThat(response.success()).isTrue();
        assertThat(response.completionStatus()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(response.stopReason()).isEqualTo(AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED);
        assertThat(response.outcome()).isEqualTo("PARTIAL");
    }
}
