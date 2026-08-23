package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ModelProviderException;
import com.yanban.core.model.ToolCall;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

class LangChain4jChatModelAdapterTest {

    @Test
    void fallsBackToNonStreamingChatWhenStreamFailsBeforeFirstChunk() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.streamChat(any()))
                .thenReturn(Flux.error(new ModelProviderException(
                        "DeepSeek API stream failed",
                        new RuntimeException("Connection prematurely closed DURING response"))));
        when(provider.chat(any()))
                .thenReturn(new ChatResponse(
                        ChatMessage.assistant("fallback answer"),
                        "stop",
                        new ChatResponse.Usage(12, 3, 15)));
        LangChain4jChatModelAdapter adapter = new LangChain4jChatModelAdapter(provider, new ObjectMapper());

        List<ChatChunk> chunks = adapter.stream(request(), runtimeRequest())
                .collectList()
                .block();

        assertThat(chunks).extracting(ChatChunk::content)
                .containsExactly("fallback answer", null, null);
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.usage()).isNotNull();
            assertThat(chunk.usage().totalTokens()).isEqualTo(15);
        });
        assertThat(chunks).last().matches(ChatChunk::done);
        verify(provider).chat(any());
    }

    @Test
    void doesNotFallbackAfterPartialStreamWasAlreadyDelivered() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.streamChat(any()))
                .thenReturn(Flux.concat(
                        Flux.just(ChatChunk.token("partial")),
                        Flux.error(new ModelProviderException(
                                "DeepSeek API stream failed",
                                new RuntimeException("Connection prematurely closed DURING response")))));
        LangChain4jChatModelAdapter adapter = new LangChain4jChatModelAdapter(provider, new ObjectMapper());

        assertThatThrownBy(() -> adapter.stream(request(), runtimeRequest()).collectList().block())
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("DeepSeek API stream failed");
        verify(provider, never()).chat(any());
    }

    @Test
    void preservesArrayToolSchemaInCoreModelRequest() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("done"), "stop", null));
        LangChain4jChatModelAdapter adapter = new LangChain4jChatModelAdapter(provider, new ObjectMapper());
        ToolSpecification tool = ToolSpecification.builder()
                .name("project_latex_outline")
                .description("outline")
                .parameters(JsonObjectSchema.builder()
                        .addProperty("relativePaths", JsonArraySchema.builder()
                                .items(JsonStringSchema.builder().build())
                                .build())
                        .required("relativePaths")
                        .build())
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("outline the paper")))
                .parameters(ChatRequestParameters.builder()
                        .modelName("deepseek-v4-flash")
                        .toolSpecifications(List.of(tool))
                        .build())
                .build();

        adapter.chat(request, runtimeRequest());

        ArgumentCaptor<com.yanban.core.model.ChatRequest> requestCaptor =
                ArgumentCaptor.forClass(com.yanban.core.model.ChatRequest.class);
        verify(provider).chat(requestCaptor.capture());
        var relativePaths = requestCaptor.getValue().tools().get(0).function().parameters()
                .path("properties").path("relativePaths");
        assertThat(relativePaths.path("type").asText()).isEqualTo("array");
        assertThat(relativePaths.path("items").path("type").asText()).isEqualTo("string");
    }

    @Test
    void rejectsBlankCompletionWithoutToolCalls() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chat(any())).thenReturn(new ChatResponse(
                ChatMessage.assistant("  "), "length", new ChatResponse.Usage(100, 4096, 4196)));
        LangChain4jChatModelAdapter adapter = new LangChain4jChatModelAdapter(provider, new ObjectMapper());

        assertThatThrownBy(() -> adapter.chat(request(), runtimeRequest()))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("empty response without tool calls")
                .hasMessageContaining("finishReason=length");
    }

    @Test
    void mapsPaperThinkingDisabledFlagToCoreRequest() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(provider.chat(any())).thenReturn(new ChatResponse(ChatMessage.assistant("done"), "stop", null));
        LangChain4jChatModelAdapter adapter = new LangChain4jChatModelAdapter(provider, new ObjectMapper());

        adapter.chat(request(), new ModelInvocationContext(
                "deepseek", null, null, "paper-model-call", null, true));

        ArgumentCaptor<com.yanban.core.model.ChatRequest> captor =
                ArgumentCaptor.forClass(com.yanban.core.model.ChatRequest.class);
        verify(provider).chat(captor.capture());
        assertThat(captor.getValue().thinking()).isEqualTo(com.yanban.core.model.ChatRequest.Thinking.disabled());
    }

    @Test
    void acceptsToolCallResponseWithoutText() {
        ChatModelProvider provider = mock(ChatModelProvider.class);
        ToolCall toolCall = new ToolCall("call-1", "function", new ToolCall.FunctionCall("project_manifest", "{}"));
        when(provider.chat(any())).thenReturn(new ChatResponse(
                new ChatMessage("assistant", "", List.of(toolCall), null), "tool_calls", null));
        LangChain4jChatModelAdapter adapter = new LangChain4jChatModelAdapter(provider, new ObjectMapper());

        var response = adapter.chat(request(), runtimeRequest());

        assertThat(response.aiMessage().toolExecutionRequests()).hasSize(1);
    }

    private ChatRequest request() {
        return ChatRequest.builder()
                .messages(List.of(UserMessage.from("hello")))
                .parameters(ChatRequestParameters.builder()
                        .modelName("deepseek-v4-flash")
                        .build())
                .build();
    }

    private ModelInvocationContext runtimeRequest() {
        return new ModelInvocationContext(
                "deepseek",
                null,
                null,
                "trace-stream-fallback"
        );
    }
}
