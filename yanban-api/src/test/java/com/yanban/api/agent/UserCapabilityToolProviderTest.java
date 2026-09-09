package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.history.GetPastConversationToolExecutor;
import com.yanban.api.agent.history.PastConversationHistoryService;
import com.yanban.api.agent.history.SearchPastConversationsToolExecutor;
import com.yanban.core.tool.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserCapabilityToolProviderTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void nativeChatCanAnswerNormallyWithOneModelCallAndZeroHistoryCalls() {
        var history = mock(PastConversationHistoryService.class);
        var registry = new ToolRegistry().register(new SearchPastConversationsToolExecutor(json, history));
        var policy = new AgentToolPolicyEngine(registry).decide("你好", false, null);
        var runtime = new AgentRuntimeRequest(AgentStrategy.AUTO, 9L, List.of(), 7L, "你好", "test", "model",
                0.0, 512, 6, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, policy.resolved(), 6, 1, "reused-client-trace", null, null);
        var decision = new AgentStrategySelector().decide(AgentCoordinationRequest.chat(runtime));
        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.SINGLE_STEP_REACT);
        var model = mock(LangChain4jChatModelAdapter.class);
        org.mockito.Mockito.when(model.chat(org.mockito.ArgumentMatchers.any(dev.langchain4j.model.chat.request.ChatRequest.class),
                org.mockito.ArgumentMatchers.any(AgentRuntimeRequest.class))).thenReturn(
                        dev.langchain4j.model.chat.response.ChatResponse.builder()
                                .aiMessage(dev.langchain4j.data.message.AiMessage.from("你好，有什么可以帮你？")).build());
        var result = new LangChain4jToolCallingStrategy(model, new LangChain4jToolProvider(registry, json, null), json)
                .run(runtime.withStrategy(decision.selectedStrategy()));
        assertThat(result.success()).isTrue();
        assertThat(result.toolTrace()).isEmpty();
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(1)).chat(
                org.mockito.ArgumentMatchers.any(dev.langchain4j.model.chat.request.ChatRequest.class),
                org.mockito.ArgumentMatchers.any(AgentRuntimeRequest.class));
        org.mockito.Mockito.verifyNoInteractions(history);
        assertThat(runtime.withInvocationScope(null).invocationScope()).isNotEqualTo(runtime.invocationScope());
        assertThat(runtime.withStrategy(AgentStrategy.SINGLE_STEP_REACT).invocationScope()).isEqualTo(runtime.invocationScope());
    }

    @Test
    void chatPolicyExposesPaperAndHistoryButEmptySkillStillDeniesAll() {
        var history = mock(PastConversationHistoryService.class);
        ToolRegistry registry = new ToolRegistry()
                .register(new PaperPolishStartToolExecutor(mock(PaperPolishStartService.class), json))
                .register(new SearchPastConversationsToolExecutor(json, history))
                .register(new GetPastConversationToolExecutor(json, history));
        var policy = new AgentToolPolicyEngine(registry);
        assertThat(policy.decide("help", false, null).allowedTools()).containsExactlyInAnyOrder(
                "paper_polish_start", "search_past_conversations", "get_past_conversation");
        assertThat(policy.decide("help", false, Set.of()).allowedTools()).isEmpty();
    }

    @Test
    void paperInvocationUsesStableServerScopeAndRejectsModelAuthorityOverrides() {
        List<String> scopes = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        ToolExecutor capture = new ToolExecutor() {
            public ToolDefinition definition() { return new ToolDefinition("paper_polish_start", "fixture",
                    json.createObjectNode().put("type", "object").set("properties", json.createObjectNode())); }
            public ToolDescriptor descriptor() { return PaperPolishToolContract.descriptor("paper_polish_start"); }
            public ToolResult execute(ToolCall call) {
                scopes.add(ToolExecutionContext.getInvocationScope());
                calls.add(call);
                assertThat(ToolExecutionContext.getCurrentUserId()).isEqualTo(7L);
                assertThat(ToolExecutionContext.getCurrentProjectId()).isEqualTo(21L);
                return ToolResult.success(call.id(), call.name(), json.createObjectNode().put("taskId", 42));
            }
        };
        var provider = new LangChain4jToolProvider(new ToolRegistry().register(capture), json, null);
        var runtime = new AgentRuntimeRequest(AgentStrategy.SINGLE_STEP_REACT, 9L, List.of(), 7L,
                "Polish paper", "test", "model", 0.0, 512, 2, true,
                null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("paper_polish_start"), 6, 1, "test"),
                6, 1, "server-turn-1", null, null)
                .withInvocationScope("chat-turn:100")
                .withProjectContext(new ProjectRuntimeContext(7L, 21L, "a".repeat(64)))
                .withStrategy(AgentStrategy.SINGLE_STEP_REACT)
                .withReducedBudget(1, 3)
                .withAdditionalSystemInstruction("bounded repair");
        var executor = provider.provideTools(runtime).toolExecutorByName("paper_polish_start");
        for (String id : List.of("model-call-1", "model-call-2")) {
            executor.execute(request(id, "{}"), 9L);
            assertThat(ToolExecutionContext.getCurrentUserId()).isNull();
            assertThat(ToolExecutionContext.getInvocationScope()).isNull();
        }
        assertThat(scopes).containsExactly("chat-turn:100", "chat-turn:100");
        assertThat(calls).allSatisfy(call -> assertThat(call.arguments().path("expectedProjectVersion").asText())
                .isEqualTo("a".repeat(64)));
        for (String field : List.of("clientRequestId", "expectedProjectVersion")) {
            assertThat(executor.execute(request("forged", "{\"" + field + "\":\"forged\"}"), 9L))
                    .contains("server-owned");
        }
        assertThat(calls).hasSize(2);
        assertThat(ToolExecutionContext.getInvocationScope()).isNull();
    }

    private ToolExecutionRequest request(String id, String args) {
        return ToolExecutionRequest.builder().id(id).name("paper_polish_start").arguments(args).build();
    }
}
