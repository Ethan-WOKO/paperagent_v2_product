package com.yanban.api.agent.reactplan.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.AgentToolPolicyEngine;
import com.yanban.api.agent.reactplan.ReactPlanCanonicalJson;
import com.yanban.api.agent.reactplan.ReactPlanHistoryToolContract;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.RegisteredToolCall;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.core.agent.AgentRunIdentity;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentEngineRegisteredToolGatewayTest {
    private static final String TASK = "task." + "1".repeat(64);
    private static final String VERSION = "3".repeat(64);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void exposesAndInvokesOnlyRegisteredReadOnlyProjectTools() {
        ToolRegistry registry = new ToolRegistry()
                .register(executor("project_search", ToolDescriptor.SideEffectType.READ_ONLY))
                .register(executor("project_candidate", ToolDescriptor.SideEffectType.CREATE));
        AgentToolPolicyEngine policies = mock(AgentToolPolicyEngine.class);
        when(policies.decideProject(null, null)).thenReturn(
                new AgentToolPolicyEngine.Decision(
                        List.of("project_search", "project_candidate"), 12, 1, "test"));
        AgentEngineRegisteredToolGateway gateway = new AgentEngineRegisteredToolGateway(
                json, registry, policies, contexts(VERSION));

        var catalog = gateway.catalog(authority());

        assertThat(catalog.tools()).singleElement().satisfies(tool ->
                assertThat(tool.function().name()).isEqualTo("project_search"));
        ObjectNode arguments = json.createObjectNode().put("query", "order-service");
        String digest = ReactPlanCanonicalJson.digest(json,
                Map.of("toolName", "project_search", "arguments", arguments));
        var result = gateway.invoke(authority(), new RegisteredToolCall(
                "1.0", "call." + "a".repeat(40), "project_search", arguments, digest));
        assertThat(result.success()).isTrue();
        assertThat(result.output().path("projectVersion").asText()).isEqualTo(VERSION);
        assertThat(ToolExecutionContext.getCurrentUserId()).isNull();

        String deniedDigest = ReactPlanCanonicalJson.digest(json,
                Map.of("toolName", "project_candidate", "arguments", arguments));
        assertThatThrownBy(() -> gateway.invoke(authority(), new RegisteredToolCall(
                "1.0", "call." + "b".repeat(40), "project_candidate",
                arguments, deniedDigest)))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("REGISTERED_TOOL_NOT_ALLOWED"));
    }

    @Test
    void exposesSynchronousRetrievalBundleAndInjectsServerOwnedIdentity() {
        ToolRegistry registry = new ToolRegistry()
                .register(executor("project_search", ToolDescriptor.SideEffectType.READ_ONLY))
                .register(retrievalExecutor("search_web",
                        ToolDescriptor.SideEffectType.EXTERNAL_READ,
                        List.of(ToolDescriptor.ResourceScope.EXTERNAL)))
                .register(retrievalExecutor("search_knowledge",
                        ToolDescriptor.SideEffectType.NONE,
                        List.of(ToolDescriptor.ResourceScope.USER_KNOWLEDGE)))
                .register(retrievalExecutor("recommend_literature",
                        ToolDescriptor.SideEffectType.CREATE,
                        List.of(ToolDescriptor.ResourceScope.EXTERNAL,
                                ToolDescriptor.ResourceScope.SESSION)));
        AgentToolPolicyEngine policies = mock(AgentToolPolicyEngine.class);
        when(policies.decideProject(null, null)).thenReturn(
                new AgentToolPolicyEngine.Decision(
                        List.of("project_search"), 12, 1, "test"));
        AgentEngineRegisteredToolGateway gateway = new AgentEngineRegisteredToolGateway(
                json, registry, policies, contexts(VERSION));

        var catalog = gateway.catalog(authority());

        assertThat(catalog.tools()).extracting(tool -> tool.function().name())
                .containsExactly("project_search", "recommend_literature",
                        "search_knowledge", "search_web");
        assertThat(catalog.tools()).allSatisfy(tool -> {
            assertThat(tool.function().parameters().toString()).contains("query");
            assertThat(tool.function().parameters().toString())
                    .doesNotContain("userId", "projectId", "taskId");
        });

        ObjectNode arguments = json.createObjectNode().put("query", "private evidence");
        String digest = ReactPlanCanonicalJson.digest(json,
                Map.of("toolName", "search_knowledge", "arguments", arguments));
        var result = gateway.invoke(authority(), new RegisteredToolCall(
                "1.0", "call." + "c".repeat(40), "search_knowledge", arguments, digest));

        assertThat(result.output().path("observedUserId").asLong()).isEqualTo(11L);
        assertThat(result.output().path("observedProjectId").asLong()).isEqualTo(14L);
        assertThat(ToolExecutionContext.getCurrentUserId()).isNull();
        assertThat(ToolExecutionContext.getCurrentProjectId()).isNull();
    }

    @Test
    void rejectsRetrievalNamesWhenTheirDescriptorBoundaryDrifts() {
        ToolRegistry registry = new ToolRegistry().register(retrievalExecutor(
                "search_web", ToolDescriptor.SideEffectType.MODIFY,
                List.of(ToolDescriptor.ResourceScope.EXTERNAL)));
        AgentToolPolicyEngine policies = mock(AgentToolPolicyEngine.class);
        when(policies.decideProject(null, null)).thenReturn(
                new AgentToolPolicyEngine.Decision(List.of(), 0, 1, "test"));
        AgentEngineRegisteredToolGateway gateway = new AgentEngineRegisteredToolGateway(
                json, registry, policies, contexts(VERSION));

        assertThat(gateway.catalog(authority()).tools()).isEmpty();
    }

    @Test
    void exposesReadOnlyMcpToolsWithoutAddingThemToTheProductPolicyList() {
        ToolRegistry registry = new ToolRegistry()
                .register(mcpExecutor("mcp_github__search_code",
                        ToolDescriptor.SideEffectType.EXTERNAL_READ))
                .register(mcpExecutor("mcp_fs__read_file",
                        ToolDescriptor.SideEffectType.READ_ONLY));
        AgentToolPolicyEngine policies = mock(AgentToolPolicyEngine.class);
        when(policies.decideProject(null, null)).thenReturn(
                new AgentToolPolicyEngine.Decision(List.of(), 0, 1, "test"));
        AgentEngineRegisteredToolGateway gateway = new AgentEngineRegisteredToolGateway(
                json, registry, policies, contexts(VERSION));

        assertThat(gateway.catalog(authority()).tools())
                .extracting(tool -> tool.function().name())
                .containsExactly("mcp_fs__read_file", "mcp_github__search_code");
    }

    @Test
    void exposesGithubMcpToolsOnlyWhenTheCurrentUserHasAUsablePat() {
        ToolRegistry registry = new ToolRegistry()
                .register(mcpExecutor("mcp_github__search_code",
                        ToolDescriptor.SideEffectType.EXTERNAL_READ))
                .register(mcpExecutor("mcp_fs__read_file",
                        ToolDescriptor.SideEffectType.READ_ONLY));
        AgentToolPolicyEngine policies = mock(AgentToolPolicyEngine.class);
        when(policies.decideProject(null, null)).thenReturn(
                new AgentToolPolicyEngine.Decision(List.of(), 0, 1, "test"));
        UserSettingsService settings = mock(UserSettingsService.class);
        AgentEngineRegisteredToolGateway gateway = new AgentEngineRegisteredToolGateway(
                json, registry, policies, contexts(VERSION), settings);

        assertThat(gateway.catalog(authority()).tools())
                .extracting(tool -> tool.function().name())
                .containsExactly("mcp_fs__read_file");

        when(settings.hasUsableGithubPat(11L)).thenReturn(true);
        assertThat(gateway.catalog(authority()).tools())
                .extracting(tool -> tool.function().name())
                .containsExactly("mcp_fs__read_file", "mcp_github__search_code");
    }

    @Test
    void exposesLiteratureTaskBundleAndOwnsStartIdentityArguments() {
        ToolRegistry registry = new ToolRegistry()
                .register(literatureTaskExecutor("literature_search_start",
                        ToolDescriptor.SideEffectType.CREATE,
                        ToolDescriptor.AsyncMode.EXTERNAL_TASK,
                        ToolDescriptor.IdempotencyPolicy.REQUIRED_KEY,
                        List.of(ToolDescriptor.ResourceScope.EXTERNAL,
                                ToolDescriptor.ResourceScope.SESSION,
                                ToolDescriptor.ResourceScope.PROJECT)))
                .register(literatureTaskExecutor("literature_search_status",
                        ToolDescriptor.SideEffectType.NONE, ToolDescriptor.AsyncMode.SYNC,
                        ToolDescriptor.IdempotencyPolicy.NONE,
                        List.of(ToolDescriptor.ResourceScope.SESSION)))
                .register(literatureTaskExecutor("literature_search_result",
                        ToolDescriptor.SideEffectType.NONE, ToolDescriptor.AsyncMode.SYNC,
                        ToolDescriptor.IdempotencyPolicy.NONE,
                        List.of(ToolDescriptor.ResourceScope.SESSION)))
                .register(literatureTaskExecutor("literature_search_cancel",
                        ToolDescriptor.SideEffectType.MODIFY, ToolDescriptor.AsyncMode.SYNC,
                        ToolDescriptor.IdempotencyPolicy.NONE,
                        List.of(ToolDescriptor.ResourceScope.SESSION)));
        AgentToolPolicyEngine policies = mock(AgentToolPolicyEngine.class);
        when(policies.decideProject(null, null)).thenReturn(
                new AgentToolPolicyEngine.Decision(List.of(), 0, 1, "test"));
        AgentEngineRegisteredToolGateway gateway = new AgentEngineRegisteredToolGateway(
                json, registry, policies, contexts(VERSION));

        var catalog = gateway.catalog(authority());
        assertThat(catalog.tools()).extracting(tool -> tool.function().name())
                .containsExactly("literature_search_cancel", "literature_search_result",
                        "literature_search_start", "literature_search_status");
        var start = catalog.tools().stream().filter(tool ->
                tool.function().name().equals("literature_search_start")).findFirst().orElseThrow();
        assertThat(start.function().parameters().toString())
                .contains("query").doesNotContain("clientRequestId", "projectId");

        ObjectNode arguments = json.createObjectNode().put("query", "agent systems");
        String digest = ReactPlanCanonicalJson.digest(json,
                Map.of("toolName", "literature_search_start", "arguments", arguments));
        var result = gateway.invoke(authority(), new RegisteredToolCall(
                "1.0", "call." + "d".repeat(40), "literature_search_start",
                arguments, digest));
        assertThat(result.output().path("arguments").path("projectId").asLong())
                .isEqualTo(14L);
        assertThat(result.output().path("arguments").path("clientRequestId").asText())
                .isEqualTo("agent-engine-" + "d".repeat(40));

        ObjectNode forbidden = json.createObjectNode().put("query", "agent systems")
                .put("projectId", 999L);
        String forbiddenDigest = ReactPlanCanonicalJson.digest(json,
                Map.of("toolName", "literature_search_start", "arguments", forbidden));
        assertThatThrownBy(() -> gateway.invoke(authority(), new RegisteredToolCall(
                "1.0", "call." + "e".repeat(40), "literature_search_start",
                forbidden, forbiddenDigest)))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                "REGISTERED_TOOL_SERVER_ARGUMENT_FORBIDDEN"));
    }

    @Test
    void exposesPaperTaskReadsButNotTheConfirmationGatedCancelTool() {
        ToolRegistry registry = new ToolRegistry()
                .register(retrievalExecutor("paper_polish_status",
                        ToolDescriptor.SideEffectType.NONE,
                        List.of(ToolDescriptor.ResourceScope.SESSION)))
                .register(retrievalExecutor("paper_polish_result",
                        ToolDescriptor.SideEffectType.NONE,
                        List.of(ToolDescriptor.ResourceScope.SESSION)))
                .register(retrievalExecutor("paper_task_cancel",
                        ToolDescriptor.SideEffectType.MODIFY,
                        List.of(ToolDescriptor.ResourceScope.SESSION)));
        AgentToolPolicyEngine policies = mock(AgentToolPolicyEngine.class);
        when(policies.decideProject(null, null)).thenReturn(
                new AgentToolPolicyEngine.Decision(List.of(), 0, 1, "test"));
        AgentEngineRegisteredToolGateway gateway = new AgentEngineRegisteredToolGateway(
                json, registry, policies, contexts(VERSION));

        assertThat(gateway.catalog(authority()).tools())
                .extracting(tool -> tool.function().name())
                .containsExactly("paper_polish_result", "paper_polish_status");
    }

    @Test
    void exposesHistoryToolsAndInjectsUnforgeableTaskScope() {
        ToolRegistry registry = new ToolRegistry()
                .register(historyExecutor(ReactPlanHistoryToolContract.SEARCH_TASKS))
                .register(historyExecutor(ReactPlanHistoryToolContract.GET_TASK))
                .register(historyExecutor(ReactPlanHistoryToolContract.GET_TRACE));
        AgentToolPolicyEngine policies = mock(AgentToolPolicyEngine.class);
        when(policies.decideProject(null, null)).thenReturn(
                new AgentToolPolicyEngine.Decision(List.of(), 0, 1, "test"));
        AgentEngineRegisteredToolGateway gateway = new AgentEngineRegisteredToolGateway(
                json, registry, policies, contexts(VERSION));

        var catalog = gateway.catalog(authority());

        assertThat(catalog.tools()).extracting(tool -> tool.function().name())
                .containsExactly(ReactPlanHistoryToolContract.GET_TASK,
                        ReactPlanHistoryToolContract.GET_TRACE,
                        ReactPlanHistoryToolContract.SEARCH_TASKS);
        assertThat(catalog.tools()).allSatisfy(tool ->
                assertThat(tool.function().parameters().toString())
                        .doesNotContain("_server", "userId", "projectId"));

        ObjectNode arguments = json.createObjectNode().put("scope", "current_project");
        String digest = ReactPlanCanonicalJson.digest(json,
                Map.of("toolName", ReactPlanHistoryToolContract.SEARCH_TASKS,
                        "arguments", arguments));
        var result = gateway.invoke(authority(), new RegisteredToolCall(
                "1.0", "call." + "f".repeat(40),
                ReactPlanHistoryToolContract.SEARCH_TASKS, arguments, digest));
        assertThat(result.output().path("arguments")
                .path(ReactPlanHistoryToolContract.SERVER_TASK_ID).asText()).isEqualTo(TASK);
        assertThat(result.output().path("arguments")
                .path(ReactPlanHistoryToolContract.SERVER_SESSION_ID).asLong()).isEqualTo(13L);
        assertThat(result.output().path("arguments")
                .path(ReactPlanHistoryToolContract.SERVER_PROJECT_ID).asLong()).isEqualTo(14L);

        ObjectNode forged = json.createObjectNode()
                .put(ReactPlanHistoryToolContract.SERVER_PROJECT_ID, 999L);
        String forgedDigest = ReactPlanCanonicalJson.digest(json,
                Map.of("toolName", ReactPlanHistoryToolContract.GET_TASK,
                        "arguments", forged));
        assertThatThrownBy(() -> gateway.invoke(authority(), new RegisteredToolCall(
                "1.0", "call." + "0".repeat(40),
                ReactPlanHistoryToolContract.GET_TASK, forged, forgedDigest)))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                "REGISTERED_TOOL_SERVER_ARGUMENT_FORBIDDEN"));
    }

    @Test
    void rejectsARegisteredToolResultFromAnotherProjectVersion() {
        ToolRegistry registry = new ToolRegistry().register(
                executor("project_search", ToolDescriptor.SideEffectType.READ_ONLY));
        AgentToolPolicyEngine policies = mock(AgentToolPolicyEngine.class);
        when(policies.decideProject(null, null)).thenReturn(
                new AgentToolPolicyEngine.Decision(
                        List.of("project_search"), 12, 1, "test"));
        AgentEngineRegisteredToolGateway gateway = new AgentEngineRegisteredToolGateway(
                json, registry, policies, contexts("4".repeat(64)));

        assertThatThrownBy(() -> gateway.catalog(authority()))
                .isInstanceOfSatisfying(EngineGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("TASK_PROJECT_VERSION_CHANGED"));
    }

    private ToolExecutor executor(String name, ToolDescriptor.SideEffectType sideEffect) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("query").put("type", "string");
        return new ToolExecutor() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "test tool " + name, schema);
            }

            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "v1", "project-read",
                        List.of(ToolDescriptor.CapabilityProfile.PROJECT),
                        List.of("project:read"),
                        List.of(ToolDescriptor.ResourceScope.PROJECT),
                        sideEffect, ToolDescriptor.ConfirmationPolicy.NEVER,
                        ToolDescriptor.AsyncMode.SYNC,
                        ToolDescriptor.IdempotencyPolicy.NONE,
                        ToolDescriptor.RepeatPolicy.ALLOW_LIMITED, true);
            }

            @Override
            public ToolResult execute(ToolCall call) {
                ObjectNode output = json.createObjectNode();
                output.put("projectVersion", VERSION);
                output.putArray("hits").add("services/order-service/pom.xml");
                return new ToolResult(call.id(), name, true, output,
                        null, null, false, List.of("project:14:search"),
                        List.of(), List.of(), VERSION);
            }
        };
    }

    private ToolExecutor retrievalExecutor(
            String name, ToolDescriptor.SideEffectType sideEffect,
            List<ToolDescriptor.ResourceScope> scopes) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("query").put("type", "string");
        schema.putArray("required").add("query");
        return new ToolExecutor() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "test retrieval " + name, schema);
            }

            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "v1", "retrieval",
                        List.of(ToolDescriptor.CapabilityProfile.PROJECT), List.of(), scopes,
                        sideEffect, ToolDescriptor.ConfirmationPolicy.NEVER,
                        ToolDescriptor.AsyncMode.SYNC,
                        ToolDescriptor.IdempotencyPolicy.NONE,
                        ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, true);
            }

            @Override
            public ToolResult execute(ToolCall call) {
                ObjectNode output = json.createObjectNode();
                output.put("observedUserId", ToolExecutionContext.getCurrentUserId());
                output.put("observedProjectId", ToolExecutionContext.getCurrentProjectId());
                return ToolResult.success(call.id(), name, output);
            }
        };
    }

    private ToolExecutor mcpExecutor(
            String name, ToolDescriptor.SideEffectType sideEffect) {
        ObjectNode schema = json.createObjectNode().put("type", "object");
        return new ToolExecutor() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "test MCP tool", schema);
            }

            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "mcp-v1", "mcp-test",
                        List.of(ToolDescriptor.CapabilityProfile.PROJECT),
                        List.of("mcp:read"),
                        List.of(ToolDescriptor.ResourceScope.EXTERNAL),
                        sideEffect, ToolDescriptor.ConfirmationPolicy.NEVER,
                        ToolDescriptor.AsyncMode.SYNC,
                        ToolDescriptor.IdempotencyPolicy.NONE,
                        ToolDescriptor.RepeatPolicy.ALLOW_LIMITED, true);
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.success(call.id(), name, json.createObjectNode());
            }
        };
    }

    private ToolExecutor literatureTaskExecutor(
            String name, ToolDescriptor.SideEffectType sideEffect,
            ToolDescriptor.AsyncMode asyncMode,
            ToolDescriptor.IdempotencyPolicy idempotency,
            List<ToolDescriptor.ResourceScope> scopes) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query").put("type", "string");
        properties.putObject("taskId").put("type", "integer");
        properties.putObject("clientRequestId").put("type", "string");
        properties.putObject("projectId").put("type", "integer");
        return new ToolExecutor() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "test literature task " + name, schema);
            }

            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "v1", "literature-task",
                        List.of(ToolDescriptor.CapabilityProfile.PROJECT), List.of(), scopes,
                        sideEffect, ToolDescriptor.ConfirmationPolicy.NEVER, asyncMode,
                        idempotency, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, true);
            }

            @Override
            public ToolResult execute(ToolCall call) {
                ObjectNode output = json.createObjectNode();
                output.set("arguments", call.arguments());
                return ToolResult.success(call.id(), name, output);
            }
        };
    }

    private ToolExecutor historyExecutor(String name) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("taskId").put("type", "string");
        schema.put("additionalProperties", false);
        return new ToolExecutor() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "test history " + name, schema);
            }

            @Override
            public ToolDescriptor descriptor() {
                return ReactPlanHistoryToolContract.descriptor(name);
            }

            @Override
            public ToolResult execute(ToolCall call) {
                ObjectNode output = json.createObjectNode();
                output.set("arguments", call.arguments());
                return ToolResult.success(call.id(), name, output);
            }
        };
    }

    private static AgentTurnProductContextResolver contexts(String version) {
        AgentTurnProductContextResolver contexts = mock(AgentTurnProductContextResolver.class);
        when(contexts.resolve(11L, 12L)).thenReturn(new VerifiedAgentTurnProductContext(
                new AgentRunIdentity("AGENT_TURN", "12", 11L, 13L, 14L),
                Optional.of(version)));
        return contexts;
    }

    private static EngineTaskAuthority authority() {
        return new EngineTaskAuthority(TASK, "2".repeat(64),
                11, 12, 13, 14, VERSION, true, true,
                Instant.parse("2026-08-16T11:00:00Z"));
    }
}
