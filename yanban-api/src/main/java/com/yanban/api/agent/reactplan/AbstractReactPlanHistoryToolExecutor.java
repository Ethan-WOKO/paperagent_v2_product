package com.yanban.api.agent.reactplan;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class AbstractReactPlanHistoryToolExecutor implements ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(
            AbstractReactPlanHistoryToolExecutor.class);
    protected final ObjectMapper json;
    protected final ReactPlanConversationHistoryService history;
    private final ToolDefinition definition;

    AbstractReactPlanHistoryToolExecutor(
            String name, String description, ObjectNode schema,
            ObjectMapper json, ReactPlanConversationHistoryService history) {
        this.json = json;
        this.history = history;
        this.definition = new ToolDefinition(name, description, schema);
    }

    @Override
    public final ToolDefinition definition() { return definition; }

    @Override
    public final ToolDescriptor descriptor() {
        return ReactPlanHistoryToolContract.descriptor(definition.name());
    }

    protected final ReactPlanConversationHistoryService.Authority authority(
            ToolCall call, Set<String> publicArguments) {
        JsonNode arguments = call.arguments();
        if (arguments == null || !arguments.isObject()) throw invalid();
        Set<String> allowed = new java.util.HashSet<>(publicArguments);
        allowed.addAll(ReactPlanHistoryToolContract.SERVER_ARGUMENTS);
        arguments.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw invalid();
        });
        Long userId = ToolExecutionContext.getCurrentUserId();
        Long trustedProjectId = ToolExecutionContext.getCurrentProjectId();
        JsonNode projectId = arguments.path(ReactPlanHistoryToolContract.SERVER_PROJECT_ID);
        JsonNode sessionId = arguments.path(ReactPlanHistoryToolContract.SERVER_SESSION_ID);
        String taskId = strictText(arguments,
                ReactPlanHistoryToolContract.SERVER_TASK_ID, true);
        if (userId == null || userId <= 0 || trustedProjectId == null || trustedProjectId <= 0
                || !projectId.isIntegralNumber() || projectId.asLong() != trustedProjectId
                || !sessionId.isIntegralNumber() || sessionId.asLong() <= 0
                || !taskId.matches("task\\.[a-f0-9]{64}")) {
            throw new HistoryAuthorizationException();
        }
        return new ReactPlanConversationHistoryService.Authority(
                userId, trustedProjectId, sessionId.asLong(), taskId);
    }

    protected final String strictText(JsonNode arguments, String name, boolean required) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) {
            if (required) throw invalid();
            return null;
        }
        if (!value.isTextual()) throw invalid();
        String text = value.asText().strip();
        if (required && text.isEmpty()) throw invalid();
        return text.isEmpty() ? null : text;
    }

    protected final int strictInt(
            JsonNode arguments, String name, int defaultValue, int min, int max) {
        JsonNode value = arguments.get(name);
        if (value == null || value.isNull()) return defaultValue;
        if (!value.isIntegralNumber() || value.asInt() < min || value.asInt() > max) {
            throw invalid();
        }
        return value.asInt();
    }

    protected final ToolResult success(ToolCall call, ObjectNode output) {
        List<String> evidence = new ArrayList<>();
        if (output.path("taskId").isTextual()) {
            evidence.add("reactplan-history:" + output.path("taskId").asText());
        }
        if (output.path("task").path("taskId").isTextual()) {
            evidence.add("reactplan-history:" + output.path("task").path("taskId").asText());
        }
        output.path("items").forEach(item -> {
            if (item.path("taskId").isTextual()) {
                evidence.add("reactplan-history:" + item.path("taskId").asText());
            }
        });
        return new ToolResult(call.id(), definition.name(), true, output,
                null, null, false, List.copyOf(evidence), List.of(), List.of(),
                "history-v1");
    }

    protected final ToolResult rejected(ToolCall call, RuntimeException failure) {
        if (failure instanceof HistoryAuthorizationException) {
            audit(call, "rejected_authority", 0);
            return ToolResult.failure(call.id(), definition.name(),
                    ToolErrorCode.PERMISSION_DENIED,
                    "Conversation history is unavailable for this task authority.");
        }
        if (failure instanceof ReactPlanConversationHistoryService.HistoryUnavailableException) {
            audit(call, "unavailable", 0);
            return ToolResult.failure(call.id(), definition.name(), ToolErrorCode.NOT_FOUND,
                    "The requested historical task is unavailable in the authorized Project.");
        }
        if (failure instanceof IllegalArgumentException) {
            audit(call, "rejected_arguments", 0);
            return ToolResult.failure(call.id(), definition.name(),
                    ToolErrorCode.VALIDATION_ERROR,
                    "Conversation history tool arguments are invalid. Reload the tool Schema and retry.");
        }
        audit(call, "failed_internal", 0);
        throw failure;
    }

    protected final void audit(ToolCall call, String outcome, int resultCount) {
        Long userId = ToolExecutionContext.getCurrentUserId();
        Long projectId = ToolExecutionContext.getCurrentProjectId();
        String requester = call.arguments() == null ? "unknown"
                : call.arguments().path(ReactPlanHistoryToolContract.SERVER_TASK_ID)
                        .asText("unknown");
        log.info("reactplan_history_lookup requesterTaskId={} userId={} projectId={} "
                        + "tool={} outcome={} resultCount={}",
                requester, userId, projectId, definition.name(), outcome, resultCount);
    }

    protected final IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid conversation history arguments");
    }

    static final class HistoryAuthorizationException extends RuntimeException { }
}
