package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolResult;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class GetTaskExecutionTraceToolExecutor
        extends AbstractReactPlanHistoryToolExecutor {
    private static final Set<String> ARGUMENTS = Set.of("taskId", "cursor", "limit");

    GetTaskExecutionTraceToolExecutor(
            ObjectMapper json, ReactPlanConversationHistoryService history) {
        super(ReactPlanHistoryToolContract.GET_TRACE,
                "Read a bounded, ordered and sanitized execution trace for one completed task "
                        + "previously located by search_conversation_tasks. Returns public step types, "
                        + "tool names, states and allowlisted result summaries; never raw arguments, "
                        + "file bodies, sandbox output, hidden prompts or model reasoning.",
                schema(json), json, history);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            var authority = authority(call, ARGUMENTS);
            String taskId = strictText(call.arguments(), "taskId", true);
            if (!taskId.matches("task\\.[a-f0-9]{64}")) throw invalid();
            String cursor = strictText(call.arguments(), "cursor", false);
            int limit = strictInt(call.arguments(), "limit", 20, 1,
                    ReactPlanConversationHistoryService.MAX_TRACE_LIMIT);
            ObjectNode output = history.trace(authority, taskId, cursor, limit);
            audit(call, "succeeded", output.path("resultCount").asInt());
            return success(call, output);
        } catch (RuntimeException failure) {
            return rejected(call, failure);
        }
    }

    private static ObjectNode schema(ObjectMapper json) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("taskId").put("type", "string")
                .put("pattern", "^task\\.[a-f0-9]{64}$")
                .put("description", "Opaque taskId returned by search_conversation_tasks.");
        properties.putObject("cursor").put("type", "string")
                .put("pattern", "^event\\.[1-9][0-9]*$");
        properties.putObject("limit").put("type", "integer").put("minimum", 1)
                .put("maximum", ReactPlanConversationHistoryService.MAX_TRACE_LIMIT);
        schema.putArray("required").add("taskId");
        schema.put("additionalProperties", false);
        return schema;
    }
}
