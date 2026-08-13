package com.yanban.api.agent.v2.chain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds the provider-visible Planner view without changing frozen Context. */
public final class ProductPlannerModelContextView {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProductPlannerModelContextView() {
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
                        instanceof ObjectNode sourceFields)) {
                    return canonicalPrompt;
                }
                ObjectNode visible = module.deepCopy();
                visible.remove("sourceVersion");
                visible.remove("readBoundary");
                visible.remove("projectionVersion");
                visible.remove("paginationVersion");
                visible.remove("projectionParameters");
                visibleModules.add(visible);
            }
            return JSON.writeValueAsString(view);
        } catch (Exception invalidCanonicalPrompt) {
            return canonicalPrompt;
        }
    }

}
