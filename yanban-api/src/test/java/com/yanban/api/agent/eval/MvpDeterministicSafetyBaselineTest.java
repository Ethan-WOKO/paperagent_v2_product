package com.yanban.api.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentLangChain4jTools;
import com.yanban.api.agent.AgentRuntimeMode;
import com.yanban.api.agent.AgentRuntimeRequest;
import com.yanban.api.agent.AgentStrategy;
import com.yanban.api.agent.AgentToolCallingMode;
import com.yanban.api.agent.LangChain4jToolProvider;
import com.yanban.api.agent.PlanningAgentPlanner;
import com.yanban.api.agent.ResolvedToolPolicy;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.tool.EchoToolExecutor;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Passing, deterministic MVP-0 checks only. Deferred security contracts live in
 * {@link MvpSafetyContractPendingTest} and are reported as NOT_IMPLEMENTED.
 */
class MvpDeterministicSafetyBaselineTest {

    @Test
    void nullIsOnlyMergeInheritanceAndRuntimeCompatibilityIsDenyAll() {
        ResolvedToolPolicy inherited = ResolvedToolPolicy.resolve(List.of("search_web"), null, 2, 1, "inherit");
        ResolvedToolPolicy denied = ResolvedToolPolicy.resolve(List.of("search_web"), List.of(), 2, 1, "deny");
        AgentRuntimeRequest legacyNullRequest = legacyRequest(null);

        assertThat(inherited.allowedTools()).containsExactly("search_web");
        assertThat(denied.allowedTools()).isEmpty();
        assertThat(legacyNullRequest.toolPolicy().allowedTools()).isEmpty();
        assertThat(legacyNullRequest.toolPolicy().reason()).isEqualTo("legacy_runtime_request");
    }

    @Test
    void plannerCannotEscalateAnExplicitStepDenyAllThroughLegacyAlias() {
        ChatModelProvider modelProvider = mock(ChatModelProvider.class);
        when(modelProvider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("""
                {"summary":"attempt escalation","steps":[{
                  "id":"one","title":"No tools","description":"Answer directly.","type":"ANALYSIS",
                  "dependencies":[],"allowedTools":[],"allowed_tools":["search_web"],
                  "successCriteria":"A direct answer is returned."}]}
                """), "stop", null));

        PlanningAgentPlanner.PlanSpec plan = new PlanningAgentPlanner(modelProvider, new ObjectMapper()).createPlan(
                "Explain the architecture.", "test", "test-model", null, null, null, List.of("search_web"));

        assertThat(plan.steps()).singleElement().satisfies(step -> assertThat(step.allowedTools()).isEmpty());
    }

    @Test
    void workspaceToolRejectsAbsoluteAndTraversalPathsBeforeFileAccess() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry().register(new ToolExecutor() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("read_file", "test read", objectMapper.createObjectNode().put("type", "object"));
            }

            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("read_file", "test", "test", List.of(ToolDescriptor.CapabilityProfile.CHAT),
                        List.of(), List.of(ToolDescriptor.ResourceScope.PROJECT), ToolDescriptor.SideEffectType.NONE,
                        ToolDescriptor.ConfirmationPolicy.NEVER, ToolDescriptor.AsyncMode.SYNC,
                        ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, true);
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.success(call.id(), call.name(), objectMapper.createObjectNode());
            }
        });
        AgentLangChain4jTools tools = new AgentLangChain4jTools(registry, objectMapper,
                null, null, null, null);
        ToolExecutionContext.setResolvedAllowedTools(Set.of("read_file"));
        try {
            assertThat(tools.readFile(Path.of("").toAbsolutePath().normalize().toString(), null))
                    .contains("\"success\":false", "absolute paths are not allowed");
            assertThat(tools.readFile("../outside.txt", null))
                    .contains("\"success\":false", "path escapes workspace");
        } finally {
            ToolExecutionContext.clear();
        }
    }

    @Test
    void denyAllPolicyDoesNotExposeARegisteredToolToTheModel() {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry().register(new EchoToolExecutor(objectMapper));
        AgentRuntimeRequest denyAllRequest = legacyRequest(null);
        LangChain4jToolProvider provider = new LangChain4jToolProvider(
                registry, objectMapper, new AgentLangChain4jTools(registry, objectMapper, null, null, null, null));

        assertThat(provider.provideTools(denyAllRequest).tools()).isEmpty();
        assertThat(provider.provideTools(denyAllRequest, Set.of()).tools()).isEmpty();
    }

    private AgentRuntimeRequest legacyRequest(List<String> allowedTools) {
        return new AgentRuntimeRequest(
                AgentStrategy.DIRECT, 1L, List.of(), 1L, "answer safely", "test", "test-model",
                null, null, 1, false, null, null, null, null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                allowedTools, 9, 9, "eval", null, null);
    }
}
