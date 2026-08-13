package com.yanban.api.agent.v2.chain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductExecutorModelContextViewTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void removesBackendMetadataButPreservesToolChoiceAndArguments()
            throws Exception {
        String canonical = prompt();

        String projected = ProductExecutorModelContextView.project(canonical);
        JsonNode root = JSON.readTree(projected);
        JsonNode catalog = root.path("modules").get(1).path("projection")
                .path("fields").path("rules.completeToolSchemas");
        JsonNode tools = catalog.path("completeToolSchemas");

        assertTrue(projected.length() < canonical.length());
        assertFalse(projected.contains("sourceVersion"));
        assertFalse(projected.contains("readBoundary"));
        assertFalse(projected.contains("publicAlias"));
        assertFalse(projected.contains("allowedExecutionTiers"));
        assertFalse(projected.contains("routingRequirements"));
        assertFalse(projected.contains("executionTarget"));
        assertFalse(projected.contains("requiredCapabilities"));
        assertFalse(projected.contains("permissionRefs"));
        assertEquals("v1", catalog.path("schemaVersion").asText());
        assertEquals("task-frame.1", catalog.path("taskFrameRef").asText());
        assertEquals(2, tools.size());
        assertEquals(List.of("project.search", "project.cross-material.search"),
                toolIds(tools));
        assertTrue(projected.contains("ordinary literal discovery"));
        assertTrue(projected.contains("cross-file proof"));
        assertTrue(projected.contains("parameterSchema"));
        assertTrue(projected.contains("maxResults"));
        assertTrue(projected.contains("maxMatches"));
        assertTrue(projected.contains("permission.project-read"));
        assertTrue(projected.contains("rules.executorSchema"));
        assertTrue(projected.contains("visibleSourceRefs"));
    }

    @Test
    void malformedCatalogFallsBackToFrozenPrompt() {
        String malformed = prompt().replace(
                "\"permissionRef\":\"permission.project-read\",", "");

        assertEquals(malformed,
                ProductExecutorModelContextView.project(malformed));
    }

    @Test
    void nonManifestInputIsPreservedForFailSafeCompatibility() {
        assertEquals("prompt", ProductExecutorModelContextView.project(
                "prompt"));
    }

    private static List<String> toolIds(JsonNode tools) {
        List<String> result = new ArrayList<>();
        tools.forEach(tool -> result.add(
                tool.path("descriptor").path("id").asText()));
        return result;
    }

    private static String prompt() {
        return """
                {"format":1,"modules":[
                  {"ordinal":1,"kind":"TASK_CONTRACT","presence":"PRESENT",
                   "sourceVersion":{"secret":"source"},"readBoundary":{"secret":"boundary"},
                   "projectionVersion":"projection-v1","paginationVersion":"none-v1",
                   "projectionParameters":{"fixture":"x"},
                   "projection":{"status":"PRESENT","fields":{
                     "taskFrame.completeOrExplicitEmpty":{"objective":"find a fact"}},
                     "visibleSourceRefs":["task-frame.1"]}},
                  {"ordinal":2,"kind":"RUNTIME_CAPABILITY_PERMISSION","presence":"PRESENT",
                   "sourceVersion":{},"readBoundary":{},"projectionVersion":"projection-v1",
                   "paginationVersion":"none-v1","projectionParameters":{},
                   "projection":{"status":"PRESENT","fields":{
                     "rules.executorSchema":{"variants":[{"kind":"TOOL_ACTION"}]},
                     "rules.completeToolSchemas":{
                       "schemaVersion":"v1","taskFrameRef":"task-frame.1",
                       "permissionTierRef":"SANDBOX_STANDARD",
                       "permissionRefs":["permission.project-read"],
                       "summary":"two tools",
                       "completeToolSchemas":[
                         {"publicAlias":"project_search","summary":"search",
                          "descriptor":{"id":"project.search",
                           "description":"Choose for ordinary literal discovery; no cross-file proof is required.",
                           "requiredCapabilities":["READ_PROJECT"],
                           "parameterSchema":{"type":"object","properties":{"maxResults":{"type":"integer"}}}},
                          "permissionRef":"permission.project-read",
                          "allowedExecutionTiers":["SANDBOX_STANDARD"],
                          "requiredCapabilities":["READ_PROJECT"],
                          "requiredNetworkAllowlistEntries":[],
                          "routingRequirements":["TOOL_USE"],"executionTarget":"PROJECT"},
                         {"publicAlias":"project_cross_material_search","summary":"cross search",
                          "descriptor":{"id":"project.cross-material.search",
                           "description":"Choose when the result must provide cross-file proof across materials.",
                           "requiredCapabilities":["READ_PROJECT"],
                           "parameterSchema":{"type":"object","properties":{"maxMatches":{"type":"integer"}}}},
                          "permissionRef":"permission.project-read",
                          "allowedExecutionTiers":["SANDBOX_STANDARD"],
                          "requiredCapabilities":["READ_PROJECT"],
                          "requiredNetworkAllowlistEntries":[],
                          "routingRequirements":["TOOL_USE"],"executionTarget":"PROJECT"}]},
                     "rules.permissions":{"networkPolicy":"DENY"}},
                     "visibleSourceRefs":["permission.project-read"]}}
                ]}
                """;
    }
}
