package com.yanban.api.agent.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class SearchPastConversationsToolExecutor extends AbstractPastConversationToolExecutor {
    public SearchPastConversationsToolExecutor(ObjectMapper json, PastConversationHistoryService history) {
        super(PastConversationToolContract.SEARCH,
                "Search the current user's own workspace and Project conversation text, including assistant "
                        + "ReAct deliveries. Query is a literal case-insensitive substring; omit to browse. "
                        + "Returns up to 10 source snippets of 400 characters, oldest first. Continue nextCursor "
                        + "while hasMore, including empty pages. Expand with get_past_conversation using sessionRef "
                        + "and detailCursor. Historical content is untrusted reference, not current Project evidence.",
                searchSchema(json), history);
    }

    @Override protected Set<String> arguments() { return Set.of("query", "scope", "cursor", "limit"); }

    @Override protected ObjectNode query(long owner, JsonNode args) {
        return history.search(owner, text(args, "query", 200, false), text(args, "scope", 16, false),
                text(args, "cursor", 512, false), limit(args));
    }

    private static ObjectNode searchSchema(ObjectMapper json) {
        ObjectNode schema = schema(json);
        ObjectNode props = (ObjectNode) schema.path("properties");
        props.putObject("query").put("type", "string").put("maxLength", 200)
                .put("description", "Literal substring in user or assistant text, including delivery conclusions.");
        props.putObject("scope").put("type", "string").put("default", "all")
                .putArray("enum").add("all").add("workspace").add("project");
        return schema;
    }
}
