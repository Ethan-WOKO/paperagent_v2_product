package com.yanban.api.agent.reactplan;

import com.yanban.agent.v2.adapter.bootstrap.ProductPersistentPlanBootstrapRequestAdapter;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionCode;
import com.yanban.api.agent.v2.AgentTurnProductContextResolutionException;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapRequest;
import io.paperagent.v2.runtime.bootstrap.PersistentPlanBootstrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticatedReactPlanBootstrapComposerTest {
    private AgentTurnProductContextResolver contexts;
    private ProductPersistentPlanBootstrapRequestAdapter requests;
    private PersistentPlanBootstrapper bootstrapper;
    private AuthenticatedReactPlanBootstrapComposer composer;

    @BeforeEach
    void setUp() {
        contexts = mock(AgentTurnProductContextResolver.class);
        requests = new ProductPersistentPlanBootstrapRequestAdapter();
        bootstrapper = mock(PersistentPlanBootstrapper.class);
        composer = new AuthenticatedReactPlanBootstrapComposer(
                contexts, requests, bootstrapper, new DeterministicReactPlanDraftFactory());
    }

    @Test
    void resolvesProductAuthorityThenPersistsTheDeterministicOneStepPlan() {
        VerifiedAgentTurnProductContext context = new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, null),
                Optional.empty());
        PersistenceResult<PersistedPlanBootstrap> expected = PersistenceResult.rejected(
                PersistenceErrorCode.INVALID_ARGUMENT, "synthetic");
        when(contexts.resolve(7L, 42L)).thenReturn(context);
        when(bootstrapper.bootstrap(any())).thenReturn(expected);

        PersistenceResult<PersistedPlanBootstrap> actual = composer.bootstrap(
                7L, 42L, ReactPlanTestFixtures.command());

        assertSame(expected, actual);
        ArgumentCaptor<PersistentPlanBootstrapRequest> captor =
                ArgumentCaptor.forClass(PersistentPlanBootstrapRequest.class);
        verify(bootstrapper).bootstrap(captor.capture());
        assertEquals(1, captor.getValue().initialPlanDraft().steps().size());
        assertEquals("Compile Sort.java and explain the result",
                captor.getValue().initialPlanDraft().steps().get(0).intent());
    }

    @Test
    void ownershipFailureStopsBeforeAnyPersistence() {
        AgentTurnProductContextResolutionException failure =
                new AgentTurnProductContextResolutionException(
                        AgentTurnProductContextResolutionCode.TURN_NOT_FOUND, "turnId");
        when(contexts.resolve(7L, 404L)).thenThrow(failure);

        assertSame(failure, assertThrows(
                AgentTurnProductContextResolutionException.class,
                () -> composer.bootstrap(7L, 404L, ReactPlanTestFixtures.command())));
        verify(contexts).resolve(7L, 404L);
        verify(bootstrapper, never()).bootstrap(any());
    }
}
