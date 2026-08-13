package com.yanban.api.agent.v2.chain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPlannerModelContextViewTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void removesTransportMetadataAndPreservesDecisionContext()
            throws Exception {
        String canonical = """
                {"format":1,"modules":[
                  {"ordinal":1,"kind":"INSTRUCTION_CHAIN","presence":"PRESENT",
                   "sourceVersion":{"secret":"source"},"readBoundary":{"secret":"boundary"},
                   "projectionVersion":"projection-v1","paginationVersion":"none-v1",
                   "projectionParameters":{"fixture":"x"},
                   "projection":{"status":"PRESENT","fields":{
                     "foundation.instructionChain":{"duplicate":true},
                     "instructions.completeStructure":{"currentInstructionRef":"instruction.1"},
                     "instructions.effectiveBodies":[{"body":"request"}],
                     "instructions.relations":[{"relationKind":"INITIAL"}]},
                     "visibleSourceRefs":["instruction.1"]}},
                  {"ordinal":2,"kind":"RUNTIME_CAPABILITY_PERMISSION","presence":"PRESENT",
                   "sourceVersion":{},"readBoundary":{},"projectionVersion":"projection-v1",
                   "paginationVersion":"none-v1","projectionParameters":{},
                   "projection":{"status":"PRESENT","fields":{
                     "foundation.roleRulesSchemaPermissionBoundaryAndSkills":{"hash":"hidden"},
                     "rules.capabilities":{"grantedCapabilities":["READ_PROJECT"]},
                     "rules.permissions":{"networkPolicy":"DENY"},
                     "rules.plannerSchema":{"variants":[{"kind":"DIRECT_ROUTE"}]}},
                     "visibleSourceRefs":["permission.1"]}}
                ]}
                """;

        String projected = ProductPlannerModelContextView.project(canonical);
        JsonNode root = JSON.readTree(projected);

        assertEquals(1, root.path("format").asInt());
        assertFalse(projected.contains("sourceVersion"));
        assertFalse(projected.contains("readBoundary"));
        assertFalse(projected.contains("projectionVersion"));
        assertFalse(projected.contains("paginationVersion"));
        assertFalse(projected.contains("projectionParameters"));
        assertTrue(projected.contains("foundation.instructionChain"));
        assertTrue(projected.contains("instructions.effectiveBodies"));
        assertTrue(projected.contains("instructions.relations"));
        assertTrue(projected.contains(
                "foundation.roleRulesSchemaPermissionBoundaryAndSkills"));
        assertTrue(projected.contains("instructions.completeStructure"));
        assertTrue(projected.contains("rules.capabilities"));
        assertTrue(projected.contains("rules.permissions"));
        assertTrue(projected.contains("rules.plannerSchema"));
        assertTrue(projected.contains("visibleSourceRefs"));
    }

    @Test
    void pendingViewPreservesAllVariantsAndFormalResumeContext()
            throws Exception {
        String canonical = pendingPrompt("PERSISTENT_PLAN");

        JsonNode root = JSON.readTree(
                ProductPlannerModelContextView.project(canonical));
        List<String> kinds = variantKinds(root);

        assertEquals(7, kinds.size());
        assertTrue(kinds.contains("DIRECT_ROUTE"));
        assertTrue(kinds.contains("PLAN_REVISION"));
        assertTrue(kinds.contains("USER_INSTRUCTION_DISPOSITION"));
        assertTrue(root.toString().contains("closingCondition"));
        assertTrue(root.toString().contains("review.resumePosition"));
    }

    @Test
    void nonManifestInputIsPreservedForFailSafeCompatibility() {
        assertEquals("prompt", ProductPlannerModelContextView.project(
                "prompt"));
    }

    private static String pendingPrompt(String resumePosition) {
        return """
                {"format":1,"modules":[
                  {"kind":"REVIEW_PENDING","projection":{"status":"PRESENT","fields":{
                    "foundation.latestDecisionCallReasonAndPendingItem":{
                      "currentPendingItem":{"closingCondition":"give one target"}},
                    "review.resumePosition":"%s"},"visibleSourceRefs":["gap.1"]}},
                  {"kind":"RUNTIME_CAPABILITY_PERMISSION","projection":{"status":"PRESENT","fields":{
                    "rules.plannerSchema":{"variants":[
                      {"kind":"PERSISTENT_PLAN"},{"kind":"DIRECT_ROUTE"},
                      {"kind":"PLAN_REVISION"},{"kind":"USER_INSTRUCTION_DISPOSITION"},
                      {"kind":"NEED_USER_INPUT"},{"kind":"NEED_PERMISSION"},
                      {"kind":"PLANNING_BLOCKED"}]},
                    "rules.permissions":{"networkPolicy":"DENY"},
                    "rules.capabilities":{"grantedCapabilities":[]}},
                    "visibleSourceRefs":["permission.1"]}}
                ]}
                """.formatted(resumePosition);
    }

    private static List<String> variantKinds(JsonNode root) {
        List<String> result = new ArrayList<>();
        for (JsonNode module : root.path("modules")) {
            if (!"RUNTIME_CAPABILITY_PERMISSION".equals(
                    module.path("kind").asText())) {
                continue;
            }
            for (JsonNode variant : module.path("projection").path("fields")
                    .path("rules.plannerSchema").path("variants")) {
                result.add(variant.path("kind").asText());
            }
        }
        return result;
    }
}
