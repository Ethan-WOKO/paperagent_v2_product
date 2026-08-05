package com.yanban.api.agent.v2.intake;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.AgentContextPackage;
import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
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
import java.util.TreeSet;
import java.util.Map;
import io.paperagent.v2.providers.CorrelationId;
import io.paperagent.v2.providers.GenerationOptions;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.providers.ProviderFailure;
import io.paperagent.v2.providers.ProviderFailureCode;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelRequestId;
import io.paperagent.v2.providers.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class V2TurnPlanner {
    private static final Logger log = LoggerFactory.getLogger(
            V2TurnPlanner.class);
    private static final int MAX_OUTPUT_CHARACTERS = 32_000;
    private static final int MAX_LIST_ITEMS = 16;
    private static final int MAX_TEXT = 2_000;
    private static final int MAX_STEPS = 8;
    private static final String SYSTEM_PROMPT = """
            You are the intake planner. Understand the user's current request,
            choose its execution route, and, when needed, create a small durable
            TaskFrame and Plan. Return exactly one JSON object and no markdown.
            The supplied capability catalog is task-level information for
            judging feasibility; never bind a Plan Step to a named tool or
            capability.

            Choose DIRECT only when all of the following are true:
            1. The answer can be produced entirely from the user's supplied text,
               general reasoning, or already-completed authoritative facts.
            2. No current Project file, Candidate, revision, evidence, or mutable
               Project state must be inspected.
            3. No tool, retrieval, network access, execution, sandbox, compilation,
               test, or validation is required.
            4. No file, Candidate, Project version, or other durable state will change.
            5. No multi-step progress must survive refresh or recovery.

            Choose PERSISTENT_PLAN_EXECUTE when any of the following is required:
            - inspect, search, quote, compare, or reason from current Project content;
            - inspect a Candidate, validation, revision, or current Project status;
            - create, edit, delete, apply, or otherwise modify durable content;
            - call a tool, retrieve evidence, access the network, or use RAG;
            - compile, run, test, validate, or use a sandbox;
            - preserve multi-step progress across refresh or recovery.

            The presence of a Project alone does not require a persistent Plan.
            Conversation history is not proof of current mutable Project state.
            If uncertain whether current Project evidence or an external capability is
            required, choose PERSISTENT_PLAN_EXECUTE.

            Always include requirements with exactly these boolean fields:
            projectEvidence, toolUse, retrieval, networkAccess, execution,
            durableModification, durableProgress.
            DIRECT requires every requirements field to be false.
            PERSISTENT_PLAN_EXECUTE requires at least one requirements field to be true.

            Routing examples:
            - "What is 1+1?" -> DIRECT with every requirement false.
            - "Explain merge sort in general." -> DIRECT with every requirement false.
            - "Does the current Sort.java contain merge sort?" ->
              PERSISTENT_PLAN_EXECUTE with projectEvidence true.
            - "Remove merge sort from Sort.java and compile it." ->
              PERSISTENT_PLAN_EXECUTE with projectEvidence, execution, and
              durableModification true.
            - "Has Candidate 43 been applied?" -> PERSISTENT_PLAN_EXECUTE with
              projectEvidence true.
            - "Search the literature and summarize the results." ->
              PERSISTENT_PLAN_EXECUTE with retrieval and networkAccess true.

            Otherwise choose PERSISTENT_PLAN_EXECUTE and author a bounded
            TaskFrame and Plan. Do not call tools while planning. Each Step must
            describe only a user-meaningful goal, expected outcome,
            dependencies, and completion criteria. Do not put tool names,
            internal storage objects, candidate creation, publication,
            automatic application, rollback bookkeeping, user confirmation, or
            redundant validation into the Plan. The runtime handles persistence
            and applies a verified working-copy revision after the Plan's real
            work succeeds. Prefer the fewest Steps that preserve genuine
            dependencies. Do not add defensive Steps for hypothetical failures;
            execution and reflection handle actual failures when they occur.
            DIRECT schema:
            {"route":"DIRECT","requirements":{"projectEvidence":false,
             "toolUse":false,"retrieval":false,"networkAccess":false,
             "execution":false,"durableModification":false,
             "durableProgress":false},"answer":"nonblank"}
            Persistent schema:
            {"route":"PERSISTENT_PLAN_EXECUTE",
             "requirements":{"projectEvidence":true,"toolUse":true,
              "retrieval":false,"networkAccess":false,"execution":false,
              "durableModification":false,"durableProgress":true},
             "taskFrame":{"objective":"...","targets":["..."],"deliverables":["..."],"constraints":["..."]},
             "plan":{"reason":"...","steps":[{"id":"step-1","intent":"...",
               "expectedOutcome":"...","dependencies":[],"completionCriteria":["..."],
               "maxAttempts":1,"maxDurationSeconds":120}]}}
            Use 1-8 ordered steps.
            """;
    private static final String PROTOCOL_RETRY_PROMPT = """
            Return exactly one JSON object and no markdown.
            Route DIRECT only when the current request needs no current
            Project evidence or state, no tool, retrieval, network,
            execution, sandbox, test, validation, durable modification, or
            durable multi-step progress. A Project being attached does not by
            itself prevent DIRECT. Otherwise route
            PERSISTENT_PLAN_EXECUTE.
            Always include requirements with exactly these booleans:
            projectEvidence, toolUse, retrieval, networkAccess, execution,
            durableModification, durableProgress. Every value must be false
            for DIRECT; at least one must be true for persistent execution.
            For example, for "What is 1+1?" return exactly:
            {"route":"DIRECT","requirements":{"projectEvidence":false,
             "toolUse":false,"retrieval":false,"networkAccess":false,
             "execution":false,"durableModification":false,
             "durableProgress":false},"answer":"2"}
            DIRECT must include a nonblank answer. Persistent execution must
            include taskFrame with objective, targets, deliverables and
            constraints, plus plan with reason and 1-8 ordered steps. Every
            step has id, intent, expectedOutcome, dependencies,
            completionCriteria, maxAttempts and maxDurationSeconds. Steps
            describe goals, not tool bindings. Do not call tools.
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
                    An authenticated Project is available to this turn.
                    Project availability alone does not determine the route.
                    If the answer depends on current Project evidence or state,
                    choose PERSISTENT_PLAN_EXECUTE so execution can obtain an
                    authoritative result. Do not treat conversation history as
                    proof of current mutable Project state.
                    """));
        }
        if (skill != null) {
            prompt.add(ChatMessage.system(
                    "User-selected Skill instructions:\n"
                            + bounded(
                                    skill.prompt(), 8_000,
                                    "SKILL_PROMPT", "skill prompt")));
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
        ModelProvider planningProvider = new V2IntakePlanningProviderAdapter(
                provider, endpoint);
        String raw;
        try {
            raw = complete(
                    planningProvider, modelRequest, traceId,
                    projectSession, "initial");
        } catch (V2TurnPlanningException failure) {
            if (!"MODEL_RESULT_INVALID".equals(failure.diagnostic())) {
                throw failure;
            }
            log.warn(
                    "V2 intake planner protocol retry requested "
                            + "traceId={} projectSession={} diagnostic={}",
                    traceId, projectSession, failure.diagnostic());
            raw = complete(
                    planningProvider,
                    protocolRetryRequest(
                            modelRequest, traceId, projectSession,
                            skill != null),
                    traceId, projectSession, "protocol-retry");
        }
        PlannedTurn planned;
        try {
            planned = parse(raw).withRawOutput(raw);
        } catch (V2TurnPlanningException firstFailure) {
            log.warn(
                    "V2 intake planner format repair requested traceId={} "
                            + "projectSession={} diagnostic={} "
                            + "validationDetail={} outputDigest={}",
                    traceId, projectSession, firstFailure.diagnostic(),
                    firstFailure.getMessage(),
                    hash(raw).substring(0, 12));
            ModelRequest repairRequest = formatRepairRequest(
                    modelRequest, traceId, firstFailure.getMessage(), raw);
            raw = complete(
                    planningProvider, repairRequest, traceId,
                    projectSession, "format-repair");
            try {
                planned = parse(raw).withRawOutput(raw);
            } catch (V2TurnPlanningException repairFailure) {
                throw repairFailure.withOutputDigest(hash(raw));
            }
        }
        auditRouteRequirements(planned, raw);
        if (!planned.capabilities().isEmpty()
                && planned.route() != Route.PERSISTENT_PLAN_EXECUTE) {
            throw new V2TurnPlanningException(
                    "DIRECT_WITH_CAPABILITY",
                    "tool use requires a persistent Plan")
                    .withOutputDigest(hash(raw));
        }
        return planned;
    }

    private String complete(
            ModelProvider planningProvider, ModelRequest modelRequest,
            String traceId, boolean projectSession, String phase) {
        io.paperagent.v2.providers.ModelProviderResult result;
        long modelStarted = System.nanoTime();
        log.info(
                "V2 intake planner model call started traceId={} "
                        + "projectSession={} phase={}",
                traceId, projectSession, phase);
        try {
            result = planningProvider.complete(modelRequest);
        } catch (RuntimeException failure) {
            log.warn(
                    "V2 intake planner model call failed traceId={} "
                            + "projectSession={} phase={} elapsedMillis={} "
                            + "exceptionType={} causeType={} origin={}",
                    traceId, projectSession, phase,
                    elapsedMillis(modelStarted),
                    V2SafeFailureDiagnostics.exceptionType(failure),
                    V2SafeFailureDiagnostics.causeType(failure),
                    V2SafeFailureDiagnostics.origin(failure));
            throw new V2TurnPlanningException(
                    "MODEL_CALL_FAILED", "planner model call failed");
        }
        log.info(
                "V2 intake planner model call completed traceId={} "
                        + "projectSession={} phase={} elapsedMillis={} "
                        + "resultType={}",
                traceId, projectSession, phase,
                elapsedMillis(modelStarted),
                result == null ? "null"
                        : result.getClass().getSimpleName());
        if (result instanceof ProviderFailure failure) {
            String diagnostic = failure.code()
                    == ProviderFailureCode.PROTOCOL_VIOLATION
                    ? "MODEL_RESULT_INVALID" : "MODEL_CALL_FAILED";
            throw new V2TurnPlanningException(
                    diagnostic, "planner provider call failed");
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
        return raw;
    }

    private static ModelRequest protocolRetryRequest(
            ModelRequest source, String traceId,
            boolean projectSession, boolean skillSelected) {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(new ModelMessage(
                MessageRole.SYSTEM, PROTOCOL_RETRY_PROMPT));
        int selectedSystemMessages = 2;
        if (projectSession) {
            source.messages().stream()
                    .filter(message -> message.role() == MessageRole.SYSTEM)
                    .skip(selectedSystemMessages)
                    .findFirst()
                    .ifPresent(messages::add);
            selectedSystemMessages++;
        }
        if (skillSelected) {
            source.messages().stream()
                    .filter(message -> message.role() == MessageRole.SYSTEM)
                    .skip(selectedSystemMessages)
                    .findFirst()
                    .ifPresent(messages::add);
        }
        messages.add(new ModelMessage(
                MessageRole.SYSTEM,
                "The previous provider response contained no usable "
                        + "assistant JSON. Apply the same routing rules and "
                        + "return exactly one complete JSON object now."));
        List<ModelMessage> conversational = source.messages().stream()
                .filter(message -> message.role() != MessageRole.SYSTEM)
                .toList();
        int first = Math.max(0, conversational.size() - 4);
        messages.addAll(conversational.subList(
                first, conversational.size()));
        return new ModelRequest(
                new ModelRequestId(traceId + "-protocol-retry-request"),
                new CorrelationId(traceId + "-protocol-retry"),
                List.copyOf(messages), source.availableTools(),
                source.generationOptions(), source.taskFrameId(),
                source.planId(), source.planRevisionId(), source.stepId(),
                source.cancellationRequested());
    }

    private static ModelRequest formatRepairRequest(
            ModelRequest source, String traceId, String validationDetail,
            String previousOutput) {
        List<ModelMessage> messages = new ArrayList<>(source.messages());
        messages.add(new ModelMessage(
                MessageRole.ASSISTANT,
                previousOutput));
        messages.add(new ModelMessage(
                MessageRole.USER,
                "Your previous planner response failed validation. "
                        + "Exact error: " + validationDetail + ". Fix that "
                        + "error, check the complete original schema, and "
                        + "rewrite the response for the "
                        + "same request as exactly one top-level "
                        + "JSON object, never an array, JSON string, prose, "
                        + "or markdown fence. Include the complete route and "
                        + "requirements wrapper, plus either answer or the "
                        + "taskFrame and plan wrapper required by the "
                        + "original schema."));
        return new ModelRequest(
                new ModelRequestId(traceId + "-format-repair-request"),
                new CorrelationId(traceId + "-format-repair"),
                List.copyOf(messages), source.availableTools(),
                source.generationOptions(), source.taskFrameId(),
                source.planId(), source.planRevisionId(), source.stepId(),
                source.cancellationRequested());
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
            JsonNode root = normalizeTopLevel(json.readTree(raw));
            requireObject(root, "root");
            String routeText = requiredText(root, "route", 32);
            if ("DIRECT".equals(routeText)) {
                exactFields(root, Set.of("route", "requirements", "answer"),
                        "DIRECT_FIELDS", "top-level DIRECT object");
                return PlannedTurn.direct(
                        routeRequirements(root),
                        requiredText(root, "answer", 20_000));
            }
            if (!"PERSISTENT_PLAN_EXECUTE".equals(routeText)) {
                throw invalid("ROUTE_VALUE",
                        "route must be DIRECT or PERSISTENT_PLAN_EXECUTE");
            }
            exactFields(root, Set.of(
                            "route", "requirements", "taskFrame", "plan"),
                    "PERSISTENT_FIELDS", "top-level persistent object");
            RouteRequirements requirements = routeRequirements(root);
            JsonNode frame = requiredObject(root, "taskFrame");
            exactFields(frame, Set.of(
                    "objective", "targets", "deliverables", "constraints"),
                    "TASK_FRAME_FIELDS", "taskFrame");
            TaskFrameDraft taskFrame = new TaskFrameDraft(
                    requiredText(frame, "objective", MAX_TEXT),
                    textList(frame, "targets", true),
                    textList(frame, "deliverables", true),
                    textList(frame, "constraints", true));

            JsonNode plan = requiredObject(root, "plan");
            exactFields(plan, Set.of("reason", "steps"), "PLAN_FIELDS",
                    "plan");
            JsonNode stepsNode = plan.get("steps");
            if (stepsNode == null) {
                throw invalid("PLAN_STEPS",
                        "plan.steps is missing; expected an array with 1-"
                                + MAX_STEPS + " steps");
            }
            if (!stepsNode.isArray()) {
                throw invalid("PLAN_STEPS",
                        "plan.steps must be an array; actual type: "
                                + nodeType(stepsNode));
            }
            if (stepsNode.isEmpty() || stepsNode.size() > MAX_STEPS) {
                throw invalid("PLAN_STEPS",
                        "plan.steps must contain 1-" + MAX_STEPS
                                + " steps; actual count: "
                                + stepsNode.size());
            }
            List<PlanStep> steps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode step : stepsNode) {
                requireObject(step, "step");
                exactFields(step, Set.of(
                                "id", "intent", "expectedOutcome",
                                "dependencies", "completionCriteria",
                                "maxAttempts", "maxDurationSeconds"),
                        "STEP_FIELDS",
                        "plan.steps[" + steps.size() + "]");
                String id = requiredText(step, "id", 128);
                if (!seen.add(id)) {
                    throw invalid("STEP_ID_DUPLICATE",
                            "plan step id must be unique; duplicate id: "
                                    + id);
                }
                List<String> dependencies = textList(
                        step, "dependencies", false);
                if (!seen.containsAll(dependencies) || dependencies.contains(id)) {
                    throw invalid("STEP_DEPENDENCIES",
                            "plan.steps[" + steps.size()
                                    + "].dependencies may reference only "
                                    + "earlier step ids and may not reference "
                                    + "the current step");
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
            }
            return PlannedTurn.persistent(
                    requirements,
                    taskFrame,
                    new InitialPlanDraft(
                            requiredText(plan, "reason", MAX_TEXT), steps),
                    List.of());
        } catch (V2TurnPlanningException failure) {
            throw failure;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            var location = failure.getLocation();
            String position = location == null ? ""
                    : " at line " + location.getLineNr()
                            + ", column " + location.getColumnNr();
            throw invalid("JSON_SYNTAX", "JSON syntax error" + position);
        } catch (java.io.IOException failure) {
            throw invalid("JSON_SYNTAX", "JSON syntax error");
        } catch (RuntimeException failure) {
            throw invalid("PARSE_RUNTIME",
                    "planner response could not be parsed using the schema");
        }
    }

    private JsonNode normalizeTopLevel(JsonNode root)
            throws java.io.IOException {
        if (root != null && root.isObject()) {
            return root;
        }
        if (root != null && root.isArray()
                && root.size() == 1 && root.get(0).isObject()) {
            log.info(
                    "V2 intake planner normalized provider wrapper "
                            + "wrapperType=ARRAY elementCount=1");
            return root.get(0);
        }
        if (root != null && root.isTextual()) {
            JsonNode decoded = json.readTree(root.textValue());
            if (decoded != null && decoded.isObject()) {
                log.info(
                        "V2 intake planner normalized provider wrapper "
                                + "wrapperType=JSON_STRING");
                return decoded;
            }
        }
        log.warn(
                "V2 intake planner rejected non-object provider output "
                        + "rootType={} arraySize={} arrayShape={}",
                root == null ? "null" : root.getNodeType(),
                root != null && root.isArray() ? root.size() : -1,
                safeArrayShape(root));
        return root;
    }

    private static String safeArrayShape(JsonNode root) {
        if (root == null || !root.isArray()) {
            return "NOT_ARRAY";
        }
        List<String> shapes = new ArrayList<>();
        for (JsonNode item : root) {
            if (!item.isObject()) {
                shapes.add(item.getNodeType().name());
            } else if (item.has("id") && item.has("intent")
                    && item.has("expectedOutcome")) {
                shapes.add("PLAN_STEP");
            } else if (item.has("route")) {
                shapes.add("ROUTED_OBJECT");
            } else if (item.has("objective") && item.has("targets")) {
                shapes.add("TASK_FRAME");
            } else {
                shapes.add("OBJECT_" + item.size());
            }
        }
        return String.join(",", shapes);
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
            throw invalid("OBJECT_" + diagnostic(field),
                    field + " must be a JSON object; actual type: "
                            + nodeType(value));
        }
    }

    private static void exactFields(
            JsonNode node, Set<String> allowed, String diagnostic,
            String location) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(allowed)) {
            throw invalid(diagnostic,
                    fieldMismatch(location, allowed, actual));
        }
    }

    private static void exactFieldsOneOf(
            JsonNode node, Set<String> current, Set<String> legacy,
            String diagnostic, String location) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(current) && !actual.equals(legacy)) {
            Set<String> closest = symmetricDifferenceSize(actual, current)
                            <= symmetricDifferenceSize(actual, legacy)
                    ? current : legacy;
            throw invalid(diagnostic,
                    fieldMismatch(location, closest, actual));
        }
    }

    private static String requiredText(
            JsonNode parent, String field, int maximum) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual()) {
            throw invalid("TEXT_" + diagnostic(field),
                    field + " must be nonblank text; actual type: "
                            + nodeType(node));
        }
        return bounded(
                node.textValue(), maximum, "TEXT_" + diagnostic(field),
                field);
    }

    private static String bounded(
            String value, int maximum, String diagnostic, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw invalid(diagnostic,
                    field + " must be nonblank text with at most "
                            + maximum + " characters");
        }
        return value.trim();
    }

    private static List<String> textList(
            JsonNode parent, String field, boolean required) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            throw invalid("LIST_" + diagnostic(field),
                    field + " must be an array of text values; actual type: "
                            + nodeType(node));
        }
        if (node.size() > MAX_LIST_ITEMS || required && node.isEmpty()) {
            String range = required ? "1-" + MAX_LIST_ITEMS
                    : "0-" + MAX_LIST_ITEMS;
            throw invalid("LIST_" + diagnostic(field),
                    field + " must contain " + range
                            + " values; actual count: " + node.size());
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            if (!item.isTextual()) {
                throw invalid("LIST_" + diagnostic(field),
                        field + "[" + index + "] must be text; actual type: "
                                + nodeType(item));
            }
            String value = bounded(
                    item.textValue(), MAX_TEXT,
                    "LIST_" + diagnostic(field),
                    field + "[" + index + "]");
            if (!unique.add(value)) {
                throw invalid("LIST_" + diagnostic(field),
                        field + " must not contain duplicate values");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static int boundedInt(
            JsonNode parent, String field, int minimum, int maximum) {
        JsonNode node = parent.get(field);
        if (node == null || !node.canConvertToInt()) {
            throw invalid("INT_" + diagnostic(field),
                    field + " must be an integer; actual type: "
                            + nodeType(node));
        }
        int value = node.intValue();
        if (value < minimum || value > maximum) {
            throw invalid("INT_" + diagnostic(field),
                    field + " must be between " + minimum + " and "
                            + maximum + "; actual value: " + value);
        }
        return value;
    }

    private static RouteRequirements routeRequirements(JsonNode root) {
        JsonNode requirements = requiredObject(root, "requirements");
        exactFields(requirements, Set.of(
                "projectEvidence", "toolUse", "retrieval",
                "networkAccess", "execution", "durableModification",
                "durableProgress"), "REQUIREMENTS_FIELDS",
                "requirements");
        return new RouteRequirements(
                requiredBoolean(requirements, "projectEvidence"),
                requiredBoolean(requirements, "toolUse"),
                requiredBoolean(requirements, "retrieval"),
                requiredBoolean(requirements, "networkAccess"),
                requiredBoolean(requirements, "execution"),
                requiredBoolean(requirements, "durableModification"),
                requiredBoolean(requirements, "durableProgress"));
    }

    private static boolean requiredBoolean(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isBoolean()) {
            throw invalid("BOOLEAN_" + diagnostic(field),
                    field + " must be boolean; actual type: "
                            + nodeType(node));
        }
        return node.booleanValue();
    }

    private static String fieldMismatch(
            String location, Set<String> expected, Set<String> actual) {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(expected);
        List<String> details = new ArrayList<>();
        if (!missing.isEmpty()) {
            details.add("missing fields: " + String.join(", ", missing));
        }
        if (!unexpected.isEmpty()) {
            details.add("unexpected fields: "
                    + String.join(", ", unexpected));
        }
        return location + " field mismatch; " + String.join("; ", details);
    }

    private static int symmetricDifferenceSize(
            Set<String> first, Set<String> second) {
        Set<String> difference = new HashSet<>(first);
        difference.removeAll(second);
        Set<String> reverse = new HashSet<>(second);
        reverse.removeAll(first);
        return difference.size() + reverse.size();
    }

    private static String nodeType(JsonNode node) {
        return node == null ? "missing"
                : node.getNodeType().name().toLowerCase(
                        java.util.Locale.ROOT);
    }

    private static void auditRouteRequirements(
            PlannedTurn planned, String raw) {
        if (planned.route() == Route.DIRECT
                && planned.requirements().any()) {
            throw new V2TurnPlanningException(
                    "DIRECT_REQUIREMENTS",
                    "DIRECT cannot require Project or external work")
                    .withOutputDigest(hash(raw));
        }
        if (planned.route() == Route.PERSISTENT_PLAN_EXECUTE
                && !planned.requirements().any()) {
            throw new V2TurnPlanningException(
                    "PERSISTENT_REQUIREMENTS",
                    "Persistent execution requires declared work")
                    .withOutputDigest(hash(raw));
        }
    }

    private static String diagnostic(String field) {
        return field.replaceAll("([a-z])([A-Z])", "$1_$2")
                .toUpperCase(java.util.Locale.ROOT);
    }

    private static long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, System.nanoTime() - startedNanos));
    }

    private static V2TurnPlanningException invalid(String diagnostic) {
        return new V2TurnPlanningException(
                diagnostic, "planner response is invalid");
    }

    private static V2TurnPlanningException invalid(
            String diagnostic, String detail) {
        return new V2TurnPlanningException(diagnostic, detail);
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

    record RouteRequirements(
            boolean projectEvidence,
            boolean toolUse,
            boolean retrieval,
            boolean networkAccess,
            boolean execution,
            boolean durableModification,
            boolean durableProgress) {
        boolean any() {
            return projectEvidence || toolUse || retrieval || networkAccess
                    || execution || durableModification || durableProgress;
        }
    }

    record PlannedTurn(
            Route route,
            RouteRequirements requirements,
            String answer,
            TaskFrameDraft taskFrame,
            InitialPlanDraft plan,
            List<PlannedCapability> capabilities,
            String rawOutput) {
        PlannedTurn {
            java.util.Objects.requireNonNull(requirements, "requirements");
            capabilities = capabilities == null
                    ? List.of() : List.copyOf(capabilities);
        }

        static PlannedTurn direct(
                RouteRequirements requirements, String answer) {
            return new PlannedTurn(
                    Route.DIRECT, requirements, answer,
                    null, null, List.of(), null);
        }

        static PlannedTurn persistent(
                RouteRequirements requirements,
                TaskFrameDraft taskFrame,
                InitialPlanDraft plan,
                List<PlannedCapability> capabilities) {
            return new PlannedTurn(
                    Route.PERSISTENT_PLAN_EXECUTE,
                    requirements, null,
                    taskFrame,
                    plan,
                    capabilities,
                    null);
        }

        PlannedTurn withRawOutput(String value) {
            return new PlannedTurn(
                    route, requirements, answer, taskFrame, plan,
                    capabilities, value);
        }
    }
}
