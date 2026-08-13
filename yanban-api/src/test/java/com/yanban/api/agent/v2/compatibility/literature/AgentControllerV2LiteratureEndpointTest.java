package com.yanban.api.agent.v2.compatibility.literature;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.AgentController;
import com.yanban.api.agent.AgentSessionService;
import com.yanban.api.agent.v2.compatibility.V2ProductAvailability;
import com.yanban.api.security.JwtUser;
import org.junit.jupiter.api.Test;

class AgentControllerV2LiteratureEndpointTest {
    @Test
    void explicitEndpointDelegatesOnlyToV2Capability() {
        V2LiteratureTurnService v2 = mock(V2LiteratureTurnService.class);
        var controller = new AgentController(
                mock(AgentSessionService.class), v2,
                mock(V2LiteratureOutcomeService.class),
                V2ProductAvailability.enabledByDefault());
        var request = new V2LiteratureTurnRequest(
                "agents", 10, null, true, "request-77");
        var expected = new V2LiteratureTurnResponse(
                9L, 41L, 42L, 43L, "request-77",
                "plan-77", "synthesis-77", "queued", false);
        when(v2.execute(7L, 9L, request)).thenReturn(expected);

        assertSame(expected, controller.sendV2LiteratureTurn(
                new JwtUser(7L, "owner"), 9L, request));
        verify(v2).execute(7L, 9L, request);
    }
}
