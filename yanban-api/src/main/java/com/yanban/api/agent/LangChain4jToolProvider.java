package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolRegistry;
import com.yanban.core.tool.ToolResult;
import com.yanban.core.tool.ToolErrorCode;
import com.yanban.api.agent.worker.ControlledWorkerExecutionScope;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LangChain4jToolProvider implements ToolProvider {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final AgentLangChain4jTools annotatedTools;
    private final Map<String, ToolBinding> annotatedBindings;

    public LangChain4jToolProvider(ToolRegistry toolRegistry,
                                   ObjectMapper objectMapper,
                                   AgentLangChain4jTools annotatedTools) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.annotatedTools = annotatedTools;
        this.annotatedBindings = buildAnnotatedBindings(annotatedTools);
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        return ToolProviderResult.builder().build();
    }

    /** Uses the already-resolved runtime policy as the sole model/executor allow-list. */
    public ToolProviderResult provideTools(AgentRuntimeRequest runtimeRequest) {
        Objects.requireNonNull(runtimeRequest, "runtimeRequest must not be null");
        return provideTools(runtimeRequest, new LinkedHashSet<>(runtimeRequest.toolPolicy().allowedTools()));
    }

    /**
     * Compatibility bridge for tests and callers migrating to {@link #provideTools(AgentRuntimeRequest)}.
     * A supplied list may never widen the runtime's resolved policy.
     */
    public ToolProviderResult provideTools(AgentRuntimeRequest runtimeRequest, Set<String> allowedToolNames) {
        Objects.requireNonNull(runtimeRequest, "runtimeRequest must not be null");
        Objects.requireNonNull(allowedToolNames, "allowedToolNames must be resolved before tool provision");
        if (!new LinkedHashSet<>(runtimeRequest.toolPolicy().allowedTools()).equals(new LinkedHashSet<>(allowedToolNames))) {
            throw new IllegalArgumentException("provided tools must equal the resolved runtime policy");
        }
        ToolProviderResult.Builder builder = ToolProviderResult.builder();
        Set<String> added = new LinkedHashSet<>();
        for (ToolBinding binding : annotatedBindings.values()) {
            if (isModelExposable(binding.specification().name(), allowedToolNames)) {
                builder.add(binding.specification(), governedAnnotatedExecutor(runtimeRequest, binding, allowedToolNames));
                added.add(binding.specification().name());
            }
        }
        for (ToolDefinition definition : toolRegistry.listDefinitions()) {
            String name = definition.name();
            if (added.contains(name) || !isModelExposable(name, allowedToolNames)) {
                continue;
            }
            builder.add(toToolSpecification(definition), fallbackExecutor(runtimeRequest, name, allowedToolNames));
        }
        return builder.build();
    }

    public java.util.Optional<ToolDescriptor> descriptor(String toolName) {
        return toolRegistry.findDescriptor(toolName);
    }

    private boolean isModelExposable(String toolName, Set<String> allowedToolNames) {
        return allowedToolNames.contains(toolName)
                && toolRegistry.findDescriptor(toolName)
                        .map(descriptor -> descriptor.modelVisible()
                                && descriptor.sideEffectType() != ToolDescriptor.SideEffectType.UNKNOWN
                                && descriptor.confirmationPolicy() == ToolDescriptor.ConfirmationPolicy.NEVER)
                        .orElse(false);
    }

    private Map<String, ToolBinding> buildAnnotatedBindings(AgentLangChain4jTools tools) {
        Map<String, ToolBinding> bindings = new LinkedHashMap<>();
        if (tools == null) {
            return bindings;
        }
        List<ToolSpecification> specifications = ToolSpecifications.toolSpecificationsFrom(tools);
        for (ToolSpecification specification : specifications) {
            Method method = findToolMethod(tools.getClass(), specification.name());
            if (method != null) {
                bindings.put(specification.name(), new ToolBinding(
                        specification,
                        new DefaultToolExecutor(tools, method)
                ));
            }
        }
        return bindings;
    }

    private Method findToolMethod(Class<?> type, String toolName) {
        for (Method method : type.getMethods()) {
            dev.langchain4j.agent.tool.Tool tool = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
            if (tool == null) {
                continue;
            }
            if (toolName.equals(tool.name()) || List.of(tool.value()).contains(toolName) || method.getName().equals(toolName)) {
                return method;
            }
        }
        return null;
    }

    private dev.langchain4j.service.tool.ToolExecutor fallbackExecutor(AgentRuntimeRequest runtimeRequest,
                                                                        String toolName,
                                                                        Set<String> allowedToolNames) {
        return (toolRequest, memoryId) -> {
            JsonNode arguments = null;
            boolean invocationAccepted = false;
            try {
                arguments = objectMapper.readTree(defaultString(toolRequest.arguments(), "{}"));
                ControlledWorkerExecutionScope.validateInvocation(toolName, arguments);
                invocationAccepted = true;
                Long userId = runtimeRequest == null ? null : runtimeRequest.userId();
                ToolExecutionContext.setCurrentUserId(userId);
                if (runtimeRequest.projectContext() != null) {
                    ToolExecutionContext.setCurrentProjectId(runtimeRequest.projectContext().projectId());
                }
                ToolExecutionContext.setResolvedAllowedTools(allowedToolNames);
                ToolResult result = toolRegistry.execute(new ToolCall(toolRequest.id(), toolName, arguments), allowedToolNames);
                ControlledWorkerExecutionScope.recordResult(toolName, arguments, result);
                return serialize(result);
            } catch (Exception ex) {
                if (invocationAccepted && ex instanceof RuntimeException runtimeException) {
                    ControlledWorkerExecutionScope.recordFailure(toolName, arguments, runtimeException);
                }
                return failureContent(ex);
            } finally {
                ToolExecutionContext.clear();
            }
        };
    }

    private dev.langchain4j.service.tool.ToolExecutor governedAnnotatedExecutor(AgentRuntimeRequest runtimeRequest,
                                                                                 ToolBinding binding,
                                                                                 Set<String> allowedToolNames) {
        return (toolRequest, memoryId) -> {
            JsonNode arguments = null;
            boolean invocationAccepted = false;
            try {
                if (!isModelExposable(binding.specification().name(), allowedToolNames)) {
                    return failureContent("tool is not allowed by the resolved runtime policy");
                }
                arguments = objectMapper.readTree(defaultString(toolRequest.arguments(), "{}"));
                ControlledWorkerExecutionScope.validateInvocation(binding.specification().name(), arguments);
                invocationAccepted = true;
                ToolExecutionContext.setCurrentUserId(runtimeRequest.userId());
                if (runtimeRequest.projectContext() != null) {
                    ToolExecutionContext.setCurrentProjectId(runtimeRequest.projectContext().projectId());
                }
                ToolExecutionContext.setResolvedAllowedTools(allowedToolNames);
                String serialized = binding.executor().execute(toolRequest, memoryId);
                ControlledWorkerExecutionScope.recordSerializedResult(
                        binding.specification().name(), arguments, serialized, objectMapper);
                return serialized;
            } catch (Exception ex) {
                if (invocationAccepted && ex instanceof RuntimeException runtimeException) {
                    ControlledWorkerExecutionScope.recordFailure(
                            binding.specification().name(), arguments, runtimeException);
                }
                return failureContent(ex);
            } finally {
                ToolExecutionContext.clear();
            }
        };
    }

    public String failureContent(String errorMessage) {
        return failureContent(ToolErrorCode.INTERNAL_ERROR, errorMessage, false);
    }

    private String failureContent(Exception exception) {
        String message = defaultString(exception.getMessage(), exception.getClass().getSimpleName());
        boolean invalidArguments = exception instanceof JsonProcessingException
                || message.matches("(?is).*(json|argument|parameter|required field|deserialize|missing required).*" );
        return invalidArguments
                ? failureContent(ToolErrorCode.VALIDATION_ERROR,
                        exception instanceof JsonProcessingException
                                ? "Tool arguments must be valid JSON." : message,
                        true)
                : failureContent(ToolErrorCode.INTERNAL_ERROR, message, false);
    }

    private String failureContent(ToolErrorCode code, String errorMessage, boolean retryable) {
        try {
            return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("success", false)
                    .put("errorCode", code == null ? ToolErrorCode.INTERNAL_ERROR.name() : code.name())
                    .put("errorMessage", defaultString(errorMessage, "tool_failed"))
                    .put("retryable", retryable));
        } catch (Exception ex) {
            return "{\"success\":false,\"error\":\"tool_result_serialization_failed\"}";
        }
    }

    private String serialize(ToolResult result) {
        try {
            if (result != null && result.success()) {
                return objectMapper.writeValueAsString(result.output());
            }
            if (result == null) {
                return failureContent("tool_failed");
            }
            return objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("success", false)
                    .put("errorCode", result.errorCode() == null ? "INTERNAL_ERROR" : result.errorCode().name())
                    .put("errorMessage", defaultString(result.errorMessage(), "tool_failed"))
                    .put("retryable", result.retryable()));
        } catch (Exception ex) {
            return failureContent("tool_result_serialization_failed");
        }
    }

    private ToolSpecification toToolSpecification(ToolDefinition definition) {
        JsonNode parameters = definition.parameters();
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        JsonNode properties = parameters.path("properties");
        if (properties.isObject()) {
            properties.fields().forEachRemaining(entry -> builder.addProperty(entry.getKey(), toSchemaElement(entry.getValue())));
        }
        JsonNode required = parameters.path("required");
        if (required.isArray()) {
            List<String> requiredFields = new java.util.ArrayList<>();
            required.forEach(node -> requiredFields.add(node.asText()));
            builder.required(requiredFields);
        }
        if (parameters.has("additionalProperties")) {
            builder.additionalProperties(parameters.path("additionalProperties").asBoolean());
        }
        return ToolSpecification.builder()
                .name(definition.name())
                .description(definition.description())
                .parameters(builder.build())
                .build();
    }

    private JsonSchemaElement toSchemaElement(JsonNode schemaNode) {
        String type = schemaNode.path("type").asText("string").trim().toLowerCase(Locale.ROOT);
        String description = schemaNode.path("description").asText(null);
        return switch (type) {
            case "array" -> JsonArraySchema.builder()
                    .description(description)
                    .items(toSchemaElement(schemaNode.path("items")))
                    .build();
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "object" -> buildObjectSchema(schemaNode, description);
            default -> JsonStringSchema.builder().description(description).build();
        };
    }

    private JsonObjectSchema buildObjectSchema(JsonNode schemaNode, String description) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (StringUtils.hasText(description)) {
            builder.description(description);
        }
        JsonNode properties = schemaNode.path("properties");
        if (properties.isObject()) {
            properties.fields().forEachRemaining(entry -> builder.addProperty(entry.getKey(), toSchemaElement(entry.getValue())));
        }
        JsonNode required = schemaNode.path("required");
        if (required.isArray()) {
            List<String> requiredFields = new java.util.ArrayList<>();
            required.forEach(node -> requiredFields.add(node.asText()));
            builder.required(requiredFields);
        }
        return builder.build();
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private record ToolBinding(ToolSpecification specification, dev.langchain4j.service.tool.ToolExecutor executor) {
    }
}
