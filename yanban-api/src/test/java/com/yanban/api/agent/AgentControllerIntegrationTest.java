package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnResponse;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureTurnService;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureOutcomeResponse;
import com.yanban.api.agent.v2.compatibility.literature.V2LiteratureOutcomeService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentSessionSummaryService;
import com.yanban.core.agent.AgentSessionSummaryUpdate;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import com.yanban.knowledge.service.KnowledgeSearchResult;
import com.yanban.knowledge.service.KnowledgeSearchService;
import com.yanban.paper.literature.AdHocLiteratureSearchService;
import com.yanban.paper.literature.AdHocLiteratureSearchService.AdHocLiteratureItem;
import com.yanban.paper.literature.AdHocLiteratureSearchService.AdHocLiteratureSearchResult;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_agent_controller_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class AgentControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    AgentMessageRepository agentMessages;

    @Autowired
    JdbcTemplate jdbc;

    @SpyBean
    AgentSessionSummaryService sessionSummaryService;

    @SpyBean
    AgentContextSnapshotService contextSnapshotService;

    @MockBean(name = "chatModelProvider")
    ChatModelProvider chatModelProvider;

    @MockBean
    AdHocLiteratureSearchService adHocLiteratureSearchService;

    @MockBean
    KnowledgeSearchService knowledgeSearchService;

    @MockBean
    V2LiteratureTurnService v2LiteratureTurnService;

    @MockBean
    V2LiteratureOutcomeService v2LiteratureOutcomeService;

    @Test
    void explicitV2LiteratureEndpointIsAuthenticatedAndDelegates() throws Exception {
        String token = registerAndGetToken("agent_v2_literature_user");
        long sessionId = createSession(token, "V2 literature");
        when(v2LiteratureTurnService.execute(
                any(), eq(sessionId), any())).thenReturn(
                new V2LiteratureTurnResponse(
                        sessionId, 41L, 42L, 43L, "request-77",
                        "plan-77", "synthesis-77", "queued", false));

        mockMvc.perform(post(
                        "/api/v1/agent/sessions/{id}/v2/literature-turns",
                        sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"agent systems","topK":10,
                                 "clientRequestId":"request-77"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value("plan-77"))
                .andExpect(jsonPath("$.assistantMessageId").value(43));
        verify(v2LiteratureTurnService).execute(
                any(), eq(sessionId), any());
    }

    @Test
    void v2LiteratureOutcomeReadAndCancelAreAuthenticatedAndDelegated()
            throws Exception {
        String token = registerAndGetToken("agent_v2_outcome_user");
        long sessionId = createSession(token, "V2 outcome");
        V2LiteratureOutcomeResponse response =
                new V2LiteratureOutcomeResponse(
                        sessionId, 41L, "request-79", 99L,
                        "RUNNING", "SEARCHING", false, true,
                        10, false, null, 0, 0,
                        List.of(), List.of());
        when(v2LiteratureOutcomeService.get(
                any(), eq(sessionId), eq("request-79")))
                .thenReturn(response);
        when(v2LiteratureOutcomeService.cancel(
                any(), eq(sessionId), eq("request-79")))
                .thenReturn(response);

        mockMvc.perform(get(
                        "/api/v1/agent/sessions/{id}/v2/"
                                + "literature-turns/{requestId}",
                        sessionId, "request-79")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.literatureTaskId").value(99))
                .andExpect(jsonPath("$.status").value("RUNNING"));
        mockMvc.perform(post(
                        "/api/v1/agent/sessions/{id}/v2/"
                                + "literature-turns/{requestId}/cancel",
                        sessionId, "request-79")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellable").value(true));

        verify(v2LiteratureOutcomeService).get(
                any(), eq(sessionId), eq("request-79"));
        verify(v2LiteratureOutcomeService).cancel(
                any(), eq(sessionId), eq("request-79"));
    }

    @Test
    void createSessionSendMessageAndListPersistedMessages() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any())).thenReturn(
                new ChatResponse(ChatMessage.assistant("你好，我是研伴。"), "stop", null)
        );
        String token = registerAndGetToken("agent_user_a");

        long sessionId = createSession(token, "测试会话");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.assistantContent").value("你好，我是研伴。"))
                .andExpect(jsonPath("$.messages.length()").value(2));

        MvcResult messagesResult = mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("你好"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].content").value("你好，我是研伴。"))
                .andReturn();

        JsonNode messages = objectMapper.readTree(messagesResult.getResponse().getContentAsString());
        assertThat(messages.get(0).get("sessionId").asLong()).isEqualTo(sessionId);
        assertThat(messages.get(1).get("sessionId").asLong()).isEqualTo(sessionId);
    }

    @Test
    void customModelSelectionUsesResolvedEndpointAndExposesModelSourceDebug() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any())).thenReturn(
                new ChatResponse(ChatMessage.assistant("custom answer"), "stop", null)
        );
        String token = registerAndGetToken("agent_user_custom_model");

        MvcResult modelResult = mockMvc.perform(post("/api/v1/models")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "label":"My Compatible Model",
                                  "apiUrl":"https://models.example.test/v1/chat/completions",
                                  "apiKey":"sk-custom-secret",
                                  "modelName":"my-compatible-model"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("apiKey"))))
                .andReturn();
        JsonNode model = objectMapper.readTree(modelResult.getResponse().getContentAsString());
        String providerKey = model.get("providerKey").asText();

        long sessionId = createSession(token, "Custom Model Session");
        mockMvc.perform(patch("/api/v1/agent/sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"modelProvider":"%s","model":"my-compatible-model"}
                                """.formatted(providerKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelProvider").value(providerKey))
                .andExpect(jsonPath("$.model").value("my-compatible-model"));

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello custom\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.debug.modelSource.providerKey").value(providerKey))
                .andExpect(jsonPath("$.debug.modelSource.modelName").value("my-compatible-model"))
                .andExpect(jsonPath("$.debug.modelSource.sourceType").value("custom"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider).chat(captor.capture());
        ChatRequest chatRequest = captor.getValue();
        assertThat(chatRequest.provider()).isEqualTo(providerKey);
        assertThat(chatRequest.model()).isEqualTo("my-compatible-model");
        assertThat(chatRequest.apiUrl()).isEqualTo("https://models.example.test/v1/chat/completions");
        assertThat(chatRequest.apiKey()).isEqualTo("sk-custom-secret");
    }

    @Test
    void runtimeIdentityQuestionUsesConfiguredProviderWithoutCallingModel() throws Exception {
        String token = registerAndGetToken("agent_user_identity");
        long sessionId = createSession(token, "Identity");

        mockMvc.perform(patch("/api/v1/agent/sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelProvider\":\"glm\",\"model\":\"glm-4.5-air\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\\u4f60\\u662f\\u4ec0\\u4e48\\u6a21\\u578b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.steps").value(0))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("GLM")))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("glm-4.5-air")))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("provider="))))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("model="))))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Runtime identity guard"))))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("backend"))))
                .andExpect(jsonPath("$.messages.length()").value(2));

        verify(chatModelProvider, times(0)).chat(any());
    }

    @Test
    void ordinaryMessagesIncludeRuntimeIdentityGuardButDoNotPersistIt() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("guarded answer"), "stop", null));
        String token = registerAndGetToken("agent_user_identity_guard");
        long sessionId = createSession(token, "Guard");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.messages.length()").value(2));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider).chat(captor.capture());
        assertThat(captor.getValue().messages())
                .anySatisfy(message -> {
                    assertThat(message.role()).isEqualTo("system");
                    assertThat(message.content()).contains("Runtime identity guard");
                    assertThat(message.content()).contains("runtime data envelope");
                    assertThat(message.content()).doesNotContain("DeepSeek", "deepseek-chat");
                });
        assertThat(captor.getValue().messages())
                .anySatisfy(message -> {
                    assertThat(message.role()).isEqualTo("user");
                    assertThat(message.content()).contains("Runtime data envelope", "\"providerDisplay\":\"deepseek\"",
                            "\"modelDisplay\":\"deepseek-v4-flash\"");
                });

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages?view=all&limit=10", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[1].role").value("process"))
                .andExpect(jsonPath("$[2].role").value("assistant"));
    }

    @Test
    void ordinaryMessagesIncludeExistingSessionSummaryInModelRequest() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("summary aware answer"), "stop", null));
        String token = registerAndGetToken("agent_user_summary_context");
        long userId = currentUserId(token);
        long sessionId = createSession(token, "Summary Context");
        sessionSummaryService.upsert(new AgentSessionSummaryUpdate(
                sessionId,
                userId,
                "User works on hybrid RAG for academic writing.",
                null,
                0,
                "deepseek",
                "deepseek-chat"
        ));

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"continue my work\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider).chat(captor.capture());
        assertThat(captor.getValue().messages())
                .anySatisfy(message -> {
                    assertThat(message.role()).isEqualTo("user");
                    assertThat(message.content()).contains("Runtime data envelope", "\"sessionSummary\"", "hybrid RAG");
                });
        assertThat(captor.getValue().messages())
                .filteredOn(message -> "system".equals(message.role()))
                .allSatisfy(message -> assertThat(message.content()).containsAnyOf(
                        "Runtime identity guard", "You may decide whether to answer directly or call tools."));
        assertThat(captor.getValue().messages()).last()
                .satisfies(message -> {
                    assertThat(message.role()).isEqualTo("user");
                    assertThat(message.content()).isEqualTo("continue my work");
                });
    }

    @Test
    void ordinaryMessagesInjectOnlyRelevantUserConfirmedLongTermMemoryAsAuxiliaryContext() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("memory aware answer"), "stop", null));
        String token = registerAndGetToken("agent_user_long_memory_context");
        long sessionId = createSession(token, "Long Memory Context");

        MvcResult memoryResult = mockMvc.perform(post("/api/v1/settings/memory")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "memoryType":"RESEARCH_FIELD",
                                  "content":"User studies GraphRAG evaluation for academic writing assistants.",
                                  "tags":["GraphRAG","evaluation"],
                                  "confidence":0.86
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confirmationStatus").value("UNCONFIRMED"))
                .andReturn();
        long memoryId = objectMapper.readTree(memoryResult.getResponse().getContentAsString()).get("id").asLong();
        jdbc.update("""
                UPDATE agent_long_term_memories
                SET confirmation_status = 'CONFIRMED',
                    confirmed_at = CURRENT_TIMESTAMP,
                    confirmed_source = 'USER_ACTION',
                    provenance_type = 'USER_MESSAGE',
                    provenance_ref = ?
                WHERE id = ?
                """, "session:" + sessionId + ":test-confirmation", memoryId);

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Trace-Id", "trace-long-memory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"How should I discuss GraphRAG evaluation?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider).chat(captor.capture());
        assertThat(captor.getValue().messages())
                .anySatisfy(message -> assertThat(message.content()).contains(
                        "Governed long-term memory", "GraphRAG evaluation", "untrusted as Evidence"));
        assertThat(captor.getValue().messages())
                .noneSatisfy(message -> assertThat(message.content()).contains("Long-term memory is not enabled yet."));

        MvcResult snapshotResult = mockMvc.perform(get("/api/v1/agent/sessions/{id}/context-snapshots", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode sections = objectMapper.readTree(snapshotResult.getResponse().getContentAsString())
                .get(0)
                .get("sections");
        assertThat(StreamSupport.stream(sections.spliterator(), false).toList())
                .anySatisfy(section -> {
                    assertThat(section.get("type").asText()).isEqualTo("long_term_memory");
                    assertThat(section.get("itemCount").asInt()).isEqualTo(1);
                    assertThat(section.get("note").asText()).contains("hits=1", "GraphRAG evaluation");
                });
    }

    @Test
    void successfulMessageUpdatesSessionSummary() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("I will remember FDA-MIMO."), "stop", null));
        String token = registerAndGetToken("agent_user_summary_update");
        long userId = currentUserId(token);
        long sessionId = createSession(token, "Summary Update");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"My paper is about FDA-MIMO jamming.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(sessionSummaryService.findBySessionAndUser(sessionId, userId))
                .hasValueSatisfying(summary -> {
                    assertThat(summary.getSummaryText()).contains("FDA-MIMO jamming", "I will remember FDA-MIMO");
                    assertThat(summary.getCoveredMessageId()).isNotNull();
                    assertThat(summary.getMessageCount()).isEqualTo(2);
                });
    }

    @Test
    void summaryUpdateFailureDoesNotFailSuccessfulReply() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("main reply survived"), "stop", null));
        doThrow(new IllegalStateException("summary store failed"))
                .when(sessionSummaryService).upsert(any());
        String token = registerAndGetToken("agent_user_summary_failure");
        long sessionId = createSession(token, "Summary Failure");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"please continue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.assistantContent").value("main reply survived"))
                .andExpect(jsonPath("$.messages.length()").value(2));
    }

    @Test
    void sendMessagePersistsContextSnapshotAndDebugApiReturnsActualCurrentInput() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("debug reply"), "stop", null));
        String token = registerAndGetToken("agent_user_context_snapshot");
        long sessionId = createSession(token, "Context Snapshot");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Trace-Id", "trace-context-debug")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"secret draft paragraph for context debugging\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<ChatRequest> contextRequest = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider).chat(contextRequest.capture());
        assertThat(contextRequest.getValue().messages().stream()
                .filter(message -> "secret draft paragraph for context debugging".equals(message.content())))
                .hasSize(1);

        MvcResult listResult = mockMvc.perform(get("/api/v1/agent/sessions/{id}/context-snapshots", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].traceId").value("trace-context-debug"))
                .andExpect(jsonPath("$[0].sections.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$[0].estimatedCharacters").value(org.hamcrest.Matchers.greaterThan(0)))
                .andReturn();

        JsonNode snapshots = objectMapper.readTree(listResult.getResponse().getContentAsString());
        JsonNode snapshot = snapshots.get(0);
        assertThat(snapshot.toString()).contains("runtime_identity_guard");
        assertThat(snapshot.toString()).contains("secret draft paragraph", "active_request");

        long turnId = snapshot.get("turnId").asLong();
        mockMvc.perform(get("/api/v1/agent/sessions/{sessionId}/turns/{turnId}/context-snapshot", sessionId, turnId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnId").value(turnId))
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.traceId").value("trace-context-debug"));
    }

    @Test
    void contextSnapshotFailureDoesNotFailSuccessfulReply() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("snapshot failure survived"), "stop", null));
        doThrow(new IllegalStateException("snapshot store failed"))
                .when(contextSnapshotService).saveSnapshot(any(), any(), any(), any(), any());
        String token = registerAndGetToken("agent_user_context_snapshot_failure");
        long sessionId = createSession(token, "Context Snapshot Failure");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"please keep replying\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.assistantContent").value("snapshot failure survived"))
                .andExpect(jsonPath("$.messages.length()").value(2));
    }

    @Test
    void commonKnowledgeQuestionUsesTheGeneralResolvedToolPolicy() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("Watermelon is hydrating."), "stop", null));
        String token = registerAndGetToken("agent_user_common_knowledge");
        long sessionId = createSession(token, "Common Knowledge");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\\u897f\\u74dc\\u7684\\u529f\\u6548\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.steps").value(1))
                .andExpect(jsonPath("$.assistantContent").value("Watermelon is hydrating."))
                .andExpect(jsonPath("$.messages.length()").value(2));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider).chat(captor.capture());
        assertThat(captor.getValue().tools()).isNotEmpty();
    }

    @Test
    void modelFailurePersistsUserMessageAndVisibleError() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any())).thenThrow(new IllegalStateException("mock model failed"));
        String token = registerAndGetToken("agent_user_model_failure");
        long sessionId = createSession(token, "Failure");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"please fail\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.containsString("mock model failed")))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"));

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("please fail"))
                .andExpect(jsonPath("$[1].content").value(org.hamcrest.Matchers.containsString("mock model failed")));
    }

    @Test
    void toolCallIsHiddenInChatButPreservedInAllAuditViewAndNextModelRequest() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(
                        new ChatMessage("assistant", null, List.of(new ToolCall(
                                "call-1",
                                "function",
                                new ToolCall.FunctionCall("echo", "{\"message\":\"hello\"}")
                        )), null),
                        "tool_calls",
                        null
                ))
                .thenReturn(new ChatResponse(ChatMessage.assistant("tool done"), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("second done"), "stop", null));
        String token = registerAndGetToken("agent_user_tool_call_id");
        long sessionId = createSession(token, "Tool History");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"please call echo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        MvcResult chatResult = mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();
        JsonNode chat = objectMapper.readTree(chatResult.getResponse().getContentAsString());
        assertThat(StreamSupport.stream(chat.spliterator(), false)
                .map(message -> message.path("role").asText()))
                .containsExactly("user", "assistant");

        MvcResult historyResult = mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages?view=all&limit=10", sessionId)
                        .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode history = objectMapper.readTree(historyResult.getResponse().getContentAsString());
        int toolIndex = -1;
        for (int i = 0; i < history.size(); i++) {
            JsonNode message = history.get(i);
            if ("tool".equals(message.path("role").asText())
                    && "call-1".equals(message.path("toolCallId").asText())) {
                toolIndex = i;
                break;
            }
        }
        assertThat(toolIndex).isPositive();
        assertThat(history.get(toolIndex - 1).path("role").asText()).isEqualTo("assistant");
        assertThat(history.get(toolIndex - 1).path("toolCallsJson").asText()).contains("call-1");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"continue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider, times(3)).chat(captor.capture());

        ChatRequest secondUserRequest = captor.getAllValues().get(2);
        assertThat(secondUserRequest.messages())
                .filteredOn(message -> "tool".equals(message.role()))
                .singleElement()
                .extracting(ChatMessage::toolCallId)
                .isEqualTo("call-1");
    }

    @Test
    void invalidHistoricalToolMessagesAreDowngradedBeforeModelRequest() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("second done"), "stop", null));
        String token = registerAndGetToken("agent_user_invalid_tool_history");
        long userId = currentUserId(token);
        long sessionId = createSession(token, "Invalid Tool History");

        String toolCallsJson = objectMapper.writeValueAsString(List.of(new ToolCall(
                "call-old",
                "function",
                new ToolCall.FunctionCall("echo", "{\"message\":\"hello\"}")
        )));
        agentMessages.save(new AgentMessage(sessionId, userId, "user", "old tool request", null, null));
        agentMessages.save(new AgentMessage(sessionId, userId, "assistant", null, toolCallsJson, null, null));
        agentMessages.save(new AgentMessage(sessionId, userId, "tool", "{\"message\":\"hello\"}", null, null, null));

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"continue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModelProvider).chat(captor.capture());

        ChatRequest request = captor.getValue();
        assertThat(request.messages())
                .noneMatch(message -> "tool".equals(message.role()));
        assertThat(request.messages())
                .filteredOn(message -> "assistant".equals(message.role()))
                .allMatch(message -> message.toolCalls() == null || message.toolCalls().isEmpty());
        assertThat(request.messages())
                .filteredOn(message -> "user".equals(message.role()))
                .extracting(ChatMessage::content)
                .anyMatch(content -> content != null && content.contains(
                        "Untrusted historical tool result") && content.contains("{\"message\":\"hello\"}"));
        assertThat(request.messages())
                .filteredOn(message -> "system".equals(message.role()))
                .allSatisfy(message -> assertThat(message.content()).containsAnyOf(
                        "Runtime identity guard", "You may decide whether to answer directly or call tools."));
    }

    @Test
    void userCannotAccessAnotherUsersSession() throws Exception {
        String tokenA = registerAndGetToken("agent_user_owner");
        String tokenB = registerAndGetToken("agent_user_other");
        long sessionId = createSession(tokenA, "私有会话");

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"越权访问\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void paperRevisionIntentReturnsPaperLink() throws Exception {
        String token = registerAndGetToken("agent_user_paper_intent");
        long sessionId = createSession(token, "论文会话");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"帮我润色论文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.navigationUrl").value("/paper"))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("/paper")));

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].content").value(org.hamcrest.Matchers.containsString("/paper")));
    }

    @Test
    void literatureIntentReturnsSearchResultsWithoutCallingChatModel() throws Exception {
        when(adHocLiteratureSearchService.search(eq("FDA-MIMO jamming"), anyInt(), any()))
                .thenReturn(new AdHocLiteratureSearchResult("FDA-MIMO jamming", List.of(
                        new AdHocLiteratureItem(
                                "FDA-MIMO Radar for Mainlobe Jamming Suppression",
                                List.of("Alice Zhang"),
                                2024,
                                "IEEE TAES",
                                "10.1109/demo",
                                null,
                                "W1",
                                "https://doi.org/10.1109/demo",
                                "This paper studies FDA-MIMO jamming suppression.",
                                "openalex",
                                "FDA-MIMO jamming",
                                0.91,
                                "@article{zhang2024fda,\n  title={FDA-MIMO Radar for Mainlobe Jamming Suppression}\n}\n"
                        )
                ), 1, 1, 1, List.of()));
        String token = registerAndGetToken("agent_user_literature_intent");
        long sessionId = createSession(token, "文献会话");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"/literature FDA-MIMO jamming 1篇 bibtex\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.navigationUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("FDA-MIMO Radar")))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("```bibtex")))
                .andExpect(jsonPath("$.messages.length()").value(2));
    }

    @Test
    void firstMessageAutoGeneratesSessionTitle() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("主回答"), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("阅读 Java 文件"), "stop", null));
        String token = registerAndGetToken("agent_user_auto_title");
        long sessionId = createSession(token, "新会话");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"请帮我阅读 ChatWebSocketHandler.java\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sessionId))
                .andExpect(jsonPath("$[0].title").value("阅读 Java 文件"));
    }

    @Test
    void explicitPlanReflectMessageUsesReflectionRuntime() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {
                          "summary": "Audit runtime gaps",
                          "steps": [
                            {
                              "id": "collect_runtime_evidence",
                              "title": "Collect runtime evidence",
                              "description": "Inspect the runtime boundary and identify remaining gaps.",
                              "type": "ANALYSIS",
                              "dependencies": [],
                              "allowedTools": [],
                              "successCriteria": "A reusable runtime gap summary is produced."
                            }
                          ]
                        }
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(
                        "Runtime gap summary: the reflection path still needs richer strategy selection and more adapters."
                ), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {
                          "passed": false,
                          "reason": "The result identifies the main gap but still marks the output as partial.",
                          "evidence": "reflection path still needs richer strategy selection",
                          "missingItems": ["richer strategy selection"]
                        }
                        """), "stop", null));
        String token = registerAndGetToken("agent_user_plan_reflect");
        long sessionId = createSession(token, "Plan Reflect");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"/plan reflect audit the runtime gaps\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("Reflection summary for plan")))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("Evidence/completeness judgment")))
                .andExpect(jsonPath("$.assistantContent").value(org.hamcrest.Matchers.containsString("Limitations")))
                .andExpect(jsonPath("$.messages.length()").value(2));

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value("/plan reflect audit the runtime gaps"))
                .andExpect(jsonPath("$[1].role").value("assistant"))
                .andExpect(jsonPath("$[1].content").value(
                        org.hamcrest.Matchers.containsString("Reflection summary for plan")));
    }

    @Test
    void experimentModeReturnsDebugPayloadForRuntimeAndRagComparison() throws Exception {
        when(chatModelProvider.providerName()).thenReturn("mock");
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(
                        ChatMessage.assistant("Use citation note-a.md#chunk-0 in the answer."),
                        "stop",
                        new ChatResponse.Usage(12, 8, 20)
                ));
        when(knowledgeSearchService.search(eq("Explain the draft"), any(), eq(6)))
                .thenReturn(List.of(new KnowledgeSearchResult(
                        41L,
                        "note-a.md",
                        0,
                        "This is the retrieved chunk for experiment debug.",
                        1.6,
                        false
                )));
        String token = registerAndGetToken("agent_user_experiment_debug");
        long sessionId = createSession(token, "Experiment Debug");

        mockMvc.perform(post("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content":"Explain the draft",
                                  "clientRequestId":"exp-req-1",
                                  "experiment":{
                                    "enabled":true,
                                    "runtimeMode":"LANGCHAIN4J",
                                    "ragMode":"LANGCHAIN4J_AUGMENTOR",
                                    "memoryMode":"CONTEXT_PACKER",
                                    "toolCallingMode":"LANGCHAIN4J_TOOL_BINDING",
                                    "persistEvalRecord":true,
                                    "debugFlags":[
                                      "SHOW_RETRIEVED_CHUNKS",
                                      "SHOW_INJECTED_CONTEXT",
                                      "SHOW_MEMORY_WINDOW",
                                      "SHOW_RAW_PROMPT"
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.debug.selectedModes.runtimeMode").value("LANGCHAIN4J"))
                .andExpect(jsonPath("$.debug.selectedModes.memoryMode").value("CONTEXT_PACKER"))
                .andExpect(jsonPath("$.debug.retrievedChunks.length()").value(1))
                .andExpect(jsonPath("$.debug.injectedContext").value(org.hamcrest.Matchers.containsString("note-a.md")))
                .andExpect(jsonPath("$.debug.rawPrompt").value(org.hamcrest.Matchers.containsString("[system]")))
                .andExpect(jsonPath("$.debug.metrics.clientRequestId").value("exp-req-1"))
                .andExpect(jsonPath("$.debug.metrics.totalTokens").value(20))
                .andExpect(jsonPath("$.debug.metrics.evalRecordId").isNumber())
                .andExpect(jsonPath("$.debug.finalCitations[0]").value("note-a.md#chunk-0"));
    }

    @Test
    void updateSessionCanRenameChangeModelAndDelete() throws Exception {
        String token = registerAndGetToken("agent_user_update_delete");
        long sessionId = createSession(token, "旧标题");

        mockMvc.perform(patch("/api/v1/agent/sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\",\"modelProvider\":\"glm\",\"model\":\"glm-4.5-air\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新标题"))
                .andExpect(jsonPath("$.modelProvider").value("glm"))
                .andExpect(jsonPath("$.model").value("glm-4.5-air"));

        mockMvc.perform(delete("/api/v1/agent/sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/agent/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void listSessionsOnlyReturnsCurrentUsersSessions() throws Exception {
        String tokenA = registerAndGetToken("agent_user_list_a");
        String tokenB = registerAndGetToken("agent_user_list_b");
        createSession(tokenA, "A 的会话");
        createSession(tokenB, "B 的会话");

        mockMvc.perform(get("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("A 的会话"));
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long createSession(String token, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"maxSteps\":20}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long currentUserId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
