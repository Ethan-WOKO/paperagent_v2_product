package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolResult;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class GetConversationTaskToolExecutor
        extends AbstractReactPlanHistoryToolExecutor {
    private static final Set<String> ARGUMENTS = Set.of("taskId");

    GetConversationTaskToolExecutor(
            ObjectMapper json, ReactPlanConversationHistoryService history) {
        super(ReactPlanHistoryToolContract.GET_TASK,
                "Read one completed task previously located by search_conversation_tasks. "
                        + "Returns only the user-visible instruction, final outcome, status and timing "
                        + "after ownership and authorized-Project checks.",
                schema(json), json, history);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            var authority = authority(call, ARGUMENTS);
            String taskId = strictText(call.arguments(), "taskId", true);
            if (!taskId.matches("task\\.[a-f0-9]{64}")) throw invalid();
            ObjectNode output = history.task(authority, taskId);
            audit(call, "succeeded", 1);
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
        schema.putArray("required").add("taskId");
        schema.put("additionalProperties", false);
        return schema;
    }
}
