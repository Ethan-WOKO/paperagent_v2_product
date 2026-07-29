package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.SendMessageRequest;
import com.yanban.api.agent.SendMessageResponse;
import com.yanban.api.agent.ProjectAgentRuntimeService;
import com.yanban.api.agent.v2.compatibility.V2ProductAvailability;
import com.yanban.api.agent.v2.compatibility.project.V2ProjectAnalysisRequest;
import com.yanban.api.agent.v2.compatibility.project.V2ProjectAnalysisService;
import com.yanban.api.agent.v2.compatibility.project.V2ProjectCandidateRequest;
import com.yanban.api.agent.v2.compatibility.project.V2ProjectCandidateService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProjectControllerV2AvailabilityTest {

    @Test
    void disabledAnalysisAndCandidateStartAndReadFailBeforeDelegation() {
        V2ProjectAnalysisService analysis =
                mock(V2ProjectAnalysisService.class);
        V2ProjectCandidateService candidate =
                mock(V2ProjectCandidateService.class);
        ProjectAgentRuntimeService legacy =
                mock(ProjectAgentRuntimeService.class);
        ProjectController controller = controller(
                legacy, analysis, candidate, null, false);
        V2ProjectAnalysisRequest analysisRequest =
                new V2ProjectAnalysisRequest(
                        "summarize", List.of("paper.md"),
                        null, 10, "analysis-1");
        V2ProjectCandidateRequest candidateRequest =
                new V2ProjectCandidateRequest(
                        "improve", List.of("paper.md"),
                        "candidate-1");

        assertUnavailable(() -> controller.startV2ProjectAnalysis(
                7L, 8L, 9L, analysisRequest));
        assertUnavailable(() -> controller.readV2ProjectAnalysis(
                7L, 8L, 9L, "analysis-1"));
        assertUnavailable(() -> controller.startV2ProjectCandidate(
                7L, 8L, 9L, candidateRequest));
        assertUnavailable(() -> controller.readV2ProjectCandidate(
                7L, 8L, 9L, "candidate-1"));
        verifyNoInteractions(legacy, analysis, candidate);
    }

    @Test
    void disabledGateRunsBeforeOptionalServiceConfigurationChecks() {
        ProjectController controller = controller(
                null, null, null, null, false);

        assertUnavailable(() -> controller.readV2ProjectAnalysis(
                7L, 8L, 9L, "analysis-1"));
        assertUnavailable(() -> controller.readV2ProjectCandidate(
                7L, 8L, 9L, "candidate-1"));
    }

    @Test
    void enabledAnalysisAndCandidateEndpointsDelegateExactlyOnce() {
        V2ProjectAnalysisService analysis =
                mock(V2ProjectAnalysisService.class);
        V2ProjectCandidateService candidate =
                mock(V2ProjectCandidateService.class);
        ProjectController controller = controller(
                null, analysis, candidate, null, true);
        V2ProjectAnalysisRequest analysisRequest =
                new V2ProjectAnalysisRequest(
                        "summarize", List.of("paper.md"),
                        null, 10, "analysis-1");
        V2ProjectCandidateRequest candidateRequest =
                new V2ProjectCandidateRequest(
                        "improve", List.of("paper.md"),
                        "candidate-1");

        controller.startV2ProjectAnalysis(
                7L, 8L, 9L, analysisRequest);
        controller.readV2ProjectAnalysis(
                7L, 8L, 9L, "analysis-1");
        controller.startV2ProjectCandidate(
                7L, 8L, 9L, candidateRequest);
        controller.readV2ProjectCandidate(
                7L, 8L, 9L, "candidate-1");

        verify(analysis).execute(7L, 8L, 9L, analysisRequest);
        verify(analysis).read(7L, 8L, 9L, "analysis-1");
        verify(candidate).execute(7L, 8L, 9L, candidateRequest);
        verify(candidate).read(7L, 8L, 9L, "candidate-1");
    }

    @Test
    void disabledV2DoesNotGateProjectMessageOrCandidateApply() {
        V2ProjectAnalysisService analysis =
                mock(V2ProjectAnalysisService.class);
        V2ProjectCandidateService candidate =
                mock(V2ProjectCandidateService.class);
        ProjectAgentRuntimeService legacy =
                mock(ProjectAgentRuntimeService.class);
        ProjectRevisionWorkflowService revisions =
                mock(ProjectRevisionWorkflowService.class);
        ProjectController controller = controller(
                legacy, analysis, candidate, revisions, false);
        SendMessageRequest message = new SendMessageRequest(
                "legacy project", false, null,
                "legacy-request", null);
        SendMessageResponse expectedMessage =
                mock(SendMessageResponse.class);
        when(legacy.send(7L, 8L, 9L, message))
                .thenReturn(expectedMessage);
        ApplyCandidateRequest apply = mock(ApplyCandidateRequest.class);
        ProjectRevisionOperationResponse expectedApply =
                mock(ProjectRevisionOperationResponse.class);
        when(revisions.applyCandidate(
                7L, 8L, 10L, "key", "version", apply))
                .thenReturn(expectedApply);

        assertThat(controller.sendProjectMessage(
                7L, 8L, 9L, message)).isSameAs(expectedMessage);
        assertThat(controller.applyCandidate(
                7L, 8L, 10L, "key", "version", apply))
                .isSameAs(expectedApply);
        verify(legacy).send(7L, 8L, 9L, message);
        verify(revisions).applyCandidate(
                7L, 8L, 10L, "key", "version", apply);
        verifyNoInteractions(analysis, candidate);
    }

    private static ProjectController controller(
            ProjectAgentRuntimeService legacy,
            V2ProjectAnalysisService analysis,
            V2ProjectCandidateService candidate,
            ProjectRevisionWorkflowService revisions,
            boolean enabled) {
        return new ProjectController(
                mock(ProjectService.class), legacy, null, revisions,
                Optional.empty(), Optional.ofNullable(analysis),
                Optional.ofNullable(candidate),
                new V2ProductAvailability(enabled));
    }

    private static void assertUnavailable(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode())
                                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                            assertThat(error.getReason()).isEqualTo(
                                    "V2 Agent capabilities are unavailable");
                        });
    }
}
