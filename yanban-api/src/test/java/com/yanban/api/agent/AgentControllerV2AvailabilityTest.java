package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.compatibility.V2ProductAvailability;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureOutcomeService;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnRequest;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnService;
import com.yanban.api.security.JwtUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AgentControllerV2AvailabilityTest {

    private static final JwtUser USER = new JwtUser(7L, "owner");

    @Test
    void capabilityDocumentIsAuthenticatedBoundedAndDoesNotReadServices() {
        AgentService legacy = mock(AgentService.class);
        V2LiteratureTurnService turns = mock(V2LiteratureTurnService.class);
        V2LiteratureOutcomeService outcomes =
                mock(V2LiteratureOutcomeService.class);
        AgentController controller = controller(
                legacy, turns, outcomes, false);

        var document = controller.v2Capabilities(USER);

        assertThat(document.formatVersion()).isEqualTo(1);
        assertThat(document.enabled()).isFalse();
        assertThat(document.capabilities()).containsExactly(
                "literature.search",
                "project.read-analysis",
                "project.candidate");
        verifyNoInteractions(legacy, turns, outcomes);
    }

    @Test
    void disabledLiteratureStartReadAndCancelFailBeforeDelegation() {
        AgentService legacy = mock(AgentService.class);
        V2LiteratureTurnService turns = mock(V2LiteratureTurnService.class);
        V2LiteratureOutcomeService outcomes =
                mock(V2LiteratureOutcomeService.class);
        AgentController controller = controller(
                legacy, turns, outcomes, false);
        V2LiteratureTurnRequest request = new V2LiteratureTurnRequest(
                "agents", 10, null, true, "request-1");

        assertUnavailable(() -> controller.sendV2LiteratureTurn(
                USER, 9L, request));
        assertUnavailable(() -> controller.getV2LiteratureTurn(
                USER, 9L, "request-1"));
        assertUnavailable(() -> controller.cancelV2LiteratureTurn(
                USER, 9L, "request-1"));
        verifyNoInteractions(legacy, turns, outcomes);
    }

    @Test
    void enabledLiteratureEndpointsDelegateExactlyOnce() {
        AgentService legacy = mock(AgentService.class);
        V2LiteratureTurnService turns = mock(V2LiteratureTurnService.class);
        V2LiteratureOutcomeService outcomes =
                mock(V2LiteratureOutcomeService.class);
        AgentController controller = controller(
                legacy, turns, outcomes, true);
        V2LiteratureTurnRequest request = new V2LiteratureTurnRequest(
                "agents", 10, null, true, "request-1");

        controller.sendV2LiteratureTurn(USER, 9L, request);
        controller.getV2LiteratureTurn(USER, 9L, "request-1");
        controller.cancelV2LiteratureTurn(USER, 9L, "request-1");

        verify(turns).execute(7L, 9L, request);
        verify(outcomes).get(7L, 9L, "request-1");
        verify(outcomes).cancel(7L, 9L, "request-1");
        verifyNoInteractions(legacy);
    }

    @Test
    void disabledV2DoesNotGateOrdinaryLegacyMessages() {
        AgentService legacy = mock(AgentService.class);
        V2LiteratureTurnService turns = mock(V2LiteratureTurnService.class);
        V2LiteratureOutcomeService outcomes =
                mock(V2LiteratureOutcomeService.class);
        AgentController controller = controller(
                legacy, turns, outcomes, false);
        SendMessageRequest request = new SendMessageRequest(
                "ordinary", false, null, "legacy-request", null);
        SendMessageResponse expected = mock(SendMessageResponse.class);
        when(legacy.sendMessage(7L, 9L, request)).thenReturn(expected);

        assertThat(controller.sendMessage(USER, 9L, request))
                .isSameAs(expected);
        verify(legacy).sendMessage(7L, 9L, request);
        verifyNoInteractions(turns, outcomes);
    }

    private static AgentController controller(
            AgentService legacy,
            V2LiteratureTurnService turns,
            V2LiteratureOutcomeService outcomes,
            boolean enabled) {
        return new AgentController(
                legacy, mock(AgentContextSnapshotService.class),
                turns, outcomes, new V2ProductAvailability(enabled));
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
