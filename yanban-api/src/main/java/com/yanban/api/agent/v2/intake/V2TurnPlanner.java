package com.yanban.api.agent.v2.intake;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentContextPackage;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.ResolvedSkill;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.Route;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.runtime.planning.InitialPlanDraft;
import io.paperagent.v2.runtime.taskframe.TaskFrameDraft;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.Map;
import io.paperagent.v2.providers.CorrelationId;
import io.paperagent.v2.providers.GenerationOptions;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelRequestId;
import io.paperagent.v2.providers.ModelResponse;

final class V2TurnPlanner {
    private static final int MAX_OUTPUT_CHARACTERS = 32_000;
    private static final int MAX_LIST_ITEMS = 16;
    private static final int MAX_TEXT = 2_000;
    private static final int MAX_STEPS = 8;
    private static final String SYSTEM_PROMPT = """
            You are the V2 intake planner. Return exactly one JSON object and no markdown.
            Choose route DIRECT only for an answer that needs no Project, tool, execution,
            network access, or modification. A Project session must be persistent.
            Otherwise choose PERSISTENT_PLAN_EXECUTE and author a bounded TaskFrame and Plan.
            Do not call tools while planning. Plan steps describe goals,
            dependencies and completion criteria only.
            Do not assign a tool or capability to a step.
            The execution model will select tools
            dynamically from the task-level catalog after the Plan is stored.
            DIRECT schema:
            {"route":"DIRECT","answer":"nonblank"}
            Persistent schema:
            {"route":"PERSISTENT_PLAN_EXECUTE",
             "taskFrame":{"objective":"...","targets":["..."],"deliverables":["..."],"constraints":["..."]},
             "plan":{"reason":"...","steps":[{"id":"step-1","intent":"...",
               "expectedOutcome":"...","dependencies":[],"completionCriteria":["..."],
               "maxAttempts":1,"maxDurationSeconds":120}]}}
            Use 1-8 ordered steps.
            """;

    private final ChatModelProvider provider;
    private final ObjectMapper json;

    V2TurnPlanner(ChatModelProvider provider, ObjectMapper json) {
        this.provider = provider;
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    PlannedTurn plan(
            AgentContextPackage context,
            UserSettingsService.ModelEndpoint endpoint,
            ResolvedSkill skill,
            boolean projectSession,
            String traceId) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(SYSTEM_PROMPT));
        prompt.add(ChatMessage.system(capabilityCatalog()));
        if (projectSession) {
            prompt.add(ChatMessage.system("""
                    This authenticated turn is bound to a Project.
                    You MUST return PERSISTENT_PLAN_EXECUTE, even when the
                    request looks conversational. Use project_read or
                    project_search when Project evidence is required.
                    Do not answer from conversation context alone.
                    """));
        }
        if (skill != null) {
            prompt.add(ChatMessage.system(
                    "User-selected Skill instructions:\n"
                            + bounded(
                                    skill.prompt(), 8_000,
                                    "SKILL_PROMPT")));
        }
        prompt.addAll(historyWithoutCurrentWorkspaceMessage(context));
        if (context.currentUserMessage() != null) {
            prompt.add(context.currentUserMessage());
        }
        ModelRequest modelRequest = new ModelRequest(
                new ModelRequestId(traceId + "-request"),
                new CorrelationId(traceId),
                prompt.stream().map(V2TurnPlanner::modelMessage).toList(),
                List.of(),
                new GenerationOptions(
                        4_096, 0, 0.1d,
                        OptionalLong.empty(), Map.of()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false);
        io.paperagent.v2.providers.ModelProviderResult result;
        try {
            result = new V2IntakePlanningProviderAdapter(
                    provider, endpoint).complete(modelRequest);
        } catch (RuntimeException failure) {
            throw new V2TurnPlanningException(
                    "MODEL_CALL_FAILED", "planner model call failed");
        }
        if (!(result instanceof ModelResponse response)
                || !response.proposedToolCalls().isEmpty()) {
            throw new V2TurnPlanningException(
                    "MODEL_RESULT_INVALID", "planner response is invalid");
        }
        String raw = response.assistantText().orElse(null);
        if (raw == null || raw.isBlank()) {
            throw new V2TurnPlanningException(
                    "MODEL_OUTPUT_EMPTY", "planner response is empty");
        }
        if (raw.length() > MAX_OUTPUT_CHARACTERS) {
            throw new V2TurnPlanningException(
                    "MODEL_OUTPUT_TOO_LARGE",
                    "planner response exceeds the limit")
                    .withOutputDigest(hash(raw));
        }
        PlannedTurn planned;
        try {
            planned = parse(raw).withRawOutput(raw);
        } catch (V2TurnPlanningException failure) {
            throw failure.withOutputDigest(hash(raw));
        }
        if (projectSession && planned.route() == Route.DIRECT) {
            throw new V2TurnPlanningException(
                    "PROJECT_DIRECT",
                    "Project turns require a persistent Plan")
                    .withOutputDigest(hash(raw));
        }
        if (!planned.capabilities().isEmpty()
                && planned.route() != Route.PERSISTENT_PLAN_EXECUTE) {
            throw new V2TurnPlanningException(
                    "DIRECT_WITH_CAPABILITY",
                    "tool use requires a persistent Plan")
                    .withOutputDigest(hash(raw));
        }
        return planned;
    }

    private static List<ChatMessage> historyWithoutCurrentWorkspaceMessage(
            AgentContextPackage context) {
        List<ChatMessage> history = context.messages();
        ChatMessage current = context.currentUserMessage();
        boolean workspaceHistory = context.sections().stream()
                .anyMatch(section -> "recent_messages".equals(section.type()));
        if (!workspaceHistory || current == null || history.isEmpty()) {
            return history;
        }
        int last = history.size() - 1;
        ChatMessage candidate = history.get(last);
        if (!"user".equals(candidate.role())
                || !java.util.Objects.equals(
                        candidate.content(), current.content())) {
            return history;
        }
        return List.copyOf(history.subList(0, last));
    }

    private static ModelMessage modelMessage(ChatMessage message) {
        MessageRole role = switch (message.role()) {
            case "system" -> MessageRole.SYSTEM;
            case "assistant" -> MessageRole.ASSISTANT;
            case "tool", "process" -> MessageRole.TOOL_FACT;
            default -> MessageRole.USER;
        };
        return new ModelMessage(
                role,
                message.content() == null ? "[empty]" : message.content());
    }

    PlannedTurn parse(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            requireObject(root, "root");
            String routeText = requiredText(root, "route", 32);
            if ("DIRECT".equals(routeText)) {
                exactFields(root, Set.of("route", "answer"),
                        "DIRECT_FIELDS");
                return PlannedTurn.direct(requiredText(root, "answer", 20_000));
            }
            if (!"PERSISTENT_PLAN_EXECUTE".equals(routeText)) {
                throw invalid("ROUTE_VALUE");
            }
            exactFields(root, Set.of("route", "taskFrame", "plan"),
                    "PERSISTENT_FIELDS");
            JsonNode frame = requiredObject(root, "taskFrame");
            exactFields(frame, Set.of(
                    "objective", "targets", "deliverables", "constraints"),
                    "TASK_FRAME_FIELDS");
            TaskFrameDraft taskFrame = new TaskFrameDraft(
                    requiredText(frame, "objective", MAX_TEXT),
                    textList(frame, "targets", true),
                    textList(frame, "deliverables", true),
                    textList(frame, "constraints", true));

            JsonNode plan = requiredObject(root, "plan");
            exactFields(plan, Set.of("reason", "steps"), "PLAN_FIELDS");
            JsonNode stepsNode = plan.get("steps");
            if (stepsNode == null || !stepsNode.isArray()
                    || stepsNode.isEmpty() || stepsNode.size() > MAX_STEPS) {
                throw invalid("PLAN_STEPS");
            }
            List<PlanStep> steps = new ArrayList<>();
            List<PlannedCapability> capabilities = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode step : stepsNode) {
                requireObject(step, "step");
                exactFieldsOneOf(step, Set.of(
                                "id", "intent", "expectedOutcome",
                                "dependencies", "completionCriteria",
                                "maxAttempts", "maxDurationSeconds"),
                        Set.of(
                                "id", "intent", "expectedOutcome",
                                "dependencies", "completionCriteria",
                                "maxAttempts", "maxDurationSeconds",
                                "capability"),
                        "STEP_FIELDS");
                String id = requiredText(step, "id", 128);
                if (!seen.add(id)) {
                    throw invalid("STEP_ID_DUPLICATE");
                }
                List<String> dependencies = textList(
                        step, "dependencies", false);
                if (!seen.containsAll(dependencies) || dependencies.contains(id)) {
                    throw invalid("STEP_DEPENDENCIES");
                }
                int attempts = boundedInt(step, "maxAttempts", 1, 5);
                int duration = boundedInt(
                        step, "maxDurationSeconds", 1, 3_600);
                PlanStep planStep = new PlanStep(
                        new PlanStepId(id),
                        requiredText(step, "intent", MAX_TEXT),
                        requiredText(step, "expectedOutcome", MAX_TEXT),
                        dependencies.stream().map(PlanStepId::new)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        textList(step, "completionCriteria", true),
                        new BoundedExecutionHints(
                                attempts, Duration.ofSeconds(duration)));
                steps.add(planStep);
                JsonNode capability = step.get("capability");
                if (capability != null && !capability.isNull()) {
                    if (!capability.isTextual()) {
                        throw invalid("CAPABILITY_TYPE");
                    }
                    String alias = bounded(
                            capability.textValue(), 64, "CAPABILITY_VALUE");
                    if (alias.contains(".")) {
                        throw invalid("CAPABILITY_DOTTED");
                    }
                    ToolId internal;
                    try {
                        internal = V2PlannerCapabilityCatalog
                                .internalToolId(alias);
                    } catch (V2TurnPlanningException failure) {
                        throw invalid("CAPABILITY_UNSUPPORTED");
                    }
                    capabilities.add(new PlannedCapability(
                            planStep.id(), alias, internal));
                }
            }
            return PlannedTurn.persistent(
                    taskFrame,
                    new InitialPlanDraft(
                            requiredText(plan, "reason", MAX_TEXT), steps),
                    capabilities);
        } catch (V2TurnPlanningException failure) {
            throw failure;
        } catch (java.io.IOException failure) {
            throw invalid("JSON_SYNTAX");
        } catch (RuntimeException failure) {
            throw invalid("PARSE_RUNTIME");
        }
    }

    private String capabilityCatalog() {
        StringBuilder value = new StringBuilder(
                "Available capability catalog (public aliases only):\n");
        for (var capability : V2PlannerCapabilityCatalog.publicCapabilities()) {
            value.append("- ").append(capability.name()).append(": ")
                    .append(capability.description()).append('\n');
        }
        return value.toString();
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        requireObject(node, field);
        return node;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw invalid("OBJECT_" + diagnostic(field));
        }
    }

    private static void exactFields(
            JsonNode node, Set<String> allowed, String diagnostic) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(allowed)) {
            throw invalid(diagnostic);
        }
    }

    private static void exactFieldsOneOf(
            JsonNode node, Set<String> current, Set<String> legacy,
            String diagnostic) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(current) && !actual.equals(legacy)) {
            throw invalid(diagnostic);
        }
    }

    private static String requiredText(
            JsonNode parent, String field, int maximum) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual()) {
            throw invalid("TEXT_" + diagnostic(field));
        }
        return bounded(
                node.textValue(), maximum, "TEXT_" + diagnostic(field));
    }

    private static String bounded(
            String value, int maximum, String diagnostic) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw invalid(diagnostic);
        }
        return value.trim();
    }

    private static List<String> textList(
            JsonNode parent, String field, boolean required) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()
                || node.size() > MAX_LIST_ITEMS
                || required && node.isEmpty()) {
            throw invalid("LIST_" + diagnostic(field));
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw invalid("LIST_" + diagnostic(field));
            }
            String value = bounded(
                    item.textValue(), MAX_TEXT,
                    "LIST_" + diagnostic(field));
            if (!unique.add(value)) {
                throw invalid("LIST_" + diagnostic(field));
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static int boundedInt(
            JsonNode parent, String field, int minimum, int maximum) {
        JsonNode node = parent.get(field);
        if (node == null || !node.canConvertToInt()) {
            throw invalid("INT_" + diagnostic(field));
        }
        int value = node.intValue();
        if (value < minimum || value > maximum) {
            throw invalid("INT_" + diagnostic(field));
        }
        return value;
    }

    private static String diagnostic(String field) {
        return field.replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase(java.util.Locale.ROOT);
    }

    private static V2TurnPlanningException invalid(String diagnostic) {
        return new V2TurnPlanningException(
                diagnostic, "planner response is invalid");
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    record PlannedCapability(
            PlanStepId stepId, String publicAlias, ToolId internalToolId) {
    }

    record PlannedTurn(
            Route route,
            String answer,
            TaskFrameDraft taskFrame,
            InitialPlanDraft plan,
            List<PlannedCapability> capabilities,
            String rawOutput) {
        PlannedTurn {
            capabilities = capabilities == null
                    ? List.of() : List.copyOf(capabilities);
        }

        static PlannedTurn direct(String answer) {
            return new PlannedTurn(
                    Route.DIRECT, answer, null, null, List.of(), null);
        }

        static PlannedTurn persistent(
                TaskFrameDraft taskFrame,
                InitialPlanDraft plan,
                List<PlannedCapability> capabilities) {
            return new PlannedTurn(
                    Route.PERSISTENT_PLAN_EXECUTE,
                    null,
                    taskFrame,
                    plan,
                    capabilities,
                    null);
        }

        PlannedTurn withRawOutput(String value) {
            return new PlannedTurn(
                    route, answer, taskFrame, plan, capabilities, value);
        }
    }
}
