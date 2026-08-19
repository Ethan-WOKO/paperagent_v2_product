package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import com.yanban.core.model.ToolSpec;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

@Component
public class LangChain4jChatModelAdapter implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jChatModelAdapter.class);

    private final ChatModelProvider chatModelProvider;
    private final ObjectMapper objectMapper;

    public LangChain4jChatModelAdapter(ChatModelProvider chatModelProvider, ObjectMapper objectMapper) {
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public dev.langchain4j.model.chat.response.ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request) {
        ChatResponse response = chatModelProvider.chat(toCoreChatRequest(request, null));
        return toLangChain4jResponse(request, response);
    }

    public dev.langchain4j.model.chat.response.ChatResponse chat(dev.langchain4j.model.chat.request.ChatRequest request,
                                                                 ModelInvocationContext context) {
        ChatResponse response = chatModelProvider.chat(toCoreChatRequest(request, context));
        return toLangChain4jResponse(request, response);
    }

    /** Compatibility entry used only by the restored legacy workspace Agent. */
    public dev.langchain4j.model.chat.response.ChatResponse chat(
            dev.langchain4j.model.chat.request.ChatRequest request,
            AgentRuntimeRequest runtimeRequest) {
        return chat(request, invocationContext(runtimeRequest));
    }

    public Flux<ChatChunk> stream(dev.langchain4j.model.chat.request.ChatRequest request,
                                  ModelInvocationContext context) {
        ChatRequest coreRequest = toCoreChatRequest(request, context);
        AtomicBoolean emitted = new AtomicBoolean(false);
        return chatModelProvider.streamChat(coreRequest)
                .doOnNext(chunk -> {
                    if (chunk != null) {
                        emitted.set(true);
                    }
                })
                .onErrorResume(ex -> shouldFallbackToNonStreaming(ex) && !emitted.get(), ex -> {
                    log.warn("Streaming chat failed before first chunk, falling back to non-streaming provider={} model={} error={}",
                            coreRequest.provider(),
                            coreRequest.model(),
                            ex.getMessage());
                    return Flux.fromIterable(toFallbackChunks(chatModelProvider.chat(coreRequest)));
                });
    }

    /** Compatibility entry used only by the restored legacy workspace Agent. */
    public Flux<ChatChunk> stream(
            dev.langchain4j.model.chat.request.ChatRequest request,
            AgentRuntimeRequest runtimeRequest) {
        return stream(request, invocationContext(runtimeRequest));
    }

    private ModelInvocationContext invocationContext(AgentRuntimeRequest runtimeRequest) {
        if (runtimeRequest == null) {
            return null;
        }
        return new ModelInvocationContext(
                runtimeRequest.provider(),
                runtimeRequest.apiKey(),
                runtimeRequest.apiUrl(),
                runtimeRequest.traceId());
    }

    private ChatRequest toCoreChatRequest(dev.langchain4j.model.chat.request.ChatRequest request,
                                          ModelInvocationContext context) {
        return new ChatRequest(
                context == null ? null : context.provider(),
                request == null ? null : request.modelName(),
                toCoreMessages(request == null ? List.of() : request.messages()),
                request == null ? null : request.temperature(),
                request == null ? null : request.maxOutputTokens(),
                toCoreToolSpecs(request == null ? List.of() : request.toolSpecifications()),
                context == null ? null : context.apiKey(),
                context == null ? null : context.apiUrl(),
                null,
                null,
                context == null ? null : context.traceId()
        );
    }

    private dev.langchain4j.model.chat.response.ChatResponse toLangChain4jResponse(
            dev.langchain4j.model.chat.request.ChatRequest request,
            ChatResponse response) {
        com.yanban.core.model.ChatMessage message = response == null ? null : response.message();
        AiMessage aiMessage = toAiMessage(message);
        ChatResponse.Usage usage = response == null ? null : response.usage();
        return dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(aiMessage)
                .modelName(request == null ? null : request.modelName())
                .tokenUsage(usage == null ? null : new TokenUsage(
                        usage.promptTokens(),
                        usage.completionTokens(),
                        usage.totalTokens()
                ))
                .finishReason(toFinishReason(response == null ? null : response.finishReason()))
                .build();
    }

    private List<ChatMessage> toCoreMessages(List<dev.langchain4j.data.message.ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> converted = new ArrayList<>();
        for (dev.langchain4j.data.message.ChatMessage message : messages) {
            if (message instanceof UserMessage userMessage) {
                converted.add(ChatMessage.user(userMessage.singleText()));
            } else if (message instanceof SystemMessage systemMessage) {
                converted.add(ChatMessage.system(systemMessage.text()));
            } else if (message instanceof ToolExecutionResultMessage toolMessage) {
                converted.add(ChatMessage.tool(toolMessage.id(), toolMessage.text()));
            } else if (message instanceof AiMessage aiMessage) {
                List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
                List<ToolCall> toolCalls = requests == null || requests.isEmpty()
                        ? null
                        : requests.stream()
                        .map(request -> new ToolCall(
                                request.id(),
                                "function",
                                new ToolCall.FunctionCall(request.name(), request.arguments())
                        ))
                        .toList();
                converted.add(new ChatMessage("assistant", aiMessage.text(), toolCalls, null));
            }
        }
        return converted;
    }

    private List<ToolSpec> toCoreToolSpecs(List<ToolSpecification> toolSpecifications) {
        if (toolSpecifications == null || toolSpecifications.isEmpty()) {
            return null;
        }
        return toolSpecifications.stream()
                .map(specification -> ToolSpec.function(
                        specification.name(),
                        specification.description(),
                        toJsonSchema(specification.parameters())
                ))
                .toList();
    }

    private AiMessage toAiMessage(com.yanban.core.model.ChatMessage message) {
        if (message == null) {
            return AiMessage.from("");
        }
        List<ToolCall> toolCalls = message.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return AiMessage.from(defaultString(message.content()));
        }
        List<ToolExecutionRequest> requests = toolCalls.stream()
                .filter(call -> call.function() != null && StringUtils.hasText(call.function().name()))
                .map(call -> ToolExecutionRequest.builder()
                        .id(call.id())
                        .name(call.function().name())
                        .arguments(defaultString(call.function().arguments(), "{}"))
                        .build())
                .toList();
        return AiMessage.from(defaultString(message.content()), requests);
    }

    private JsonNode toJsonSchema(JsonObjectSchema schema) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        if (StringUtils.hasText(schema.description())) {
            root.put("description", schema.description());
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, JsonSchemaElement> entry : schema.properties().entrySet()) {
            properties.put(entry.getKey(), toJsonSchemaElement(entry.getValue()));
        }
        root.put("properties", properties);
        root.put("required", schema.required());
        if (schema.additionalProperties() != null) {
            root.put("additionalProperties", schema.additionalProperties());
        }
        return objectMapper.valueToTree(root);
    }

    private Object toJsonSchemaElement(JsonSchemaElement element) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (element instanceof JsonArraySchema arraySchema) {
            schema.put("type", "array");
            if (StringUtils.hasText(arraySchema.description())) {
                schema.put("description", arraySchema.description());
            }
            schema.put("items", toJsonSchemaElement(arraySchema.items()));
            return schema;
        }
        if (element instanceof JsonStringSchema stringSchema) {
            schema.put("type", "string");
            if (StringUtils.hasText(stringSchema.description())) {
                schema.put("description", stringSchema.description());
            }
            return schema;
        }
        if (element instanceof JsonIntegerSchema integerSchema) {
            schema.put("type", "integer");
            if (StringUtils.hasText(integerSchema.description())) {
                schema.put("description", integerSchema.description());
            }
            return schema;
        }
        if (element instanceof JsonBooleanSchema booleanSchema) {
            schema.put("type", "boolean");
            if (StringUtils.hasText(booleanSchema.description())) {
                schema.put("description", booleanSchema.description());
            }
            return schema;
        }
        if (element instanceof JsonNumberSchema numberSchema) {
            schema.put("type", "number");
            if (StringUtils.hasText(numberSchema.description())) {
                schema.put("description", numberSchema.description());
            }
            return schema;
        }
        if (element instanceof JsonObjectSchema objectSchema) {
            return toJsonSchema(objectSchema);
        }
        schema.put("type", "string");
        if (element != null && StringUtils.hasText(element.description())) {
            schema.put("description", element.description());
        }
        return schema;
    }

    private FinishReason toFinishReason(String finishReason) {
        if (!StringUtils.hasText(finishReason)) {
            return FinishReason.STOP;
        }
        return switch (finishReason.trim().toLowerCase(Locale.ROOT)) {
            case "length" -> FinishReason.LENGTH;
            case "tool_calls", "tool_call", "function_call" -> FinishReason.TOOL_EXECUTION;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            case "stop" -> FinishReason.STOP;
            default -> FinishReason.OTHER;
        };
    }

    private String defaultString(String value) {
        return defaultString(value, "");
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private boolean shouldFallbackToNonStreaming(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            String className = current.getClass().getName();
            if (StringUtils.hasText(message) && (
                    message.contains("PrematureCloseException")
                            || message.contains("Connection prematurely closed")
                            || message.contains("stream failed"))) {
                return true;
            }
            if (className.contains("PrematureCloseException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private List<ChatChunk> toFallbackChunks(ChatResponse response) {
        if (response == null || response.message() == null) {
            return List.of(ChatChunk.done("stop"));
        }
        List<ChatChunk> chunks = new ArrayList<>();
        if (StringUtils.hasText(response.message().content())) {
            chunks.add(ChatChunk.token(response.message().content()));
        }
        if (response.message().toolCalls() != null) {
            for (int i = 0; i < response.message().toolCalls().size(); i++) {
                ToolCall toolCall = response.message().toolCalls().get(i);
                if (toolCall == null || toolCall.function() == null) {
                    continue;
                }
                chunks.add(ChatChunk.toolCallDelta(new ChatChunk.ToolCallDelta(
                        i,
                        toolCall.id(),
                        toolCall.type(),
                        toolCall.function().name(),
                        toolCall.function().arguments()
                )));
            }
        }
        if (response.usage() != null) {
            chunks.add(ChatChunk.usage(response.usage()));
        }
        chunks.add(ChatChunk.done(defaultString(response.finishReason(), "stop")));
        return chunks;
    }
}
