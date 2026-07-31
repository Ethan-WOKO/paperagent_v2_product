package com.yanban.api.agent.v2.adaptive.reflection;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ToolId;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the untrusted model response without accepting schema extensions.
 */
public final class StrictReflectionDecisionParser {
    public static final int MAX_OUTPUT_CHARACTERS = 32_000;
    private static final int MAX_REASON_CHARACTERS = 2_000;
    private static final int MAX_FINAL_TEXT_CHARACTERS = 20_000;
    private static final int MAX_TEXT_CHARACTERS = 2_000;
    private static final int MAX_LIST_ITEMS = 16;
    private static final int MAX_STEPS = 8;
    private static final Map<String, String> CAPABILITIES = Map.of(
            "literature_search", "literature.search",
            "project_read", "project.read",
            "project_search", "project.search",
            "project_candidate", "project.candidate.compose",
            "sandbox_execute", "sandbox.execute");

    private final ObjectMapper json;

    public StrictReflectionDecisionParser(ObjectMapper json) {
        if (json == null) {
            throw new IllegalArgumentException("json is required");
        }
        this.json = json.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public ReflectionOutcome parse(String raw) {
        if (raw == null || raw.isBlank()
                || raw.length() > MAX_OUTPUT_CHARACTERS) {
            throw invalid();
        }
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isObject()) {
                throw invalid();
            }
            String decisionText = requiredText(root, "decision", 32);
            ReflectionAction decision;
            try {
                decision = ReflectionAction.valueOf(decisionText);
            } catch (IllegalArgumentException failure) {
                throw invalid();
            }

            exactFields(root, Set.of(
                    "decision", "reason", "finalText", "replacementSteps"));
            JsonNode finalTextNode = root.get("finalText");
            String finalText;
            if (decision == ReflectionAction.COMPLETE) {
                finalText = requiredText(
                        root, "finalText", MAX_FINAL_TEXT_CHARACTERS);
            } else {
                if (finalTextNode == null || !finalTextNode.isNull()) {
                    throw invalid();
                }
                finalText = null;
            }
            List<ReflectionReplacementStep> replacementSteps =
                    replacementSteps(root, decision);
            return new ReflectionOutcome(
                    decision,
                    requiredText(root, "reason", MAX_REASON_CHARACTERS),
                    finalText,
                    replacementSteps);
        } catch (ReflectionParseException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    private static List<ReflectionReplacementStep> replacementSteps(
            JsonNode root, ReflectionAction decision) {
        JsonNode stepsNode = root.get("replacementSteps");
        if (stepsNode == null || !stepsNode.isArray()
                || stepsNode.size() > MAX_STEPS) {
            throw invalid();
        }
        if (decision == ReflectionAction.REPLAN) {
            if (stepsNode.isEmpty()) {
                throw invalid();
            }
        } else if (!stepsNode.isEmpty()) {
            throw invalid();
        }

        List<ReflectionReplacementStep> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode step : stepsNode) {
            if (!step.isObject()) {
                throw invalid();
            }
            Set<String> fields = new HashSet<>();
            step.fieldNames().forEachRemaining(fields::add);
            Set<String> currentFields = Set.of(
                    "id", "intent", "expectedOutcome", "dependencies",
                    "completionCriteria", "maxAttempts",
                    "maxDurationSeconds");
            Set<String> legacyFields = new HashSet<>(currentFields);
            legacyFields.add("capability");
            if (!fields.equals(currentFields)
                    && !fields.equals(legacyFields)) {
                throw invalid();
            }
            String id = requiredText(step, "id", 128);
            if (!seen.add(id)) {
                throw invalid();
            }
            List<String> dependencies = textList(
                    step, "dependencies", false);
            if (!seen.containsAll(dependencies) || dependencies.contains(id)) {
                throw invalid();
            }
            PlanStep planStep = new PlanStep(
                    new PlanStepId(id),
                    requiredText(step, "intent", MAX_TEXT_CHARACTERS),
                    requiredText(
                            step, "expectedOutcome", MAX_TEXT_CHARACTERS),
                    dependencies.stream().map(PlanStepId::new)
                            .collect(java.util.stream.Collectors
                                    .toUnmodifiableSet()),
                    textList(step, "completionCriteria", true),
                    new BoundedExecutionHints(
                            boundedInt(step, "maxAttempts", 1, 5),
                            Duration.ofSeconds(boundedInt(
                                    step,
                                    "maxDurationSeconds",
                                    1,
                                    3_600))));
            JsonNode capability = step.get("capability");
            String publicAlias = null;
            ToolId internalToolId = null;
            if (capability != null && !capability.isNull()) {
                if (!capability.isTextual()) {
                    throw invalid();
                }
                publicAlias = bounded(capability.textValue(), 64);
                String internal = CAPABILITIES.get(publicAlias);
                if (publicAlias.contains(".") || internal == null) {
                    throw invalid();
                }
                internalToolId = new ToolId(internal);
            }
            result.add(new ReflectionReplacementStep(
                    planStep, publicAlias, internalToolId));
        }
        return List.copyOf(result);
    }

    private static void exactFields(JsonNode node, Set<String> allowed) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(allowed)) {
            throw invalid();
        }
    }

    private static String requiredText(
            JsonNode parent, String field, int maximum) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual()) {
            throw invalid();
        }
        return bounded(node.textValue(), maximum);
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw invalid();
        }
        return value.trim();
    }

    private static List<String> textList(
            JsonNode parent, String field, boolean required) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()
                || node.size() > MAX_LIST_ITEMS
                || required && node.isEmpty()) {
            throw invalid();
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw invalid();
            }
            String value = bounded(item.textValue(), MAX_TEXT_CHARACTERS);
            if (!unique.add(value)) {
                throw invalid();
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static int boundedInt(
            JsonNode parent, String field, int minimum, int maximum) {
        JsonNode node = parent.get(field);
        if (node == null || !node.canConvertToInt()) {
            throw invalid();
        }
        int value = node.intValue();
        if (value < minimum || value > maximum) {
            throw invalid();
        }
        return value;
    }

    private static ReflectionParseException invalid() {
        return new ReflectionParseException();
    }
}
