package com.yanban.api.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.compatibility.project.V2ProjectAnalysisRequest;
import com.yanban.api.agent.v2.compatibility.project.V2ProjectAnalysisResponse;
import com.yanban.api.agent.v2.compatibility.project.V2ProjectAnalysisService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectControllerV2AnalysisTest {
    @Test
    void explicitEndpointsDelegateAuthenticatedRouteAuthorityOnly() {
        V2ProjectAnalysisService service =
                mock(V2ProjectAnalysisService.class);
        V2ProjectAnalysisRequest request =
                new V2ProjectAnalysisRequest(
                        "objective", List.of("paper.md"),
                        null, 10, "request-1");
        V2ProjectAnalysisResponse response =
                new V2ProjectAnalysisResponse(
                        8L, 9L, "request-1", "SUCCEEDED",
                        true, 10L, "plan", "version",
                        "analysis", 11L, null, false);
        when(service.execute(7L, 8L, 9L, request))
                .thenReturn(response);
        when(service.read(7L, 8L, 9L, "request-1"))
                .thenReturn(response);
        ProjectController controller = new ProjectController(
                mock(ProjectService.class), null, null, null,
                Optional.empty(), Optional.of(service));

        assertEquals(response, controller.startV2ProjectAnalysis(
                7L, 8L, 9L, request));
        assertEquals(response, controller.readV2ProjectAnalysis(
                7L, 8L, 9L, "request-1"));
        verify(service).execute(7L, 8L, 9L, request);
        verify(service).read(7L, 8L, 9L, "request-1");
    }
}
