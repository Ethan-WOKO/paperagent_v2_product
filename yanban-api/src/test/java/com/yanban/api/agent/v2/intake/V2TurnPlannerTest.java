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
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import io.paperagent.v2.contracts.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.mockito.ArgumentCaptor;

class V2TurnPlannerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesToolFreePersistentPlan() {
        var planned = planner(answer("""
                {
                  "route":"PERSISTENT_PLAN_EXECUTE",
                  "requirements":{"projectEvidence":true,"toolUse":true,
                    "retrieval":false,"networkAccess":false,
                    "execution":false,"durableModification":false,
                    "durableProgress":true},
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
                      "maxDurationSeconds":120
                    }]
                  }
                }
                """)).parse(answerText());

        assertEquals(Route.PERSISTENT_PLAN_EXECUTE, planned.route());
        assertEquals("Inspect the project", planned.taskFrame().objective());
        assertEquals(1, planned.plan().steps().size());
        assertTrue(planned.capabilities().isEmpty());
    }

    @Test
    void acceptsLegacyCapabilityAsCompatibilityHintOnly() {
        var planned = planner(answer("unused")).parse(persistent(
                "\"capability\":\"project_read\"",
                "\"dependencies\":[]"));

        assertEquals("project_read",
                planned.capabilities().get(0).publicAlias());
        assertEquals("project.read",
                planned.capabilities().get(0).internalToolId().value());
    }

    @Test
    void acceptsDirectWithoutPlan() {
        var planned = planner(answer("unused")).parse("""
                {"route":"DIRECT","requirements":{
                  "projectEvidence":false,"toolUse":false,
                  "retrieval":false,"networkAccess":false,
                  "execution":false,"durableModification":false,
                  "durableProgress":false},"answer":"A concise answer"}
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
    void projectSessionAllowsAuditedDirect() {
        var planned = planner(answer(direct("2"))).plan(
                new com.yanban.api.agent.AgentContextPackage(
                        java.util.List.of(ChatMessage.user("1+1?")),
                        java.util.List.of(), java.util.List.of(), 1, 1, 1),
                new com.yanban.api.settings.UserSettingsService.ModelEndpoint(
                        "deepseek", "model", null, null,
                        "builtin", "DeepSeek"),
                null, true, "trace");

        assertEquals(Route.DIRECT, planned.route());
        assertEquals("2", planned.answer());
        assertTrue(!planned.requirements().any());
    }

    @Test
    void retriesOneCompactProtocolResponseForEmptyAssistantText() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chat(any())).thenReturn(
                new ChatResponse(
                        ChatMessage.assistant(""), "stop",
                        new ChatResponse.Usage(4_000, 1, 4_001)),
                new ChatResponse(
                        ChatMessage.assistant(direct("23")),
                        "stop", null));

        var planned = planner(provider).plan(
                context("16+7?"), endpoint(),
                null, true, "trace");

        assertEquals(Route.DIRECT, planned.route());
        assertEquals("23", planned.answer());
        ArgumentCaptor<ChatRequest> requests =
                ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(provider,
                org.mockito.Mockito.times(2)).chat(requests.capture());
        ChatRequest retry = requests.getAllValues().get(1);
        assertTrue(retry.messages().stream()
                .anyMatch(message -> message.content().contains(
                        "previous provider response contained no usable")));
        assertTrue(retry.messages().stream()
                .anyMatch(message -> "16+7?".equals(message.content())));
    }

    @Test
    void auditsRequirementsWithoutAutomaticSemanticRepair() {
        ChatModelProvider directProvider = answer("""
                {"route":"DIRECT","requirements":{
                  "projectEvidence":true,"toolUse":false,
                  "retrieval":false,"networkAccess":false,
                  "execution":false,"durableModification":false,
                  "durableProgress":false},"answer":"unsafe"}
                """);
        var directFailure = assertThrows(V2TurnPlanningException.class,
                () -> planner(directProvider).plan(
                        context("inspect the project"), endpoint(),
                        null, true, "trace"));
        assertTrue(directFailure.failureCode().matches(
                "PLANNER_DIRECT_REQUIREMENTS_[0-9a-f]{12}"));
        org.mockito.Mockito.verify(directProvider,
                org.mockito.Mockito.times(1)).chat(any());

        ChatModelProvider persistentProvider = answer(answerText()
                .replace("\"projectEvidence\":true",
                        "\"projectEvidence\":false")
                .replace("\"toolUse\":true", "\"toolUse\":false")
                .replace("\"durableProgress\":true",
                        "\"durableProgress\":false"));
        var persistentFailure = assertThrows(V2TurnPlanningException.class,
                () -> planner(persistentProvider).plan(
                        context("inspect the project"), endpoint(),
                        null, true, "trace"));
        assertTrue(persistentFailure.failureCode().matches(
                "PLANNER_PERSISTENT_REQUIREMENTS_[0-9a-f]{12}"));
        org.mockito.Mockito.verify(persistentProvider,
                org.mockito.Mockito.times(1)).chat(any());
    }

    @Test
    void rejectedOutputReportsSafeFieldAndDigest() {
        String dotted = persistent(
                "\"capability\":\"project.read\"",
                "\"dependencies\":[]");

        var failure = assertThrows(V2TurnPlanningException.class,
                () -> planner(answer(dotted)).plan(
                        context("question"),
                        endpoint(),
                        null,
                        true,
                        "trace"));

        assertTrue(failure.failureCode().matches(
                "PLANNER_CAPABILITY_DOTTED_[0-9a-f]{12}"));
    }

    @Test
    void projectTurnExplainsSemanticRoutingAndExamples() {
        ChatModelProvider provider = answer(answerText());

        planner(provider).plan(
                context("read the project"),
                endpoint(),
                null,
                true,
                "trace");

        ArgumentCaptor<ChatRequest> request =
                ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(provider).chat(request.capture());
        assertTrue(request.getValue().messages().stream()
                .anyMatch(message -> message.content().contains(
                        "Project availability alone does not determine the route")));
        assertTrue(request.getValue().messages().stream()
                .anyMatch(message -> message.content().contains(
                        "What is 1+1?")));
        assertTrue(request.getValue().messages().stream()
                .anyMatch(message -> message.content().contains(
                        "Remove merge sort from Sort.java and compile it")));
        assertTrue(request.getValue().messages().stream()
                .anyMatch(message -> message.content().contains(
                        "Do not assign a tool or capability to a step")));
    }

    @Test
    void retriesOneFreshFormatRepairForNonObjectPlannerOutput() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chat(any())).thenReturn(
                new ChatResponse(
                        ChatMessage.assistant("[]"), "stop", null),
                new ChatResponse(
                        ChatMessage.assistant(answerText()), "stop", null));

        var planned = planner(provider).plan(
                context("read the project"), endpoint(),
                null, true, "trace");

        assertEquals(Route.PERSISTENT_PLAN_EXECUTE, planned.route());
        ArgumentCaptor<ChatRequest> requests =
                ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(provider,
                org.mockito.Mockito.times(2)).chat(requests.capture());
        ChatRequest repair = requests.getAllValues().get(1);
        assertTrue(repair.messages().stream()
                .anyMatch(message -> message.content().contains(
                        "exactly one top-level JSON object")));
        assertEquals("assistant",
                repair.messages().get(repair.messages().size() - 2).role());
        assertEquals("[]",
                repair.messages().get(repair.messages().size() - 2).content());
        assertEquals("user",
                repair.messages().get(repair.messages().size() - 1).role());
    }

    @Test
    void acceptsOneObjectWrappedByProviderArray() {
        ChatModelProvider provider = answer("[" + answerText() + "]");

        var planned = planner(provider).plan(
                context("read the project"), endpoint(),
                null, true, "trace");

        assertEquals(Route.PERSISTENT_PLAN_EXECUTE, planned.route());
        org.mockito.Mockito.verify(provider).chat(any());
    }

    @Test
    void acceptsOneObjectDoubleEncodedByProvider() throws Exception {
        String encoded = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(answerText());
        ChatModelProvider provider = answer(encoded);

        var planned = planner(provider).plan(
                context("read the project"), endpoint(),
                null, true, "trace");

        assertEquals(Route.PERSISTENT_PLAN_EXECUTE, planned.route());
        org.mockito.Mockito.verify(provider).chat(any());
    }

    @TestFactory
    java.util.stream.Stream<DynamicTest> mapsEveryPublicAliasExactly() {
        java.util.Map<String, String> expected = java.util.Map.of(
                "literature_search", "literature.search",
                "project_read", "project.read",
                "project_search", "project.search",
                "project_document_extract", "project.document.extract",
                "project_spreadsheet_inspect", "project.spreadsheet.inspect",
                "project_bibtex_audit", "project.bibtex.audit",
                "project_candidate", "project.candidate.compose",
                "sandbox_execute", "sandbox.execute");
        return expected.entrySet().stream().map(entry ->
                DynamicTest.dynamicTest(entry.getKey(), () ->
                        assertEquals(
                                entry.getValue(),
                                V2PlannerCapabilityCatalog.internalToolId(
                                        entry.getKey()).value())));
    }

    @Test
    void rejectsDuplicateStepIdsAndOversizedModelOutput() {
        String duplicate = """
                {"route":"PERSISTENT_PLAN_EXECUTE",
                 "requirements":{"projectEvidence":true,"toolUse":true,
                   "retrieval":false,"networkAccess":false,
                   "execution":false,"durableModification":false,
                   "durableProgress":true},
                 "taskFrame":{"objective":"x","targets":["x"],
                   "deliverables":["x"],"constraints":["x"]},
                 "plan":{"reason":"x","steps":[
                  {"id":"same","intent":"x","expectedOutcome":"x",
                   "dependencies":[],"completionCriteria":["x"],
                   "maxAttempts":1,"maxDurationSeconds":1,"capability":null},
                  {"id":"same","intent":"x","expectedOutcome":"x",
                   "dependencies":[],"completionCriteria":["x"],
                   "maxAttempts":1,"maxDurationSeconds":1,"capability":null}]}}
                """;
        assertThrows(V2TurnPlanningException.class,
                () -> planner(answer("unused")).parse(duplicate));

        String oversized = "{\"route\":\"DIRECT\",\"answer\":\""
                + "x".repeat(32_001) + "\"}";
        assertThrows(V2TurnPlanningException.class,
                () -> planner(answer(oversized)).plan(
                        context("question"),
                        endpoint(),
                        null,
                        false,
                        "trace"));
    }

    private String answerText() {
        return """
                {
                  "route":"PERSISTENT_PLAN_EXECUTE",
                  "requirements":{"projectEvidence":true,"toolUse":true,
                    "retrieval":false,"networkAccess":false,
                    "execution":false,"durableModification":false,
                    "durableProgress":true},
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
                      "maxDurationSeconds":120
                    }]
                  }
                }
                """;
    }

    private String persistent(String capability, String dependencies) {
        return """
                {"route":"PERSISTENT_PLAN_EXECUTE",
                 "requirements":{"projectEvidence":true,"toolUse":true,
                   "retrieval":false,"networkAccess":false,
                   "execution":false,"durableModification":false,
                   "durableProgress":true},
                 "taskFrame":{"objective":"x","targets":["x"],
                   "deliverables":["x"],"constraints":["x"]},
                 "plan":{"reason":"x","steps":[{
                   "id":"one","intent":"x","expectedOutcome":"x",
                   %s,"completionCriteria":["x"],
                   "maxAttempts":1,"maxDurationSeconds":1,%s}]}}
                """.formatted(dependencies, capability);
    }

    private String direct(String answer) {
        return """
                {"route":"DIRECT","requirements":{
                  "projectEvidence":false,"toolUse":false,
                  "retrieval":false,"networkAccess":false,
                  "execution":false,"durableModification":false,
                  "durableProgress":false},"answer":"%s"}
                """.formatted(answer);
    }

    private V2TurnPlanner planner(ChatModelProvider provider) {
        return new V2TurnPlanner(provider, json);
    }

    private com.yanban.api.agent.AgentContextPackage context(String current) {
        return new com.yanban.api.agent.AgentContextPackage(
                java.util.List.of(ChatMessage.system("context")),
                java.util.List.of(),
                java.util.List.of(),
                1, 1, 1,
                com.yanban.api.agent.EvidenceLedger.empty(),
                ChatMessage.user(current),
                null);
    }

    private com.yanban.api.settings.UserSettingsService.ModelEndpoint endpoint() {
        return new com.yanban.api.settings.UserSettingsService.ModelEndpoint(
                "deepseek", "model", null, null, "builtin", "DeepSeek");
    }

    private ChatModelProvider answer(String value) {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant(value), "stop", null));
        return provider;
    }
}
