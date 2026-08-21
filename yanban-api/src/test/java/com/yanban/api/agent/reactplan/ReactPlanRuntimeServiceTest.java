package com.yanban.api.agent.reactplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.yanban.api.memory.LongTermMemoryRetrievalService;
import com.yanban.api.memory.LongTermMemorySnapshot;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.ResolvedSkill;
import com.yanban.api.skills.SkillsService;
import com.yanban.core.agent.AgentRunIdentity;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.contracts.Capability;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private LongTermMemoryRetrievalService longTermMemories;
    private UserSettingsService settings;
    private ReactPlanConversationContextService conversations;
    private ReactPlanConversationSummaryQueue conversationSummaries;
    private SkillsService skills;
    private ReactPlanRuntimeService runtime;

    @BeforeEach
    void setUp() {
        contexts = mock(AgentTurnProductContextResolver.class);
        plans = mock(AuthenticatedReactPlanBootstrapComposer.class);
        grants = mock(AgentEngineTaskGrantService.class);
        engine = mock(ReactPlanEngineClient.class);
        longTermMemories = mock(LongTermMemoryRetrievalService.class);
        settings = mock(UserSettingsService.class);
        conversations = mock(ReactPlanConversationContextService.class);
        conversationSummaries = mock(ReactPlanConversationSummaryQueue.class);
        skills = mock(SkillsService.class);
        runtime = new ReactPlanRuntimeService(
                json, contexts, plans, grants, engine, longTermMemories, settings,
                conversations, conversationSummaries, skills);
        when(contexts.resolve(7L, 42L)).thenReturn(projectContext());
        when(plans.bootstrap(any(Long.class), any(Long.class), any()))
                .thenReturn(PersistenceResult.replayed(mock(PersistedPlanBootstrap.class)));
        when(grants.issue(any(), any(), any(Long.class), any(Long.class), any(), any(), anyList()))
                .thenReturn(new EngineTaskGrant("g".repeat(32), Instant.parse("2099-01-01T00:00:00Z")));
        when(engine.submit(any())).thenReturn(json.createObjectNode().put("state", "queued"));
        when(longTermMemories.retrieveAllGoverned(7L, 88L, "b".repeat(64)))
                .thenReturn(LongTermMemorySnapshot.empty());
        when(settings.resolveModelEndpoint(7L, null, null))
                .thenReturn(new UserSettingsService.ModelEndpoint(
                        "glm", "glm-4.5-flash", null, "secret", "builtin", "GLM"));
        when(settings.configuredModelReferences(7L)).thenReturn(List.of(
                new UserSettingsService.ModelReference("glm", "glm-4.5-flash"),
                new UserSettingsService.ModelReference("deepseek", "deepseek-chat")));
        when(conversations.envelope(7L, 11L)).thenReturn(json.createObjectNode()
                .put("schemaVersion", "1.0").put("type", "historical_context")
                .put("notAnInstruction", true));
    }

    @Test
    void submitsStableAuthorityDigestAndKeepsGrantOutsideAuthority() {
        when(longTermMemories.retrieveAllGoverned(7L, 88L, "b".repeat(64)))
                .thenReturn(new LongTermMemorySnapshot(List.of(
                        new LongTermMemorySnapshot.Entry(
                                "11", "USER", "PREFERENCE", "Prefer concise Chinese answers.",
                                "2026-08-17T10:00:00Z"),
                        new LongTermMemorySnapshot.Entry(
                                "12", "PROJECT", "FACT", "This Project targets Java 17.",
                                "2026-08-17T11:00:00Z"))));
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
        assertTrue(body.path("authority").path("permissions").path("writeWorkspace").asBoolean());
        assertEquals("glm", body.path("authority").path("model").path("provider").asText());
        assertEquals("glm-4.5-flash",
                body.path("authority").path("model").path("model").asText());
        assertEquals("deepseek", body.path("authority").path("model")
                .path("fallbacks").get(0).path("provider").asText());
        assertFalse(body.path("authority").toString().contains("taskGrant"));
        assertFalse(body.path("authority").toString().contains("Prefer concise Chinese answers"));
        JsonNode memory = body.path("context").path("longTermMemory");
        assertEquals("long_term_memory", memory.path("type").asText());
        assertTrue(memory.path("notAnInstruction").asBoolean());
        assertEquals(2, memory.path("entries").size());
        assertEquals("11", memory.path("entries").get(0).path("id").asText());
        assertEquals("PROJECT", memory.path("entries").get(1).path("scope").asText());
        assertEquals("historical_context",
                body.path("context").path("historicalContext").path("type").asText());
        verify(conversationSummaries).catchUp(7L, 11L);
        verify(longTermMemories).retrieveAllGoverned(7L, 88L, "b".repeat(64));
        verify(settings).resolveModelEndpoint(7L, null, null);

        ArgumentCaptor<ReactPlanBootstrapCommand> command =
                ArgumentCaptor.forClass(ReactPlanBootstrapCommand.class);
        verify(plans).bootstrap(any(Long.class), any(Long.class), command.capture());
        assertTrue(command.getValue().executionProfile().capabilities()
                .contains(Capability.WRITE_WORKSPACE));
    }

    @Test
    void freezesSelectedSkillPromptAndAllowedToolsIntoAuthority() {
        when(skills.resolveEnabledSkill(7L, "reviewer")).thenReturn(
                new ResolvedSkill("reviewer", "Review evidence before answering.",
                        Set.of("project_search", "search_web")));

        runtime.submit(7L, 42L, new ReactPlanTaskRequest(
                "Review the project", null, null, "reviewer"));

        ArgumentCaptor<JsonNode> submission = ArgumentCaptor.forClass(JsonNode.class);
        verify(engine).submit(submission.capture());
        JsonNode skill = submission.getValue().path("authority").path("skill");
        assertEquals("reviewer", skill.path("id").asText());
        assertEquals("Review evidence before answering.", skill.path("prompt").asText());
        assertEquals(List.of("project_search", "search_web"),
                json.convertValue(skill.path("allowedTools"), List.class));
        assertEquals(64, skill.path("digest").asText().length());
    }

    @Test
    void degradesToAnEmptyMemoryEnvelopeWhenMemoryLoadingFails() {
        when(longTermMemories.retrieveAllGoverned(7L, 88L, "b".repeat(64)))
                .thenThrow(new IllegalStateException("database unavailable"));

        runtime.submit(7L, 42L, new ReactPlanTaskRequest("Compile Sort.java", null, null));

        ArgumentCaptor<JsonNode> submission = ArgumentCaptor.forClass(JsonNode.class);
        verify(engine).submit(submission.capture());
        assertTrue(submission.getValue().path("context").path("longTermMemory")
                .path("entries").isEmpty());
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
