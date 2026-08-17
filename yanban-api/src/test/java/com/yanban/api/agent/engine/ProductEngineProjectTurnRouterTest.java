package com.yanban.api.agent.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yanban.api.agent.v2.chain.api.ProjectChainTurnCoordinator;
import com.yanban.api.agent.v2.chain.api.V2ProjectTurnResponse;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnRequest;
import com.yanban.api.agent.v2.intake.V2NaturalLanguageTurnResponse;
import org.junit.jupiter.api.Test;

class ProductEngineProjectTurnRouterTest {
    private final ProductEngineProperties properties = new ProductEngineProperties();
    private final ProjectChainTurnCoordinator legacy = mock(ProjectChainTurnCoordinator.class);
    private final ExternalProductEngineTurnService external = mock(ExternalProductEngineTurnService.class);
    private final ProductEngineProjectTurnRouter router =
            new ProductEngineProjectTurnRouter(properties, legacy, external);

    @Test
    void legacyIsTheDefaultStartPath() {
        V2NaturalLanguageTurnRequest request = new V2NaturalLanguageTurnRequest(
                "check", false, null, "client-1", "INITIAL", null);
        V2NaturalLanguageTurnResponse expected = new V2NaturalLanguageTurnResponse(
                2L, 3L, 4L, null, "client-1", "PERSISTENT_PLAN_EXECUTE", null, null, false, "client-1");
        when(legacy.start(1, 2, request)).thenReturn(expected);

        assertThat(router.start(1, 2, request)).isSameAs(expected);
        verify(legacy).start(1, 2, request);
    }

    @Test
    void existingExternalTurnKeepsItsEngineAffinityAfterRollback() {
        V2ProjectTurnResponse expected = new V2ProjectTurnResponse("root", "EXECUTING", null, null,
                "PERSISTENT_PLAN_EXECUTE", "task", "a".repeat(64), null, null, null,
                null, null, null, null, null, null, null, null, null);
        when(external.owns(1, 2, "root")).thenReturn(true);
        when(external.persistedGet(1, 2, "root")).thenReturn(expected);

        assertThat(router.get(1, 2, "root")).isSameAs(expected);
        verify(external).persistedGet(1, 2, "root");
    }

    @Test
    void exactExternalStartReplayDoesNotFallIntoLegacyAfterRollback() {
        V2NaturalLanguageTurnRequest request = new V2NaturalLanguageTurnRequest(
                "check", false, null, "client-1", "INITIAL", null);
        V2NaturalLanguageTurnResponse expected = new V2NaturalLanguageTurnResponse(
                2L, 3L, 4L, 5L, "client-1", "PERSISTENT_PLAN_EXECUTE",
                "done", "task", true, "client-1");
        when(external.owns(1, 2, "client-1")).thenReturn(true);
        when(external.persistedStart(1, 2, request)).thenReturn(expected);

        assertThat(router.start(1, 2, request)).isSameAs(expected);
        verify(external).persistedStart(1, 2, request);
    }
}
