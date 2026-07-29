package com.yanban.agent.v2.adapter.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.providers.CorrelationId;
import io.paperagent.v2.providers.GenerationOptions;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelRequestId;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.ProviderFailure;
import io.paperagent.v2.providers.ProviderFailureCode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductChatModelProviderAdapterTest {
    @Test
    void mapsOrderedMessagesToolsBoundsCorrelationAndTransientOwnerEndpoint() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        var adapter = adapter(
                request -> {
                    captured.set(request);
                    return new ChatResponse(
                            new ChatMessage(
                                    "assistant",
                                    null,
                                    List.of(new ToolCall(
                                            "provider-call-1",
                                            "function",
                                            new ToolCall.FunctionCall(
                                                    "literature.search",
                                                    "{\"query\":\"agents\"}"))),
                                    null),
                            "tool_calls",
                            new ChatResponse.Usage(11, 7, 18));
                },
                planId -> {
                    assertEquals("plan-provider", planId.value());
                    return endpoint();
                });

        ModelResponse result = assertInstanceOf(
                ModelResponse.class, adapter.complete(request()));
        ChatRequest mapped = captured.get();
        assertEquals("custom-provider", mapped.provider());
        assertEquals("custom-model", mapped.model());
        assertEquals(List.of("system", "user"),
                mapped.messages().stream().map(ChatMessage::role).toList());
        assertEquals(List.of("first", "second"),
                mapped.messages().stream().map(ChatMessage::content).toList());
        assertEquals(0.25d, mapped.temperature());
        assertEquals(512, mapped.maxTokens());
        assertEquals("literature.search",
                mapped.tools().get(0).function().name());
        assertEquals("correlation-1", mapped.traceId());
        assertEquals("owner-api-key", mapped.apiKey());
        assertEquals("https://owner.example/v1", mapped.apiUrl());
        assertEquals("agents",
                ((io.paperagent.v2.contracts.TextValue)
                        result.proposedToolCalls().get(0)
                                .arguments().values().get("query")).value());
    }

    @Test
    void assistantOnlyMapsWithoutToolAndNullUsageIsBounded() {
        var adapter = adapter(request -> new ChatResponse(
                ChatMessage.assistant("done"), "stop", null));

        ModelResponse result = assertInstanceOf(
                ModelResponse.class, adapter.complete(request()));

        assertEquals(Optional.of("done"), result.assistantText());
        assertEquals(List.of(), result.proposedToolCalls());
        assertEquals(0, result.usage().inputTokens());
    }

    @Test
    void malformedAndThrowingCollaboratorsBecomeSanitizedFailures() {
        var malformed = adapter(request -> new ChatResponse(
                new ChatMessage(
                        "assistant", null,
                        List.of(new ToolCall(
                                "call", "function",
                                new ToolCall.FunctionCall(
                                        "literature.search", "{secret"))),
                        null),
                "tool_calls", null));
        ProviderFailure malformedFailure = assertInstanceOf(
                ProviderFailure.class, malformed.complete(request()));
        assertEquals(ProviderFailureCode.PROTOCOL_VIOLATION,
                malformedFailure.code());
        assertFalse(malformedFailure.toString().contains("secret"));

        var throwing = adapter(request -> {
            throw new IllegalStateException("credential-should-not-leak");
        });
        ProviderFailure failure = assertInstanceOf(
                ProviderFailure.class, throwing.complete(request()));
        assertEquals(ProviderFailureCode.UNAVAILABLE, failure.code());
        assertFalse(failure.toString().contains("credential-should-not-leak"));

        var resolving = adapter(
                request -> {
                    throw new AssertionError("delegate must not be called");
                },
                plan -> {
                    throw new IllegalStateException(
                            "owner-api-key https://owner.example/v1");
                });
        ProviderFailure resolverFailure = assertInstanceOf(
                ProviderFailure.class, resolving.complete(request()));
        assertEquals(ProviderFailureCode.UNAVAILABLE, resolverFailure.code());
        assertFalse(resolverFailure.toString().contains("owner-api-key"));
        assertFalse(resolverFailure.toString().contains("owner.example"));
        assertEquals("ProductModelEndpoint[redacted]", endpoint().toString());
    }

    @Test
    void missingPlanFailsBeforeEndpointResolutionAndProviderInvocation() {
        AtomicReference<String> called = new AtomicReference<>();
        var adapter = adapter(
                request -> {
                    called.set("provider");
                    throw new AssertionError("provider must not be called");
                },
                plan -> {
                    called.set("resolver");
                    throw new AssertionError("resolver must not be called");
                });

        ProviderFailure failure = assertInstanceOf(
                ProviderFailure.class, adapter.complete(requestWithoutPlan()));

        assertEquals(ProviderFailureCode.INVALID_REQUEST, failure.code());
        assertTrue(called.compareAndSet(null, "not-called"));
    }

    private static ProductChatModelProviderAdapter adapter(Chat call) {
        return adapter(call, planId -> endpoint());
    }

    private static ProductChatModelProviderAdapter adapter(
            Chat call, ProductModelEndpointResolver endpoints) {
        ChatModelProvider provider = new ChatModelProvider() {
            @Override
            public String providerName() {
                return "fake";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                return call.apply(request);
            }

            @Override
            public Flux<ChatChunk> streamChat(ChatRequest request) {
                throw new AssertionError("streaming is outside this boundary");
            }
        };
        return new ProductChatModelProviderAdapter(
                provider,
                new ObjectMapper(),
                endpoints);
    }

    private static ModelRequest request() {
        var input = ProductProviderAdapterTestFixtures.input("provider");
        return new ModelRequest(
                new ModelRequestId("request-1"),
                new CorrelationId("correlation-1"),
                List.of(
                        new ModelMessage(MessageRole.SYSTEM, "first"),
                        new ModelMessage(MessageRole.USER, "second")),
                List.of(new ToolDescriptor(
                        new ToolId("literature.search"),
                        "search",
                        Set.of())),
                new GenerationOptions(
                        512, 1, 0.25d, OptionalLong.empty(), Map.of()),
                Optional.of(input.taskFrame().id()),
                Optional.of(input.plan().id()),
                Optional.of(input.plan().latestRevision().id()),
                Optional.of(input.activeStep().id()),
                false);
    }

    private static ModelRequest requestWithoutPlan() {
        ModelRequest source = request();
        return new ModelRequest(
                source.requestId(),
                source.correlationId(),
                source.messages(),
                source.availableTools(),
                source.generationOptions(),
                source.taskFrameId(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false);
    }

    private static ProductModelEndpoint endpoint() {
        return new ProductModelEndpoint(
                "custom-provider",
                "custom-model",
                "owner-api-key",
                "https://owner.example/v1");
    }

    @FunctionalInterface
    private interface Chat {
        ChatResponse apply(ChatRequest request);
    }
}
