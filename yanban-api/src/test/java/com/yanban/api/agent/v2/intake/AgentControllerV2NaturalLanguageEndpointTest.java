package com.yanban.api.agent.v2.intake;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.AgentContextSnapshotService;
import com.yanban.api.agent.AgentController;
import com.yanban.api.agent.AgentService;
import com.yanban.api.agent.v2.compatibility.V2ProductAvailability;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureOutcomeService;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnService;
import com.yanban.api.security.JwtUser;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AgentControllerV2NaturalLanguageEndpointTest {
    private static final JwtUser USER =
            new JwtUser(7L, "user", 0L, "USER");

    @Test
    void enabledEndpointDelegatesExactlyOnce() {
        V2NaturalLanguageTurnService turns =
                mock(V2NaturalLanguageTurnService.class);
        var request = new V2NaturalLanguageTurnRequest(
                "question", null, null, null, "request-1");
        var expected = new V2NaturalLanguageTurnResponse(
                9L, 10L, 11L, null, "request-1",
                "PERSISTENT_PLAN_EXECUTE", null, "plan-1", false);
        when(turns.execute(7L, 9L, request)).thenReturn(expected);

        var controller = controller(
                new V2ProductAvailability(true), turns);

        assertSame(expected,
                controller.sendV2NaturalLanguageTurn(USER, 9L, request));
        verify(turns).execute(7L, 9L, request);
    }

    @Test
    void disabledEndpointFailsBeforeDelegation() {
        V2NaturalLanguageTurnService turns =
                mock(V2NaturalLanguageTurnService.class);
        var request = new V2NaturalLanguageTurnRequest(
                "question", null, null, null, "request-1");
        var controller = controller(
                new V2ProductAvailability(false), turns);

        assertThrows(ResponseStatusException.class,
                () -> controller.sendV2NaturalLanguageTurn(
                        USER, 9L, request));
        verify(turns, never()).execute(7L, 9L, request);
    }

    private AgentController controller(
            V2ProductAvailability availability,
            V2NaturalLanguageTurnService turns) {
        return new AgentController(
                mock(AgentService.class),
                mock(AgentContextSnapshotService.class),
                mock(V2LiteratureTurnService.class),
                mock(V2LiteratureOutcomeService.class),
                availability,
                turns);
    }
}
