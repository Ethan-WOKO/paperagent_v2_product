package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.v2.chain.model.ProductChainChatModelAdapter;
import com.yanban.api.agent.v2.chain.model.ProductPlannerModelContextView;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpoint;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextManifestCodec;
import com.yanban.core.model.DeepSeekModelProvider;
import com.yanban.core.model.DeepSeekProperties;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextModuleRecord;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextRevisionRecord;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.GapValidation;
import io.paperagent.v2.chain.PlannerPayload;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.context.ChainContextInputMatrix;
import io.paperagent.v2.chain.context.ChainContextSourceSnapshot;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.context.ChainContextVersionMatrix;
import io.paperagent.v2.chain.model.ChainModelCallRequest;
import io.paperagent.v2.chain.model.ChainModelCallResult;
import io.paperagent.v2.chain.model.ChainProviderProtocolException;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Explicitly enabled, real-provider Planner routing evaluation.
 *
 * <p>This test does not start Spring, a web server, or a database. It uses the
 * production Planner schema, role rules, compact planning tool catalog, model
 * adapter, provider client, and strict output parser. Synthetic frozen Context
 * fixtures isolate routing behavior from persistence and downstream execution.</p>
 */
class PlannerRoutingEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW =
            Instant.parse("2026-08-11T08:00:00Z");
    private static final String TASK_ID = "planner-evaluation-task";
    private static final String CONTEXT_ID = "planner-evaluation-context";
    private static final String INSTRUCTION_ID =
            "planner-evaluation-instruction";

    @Test
    void evaluatesPlannerRoutingWithoutStartingTheBackend() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(
                        System.getProperty("planner.eval.enabled", "false")),
                "set -Dplanner.eval.enabled=true to run real model evaluation");

        Endpoint endpoint = endpoint();
        List<Scenario> selected = selectedScenarios(loadScenarios());
        ProductChainChatModelAdapter adapter = adapter(endpoint);
        StrictChainProviderOutputParser parser =
                new StrictChainProviderOutputParser();
        ArrayNode results = JSON.createArrayNode();
        List<String> failures = new ArrayList<>();
        int passed = 0;

        for (Scenario scenario : selected) {
            Evaluation result = evaluate(adapter, parser, endpoint, scenario);
            results.add(result.json());
            String marker = result.passed() ? "PASS" : "FAIL";
            System.out.printf(Locale.ROOT,
                    "PLANNER_EVAL %s id=%s stage=%s expected=%s actual=%s detail=%s%n",
                    marker, scenario.id(), scenario.stage(),
                    scenario.expectedKinds(), result.actualKind(),
                    result.detail());
            if (result.passed()) {
                passed++;
            } else {
                failures.add(scenario.id() + ": " + result.detail());
            }
        }

        writeReport(endpoint, selected.size(), passed, results);
        assertEquals(selected.size(), passed,
                "Planner evaluation failures:\n" + String.join("\n", failures));
    }

    private static Evaluation evaluate(
            ProductChainChatModelAdapter adapter,
            StrictChainProviderOutputParser parser,
            Endpoint endpoint,
            Scenario scenario) {
        ChainWorkState state = workState(scenario.stage());
        String prompt = canonicalPrompt(scenario, state);
        int modelPromptCharacters = ProductPlannerModelContextView.project(
                prompt).length();
        int maxAttempts = Integer.getInteger(
                "planner.eval.maxAttempts", 1);
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException(
                    "planner.eval.maxAttempts must be between 1 and 3");
        }
        String previousInvalidOutput = null;
        String repairFeedback = null;
        for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
            boolean repair = attemptNo > 1;
            ChainModelCallResult call = adapter.call(
                    new ChainModelCallRequest(
                            "planner-eval-" + scenario.id(), CONTEXT_ID,
                            "planner-eval-completion", ChainRole.PLANNER,
                            state, scenario.stage(), endpoint.provider(),
                            endpoint.model(), prompt, attemptNo, repair,
                            repairFeedback, previousInvalidOutput));
            if (call instanceof ChainModelCallResult.Failure failure) {
                String detail = "provider failure " + failure.errorCode()
                        + " " + failure.safeMetadata().getOrDefault(
                        "failureReason", "unknown");
                return Evaluation.failed(scenario, null, detail, null,
                        prompt.length(), modelPromptCharacters,
                        failure.safeMetadata());
            }

            ChainModelCallResult.Success success =
                    (ChainModelCallResult.Success) call;
            try {
                ProviderRoleOutput output = parser.parse(
                        success.rawOutput(), ChainRole.PLANNER, state,
                        scenario.gapId());
                String kind = output.kind();
                List<String> problems = new ArrayList<>();
                if (!scenario.expectedKinds().contains(kind)) {
                    problems.add("unexpected kind");
                }
                validateExpectedBoundary(scenario, output, problems);
                validateExpectedGap(scenario, output, problems);
                boolean passed = problems.isEmpty();
                return new Evaluation(passed, kind,
                        passed ? attemptNo == 1 ? "matched"
                                : "matched after protocol repair attempt "
                                + attemptNo
                                : String.join("; ", problems),
                        success.rawOutput(), prompt.length(),
                        modelPromptCharacters,
                        success.safeMetadata(), scenario);
            } catch (ChainProviderProtocolException invalid) {
                String detail = "protocol " + invalid.code() + " at "
                        + invalid.path() + ": " + invalid.getMessage();
                if (attemptNo < maxAttempts) {
                    previousInvalidOutput = success.rawOutput();
                    repairFeedback = detail;
                    continue;
                }
                return Evaluation.failed(scenario,
                        rawKind(success.rawOutput()), detail,
                        success.rawOutput(), prompt.length(),
                        modelPromptCharacters,
                        success.safeMetadata());
            } catch (RuntimeException invalid) {
                String detail = "validation "
                        + invalid.getClass().getSimpleName()
                        + ": " + safeMessage(invalid);
                if (attemptNo < maxAttempts) {
                    previousInvalidOutput = success.rawOutput();
                    repairFeedback = detail;
                    continue;
                }
                return Evaluation.failed(scenario,
                        rawKind(success.rawOutput()), detail,
                        success.rawOutput(), prompt.length(),
                        modelPromptCharacters,
                        success.safeMetadata());
            }
        }
        throw new IllegalStateException("planner evaluation attempt loop ended");
    }

    private static void validateExpectedBoundary(
            Scenario scenario, ProviderRoleOutput output,
            List<String> problems) {
        if (scenario.expectedBoundaryTrue() == null
                || scenario.expectedBoundaryTrue().isEmpty()
                || !(output.payload()
                instanceof PlannerPayload.PersistentPlan plan)) {
            return;
        }
        Set<String> actual = new LinkedHashSet<>();
        if (plan.routingBoundary().needsTool()) actual.add("needsTool");
        if (plan.routingBoundary().needsNetwork()) actual.add("needsNetwork");
        if (plan.routingBoundary().needsProject()) actual.add("needsProject");
        if (plan.routingBoundary().needsPersistentProgress()) {
            actual.add("needsPersistentProgress");
        }
        if (!actual.containsAll(scenario.expectedBoundaryTrue())) {
            problems.add("missing routing flags expected="
                    + scenario.expectedBoundaryTrue() + " actual=" + actual);
        }
    }

    private static void validateExpectedGap(
            Scenario scenario, ProviderRoleOutput output,
            List<String> problems) {
        if (scenario.expectedGapOutcome() == null) {
            return;
        }
        GapValidation gap = output.payload().gapValidation();
        String actual = gap == null ? "NONE" : gap.outcome().name();
        if (!scenario.expectedGapOutcome().equals(actual)) {
            problems.add("gap outcome expected="
                    + scenario.expectedGapOutcome() + " actual=" + actual);
        }
    }

    private static ProductChainChatModelAdapter adapter(Endpoint endpoint) {
        DeepSeekProperties properties = new DeepSeekProperties();
        properties.setApiKey(endpoint.apiKey());
        properties.setApiUrl(endpoint.apiUrl());
        properties.setModel(endpoint.model());
        properties.setTemperature(0.0d);
        properties.setMaxTokens(8_192);
        return new ProductChainChatModelAdapter(
                new DeepSeekModelProvider(properties),
                ignored -> new ProductChainModelEndpoint(
                        endpoint.provider(), endpoint.model(),
                        endpoint.apiKey(), endpoint.apiUrl()));
    }

    private static String canonicalPrompt(
            Scenario scenario, ChainWorkState state) {
        Map<ChainContextModule, Map<String, ChainContextValue>> values =
                scenarioFields(scenario, state);
        List<ContextModuleRecord> records = new ArrayList<>();
        for (ChainContextModule module
                : ChainContextInputMatrix.orderedModules()) {
            ChainContextVersionMatrix.VersionRequirement version =
                    ChainContextVersionMatrix.requirement(module);
            ChainContextSourceSnapshot snapshot = new ChainContextSourceSnapshot(
                    module, ChainContextModuleStatus.PRESENT,
                    vector(version.sourceVersionFields(), module, "source"),
                    vector(version.readBoundaryFields(), module, "boundary"),
                    "planner-evaluation-v1", "none-v1",
                    Map.of("fixtureSet", ChainContextValue.text(
                            "planner-routing-v1")),
                    values.get(module), null);
            records.add(new ContextModuleRecord(
                    CONTEXT_ID, TASK_ID, module.ordinalCode(), module,
                    snapshot.presenceKind(), snapshot.sourceVersion(),
                    snapshot.readBoundary(), snapshot.projectionVersion(),
                    snapshot.paginationVersion(),
                    snapshot.projectionParameters(), snapshot.projection(),
                    NOW));
        }
        return new ProductChainContextManifestCodec(JSON)
                .canonicalPrompt(records);
    }

    private static Map<ChainContextModule, Map<String, ChainContextValue>>
            scenarioFields(Scenario scenario, ChainWorkState state) {
        Map<ChainContextModule, Map<String, ChainContextValue>> result =
                new LinkedHashMap<>();
        for (ChainContextModule module
                : ChainContextInputMatrix.orderedModules()) {
            Map<String, ChainContextValue> fields = new LinkedHashMap<>();
            for (String name : ChainContextInputMatrix
                    .requiredProjectionFields(ChainRole.PLANNER, module)) {
                fields.put(name, empty(name));
            }
            result.put(module, fields);
        }

        instructionFields(result.get(
                ChainContextModule.USER_INSTRUCTION_CHAIN), scenario);
        conversationFields(result.get(
                ChainContextModule.CONVERSATION_CONTEXT), scenario);
        projectFields(result.get(
                ChainContextModule.PROJECT_AND_INPUT_MATERIALS), scenario);
        taskFields(result.get(ChainContextModule.TASK_CONTRACT), scenario);
        planFields(result.get(
                ChainContextModule.PLAN_AND_STEP_CONTRACT), scenario);
        runtimeFields(result.get(
                ChainContextModule.TASK_AND_STEP_RUNTIME_STATE), scenario);
        reviewFields(result.get(
                ChainContextModule.REVIEW_DECISIONS_AND_PENDING_ITEMS),
                scenario);
        modelFields(result.get(
                ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS),
                scenario, state);

        result.put(
                ChainContextModule.RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS,
                plannerRuntimeRules(scenario, state));
        return Map.copyOf(result);
    }

    private static void instructionFields(
            Map<String, ChainContextValue> fields, Scenario scenario) {
        List<ChainContextValue> chain = new ArrayList<>();
        List<ChainContextValue> relations = new ArrayList<>();
        if (scenario.priorTask() != null) {
            chain.add(ChainContextValue.object(Map.of(
                    "instructionRef", referenced("instruction.initial"),
                    "relationKind", ChainContextValue.text("INITIAL"),
                    "answeredGapId", ChainContextValue.nil(),
                    "body", ChainContextValue.text(scenario.priorTask()))));
            relations.add(ChainContextValue.object(Map.of(
                    "instructionRef", referenced("instruction.initial"),
                    "relationKind", ChainContextValue.text("INITIAL"),
                    "answeredGapId", ChainContextValue.nil())));
        }
        String relation = "PENDING_ITEM_VALIDATION".equals(scenario.stage())
                ? "ANSWER_TO_PENDING_ITEM"
                : scenario.instructionRelation() == null
                ? "INITIAL" : scenario.instructionRelation();
        ChainContextValue answeredGap = scenario.gapId() == null
                ? ChainContextValue.nil()
                : ChainContextValue.text(scenario.gapId());
        chain.add(ChainContextValue.object(Map.of(
                "instructionRef", referenced(INSTRUCTION_ID),
                "relationKind", ChainContextValue.text(relation),
                "answeredGapId", answeredGap,
                "body", ChainContextValue.text(scenario.instruction()))));
        relations.add(ChainContextValue.object(Map.of(
                "instructionRef", referenced(INSTRUCTION_ID),
                "relationKind", ChainContextValue.text(relation),
                "answeredGapId", answeredGap)));
        ChainContextValue chainValue = ChainContextValue.object(Map.of(
                "currentInstructionRef", referenced(INSTRUCTION_ID),
                "instructions", ChainContextValue.array(chain)));
        fields.put("foundation.instructionChain", chainValue);
        fields.put("instructions.completeStructure", chainValue);
        fields.put("instructions.effectiveBodies", ChainContextValue.array(
                chain));
        fields.put("instructions.relations",
                ChainContextValue.array(relations));
    }

    private static void conversationFields(
            Map<String, ChainContextValue> fields, Scenario scenario) {
        ChainContextValue recent = ChainContextValue.array(List.of(
                ChainContextValue.object(Map.of(
                        "role", ChainContextValue.text("user"),
                        "messageRef", referenced("message.current"),
                        "body", ChainContextValue.text(
                                scenario.instruction())))));
        fields.put("foundation.conversationWithCoverage",
                ChainContextValue.object(Map.of(
                        "recent", recent,
                        "earlierSummary", ChainContextValue.nil())));
        fields.put("conversation.recentComplete", recent);
        fields.put("conversation.earlierSummary", ChainContextValue.nil());
        fields.put("conversation.summaryCoverage",
                ChainContextValue.object(Map.of(
                        "coveredThroughMessageRef",
                        ChainContextValue.text("NONE"))));
    }

    private static void projectFields(
            Map<String, ChainContextValue> fields, Scenario scenario) {
        ChainContextValue version = ChainContextValue.object(Map.of(
                "projectRef", referenced("project.41"),
                "projectVersionRef", referenced("project-version.1"),
                "note", ChainContextValue.text(
                        "A Project is attached to the conversation, but its mere presence does not make an unrelated request require Project access.")));
        fields.put("project.version", version);
        ChainContextValue file = ChainContextValue.object(Map.of(
                "path", ChainContextValue.text(
                        "src/main/java/example/Planner.java"),
                "sha256", ChainContextValue.text("1".repeat(64))));
        fields.put("project.manifest.complete",
                ChainContextValue.object(Map.of(
                        "projectVersionRef",
                        referenced("project-version.1"),
                        "files", ChainContextValue.array(List.of(file)))));
        fields.put("project.explicitInputExpansion",
                ChainContextValue.object(Map.of(
                        "status", ChainContextValue.text("NONE"))));
    }

    private static void taskFields(
            Map<String, ChainContextValue> fields, Scenario scenario) {
        boolean existing = !"INITIAL_INTAKE".equals(scenario.stage());
        ChainContextValue frame = existing
                ? ChainContextValue.object(Map.of(
                "taskFrameRef", referenced("task-frame.1"),
                "objective", ChainContextValue.text(firstNonBlank(
                        scenario.priorTask(), scenario.instruction())),
                "projectVersionRef", referenced("project-version.1"),
                "permissionTier", ChainContextValue.text(
                        ProductChainPermissionPolicySource
                                .SUPPORTED_PERMISSION_TIER)))
                : ChainContextValue.object(Map.of(
                "status", ChainContextValue.text("EXPLICIT_EMPTY"),
                "reason", ChainContextValue.text(
                        "Initial Planner invocation has no TaskFrame yet.")));
        fields.put("taskFrame.completeOrExplicitEmpty", frame);
        fields.put("foundation.taskFrameAndHardBoundary",
                ChainContextValue.object(Map.of(
                        "taskFrame", frame,
                        "permissionConstraint", nullableText(
                                scenario.permissionConstraint()))));
        fields.put("taskFrame.hardBoundary",
                ChainContextValue.object(Map.of(
                        "permissionConstraint", nullableText(
                                scenario.permissionConstraint()),
                        "formalConflict", nullableText(
                                scenario.formalConflict()))));
    }

    private static void planFields(
            Map<String, ChainContextValue> fields, Scenario scenario) {
        boolean hasPlan = "PLAN_REVISION".equals(scenario.stage())
                || "USER_INSTRUCTION_DISPOSITION".equals(scenario.stage())
                || ("PENDING_ITEM_VALIDATION".equals(scenario.stage())
                && !"INITIAL_INTAKE".equals(scenario.resumePosition()));
        ChainContextValue plan = hasPlan
                ? ChainContextValue.object(Map.of(
                "planRef", referenced("plan.1"),
                "currentRevisionRef", referenced("plan-revision.1"),
                "taskFrameRef", referenced("task-frame.1"),
                "steps", ChainContextValue.array(List.of(
                        ChainContextValue.object(Map.of(
                                "stepRef", referenced("step.1"),
                                "status", ChainContextValue.text("ACTIVE"),
                                "objective", ChainContextValue.text(
                                        firstNonBlank(scenario.priorTask(),
                                                scenario.instruction()))))))))
                : ChainContextValue.object(Map.of(
                "status", ChainContextValue.text("EXPLICIT_EMPTY"),
                "reason", ChainContextValue.text(
                        "No current Plan binding exists at this stage.")));
        fields.put("plan.currentRevisionCompleteOrExplicitEmpty", plan);
    }

    private static void runtimeFields(
            Map<String, ChainContextValue> fields, Scenario scenario) {
        fields.put("foundation.stateHeader", ChainContextValue.object(Map.of(
                "role", ChainContextValue.text("PLANNER"),
                "workState", ChainContextValue.text(
                        workState(scenario.stage()).name()),
                "callReason", ChainContextValue.text(scenario.stage()))));
        fields.put("runtime.executionMode", ChainContextValue.text(
                "INITIAL_INTAKE".equals(scenario.stage())
                        ? "UNDECIDED"
                        : "PERSISTENT_PLAN_EXECUTE"));
        fields.put("runtime.applicability", ChainContextValue.object(Map.of(
                "revisionTrigger", nullableText(scenario.revisionTrigger()),
                "formalConflict", formalConflict(scenario))));
    }

    private static void reviewFields(
            Map<String, ChainContextValue> fields, Scenario scenario) {
        ChainContextValue pending = scenario.gapId() == null
                ? ChainContextValue.object(Map.of(
                "status", ChainContextValue.text("NONE")))
                : ChainContextValue.object(Map.ofEntries(
                Map.entry("gapRef", referenced(scenario.gapId())),
                Map.entry("question", ChainContextValue.text(
                        scenario.pendingQuestion())),
                Map.entry("closingCondition", ChainContextValue.text(
                        scenario.closingCondition())),
                Map.entry("answerInstructionRef", referenced(INSTRUCTION_ID)),
                Map.entry("status", ChainContextValue.text(
                        "RESPONSE_RECEIVED")),
                Map.entry("resumeRole", ChainContextValue.text("PLANNER")),
                Map.entry("resumePosition", ChainContextValue.text(
                        firstNonBlank(scenario.resumePosition(), "NONE")))));
        fields.put("foundation.latestDecisionCallReasonAndPendingItem",
                ChainContextValue.object(Map.of(
                        "callReason", ChainContextValue.text(scenario.stage()),
                        "currentPendingItem", pending)));
        fields.put("review.latestDecision", ChainContextValue.object(Map.of(
                "revisionTrigger", nullableText(scenario.revisionTrigger()))));
        fields.put("review.replanGap", ChainContextValue.object(Map.of(
                "formalConflict", formalConflict(scenario))));
        fields.put("review.instructionDisposition",
                ChainContextValue.object(Map.of(
                        "candidateRelation", nullableText(
                                scenario.instructionRelation()))));
        fields.put("review.resumePosition", ChainContextValue.text(
                firstNonBlank(scenario.resumePosition(), "NONE")));
    }

    private static void modelFields(
            Map<String, ChainContextValue> fields, Scenario scenario,
            ChainWorkState state) {
        fields.put("foundation.contextRevisionAndSourceVersions",
                ChainContextValue.object(Map.of(
                        "contextRevisionRef", referenced(CONTEXT_ID),
                        "fixtureVersion", ChainContextValue.text(
                                "planner-routing-v1"))));
        fields.put("model.stateHeader", ChainContextValue.object(Map.of(
                "role", ChainContextValue.text("PLANNER"),
                "workState", ChainContextValue.text(state.name()))));
        fields.put("model.callReason",
                ChainContextValue.text(scenario.stage()));
        fields.put("model.latestAcceptedOrFailedPlannerMetadata",
                ChainContextValue.object(Map.of(
                        "revisionTrigger", nullableText(
                                scenario.revisionTrigger()),
                        "formalConflict", formalConflict(scenario))));
    }

    private static Map<String, ChainContextValue> plannerRuntimeRules(
            Scenario scenario, ChainWorkState state) {
        ContextRevisionRecord revision = new ContextRevisionRecord(
                CONTEXT_ID, TASK_ID, null, ChainRole.PLANNER, state,
                scenario.stage(), INSTRUCTION_ID, null, null, null,
                null, null, null, 41L, "project-version.1", null,
                null, null, null, null, null,
                "planner-evaluation-v1", "none-v1", "policy-v1",
                io.paperagent.v2.chain.ChainContextRevisionStatus.BUILDING,
                0, null, null, null, null, null, NOW, null);
        ProductChainTaskSkillSnapshot skill =
                ProductChainTaskSkillSnapshot.none(
                        TASK_ID, INSTRUCTION_ID, NOW);
        ProductChainPermissionPolicySource.Projection policy =
                ProductChainPermissionPolicySource.policy();
        ProductChainToolContextValueCodec.Projection tools =
                ProductChainToolContextValueCodec.encode(
                        ProductChainToolContextProjection.projectPolicy(
                                "permission-policy:"
                                        + ProductChainPermissionPolicySource
                                        .POLICY_VERSION,
                                ProductChainPermissionPolicySource
                                        .planningProfile(),
                                skill));
        return ProductChainRuntimeRuleValues.fields(
                revision,
                ProductChainRoleSchemaSource.schema(
                        ChainRole.PLANNER, scenario.stage()),
                tools, policy, policy, skill,
                ProductChainPermissionPolicySource.planningProfile());
    }

    private static Map<String, ChainContextValue> vector(
            List<String> names, ChainContextModule module, String kind) {
        Map<String, ChainContextValue> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(name, referenced("planner-eval:" + module.wireName()
                    + ":" + kind + ":" + name));
        }
        return Map.copyOf(result);
    }

    private static ChainContextValue empty(String field) {
        return ChainContextValue.object(Map.of(
                "status", ChainContextValue.text("EXPLICIT_EMPTY"),
                "field", ChainContextValue.text(field)));
    }

    private static ChainContextValue formalConflict(Scenario scenario) {
        if (scenario.formalConflict() == null) {
            return ChainContextValue.nil();
        }
        ChainContextValue refs = ChainContextValue.array(List.of(
                referenced("fact:conflict-a"),
                referenced("fact:conflict-b")));
        return ChainContextValue.object(Map.of(
                "description", ChainContextValue.text(
                        scenario.formalConflict()),
                "knownFactRefs", refs));
    }

    private static ChainContextValue nullableText(String value) {
        return value == null ? ChainContextValue.nil()
                : ChainContextValue.text(value);
    }

    private static ChainContextValue.Text referenced(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static ChainWorkState workState(String stage) {
        return switch (stage) {
            case "USER_INSTRUCTION_DISPOSITION" ->
                    ChainWorkState.CLASSIFYING_INSTRUCTION;
            case "PENDING_ITEM_VALIDATION" ->
                    ChainWorkState.VALIDATING_PENDING_ITEM;
            default -> ChainWorkState.PLANNING;
        };
    }

    private static Endpoint endpoint() {
        String key = requiredEnvironment("DEEPSEEK_API_KEY");
        return new Endpoint(
                "deepseek",
                environment("DEEPSEEK_MODEL", "deepseek-chat"),
                key,
                environment("DEEPSEEK_API_URL",
                        "https://api.deepseek.com/v1/chat/completions"));
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                name + " is required for real model evaluation");
        return value.trim();
    }

    private static List<Scenario> loadScenarios() throws Exception {
        try (InputStream input = PlannerRoutingEvaluationTest.class
                .getResourceAsStream(
                        "/planner-evaluation/planner-routing-v1.json")) {
            if (input == null) {
                throw new IllegalStateException(
                        "planner evaluation dataset is missing");
            }
            return JSON.readValue(input,
                    new TypeReference<List<Scenario>>() { });
        }
    }

    private static List<Scenario> selectedScenarios(
            List<Scenario> scenarios) {
        String requested = System.getProperty("planner.eval.ids", "").trim();
        List<Scenario> filtered = scenarios;
        if (!requested.isEmpty()) {
            Set<String> ids = Set.copyOf(Arrays.asList(requested.split(",")));
            filtered = scenarios.stream()
                    .filter(value -> ids.contains(value.id())).toList();
        }
        int limit = Integer.getInteger("planner.eval.limit", filtered.size());
        if (limit < 1) {
            throw new IllegalArgumentException(
                    "planner.eval.limit must be positive");
        }
        return filtered.stream().limit(limit).toList();
    }

    private static void writeReport(
            Endpoint endpoint, int total, int passed,
            ArrayNode results) throws Exception {
        ObjectNode report = JSON.createObjectNode();
        report.put("dataset", "planner-routing-v1");
        report.put("provider", endpoint.provider());
        report.put("model", endpoint.model());
        report.put("total", total);
        report.put("passed", passed);
        report.put("failed", total - passed);
        report.set("results", results);
        Path directory = Path.of("target", "planner-evaluation");
        Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                directory.resolve("report.json").toFile(), report);
    }

    private static String rawKind(String raw) {
        try {
            JsonNode root = JSON.readTree(raw);
            return root.path("kind").asText("UNKNOWN");
        } catch (Exception ignored) {
            return "UNPARSEABLE";
        }
    }

    private static String safeMessage(RuntimeException failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank()
                ? failure.getClass().getSimpleName() : value;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record Endpoint(
            String provider, String model, String apiKey, String apiUrl) {
    }

    private record Scenario(
            String id,
            String stage,
            String instruction,
            List<String> expectedKinds,
            List<String> expectedBoundaryTrue,
            String expectedGapOutcome,
            String priorTask,
            String revisionTrigger,
            String instructionRelation,
            String permissionConstraint,
            String gapId,
            String pendingQuestion,
            String closingCondition,
            String resumePosition,
            String formalConflict,
            String note) {
        private Scenario {
            if (id == null || id.isBlank()
                    || stage == null || stage.isBlank()
                    || instruction == null || instruction.isBlank()
                    || expectedKinds == null || expectedKinds.isEmpty()) {
                throw new IllegalArgumentException(
                        "invalid planner evaluation scenario");
            }
            expectedKinds = List.copyOf(expectedKinds);
            expectedBoundaryTrue = expectedBoundaryTrue == null
                    ? List.of() : List.copyOf(expectedBoundaryTrue);
        }
    }

    private record Evaluation(
            boolean passed,
            String actualKind,
            String detail,
            String rawOutput,
            int canonicalPromptCharacters,
            int modelPromptCharacters,
            Map<String, String> metadata,
            Scenario scenario) {
        private static Evaluation failed(
                Scenario scenario, String actualKind, String detail,
                String rawOutput, int canonicalPromptCharacters,
                int modelPromptCharacters,
                Map<String, String> metadata) {
            return new Evaluation(false,
                    actualKind == null ? "NONE" : actualKind,
                    detail, rawOutput, canonicalPromptCharacters,
                    modelPromptCharacters, metadata, scenario);
        }

        private ObjectNode json() {
            ObjectNode value = JSON.createObjectNode();
            value.put("id", scenario.id());
            value.put("stage", scenario.stage());
            value.putPOJO("expectedKinds", scenario.expectedKinds());
            value.put("actualKind", actualKind);
            value.put("passed", passed);
            value.put("detail", detail);
            value.put("canonicalPromptCharacters",
                    canonicalPromptCharacters);
            value.put("modelPromptCharacters", modelPromptCharacters);
            value.putPOJO("metadata", metadata);
            value.put("note", scenario.note());
            if (rawOutput != null) {
                value.put("rawOutput", rawOutput);
            }
            return value;
        }
    }
}
