package com.yanban.agent.v2.adapter.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import com.yanban.core.model.ToolCall;
import com.yanban.core.model.ToolSpec;
import io.paperagent.v2.contracts.BooleanValue;
import io.paperagent.v2.contracts.ContractValue;
import io.paperagent.v2.contracts.ListValue;
import io.paperagent.v2.contracts.NullValue;
import io.paperagent.v2.contracts.NumberValue;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.providers.FinishReason;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.providers.ModelProviderResult;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.ProposedToolCall;
import io.paperagent.v2.providers.ProviderFailure;
import io.paperagent.v2.providers.ProviderFailureCode;
import io.paperagent.v2.providers.UsageMetadata;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Credential-free bridge from the stable V2 model port to the product's
 * general synchronous chat provider.
 */
public final class ProductChatModelProviderAdapter implements ModelProvider {
    private static final String FAILURE_MESSAGE = "product model turn failed";

    private final ChatModelProvider delegate;
    private final ObjectMapper json;
    private final ProductModelProviderConfiguration configuration;

    public ProductChatModelProviderAdapter(
            ChatModelProvider delegate,
            ObjectMapper json,
            ProductModelProviderConfiguration configuration) {
        if (delegate == null || json == null || configuration == null) {
            throw new ProductStepTurnException(
                    ProductStepTurnError.INVALID_CONFIGURATION,
                    "productModelProvider");
        }
        this.delegate = delegate;
        this.json = json.copy();
        this.configuration = configuration;
    }

    @Override
    public ModelProviderResult complete(ModelRequest request) {
        if (request == null) {
            return failure(ProviderFailureCode.INVALID_REQUEST);
        }
        if (request.cancellationRequested()) {
            return failure(ProviderFailureCode.CANCELLED);
        }
        ChatResponse response;
        try {
            response = delegate.chat(mapRequest(request));
        } catch (RuntimeException exception) {
            return failure(ProviderFailureCode.UNAVAILABLE);
        }
        try {
            return mapResponse(response);
        } catch (RuntimeException exception) {
            return failure(ProviderFailureCode.PROTOCOL_VIOLATION);
        }
    }

    private ChatRequest mapRequest(ModelRequest request) {
        List<ChatMessage> messages = request.messages().stream()
                .map(this::message)
                .toList();
        List<ToolSpec> tools = request.availableTools().stream()
                .map(this::tool)
                .toList();
        return new ChatRequest(
                configuration.provider(),
                configuration.model(),
                messages,
                request.generationOptions().temperature(),
                request.generationOptions().maxOutputTokens(),
                tools,
                null,
                null,
                null,
                ChatRequest.Thinking.disabled(),
                request.correlationId().value());
    }

    private ChatMessage message(ModelMessage message) {
        return switch (message.role()) {
            case SYSTEM -> ChatMessage.system(message.content());
            case USER -> ChatMessage.user(message.content());
            case ASSISTANT -> ChatMessage.assistant(message.content());
            case TOOL_FACT -> ChatMessage.process(message.content());
        };
    }

    private ToolSpec tool(ToolDescriptor descriptor) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        return ToolSpec.function(
                descriptor.id().value(), descriptor.description(), schema);
    }

    private ModelResponse mapResponse(ChatResponse response) {
        if (response == null || response.message() == null) {
            throw malformed();
        }
        Optional<String> text = optionalText(response.assistantText());
        List<ToolCall> calls = response.toolCalls() == null
                ? List.of() : List.copyOf(response.toolCalls());
        List<ProposedToolCall> proposed = new ArrayList<>();
        for (ToolCall call : calls) {
            if (call == null
                    || !"function".equals(call.type())
                    || call.function() == null) {
                throw malformed();
            }
            proposed.add(new ProposedToolCall(
                    required(call.id()),
                    new ToolId(required(call.function().name())),
                    arguments(call.function().arguments())));
        }
        FinishReason finishReason = finishReason(response.finishReason());
        if ((proposed.isEmpty() && finishReason == FinishReason.TOOL_CALLS)
                || (!proposed.isEmpty()
                        && finishReason != FinishReason.TOOL_CALLS)) {
            throw malformed();
        }
        ChatResponse.Usage usage = response.usage();
        return new ModelResponse(
                text,
                proposed,
                finishReason,
                new UsageMetadata(
                        nonNegative(usage == null ? null : usage.promptTokens()),
                        nonNegative(usage == null ? null : usage.completionTokens()),
                        0,
                        Map.of()),
                Map.of());
    }

    private ObjectValue arguments(String value) {
        try {
            JsonNode node = json.readTree(required(value));
            if (node == null || !node.isObject()) {
                throw malformed();
            }
            return (ObjectValue) contractValue(node);
        } catch (JsonProcessingException exception) {
            throw malformed();
        }
    }

    private ContractValue contractValue(JsonNode node) {
        if (node.isNull()) {
            return NullValue.INSTANCE;
        }
        if (node.isTextual()) {
            return new TextValue(node.textValue());
        }
        if (node.isBoolean()) {
            return new BooleanValue(node.booleanValue());
        }
        if (node.isNumber()) {
            return new NumberValue(new BigDecimal(node.asText()));
        }
        if (node.isArray()) {
            List<ContractValue> values = new ArrayList<>();
            node.forEach(child -> values.add(contractValue(child)));
            return new ListValue(values);
        }
        if (node.isObject()) {
            Map<String, ContractValue> values = new LinkedHashMap<>();
            node.fields().forEachRemaining(
                    entry -> values.put(entry.getKey(), contractValue(entry.getValue())));
            return new ObjectValue(values);
        }
        throw malformed();
    }

    private static Optional<String> optionalText(String value) {
        return value == null || value.isBlank()
                ? Optional.empty() : Optional.of(value);
    }

    private static FinishReason finishReason(String value) {
        if ("stop".equalsIgnoreCase(value)) {
            return FinishReason.STOP;
        }
        if ("length".equalsIgnoreCase(value)) {
            return FinishReason.MAX_OUTPUT_TOKENS;
        }
        if ("content_filter".equalsIgnoreCase(value)) {
            return FinishReason.CONTENT_FILTERED;
        }
        if ("tool_calls".equalsIgnoreCase(value)) {
            return FinishReason.TOOL_CALLS;
        }
        throw malformed();
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw malformed();
        }
        return value.trim();
    }

    private static long nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value.longValue();
    }

    private static ProviderFailure failure(ProviderFailureCode code) {
        return new ProviderFailure(code, FAILURE_MESSAGE, Map.of());
    }

    private static ProductStepTurnException malformed() {
        return new ProductStepTurnException(
                ProductStepTurnError.MALFORMED_RESPONSE,
                "productModelProvider.response");
    }
}
