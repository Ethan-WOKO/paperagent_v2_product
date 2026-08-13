package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.ProjectSessionService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectControllerV2AvailabilityTest {

    @Test
    void disabledV2DoesNotGateCandidateApply() {
        ProjectRevisionWorkflowService revisions =
                mock(ProjectRevisionWorkflowService.class);
        ProjectController controller = controller(null, revisions);
        ApplyCandidateRequest apply = mock(ApplyCandidateRequest.class);
        ProjectRevisionOperationResponse expectedApply =
                mock(ProjectRevisionOperationResponse.class);
        when(revisions.applyCandidate(
                7L, 8L, 10L, "key", "version", apply))
                .thenReturn(expectedApply);

        assertThat(controller.applyCandidate(
                7L, 8L, 10L, "key", "version", apply))
                .isSameAs(expectedApply);
        verify(revisions).applyCandidate(
                7L, 8L, 10L, "key", "version", apply);
    }

    private static ProjectController controller(
            ProjectSessionService sessions,
            ProjectRevisionWorkflowService revisions) {
        return new ProjectController(
                mock(ProjectService.class), sessions, null, revisions,
                Optional.empty());
    }
}
