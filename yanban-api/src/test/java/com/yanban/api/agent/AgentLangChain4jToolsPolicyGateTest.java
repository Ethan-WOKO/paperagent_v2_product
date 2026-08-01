package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentLangChain4jToolsPolicyGateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearContext() {
        ToolExecutionContext.clear();
    }

    @Test
    void annotationBridgeFailsClosedWithoutCallingPeriodPolicy() {
        AgentLangChain4jTools tools = new AgentLangChain4jTools(registry(ToolDescriptor.SideEffectType.NONE), objectMapper);

        assertThat(tools.searchWeb(7L, "runtime coordinator", 1))
                .contains("success").contains("false").contains("resolved runtime policy");
    }

    @Test
    void nonUserAnnotatedEntryFailsClosedBeforeAnyFilesystemRead() {
        AgentLangChain4jTools tools = new AgentLangChain4jTools(registry(ToolDescriptor.SideEffectType.NONE), objectMapper);

        assertThat(tools.readFile("definitely-not-read.txt", 10))
                .contains("success").contains("false").contains("resolved runtime policy");
    }

    @Test
    void userAnnotatedEntryFailsClosedBeforeAnyKnowledgeLookup() {
        AgentLangChain4jTools tools = new AgentLangChain4jTools(registry(ToolDescriptor.SideEffectType.NONE), objectMapper);

        assertThat(tools.readDocument(7L, 1L, null, null, 10))
                .contains("success").contains("false").contains("resolved runtime policy");
    }

    @Test
    void allowlistedLocalAnnotationToolWithoutDescriptorCannotReadExistingFile() {
        AgentLangChain4jTools tools = new AgentLangChain4jTools(registry(ToolDescriptor.SideEffectType.NONE), objectMapper);
        ToolExecutionContext.setResolvedAllowedTools(Set.of("read_file"));

        assertThat(tools.readFile("pom.xml", 10))
                .contains("success").contains("false").contains("not governed")
                .doesNotContain("<project");
    }

    @Test
    void annotationBridgeExecutesOnlyWhenPolicyAndUserMatch() {
        AgentLangChain4jTools tools = new AgentLangChain4jTools(registry(ToolDescriptor.SideEffectType.NONE), objectMapper);
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setResolvedAllowedTools(Set.of("search_web"));

        assertThat(tools.searchWeb(7L, "runtime coordinator", 1)).contains("success").contains("true");
        assertThat(tools.searchWeb(8L, "runtime coordinator", 1)).contains("success").contains("false");
    }

    @Test
    void unknownOrUnallowedAnnotationToolFailsClosed() {
        AgentLangChain4jTools unknown = new AgentLangChain4jTools(registry(ToolDescriptor.SideEffectType.UNKNOWN), objectMapper);
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setResolvedAllowedTools(Set.of("search_web"));

        assertThat(unknown.searchWeb(7L, "runtime coordinator", 1)).contains("success").contains("false");
        ToolExecutionContext.setResolvedAllowedTools(Set.of("other_tool"));
        assertThat(unknown.searchWeb(7L, "runtime coordinator", 1)).contains("success").contains("false");
    }

    @Test
    void confirmationRequiredAnnotationToolFailsClosedEvenWhenAllowlisted() {
        AgentLangChain4jTools tools = new AgentLangChain4jTools(
                registry(ToolDescriptor.SideEffectType.NONE, ToolDescriptor.ConfirmationPolicy.ALWAYS), objectMapper);
        ToolExecutionContext.setCurrentUserId(7L);
        ToolExecutionContext.setResolvedAllowedTools(Set.of("search_web"));

        assertThat(tools.searchWeb(7L, "runtime coordinator", 1))
                .contains("success").contains("false").contains("not governed");
    }

    private ToolRegistry registry(ToolDescriptor.SideEffectType effect) {
        return registry(effect, ToolDescriptor.ConfirmationPolicy.NEVER);
    }

    private ToolRegistry registry(ToolDescriptor.SideEffectType effect,
                                  ToolDescriptor.ConfirmationPolicy confirmationPolicy) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolExecutor() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("search_web", "search", objectMapper.createObjectNode().put("type", "object"));
            }

            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("search_web", "test", "test", List.of(ToolDescriptor.CapabilityProfile.CHAT),
                        List.of(), List.of(ToolDescriptor.ResourceScope.EXTERNAL), effect,
                        confirmationPolicy, ToolDescriptor.AsyncMode.SYNC,
                        ToolDescriptor.IdempotencyPolicy.NONE, ToolDescriptor.RepeatPolicy.DENY_SAME_INPUT, true);
            }

            @Override
            public ToolResult execute(ToolCall call) {
                return ToolResult.success(call.id(), call.name(), objectMapper.createObjectNode().put("success", true));
            }
        });
        return registry;
    }
}
