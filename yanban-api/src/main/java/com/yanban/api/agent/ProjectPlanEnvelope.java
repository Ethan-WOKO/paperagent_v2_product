package com.yanban.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Versioned server-owned wrapper around an otherwise untrusted planner raw JSON payload. */
final class ProjectPlanEnvelope {
    private static final String SCHEMA_V1 = "project_plan_envelope_v1";
    private static final String SCHEMA_V2 = "project_plan_envelope_v2";
    private static final String SCHEMA_V3 = "project_plan_envelope_v3";
    private static final String SCHEMA_V4 = "project_plan_envelope_v4";
    private static final String SCHEMA_V5 = "project_plan_envelope_v5";
    private ProjectPlanEnvelope() { }

    static String wrap(ObjectMapper json, String plannerRawJson, ProjectRuntimeContext context) {
        return wrap(json, plannerRawJson, context, null, null, true);
    }

    static String wrap(ObjectMapper json, String plannerRawJson, ProjectRuntimeContext context,
                       String governedMemoryContext) {
        return wrap(json, plannerRawJson, context, null, governedMemoryContext, true);
    }

    static String wrap(ObjectMapper json, String plannerRawJson, ProjectRuntimeContext context,
                       String governedMemoryContext, boolean persistConversationSummary) {
        return wrap(json, plannerRawJson, context, null, governedMemoryContext, persistConversationSummary);
    }

    static String wrapControlled(ObjectMapper json, String plannerRawJson, ProjectRuntimeContext context,
                                 JsonNode controlledPlanEnvelope) {
        return wrapControlled(json, plannerRawJson, context, controlledPlanEnvelope, null);
    }

    static String wrapControlled(ObjectMapper json, String plannerRawJson, ProjectRuntimeContext context,
                                 JsonNode controlledPlanEnvelope, String governedMemoryContext) {
        return wrapControlled(json, plannerRawJson, context, controlledPlanEnvelope, governedMemoryContext, true);
    }

    static String wrapControlled(ObjectMapper json, String plannerRawJson, ProjectRuntimeContext context,
                                 JsonNode controlledPlanEnvelope, String governedMemoryContext,
                                 boolean persistConversationSummary) {
        if (context == null || controlledPlanEnvelope == null || !controlledPlanEnvelope.isObject()) {
            throw new IllegalArgumentException("controlled Project Plan envelope requires Project context");
        }
        return wrap(json, plannerRawJson, context, controlledPlanEnvelope, governedMemoryContext,
                persistConversationSummary);
    }

    private static String wrap(ObjectMapper json, String plannerRawJson, ProjectRuntimeContext context,
                               JsonNode controlledPlanEnvelope, String governedMemoryContext,
                               boolean persistConversationSummary) {
        ObjectNode root = json.createObjectNode();
        boolean hasMemory = governedMemoryContext != null && !governedMemoryContext.isBlank();
        root.put("schemaVersion", !persistConversationSummary ? SCHEMA_V5
                : controlledPlanEnvelope != null ? hasMemory ? SCHEMA_V4 : SCHEMA_V2
                : hasMemory ? SCHEMA_V3 : SCHEMA_V1);
        root.put("plannerRawJson", plannerRawJson == null ? "" : plannerRawJson);
        if (context == null) {
            root.putNull("serverAttestedProjectContext");
        } else {
            ObjectNode trusted = root.putObject("serverAttestedProjectContext");
            trusted.put("projectId", context.projectId());
            trusted.put("capability", "PROJECT_READ");
        }
        if (controlledPlanEnvelope != null) {
            root.set("controlledPlanEnvelope", controlledPlanEnvelope.deepCopy());
        }
        if (hasMemory) {
            root.put("serverSelectedMemoryContext", governedMemoryContext);
        }
        if (!persistConversationSummary) {
            root.put("serverSelectedConversationSummaryPersistence", false);
        }
        return write(json, root);
    }

    static ProjectRuntimeContext restore(ObjectMapper json, String raw, Long userId) {
        if (raw == null || userId == null) return null;
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isObject()) return null; // legacy planner JSON is never trusted.
            boolean claimsEnvelope = root.has("schemaVersion") || root.has("serverAttestedProjectContext") || root.has("plannerRawJson");
            if (!claimsEnvelope) return null;
            String schema = root.path("schemaVersion").asText();
            boolean v1 = SCHEMA_V1.equals(schema);
            boolean v2 = SCHEMA_V2.equals(schema);
            boolean v3 = SCHEMA_V3.equals(schema);
            boolean v4 = SCHEMA_V4.equals(schema);
            boolean v5 = SCHEMA_V5.equals(schema);
            int v5Size = 4 + (root.has("controlledPlanEnvelope") ? 1 : 0)
                    + (root.has("serverSelectedMemoryContext") ? 1 : 0);
            if ((!v1 && !v2 && !v3 && !v4 && !v5)
                    || root.size() != (v1 ? 3 : v4 ? 5 : v5 ? v5Size : 4)
                    || !root.path("plannerRawJson").isTextual() || !root.has("serverAttestedProjectContext")) {
                throw new IllegalStateException("Invalid server-owned Project Plan envelope");
            }
            if ((v2 || v4) && !root.path("controlledPlanEnvelope").isObject()) {
                throw new IllegalStateException("Invalid controlled Project Plan envelope");
            }
            if ((v3 || v4) && (!root.path("serverSelectedMemoryContext").isTextual()
                    || root.path("serverSelectedMemoryContext").asText().length() > 3_000)) {
                throw new IllegalStateException("Invalid governed memory context in Project Plan envelope");
            }
            if (v5 && (!root.path("serverSelectedConversationSummaryPersistence").isBoolean()
                    || root.path("serverSelectedConversationSummaryPersistence").booleanValue()
                    || (root.has("controlledPlanEnvelope") && !root.path("controlledPlanEnvelope").isObject())
                    || (root.has("serverSelectedMemoryContext")
                    && (!root.path("serverSelectedMemoryContext").isTextual()
                    || root.path("serverSelectedMemoryContext").asText().length() > 3_000)))) {
                throw new IllegalStateException("Invalid unified Project Plan presentation policy");
            }
            JsonNode trusted = root.get("serverAttestedProjectContext");
            if (trusted == null || trusted.isNull()) return null;
            if (!trusted.isObject() || trusted.size() != 2 || !trusted.path("projectId").canConvertToLong()
                    || trusted.path("projectId").longValue() <= 0 || !"PROJECT_READ".equals(trusted.path("capability").asText())) {
                throw new IllegalStateException("Invalid server-attested Project Plan context");
            }
            return new ProjectRuntimeContext(userId, trusted.path("projectId").longValue());
        } catch (IllegalStateException ex) { throw ex;
        } catch (Exception ex) { throw new IllegalStateException("Invalid Project Plan envelope", ex); }
    }

    static JsonNode restoreControlled(ObjectMapper json, String raw, Long userId) {
        ProjectRuntimeContext context = restore(json, raw, userId);
        if (context == null) return null;
        try {
            JsonNode root = json.readTree(raw);
            String schema = root.path("schemaVersion").asText();
            if (!SCHEMA_V2.equals(schema) && !SCHEMA_V4.equals(schema)
                    && !(SCHEMA_V5.equals(schema) && root.has("controlledPlanEnvelope"))) return null;
            return root.path("controlledPlanEnvelope").deepCopy();
        } catch (IllegalStateException ex) { throw ex;
        } catch (Exception ex) { throw new IllegalStateException("Invalid controlled Project Plan envelope", ex); }
    }

    static String restoreGovernedMemory(ObjectMapper json, String raw, Long userId) {
        restore(json, raw, userId);
        if (raw == null) return null;
        try {
            JsonNode root = json.readTree(raw);
            String schema = root.path("schemaVersion").asText();
            if (!SCHEMA_V3.equals(schema) && !SCHEMA_V4.equals(schema)
                    && !(SCHEMA_V5.equals(schema) && root.has("serverSelectedMemoryContext"))) return null;
            String memory = root.path("serverSelectedMemoryContext").asText(null);
            return memory == null || memory.isBlank() ? null : memory;
        } catch (IllegalStateException ex) { throw ex;
        } catch (Exception ex) { throw new IllegalStateException("Invalid Project Plan memory context", ex); }
    }

    static boolean restoreConversationSummaryPersistence(ObjectMapper json, String raw, Long userId) {
        restore(json, raw, userId);
        if (raw == null) return true;
        try {
            JsonNode root = json.readTree(raw);
            if (!SCHEMA_V5.equals(root.path("schemaVersion").asText())) return true;
            return root.path("serverSelectedConversationSummaryPersistence").booleanValue();
        } catch (IllegalStateException ex) { throw ex;
        } catch (Exception ex) { throw new IllegalStateException("Invalid Project Plan presentation policy", ex); }
    }

    /** Returns the normalized planner payload after validating the server-owned wrapper. */
    static String restorePlannerRawJson(ObjectMapper json, String raw, Long userId) {
        restore(json, raw, userId);
        if (raw == null) return null;
        try {
            JsonNode root = json.readTree(raw);
            String plannerRawJson = root.path("plannerRawJson").asText(null);
            return plannerRawJson == null || plannerRawJson.isBlank() ? null : plannerRawJson;
        } catch (IllegalStateException ex) { throw ex;
        } catch (Exception ex) { throw new IllegalStateException("Invalid Project Plan planner payload", ex); }
    }

    private static String write(ObjectMapper json, ObjectNode root) {
        try { return json.writeValueAsString(root); }
        catch (Exception ex) { throw new IllegalStateException("Cannot persist Project plan envelope", ex); }
    }
}
