package com.yanban.api.agent.reactplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.reactplan.gateway.AgentEngineTaskGrantService;
import com.yanban.api.agent.reactplan.gateway.EngineTaskGrant;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ReactPlanRuntimeServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private AgentTurnProductContextResolver contexts;
    private AuthenticatedReactPlanBootstrapComposer plans;
    private AgentEngineTaskGrantService grants;
    private ReactPlanEngineClient engine;
    private ReactPlanRuntimeService runtime;

    @BeforeEach
    void setUp() {
        contexts = mock(AgentTurnProductContextResolver.class);
        plans = mock(AuthenticatedReactPlanBootstrapComposer.class);
        grants = mock(AgentEngineTaskGrantService.class);
        engine = mock(ReactPlanEngineClient.class);
        ReactPlanRuntimeProperties properties = new ReactPlanRuntimeProperties();
        runtime = new ReactPlanRuntimeService(json, properties, contexts, plans, grants, engine);
        when(contexts.resolve(7L, 42L)).thenReturn(projectContext());
        when(plans.bootstrap(any(Long.class), any(Long.class), any()))
                .thenReturn(PersistenceResult.replayed(mock(PersistedPlanBootstrap.class)));
        when(grants.issue(any(), any(), any(Long.class), any(Long.class)))
                .thenReturn(new EngineTaskGrant("g".repeat(32), Instant.parse("2099-01-01T00:00:00Z")));
        when(engine.submit(any())).thenReturn(json.createObjectNode().put("state", "queued"));
    }

    @Test
    void submitsStableAuthorityDigestAndKeepsGrantOutsideAuthority() {
        runtime.submit(7L, 42L, new ReactPlanTaskRequest("Compile Sort.java", null, null));

        ArgumentCaptor<JsonNode> submission = ArgumentCaptor.forClass(JsonNode.class);
        verify(engine).submit(submission.capture());
        JsonNode body = submission.getValue();
        assertEquals(ReactPlanRuntimeService.taskId(7L, 42L), body.path("taskId").asText());
        assertEquals(ReactPlanCanonicalJson.digest(json, body.path("authority")),
                body.path("requestDigest").asText());
        assertEquals("88", body.path("authority").path("project").path("projectId").asText());
        assertEquals("b".repeat(64),
                body.path("authority").path("project").path("projectVersion").asText());
        assertEquals("g".repeat(32), body.path("gateway").path("taskGrant").asText());
        assertFalse(body.path("authority").toString().contains("taskGrant"));
    }

    @Test
    void rejectsAProjectlessTurnBeforePlanOrEngine() {
        when(contexts.resolve(7L, 42L)).thenReturn(new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, null), Optional.empty()));

        assertThrows(ResponseStatusException.class,
                () -> runtime.submit(7L, 42L, new ReactPlanTaskRequest("Compile", null, null)));
        verify(plans, never()).bootstrap(any(Long.class), any(Long.class), any());
        verify(engine, never()).submit(any());
    }

    @Test
    void createsAnExactAnswerDigestWithoutAcceptingCallerSuppliedAuthority() {
        String taskId = ReactPlanRuntimeService.taskId(7L, 42L);
        when(engine.answer(any(), any())).thenReturn(json.createObjectNode().put("state", "running"));

        runtime.answer(7L, 42L, taskId, new ReactPlanAnswerRequest("question.abc", "  yes  "));

        ArgumentCaptor<JsonNode> answer = ArgumentCaptor.forClass(JsonNode.class);
        verify(engine).answer(org.mockito.ArgumentMatchers.eq(taskId), answer.capture());
        assertEquals("yes", answer.getValue().path("answer").asText());
        assertEquals(ReactPlanCanonicalJson.sha256Utf8("yes"),
                answer.getValue().path("answerDigest").asText());
    }

    private static VerifiedAgentTurnProductContext projectContext() {
        return new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "42", 7L, 11L, 88L),
                Optional.of("b".repeat(64)));
    }
}
