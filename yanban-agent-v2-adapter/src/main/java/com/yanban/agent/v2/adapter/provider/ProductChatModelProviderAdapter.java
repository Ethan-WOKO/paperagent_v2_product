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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bridge from the stable V2 model port to one owner-resolved product call. */
public final class ProductChatModelProviderAdapter implements ModelProvider {
    private static final Logger log = LoggerFactory.getLogger(
            ProductChatModelProviderAdapter.class);
    private static final String FAILURE_MESSAGE = "product model turn failed";

    private final ChatModelProvider delegate;
    private final ObjectMapper json;
    private final ProductModelEndpointResolver endpoints;

    public ProductChatModelProviderAdapter(
            ChatModelProvider delegate,
            ObjectMapper json,
            ProductModelEndpointResolver endpoints) {
        if (delegate == null || json == null || endpoints == null) {
            throw new ProductStepTurnException(
                    ProductStepTurnError.INVALID_CONFIGURATION,
                    "productModelProvider");
        }
        this.delegate = delegate;
        this.json = json.copy();
        this.endpoints = endpoints;
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
        MappedRequest mapped;
        try {
            if (request.planId().isEmpty()) {
                return failure(ProviderFailureCode.INVALID_REQUEST);
            }
            ProductModelEndpoint endpoint =
                    endpoints.resolve(request.planId().orElseThrow());
            mapped = mapRequest(request, endpoint);
            logModelInput(request, mapped.request());
            response = delegate.chat(mapped.request());
            logModelOutput(request, response);
        } catch (RuntimeException exception) {
            log.warn(
                    "V2 model debug call failed requestId={} correlationId={} "
                            + "exceptionType={} causeType={}",
                    request.requestId().value(),
                    request.correlationId().value(),
                    exception.getClass().getName(),
                    exception.getCause() == null ? "none"
                            : exception.getCause().getClass().getName());
            return failure(ProviderFailureCode.UNAVAILABLE);
        }
        try {
            return mapResponse(response, mapped.toolIdsByProviderName());
        } catch (RuntimeException exception) {
            return failure(ProviderFailureCode.PROTOCOL_VIOLATION);
        }
    }

    private MappedRequest mapRequest(
            ModelRequest request, ProductModelEndpoint endpoint) {
        List<ChatMessage> messages = request.messages().stream()
                .map(this::message)
                .toList();
        Map<String, ToolId> toolIdsByProviderName = new LinkedHashMap<>();
        List<ToolSpec> tools = new ArrayList<>();
        for (ToolDescriptor descriptor : request.availableTools()) {
            String providerName = ProductProviderToolAlias.from(
                    descriptor.id());
            ToolId previous = toolIdsByProviderName.putIfAbsent(
                    providerName, descriptor.id());
            if (previous != null) {
                throw new ProductStepTurnException(
                        ProductStepTurnError.INVALID_CONFIGURATION,
                        "productModelProvider.tools");
            }
            tools.add(tool(descriptor, providerName));
        }
        ChatRequest mapped = new ChatRequest(
                endpoint.provider(),
                endpoint.model(),
                messages,
                request.generationOptions().temperature(),
                request.generationOptions().maxOutputTokens(),
                tools,
                endpoint.apiKey(),
                endpoint.apiUrl(),
                null,
                ChatRequest.Thinking.disabled(),
                request.correlationId().value());
        return new MappedRequest(
                mapped, Map.copyOf(toolIdsByProviderName));
    }

    private static void logModelInput(
            ModelRequest source, ChatRequest request) {
        log.info(
                "V2 model debug input requestId={} correlationId={} "
                        + "taskFrameId={} planId={} revisionId={} stepId={} "
                        + "provider={} model={} temperature={} maxTokens={} "
                        + "responseFormat={} thinking={} messages={} tools={}",
                source.requestId().value(), source.correlationId().value(),
                source.taskFrameId().map(value -> value.value())
                        .orElse("none"),
                source.planId().map(value -> value.value()).orElse("none"),
                source.planRevisionId().map(value -> value.value())
                        .orElse("none"),
                source.stepId().map(value -> value.value()).orElse("none"),
                request.provider(), request.model(), request.temperature(),
                request.maxTokens(), request.responseFormat(),
                request.thinking(), request.messages(), request.tools());
    }

    private static void logModelOutput(
            ModelRequest request, ChatResponse response) {
        log.info(
                "V2 model debug output requestId={} correlationId={} "
                        + "response={}",
                request.requestId().value(),
                request.correlationId().value(), response);
    }

    private ChatMessage message(ModelMessage message) {
        return switch (message.role()) {
            case SYSTEM -> ChatMessage.system(message.content());
            case USER -> ChatMessage.user(message.content());
            case ASSISTANT -> ChatMessage.assistant(message.content());
            case TOOL_FACT -> ChatMessage.process(message.content());
        };
    }

    private ToolSpec tool(
            ToolDescriptor descriptor, String providerName) {
        return ToolSpec.function(
                providerName,
                descriptor.description(),
                jsonNode(descriptor.parameterSchema()));
    }

    private JsonNode jsonNode(ContractValue value) {
        if (value instanceof NullValue) {
            return json.nullNode();
        }
        if (value instanceof TextValue text) {
            return json.getNodeFactory().textNode(text.value());
        }
        if (value instanceof BooleanValue bool) {
            return json.getNodeFactory().booleanNode(bool.value());
        }
        if (value instanceof NumberValue number) {
            return json.getNodeFactory().numberNode(number.value());
        }
        if (value instanceof ListValue list) {
            var array = json.createArrayNode();
            list.values().forEach(item -> array.add(jsonNode(item)));
            return array;
        }
        if (value instanceof ObjectValue object) {
            ObjectNode node = json.createObjectNode();
            object.values().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> node.set(
                            entry.getKey(), jsonNode(entry.getValue())));
            return node;
        }
        throw new ProductStepTurnException(
                ProductStepTurnError.INVALID_CONFIGURATION,
                "productModelProvider.tools.parameterSchema");
    }

    private ModelResponse mapResponse(
            ChatResponse response,
            Map<String, ToolId> toolIdsByProviderName) {
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
            ToolId toolId = toolIdsByProviderName.get(
                    required(call.function().name()));
            if (toolId == null) {
                throw malformed();
            }
            proposed.add(new ProposedToolCall(
                    required(call.id()),
                    toolId,
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

    private record MappedRequest(
            ChatRequest request,
            Map<String, ToolId> toolIdsByProviderName) {
    }
}
