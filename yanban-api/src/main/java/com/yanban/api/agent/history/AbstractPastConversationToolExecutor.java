package com.yanban.api.agent.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolDescriptor;
import com.yanban.core.tool.ToolErrorCode;
import com.yanban.core.tool.ToolExecutionContext;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import java.util.List;
import java.util.Set;

abstract class AbstractPastConversationToolExecutor implements ToolExecutor {
    protected final PastConversationHistoryService history;
    private final ToolDefinition definition;

    AbstractPastConversationToolExecutor(String name, String description, ObjectNode schema,
                                         PastConversationHistoryService history) {
        this.definition = new ToolDefinition(name, description, schema);
        this.history = history;
    }

    @Override public final ToolDefinition definition() { return definition; }
    @Override public final ToolDescriptor descriptor() {
        return PastConversationToolContract.descriptor(definition.name());
    }

    @Override public final ToolResult execute(ToolCall call) {
        Long owner = ToolExecutionContext.getCurrentUserId();
        if (owner == null || owner <= 0) {
            return failure(call, ToolErrorCode.PERMISSION_DENIED, "Authentication is required to read conversation history.");
        }
        try {
            if (call.arguments() == null || !call.arguments().isObject()) throw invalid();
            call.arguments().fieldNames().forEachRemaining(name -> {
                if (!arguments().contains(name)) throw invalid();
            });
            ObjectNode output = query(owner, call.arguments());
            return new ToolResult(call.id(), definition.name(), true, output, null, null,
                    false, List.of(), List.of(), List.of(), PastConversationToolContract.VERSION);
        } catch (PastConversationHistoryService.UnavailableException unavailable) {
            return failure(call, ToolErrorCode.NOT_FOUND, "The requested conversation is unavailable.");
        } catch (IllegalArgumentException invalid) {
            return failure(call, ToolErrorCode.VALIDATION_ERROR,
                    "Invalid conversation history arguments or cursor. Use the published tool schema.");
        } catch (RuntimeException failure) {
            // Never echo database exceptions, stored JSON, model arguments, or credential-bearing text.
            return failure(call, ToolErrorCode.INTERNAL_ERROR, "Conversation history could not be read.");
        }
    }

    protected abstract Set<String> arguments();
    protected abstract ObjectNode query(long owner, JsonNode args);

    protected final String text(JsonNode args, String name, int max, boolean required) {
        JsonNode value = args.get(name);
        if (value == null) {
            if (required) throw invalid();
            return null;
        }
        if (!value.isTextual() || value.textValue().length() > max) throw invalid();
        String result = value.textValue().strip();
        if (required && result.isEmpty()) throw invalid();
        return result;
    }

    protected final int limit(JsonNode args) {
        JsonNode value = args.get("limit");
        if (value == null) return 5;
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 1 || value.intValue() > PastConversationHistoryService.MAX_LIMIT) throw invalid();
        return value.intValue();
    }

    protected static ObjectNode schema(ObjectMapper json) {
        ObjectNode schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode props = schema.putObject("properties");
        props.putObject("cursor").put("type", "string").put("maxLength", 512)
                .put("description", "Opaque continuation returned by this operation; keep the original query/scope or sessionRef.");
        props.putObject("limit").put("type", "integer").put("minimum", 1)
                .put("maximum", PastConversationHistoryService.MAX_LIMIT).put("default", 5);
        return schema;
    }

    private ToolResult failure(ToolCall call, ToolErrorCode code, String message) {
        return ToolResult.failure(call.id(), definition.name(), code, message);
    }

    protected static IllegalArgumentException invalid() { return PastConversationHistoryService.invalid(); }
}
