package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.core.tool.ToolCall;
import com.yanban.core.tool.ToolDefinition;
import com.yanban.core.tool.ToolExecutor;
import com.yanban.core.tool.ToolResult;
import com.yanban.paper.literature.LiteratureRecommendationService;
import com.yanban.paper.literature.LiteratureRecommendationService.RecommendationRequest;
import com.yanban.paper.literature.LiteratureRecommendationService.RecommendationResult;
import org.springframework.stereotype.Component;

@Component
public class RecommendLiteratureToolExecutor implements ToolExecutor {

    static final String TOOL_NAME = "recommend_literature";

    private final LiteratureRecommendationService recommendationService;
    private final ObjectMapper objectMapper;
    private final ToolDefinition definition;

    public RecommendLiteratureToolExecutor(LiteratureRecommendationService recommendationService,
                                           ObjectMapper objectMapper) {
        this.recommendationService = recommendationService;
        this.objectMapper = objectMapper;
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("query").put("type", "string")
                .put("description", "Main academic literature search topic or natural-language question.");
        properties.putObject("goal").put("type", "string")
                .put("description", "Optional usage goal, such as survey, latest work, method comparison, or support a paper claim.");
        properties.putObject("claims").put("type", "string")
                .put("description", "Optional citation claims or evidence needs, separated by newline or semicolon.");
        properties.putObject("topK").put("type", "integer")
                .put("description", "Number of final recommendations, default 8, max 30.");
        properties.putObject("candidateK").put("type", "integer")
                .put("description", "Number of candidates to fetch from each source for each generated query, default is derived from topK, max 50.");
        properties.putObject("maxQueries").put("type", "integer")
                .put("description", "Maximum number of generated search queries, default 4, max 6.");
        properties.putObject("analysisLimit").put("type", "integer")
                .put("description", "Maximum number of top literature cards to analyze with LLM before reranking, default 15, max 30. Use lower values for faster chat responses.");
        properties.putObject("yearFrom").put("type", "integer")
                .put("description", "Optional minimum publication year, for example 2020.");
        properties.putObject("includeBibtex").put("type", "boolean")
                .put("description", "Whether to include BibTeX in each recommendation, default true.");
        properties.putObject("existingBibtex").put("type", "string")
                .put("description", "Optional uploaded or project BibTeX, used to mark already-present papers.");
        parameters.putArray("required").add("query");
        this.definition = new ToolDefinition(
                TOOL_NAME,
                "Topic-based academic literature search v1. Returns real, deduplicated, ranked, explainable paper recommendations with citation metadata status, existing-BibTeX markers, and metadata risk notes. Does not inspect full manuscripts, diagnose literature-review gaps, replace old citations, or modify paper text.",
                parameters
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call) {
        JsonNode args = call.arguments();
        RecommendationRequest request = new RecommendationRequest(
                text(args, "query"),
                text(args, "goal"),
                text(args, "claims"),
                integer(args, "yearFrom"),
                integer(args, "topK"),
                integer(args, "candidateK"),
                integer(args, "maxQueries"),
                bool(args, "includeBibtex"),
                text(args, "existingBibtex"),
                integer(args, "analysisLimit")
        );
        RecommendationResult result = recommendationService.recommendWithProgress(
                request,
                (stage, message) -> ToolExecutionProgressScope.emit(message));
        return ToolResult.success(call.id(), definition.name(), objectMapper.valueToTree(result));
    }

    private String text(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.path(field).isNull()) return null;
        String value = args.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private Integer integer(JsonNode args, String field) {
        return args != null && args.has(field) && args.path(field).canConvertToInt() ? args.path(field).asInt() : null;
    }

    private Boolean bool(JsonNode args, String field) {
        if (args == null || !args.has(field) || args.path(field).isNull()) return null;
        JsonNode value = args.path(field);
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) return value.asInt() != 0;
        String text = value.asText("").trim().toLowerCase();
        if (text.isBlank()) return null;
        return switch (text) {
            case "true", "yes", "y", "1", "on" -> true;
            case "false", "no", "n", "0", "off" -> false;
            default -> null;
        };
    }
}
