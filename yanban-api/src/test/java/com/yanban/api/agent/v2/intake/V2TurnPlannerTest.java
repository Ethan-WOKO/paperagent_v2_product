package com.yanban.api.agent.v2.intake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import io.paperagent.v2.contracts.Route;
import org.junit.jupiter.api.Test;

class V2TurnPlannerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesStrictPersistentPlanAndMapsPublicAlias() {
        var planned = planner(answer("""
                {
                  "route":"PERSISTENT_PLAN_EXECUTE",
                  "taskFrame":{
                    "objective":"Inspect the project",
                    "targets":["current project"],
                    "deliverables":["analysis"],
                    "constraints":["read only"]
                  },
                  "plan":{
                    "reason":"Project evidence is required",
                    "steps":[{
                      "id":"read-1",
                      "intent":"Read the requested source",
                      "expectedOutcome":"Source text is available",
                      "dependencies":[],
                      "completionCriteria":["A bounded read receipt exists"],
                      "maxAttempts":1,
                      "maxDurationSeconds":120,
                      "capability":"project_read"
                    }]
                  }
                }
                """)).parse(answerText());

        assertEquals(Route.PERSISTENT_PLAN_EXECUTE, planned.route());
        assertEquals("Inspect the project", planned.taskFrame().objective());
        assertEquals(1, planned.plan().steps().size());
        assertEquals("project_read",
                planned.capabilities().get(0).publicAlias());
        assertEquals("project.read",
                planned.capabilities().get(0).internalToolId().value());
    }

    @Test
    void acceptsDirectWithoutPlan() {
        var planned = planner(answer("unused")).parse("""
                {"route":"DIRECT","answer":"A concise answer"}
                """);

        assertEquals(Route.DIRECT, planned.route());
        assertEquals("A concise answer", planned.answer());
        assertTrue(planned.capabilities().isEmpty());
    }

    @Test
    void rejectsDottedUnsupportedAndInvalidDependencyAliases() {
        assertThrows(V2TurnPlanningException.class,
                () -> planner(answer("unused")).parse(persistent(
                        "\"capability\":\"project.read\"",
                        "\"dependencies\":[]")));
        assertThrows(V2TurnPlanningException.class,
                () -> planner(answer("unused")).parse(persistent(
                        "\"capability\":\"unknown_tool\"",
                        "\"dependencies\":[]")));
        assertThrows(V2TurnPlanningException.class,
                () -> planner(answer("unused")).parse(persistent(
                        "\"capability\":null",
                        "\"dependencies\":[\"missing\"]")));
    }

    @Test
    void rejectsMalformedDuplicateFieldsAndPlanningToolCalls() {
        assertThrows(V2TurnPlanningException.class,
                () -> planner(answer("unused")).parse("{bad json"));
        assertThrows(V2TurnPlanningException.class,
                () -> planner(answer("unused")).parse(
                        "{\"route\":\"DIRECT\",\"route\":\"DIRECT\","
                                + "\"answer\":\"x\"}"));
        ChatModelProvider callsTool = mock(ChatModelProvider.class);
        when(callsTool.chat(any())).thenReturn(new ChatResponse(
                new ChatMessage(
                        "assistant", null,
                        java.util.List.of(new ToolCall(
                                "call-1", "function",
                                new ToolCall.FunctionCall(
                                        "project_read", "{}"))),
                        null),
                "tool_calls",
                null));
        assertThrows(V2TurnPlanningException.class,
                () -> planner(callsTool).plan(
                        new com.yanban.api.agent.AgentContextPackage(
                                java.util.List.of(ChatMessage.user("x")),
                                java.util.List.of(),
                                java.util.List.of(),
                                1, 1, 1),
                        new com.yanban.api.settings.UserSettingsService.ModelEndpoint(
                                "deepseek", "model", null, "secret",
                                "builtin", "DeepSeek"),
                        null,
                        false,
                        "trace"));
    }

    @Test
    void projectSessionCannotBecomeDirect() {
        assertThrows(V2TurnPlanningException.class,
                () -> planner(answer(
                        "{\"route\":\"DIRECT\",\"answer\":\"x\"}"))
                        .plan(
                                new com.yanban.api.agent.AgentContextPackage(
                                        java.util.List.of(
                                                ChatMessage.user("x")),
                                        java.util.List.of(),
                                        java.util.List.of(),
                                        1, 1, 1),
                                new com.yanban.api.settings.UserSettingsService.ModelEndpoint(
                                        "deepseek", "model", null, null,
                                        "builtin", "DeepSeek"),
                                null,
                                true,
                                "trace"));
    }

    private String answerText() {
        return """
                {
                  "route":"PERSISTENT_PLAN_EXECUTE",
                  "taskFrame":{
                    "objective":"Inspect the project",
                    "targets":["current project"],
                    "deliverables":["analysis"],
                    "constraints":["read only"]
                  },
                  "plan":{
                    "reason":"Project evidence is required",
                    "steps":[{
                      "id":"read-1",
                      "intent":"Read the requested source",
                      "expectedOutcome":"Source text is available",
                      "dependencies":[],
                      "completionCriteria":["A bounded read receipt exists"],
                      "maxAttempts":1,
                      "maxDurationSeconds":120,
                      "capability":"project_read"
                    }]
                  }
                }
                """;
    }

    private String persistent(String capability, String dependencies) {
        return """
                {"route":"PERSISTENT_PLAN_EXECUTE",
                 "taskFrame":{"objective":"x","targets":["x"],
                   "deliverables":["x"],"constraints":[]},
                 "plan":{"reason":"x","steps":[{
                   "id":"one","intent":"x","expectedOutcome":"x",
                   %s,"completionCriteria":["x"],
                   "maxAttempts":1,"maxDurationSeconds":1,%s}]}}
                """.formatted(dependencies, capability);
    }

    private V2TurnPlanner planner(ChatModelProvider provider) {
        return new V2TurnPlanner(provider, json);
    }

    private ChatModelProvider answer(String value) {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant(value), "stop", null));
        return provider;
    }
}
