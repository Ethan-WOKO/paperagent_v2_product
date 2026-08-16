package com.yanban.api.agent.reactplan.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.AgentToolPolicyEngine;
import com.yanban.api.agent.reactplan.ReactPlanCanonicalJson;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.RegisteredToolCall;
import com.yanban.api.agent.v2.AgentTurnProductContextResolver;
import com.yanban.api.agent.v2.VerifiedAgentTurnProductContext;
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
