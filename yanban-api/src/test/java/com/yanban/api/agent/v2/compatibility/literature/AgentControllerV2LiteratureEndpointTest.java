package com.yanban.api.agent.v2.compatibility.literature;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.AgentContextSnapshotService;
import com.yanban.api.agent.AgentController;
import com.yanban.api.agent.AgentService;
import com.yanban.api.agent.SendMessageRequest;
import com.yanban.api.agent.SendMessageResponse;
import com.yanban.api.security.JwtUser;
import org.junit.jupiter.api.Test;

class AgentControllerV2LiteratureEndpointTest {
    @Test
    void explicitEndpointDelegatesOnlyToV2Capability() {
        AgentService legacy = mock(AgentService.class);
        V2LiteratureTurnService v2 = mock(V2LiteratureTurnService.class);
        var controller = new AgentController(
                legacy, mock(AgentContextSnapshotService.class), v2);
        var request = new V2LiteratureTurnRequest(
                "agents", 10, null, true, "request-77");
        var expected = new V2LiteratureTurnResponse(
                9L, 41L, 42L, 43L, "request-77",
                "plan-77", "synthesis-77", "queued", false);
        when(v2.execute(7L, 9L, request)).thenReturn(expected);

        assertSame(expected, controller.sendV2LiteratureTurn(
                new JwtUser(7L, "owner"), 9L, request));
        verify(v2).execute(7L, 9L, request);
        verifyNoInteractions(legacy);
    }

    @Test
    void legacyMessagesEndpointStillDelegatesOnlyToLegacyService() {
        AgentService legacy = mock(AgentService.class);
        V2LiteratureTurnService v2 = mock(V2LiteratureTurnService.class);
        var controller = new AgentController(
                legacy, mock(AgentContextSnapshotService.class), v2);
        var request = new SendMessageRequest(
                "hello", false, null, "legacy-request", null);
        SendMessageResponse expected = mock(SendMessageResponse.class);
        when(legacy.sendMessage(7L, 9L, request)).thenReturn(expected);

        assertSame(expected, controller.sendMessage(
                new JwtUser(7L, "owner"), 9L, request));
        verify(legacy).sendMessage(7L, 9L, request);
        verifyNoInteractions(v2);
    }
}
