package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatResponse;
import com.yanban.paper.literature.AdHocLiteratureSearchService;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:yanban_plan_controller_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "yanban.jwt.secret=test_secret_123456789012345678901234567890"
})
class PlanAgentControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean(name = "chatModelProvider")
    ChatModelProvider chatModelProvider;

    @MockBean
    AdHocLiteratureSearchService adHocLiteratureSearchService;

    @BeforeEach
    void setUp() {
        when(chatModelProvider.providerName()).thenReturn("mock");
    }

    @Test
    void createExecuteAndListEventsThroughApi() throws Exception {
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {
                          "summary": "Create a short launch checklist",
                          "steps": [
                            {
                              "id": "collect_requirements",
                              "title": "Produce checklist",
                              "description": "Produce a concise RAG launch checklist with concrete acceptance items.",
                              "type": "ANALYSIS",
                              "dependencies": [],
                              "allowedTools": [],
                              "successCriteria": "A reusable checklist with concrete acceptance items is produced."
                            }
                          ]
                        }
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(
                        "Reusable checklist: verify auth, RAG retrieval, plan execution, concurrency, and ops health."
                ), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {
                          "passed": true,
                          "reason": "The result is a reusable checklist with concrete acceptance items.",
                          "evidence": "verify auth, RAG retrieval, plan execution, concurrency, and ops health",
                          "missingItems": []
                        }
                        """), "stop", null));

        String token = registerAndGetToken("plan_api_owner");
        long sessionId = createSession(token, "Plan API");
        long planId = createPlan(token, sessionId, "Create a launch-readiness checklist for the private helper agent.");

        mockMvc.perform(get("/api/v1/agent/plans/{planId}", planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(planId))
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.steps[0].stepKey").value("step_1"))
                .andExpect(jsonPath("$.steps[0].status").value("PENDING"));

        MvcResult executeResult = mockMvc.perform(post("/api/v1/agent/plans/{planId}/execute", planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps[0].result").value(org.hamcrest.Matchers.containsString("Reusable checklist")))
                .andReturn();

        JsonNode executedPlan = objectMapper.readTree(executeResult.getResponse().getContentAsString());
        assertThat(executedPlan.path("finishedAt").isMissingNode()).isFalse();

        MvcResult eventsResult = mockMvc.perform(get("/api/v1/agent/plans/{planId}/events", planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Set<String> eventTypes = readEventTypes(eventsResult);
        assertThat(eventTypes)
                .contains("plan_created", "plan_started", "step_started", "step_completed", "plan_completed");

        mockMvc.perform(get("/api/v1/agent/sessions/{sessionId}/plans", sessionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(planId))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));

        mockMvc.perform(get("/api/v1/observability/dashboard")
                        .param("windowMinutes", "1440")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planStatusCounts.COMPLETED").exists())
                .andExpect(jsonPath("$.eventTypeCounts.plan_completed").exists())
                .andExpect(jsonPath("$.alerts.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)));

        mockMvc.perform(get("/api/v1/observability/alerts")
                        .param("windowMinutes", "1440")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is("OK"),
                        org.hamcrest.Matchers.is("WARN"),
                        org.hamcrest.Matchers.is("CRITICAL")
                )))
                .andExpect(jsonPath("$.alerts.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)));
    }

    @Test
    void executeAsyncReturnsImmediatelyAndEventsReachTerminalState() throws Exception {
        when(chatModelProvider.chat(any()))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {
                          "summary": "Create a short async checklist",
                          "steps": [
                            {
                              "id": "write_checklist",
                              "title": "Write checklist",
                              "description": "Write a concise async plan checklist with acceptance signals.",
                              "type": "ANALYSIS",
                              "dependencies": [],
                              "allowedTools": [],
                              "successCriteria": "A concise checklist with acceptance signals is produced."
                            }
                          ]
                        }
                        """), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant(
                        "Async checklist: queue the plan, poll events, verify completion, and expose cancellation."
                ), "stop", null))
                .thenReturn(new ChatResponse(ChatMessage.assistant("""
                        {
                          "passed": true,
                          "reason": "The result contains a concise checklist with acceptance signals.",
                          "evidence": "queue the plan, poll events, verify completion, and expose cancellation",
                          "missingItems": []
                        }
                        """), "stop", null));

        String token = registerAndGetToken("plan_api_async_owner");
        long sessionId = createSession(token, "Async Plan API");
        long planId = createPlan(token, sessionId, "Create an async execution checklist.");

        long started = System.nanoTime();
        MvcResult asyncResult = mockMvc.perform(post("/api/v1/agent/plans/{planId}/execute-async", planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(durationMs).isLessThan(2000);
        JsonNode asyncPlan = objectMapper.readTree(asyncResult.getResponse().getContentAsString());
        assertThat(asyncPlan.path("status").asText()).isIn("RUNNING", "COMPLETED");

        JsonNode terminalPlan = waitForTerminalPlan(token, planId);
        assertThat(terminalPlan.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(terminalPlan.path("steps").get(0).path("status").asText()).isEqualTo("COMPLETED");

        MvcResult eventsResult = mockMvc.perform(get("/api/v1/agent/plans/{planId}/events", planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Set<String> eventTypes = readEventTypes(eventsResult);
        assertThat(eventTypes)
                .contains("plan_queued", "plan_started", "step_started", "step_completed", "plan_completed");

        mockMvc.perform(post("/api/v1/agent/plans/{planId}/retry", planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void userCannotAccessAnotherUsersPlan() throws Exception {
        when(chatModelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {
                  "summary": "Private plan",
                  "steps": [
                    {
                      "id": "step_a",
                      "title": "Private step",
                      "description": "Produce a private result.",
                      "type": "ANALYSIS",
                      "dependencies": [],
                      "allowedTools": [],
                      "successCriteria": "The private result is produced."
                    }
                  ]
                }
                """), "stop", null));

        String ownerToken = registerAndGetToken("plan_api_private_owner");
        String otherToken = registerAndGetToken("plan_api_private_other");
        long ownerSessionId = createSession(ownerToken, "Owner Plan API");
        long planId = createPlan(ownerToken, ownerSessionId, "Create a private plan.");

        mockMvc.perform(get("/api/v1/agent/plans/{planId}", planId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/agent/plans/{planId}/execute", planId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/agent/plans/{planId}/execute-async", planId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/agent/plans/{planId}/retry", planId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/agent/plans/{planId}/cancel", planId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/agent/plans/{planId}/events", planId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    private String registerAndGetToken(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", "password123"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long createSession(String token, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/agent/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", title, "maxSteps", 4))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createPlan(String token, long sessionId, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/agent/sessions/{sessionId}/plans", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "content", content,
                                "ragDisabled", true,
                                "autoExecute", false
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Set<String> readEventTypes(MvcResult eventsResult) throws Exception {
        JsonNode events = objectMapper.readTree(eventsResult.getResponse().getContentAsString());
        return StreamSupport.stream(events.spliterator(), false)
                .map(event -> event.path("eventType").asText())
                .collect(Collectors.toSet());
    }

    private JsonNode waitForTerminalPlan(String token, long planId) throws Exception {
        JsonNode last = null;
        for (int i = 0; i < 60; i++) {
            MvcResult result = mockMvc.perform(get("/api/v1/agent/plans/{planId}", planId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            last = objectMapper.readTree(result.getResponse().getContentAsString());
            String status = last.path("status").asText();
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                return last;
            }
            Thread.sleep(250);
        }
        return last;
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
