package com.yanban.api.project;

import com.yanban.api.agent.v2.compatibility.project.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ProjectControllerV2CandidateTest {
    @Test
    void explicitEndpointsDelegateOnlyAuthenticatedRouteAuthority() {
        var service = mock(V2ProjectCandidateService.class);
        var request = new V2ProjectCandidateRequest(
                "improve explanation", List.of("README.md"), "request-1");
        var succeeded = new V2ProjectCandidateResponse(
                8L, 9L, "request-1", "SUCCEEDED", true, 10L, "plan",
                "version", 42L, "a".repeat(64), "b".repeat(64),
                11L, null, false);
        var failed = new V2ProjectCandidateResponse(
                8L, 9L, "request-2", "FAILED", true, 12L, "plan-2",
                "version", null, null, null, null,
                "PROJECT_CANDIDATE_FAILED", true);
        when(service.execute(7L, 8L, 9L, request)).thenReturn(succeeded);
        when(service.read(7L, 8L, 9L, "request-2")).thenReturn(failed);
        var controller = new ProjectController(mock(ProjectService.class),
                null, null, null, Optional.empty(), Optional.empty(),
                Optional.of(service));

        assertEquals(succeeded, controller.startV2ProjectCandidate(
                7L, 8L, 9L, request));
        assertEquals(failed, controller.readV2ProjectCandidate(
                7L, 8L, 9L, "request-2"));
        verify(service).execute(7L, 8L, 9L, request);
        verify(service).read(7L, 8L, 9L, "request-2");
    }
}
