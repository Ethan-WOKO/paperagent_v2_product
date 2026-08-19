package com.yanban.api.agent.reactplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolResult;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class SearchConversationTasksToolExecutor
        extends AbstractReactPlanHistoryToolExecutor {
    private static final Set<String> ARGUMENTS = Set.of(
            "query", "scope", "status", "cursor", "limit");

    SearchConversationTasksToolExecutor(
            ObjectMapper json, ReactPlanConversationHistoryService history) {
        super(ReactPlanHistoryToolContract.SEARCH_TASKS,
                "Search completed tasks owned by the current user in the authorized Project. "
                        + "The optional query searches user instructions only. Use current_session "
                        + "for this conversation or current_project when the user explicitly refers "
                        + "to another conversation. Results are bounded and paginated.",
                schema(json), json, history);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            var authority = authority(call, ARGUMENTS);
            String query = strictText(call.arguments(), "query", false);
            if (query != null && query.length() > 200) throw invalid();
            String scope = strictText(call.arguments(), "scope", false);
            scope = scope == null ? "current_session" : scope;
            if (!Set.of("current_session", "current_project").contains(scope)) throw invalid();
            String status = strictText(call.arguments(), "status", false);
            if (status != null && !Set.of("succeeded", "failed", "cancelled").contains(status)) {
                throw invalid();
            }
            String cursor = strictText(call.arguments(), "cursor", false);
            int limit = strictInt(call.arguments(), "limit", 5, 1,
                    ReactPlanConversationHistoryService.MAX_SEARCH_LIMIT);
            ObjectNode output = history.search(authority,
                    new ReactPlanConversationHistoryService.SearchRequest(
                            scope, query, status, cursor, limit));
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
        properties.putObject("query").put("type", "string").put("maxLength", 200)
                .put("description", "Optional literal keyword searched only in prior user instructions.");
        properties.putObject("scope").put("type", "string")
                .putArray("enum").add("current_session").add("current_project");
        properties.putObject("status").put("type", "string")
                .putArray("enum").add("succeeded").add("failed").add("cancelled");
        properties.putObject("cursor").put("type", "string")
                .put("pattern", "^intake\\.[1-9][0-9]*$");
        properties.putObject("limit").put("type", "integer").put("minimum", 1)
                .put("maximum", ReactPlanConversationHistoryService.MAX_SEARCH_LIMIT);
        schema.put("additionalProperties", false);
        return schema;
    }
}
