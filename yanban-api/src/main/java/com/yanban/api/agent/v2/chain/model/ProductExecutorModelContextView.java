package com.yanban.api.agent.v2.chain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds the provider-visible Executor view without changing frozen Context. */
public final class ProductExecutorModelContextView {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUNTIME_MODULE =
            "RUNTIME_CAPABILITY_PERMISSION";
    private static final String TOOL_FIELD = "rules.completeToolSchemas";

    private ProductExecutorModelContextView() {
    }

    public static String project(String canonicalPrompt) {
        if (canonicalPrompt == null || canonicalPrompt.isBlank()) {
            return canonicalPrompt;
        }
        try {
            JsonNode parsed = JSON.readTree(canonicalPrompt);
            if (!(parsed instanceof ObjectNode root)
                    || root.path("format").asInt(-1) != 1
                    || !(root.get("modules") instanceof ArrayNode modules)) {
                return canonicalPrompt;
            }
            ObjectNode view = JSON.createObjectNode();
            view.put("format", 1);
            ArrayNode visibleModules = view.putArray("modules");
            for (JsonNode value : modules) {
                if (!(value instanceof ObjectNode module)
                        || !module.hasNonNull("kind")
                        || !(module.get("projection")
                        instanceof ObjectNode projection)
                        || !(projection.get("fields")
                        instanceof ObjectNode fields)) {
                    return canonicalPrompt;
                }
                ObjectNode visible = module.deepCopy();
                visible.remove("sourceVersion");
                visible.remove("readBoundary");
                visible.remove("projectionVersion");
                visible.remove("paginationVersion");
                visible.remove("projectionParameters");
                if (RUNTIME_MODULE.equals(module.path("kind").asText())
                        && !compactTools(visible)) {
                    return canonicalPrompt;
                }
                visibleModules.add(visible);
            }
            return JSON.writeValueAsString(view);
        } catch (Exception invalidCanonicalPrompt) {
            return canonicalPrompt;
        }
    }

    private static boolean compactTools(ObjectNode module) {
        JsonNode fieldsNode = module.path("projection").path("fields");
        if (!(fieldsNode instanceof ObjectNode fields)) {
            return false;
        }
        JsonNode catalogNode = fields.get(TOOL_FIELD);
        if (!(catalogNode instanceof ObjectNode catalog)
                || !(catalog.get("completeToolSchemas")
                instanceof ArrayNode tools)) {
            return false;
        }
        ObjectNode compactCatalog = JSON.createObjectNode();
        if (!copyRequired(catalog, compactCatalog, "schemaVersion")
                || !copyRequired(catalog, compactCatalog, "taskFrameRef")
                || !copyRequired(catalog, compactCatalog,
                "permissionTierRef")) {
            return false;
        }
        ArrayNode compactTools = compactCatalog.putArray(
                "completeToolSchemas");
        for (JsonNode value : tools) {
            if (!(value instanceof ObjectNode tool)
                    || !(tool.get("descriptor")
                    instanceof ObjectNode descriptor)
                    || !tool.hasNonNull("permissionRef")) {
                return false;
            }
            ObjectNode compactDescriptor = JSON.createObjectNode();
            if (!copyRequired(descriptor, compactDescriptor, "id")
                    || !copyRequired(descriptor, compactDescriptor,
                    "description")
                    || !copyRequired(descriptor, compactDescriptor,
                    "parameterSchema")) {
                return false;
            }
            ObjectNode compactTool = JSON.createObjectNode();
            compactTool.set("descriptor", compactDescriptor);
            compactTool.set("permissionRef", tool.get("permissionRef"));
            compactTools.add(compactTool);
        }
        fields.set(TOOL_FIELD, compactCatalog);
        return true;
    }

    private static boolean copyRequired(
            ObjectNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        target.set(field, value);
        return true;
    }
}
