package com.yanban.api.agent.v2.chain.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.v2.chain.model.ProductChainChatModelAdapter;
import com.yanban.api.agent.v2.chain.model.ProductChainModelEndpoint;
import com.yanban.api.agent.v2.chain.model.ProductExecutorModelContextView;
import com.yanban.api.agent.v2.chain.persistence.ProductChainContextManifestCodec;
import com.yanban.api.agent.v2.tool.V2ProductToolCatalog;
import com.yanban.core.model.DeepSeekModelProvider;
import com.yanban.core.model.DeepSeekProperties;
import io.paperagent.v2.chain.ChainContextModule;
import io.paperagent.v2.chain.ChainContextModuleStatus;
import io.paperagent.v2.chain.ChainPersistenceRecords.ContextModuleRecord;
import io.paperagent.v2.chain.ChainRole;
import io.paperagent.v2.chain.ChainWorkState;
import io.paperagent.v2.chain.ExecutorPayload;
import io.paperagent.v2.chain.ProviderRoleOutput;
import io.paperagent.v2.chain.context.ChainContextInputMatrix;
import io.paperagent.v2.chain.context.ChainContextSourceSnapshot;
import io.paperagent.v2.chain.context.ChainContextValue;
import io.paperagent.v2.chain.context.ChainContextVersionMatrix;
import io.paperagent.v2.chain.model.ChainModelCallRequest;
import io.paperagent.v2.chain.model.ChainModelCallResult;
import io.paperagent.v2.chain.model.ChainProviderProtocolException;
import io.paperagent.v2.chain.model.StrictChainProviderOutputParser;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in real-model evaluation plus always-on dataset contract checks. */
class ExecutorDecisionEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW =
            Instant.parse("2026-08-11T08:00:00Z");
    private static final String TASK_ID = "executor-evaluation-task";
    private static final String CONTEXT_ID = "executor-evaluation-context";

    @Test
    void datasetCoversToolsConfusionsStagesAndAllOutputKinds()
            throws Exception {
        List<Scenario> scenarios = loadScenarios();
        assertEquals(23, scenarios.size());
        assertEquals(scenarios.size(), scenarios.stream()
                .map(Scenario::id).distinct().count());

        Set<String> kinds = new LinkedHashSet<>();
        Set<String> selectedTools = new LinkedHashSet<>();
        for (Scenario scenario : scenarios) {
            kinds.addAll(scenario.expectedKinds());
            if (scenario.expectedKinds().contains("TOOL_ACTION")) {
                assertNotNull(scenario.expectedToolId(), scenario.id());
                assertNotNull(scenario.expectedPermissionRef(), scenario.id());
                assertFalse(scenario.requiredArgumentKeys().isEmpty(),
                        scenario.id());
                assertTrue(V2ProductToolCatalog.supports(
                        scenario.expectedToolId()), scenario.id());
                assertFalse(scenario.forbiddenToolIds().contains(
                        scenario.expectedToolId()), scenario.id());
                selectedTools.add(scenario.expectedToolId());
            } else {
                assertNull(scenario.expectedToolId(), scenario.id());
                assertNull(scenario.expectedPermissionRef(), scenario.id());
            }
            scenario.forbiddenToolIds().forEach(tool -> assertTrue(
                    V2ProductToolCatalog.supports(tool),
                    scenario.id() + " unknown forbidden tool " + tool));
        }

        assertEquals(Set.of("TOOL_ACTION", "WORKSPACE_CHANGE",
                "STEP_RESULT", "STEP_BLOCKED"), kinds);
        assertEquals(Set.of(
                "literature.search", "project.read", "project.search",
                "project.cross-material.search",
                "project.document.extract",
                "project.spreadsheet.inspect", "project.latex.outline",
                "project.latex.crossref.audit",
                "project.latex.float.audit",
                "project.latex.protected.inventory",
                "project.paper.acronym.audit",
                "project.paper.language.stats", "project.bibtex.audit",
                "project.code.symbols", "project.experiment.summary",
                "sandbox.execute"), selectedTools);
        assertTrue(scenarios.stream().anyMatch(value ->
                "PENDING_ITEM_VALIDATION".equals(value.callReason())));
        assertTrue(scenarios.stream().anyMatch(value ->
                "STEP_RESULT".equals(value.callReason())));
        assertTrue(scenarios.stream().anyMatch(value ->
                "PRESENT".equals(value.expectedRepairFields())));
        assertTrue(scenarios.stream().anyMatch(value ->
                "ABSENT".equals(value.expectedRepairFields())));
    }

    @Test
    void productionModelViewKeepsEveryToolDistinctAndConservativelySized()
            throws Exception {
        String canonical = canonicalPrompt(loadScenarios().get(0));
        String projected = ProductExecutorModelContextView.project(canonical);
        JsonNode root = JSON.readTree(projected);
        JsonNode catalog = null;
        for (JsonNode module : root.path("modules")) {
            if ("RUNTIME_CAPABILITY_PERMISSION".equals(
                    module.path("kind").asText())) {
                catalog = module.path("projection").path("fields")
                        .path("rules.completeToolSchemas");
            }
        }
        assertNotNull(catalog);
        JsonNode tools = catalog.path("completeToolSchemas");
        assertEquals(V2ProductToolCatalog.entries().size(), tools.size());
        Set<String> ids = new LinkedHashSet<>();
        Set<String> descriptions = new LinkedHashSet<>();
        for (JsonNode tool : tools) {
            JsonNode descriptor = tool.path("descriptor");
            assertTrue(ids.add(descriptor.path("id").asText()));
            assertTrue(descriptions.add(
                    descriptor.path("description").asText()));
            assertTrue(descriptor.path("parameterSchema").isObject());
            assertFalse(tool.path("permissionRef").asText().isBlank());
        }
        String compactCatalog = JSON.writeValueAsString(catalog);
        String fullCatalog = ProductChainToolContextValueCodec.encode(
                ProductChainToolContextProjection.project(
                        evaluationFrame())).canonicalJson();
        double ratio = (double) compactCatalog.length()
                / fullCatalog.length();
        assertTrue(ratio >= 0.55d,
                "tool view was compressed too aggressively: " + ratio);
        assertTrue(ratio <= 0.80d,
                "tool view did not remove enough duplication: " + ratio);
        assertTrue(compactCatalog.contains("ordinary literal discovery"));
        assertTrue(compactCatalog.contains("cross-file proof"));
        assertTrue(compactCatalog.contains("duplicate labels"));
        assertTrue(compactCatalog.contains("figures and tables"));
        assertTrue(compactCatalog.contains("protected facts"));
        assertTrue(compactCatalog.contains("yanban-runner"));
        System.out.printf(Locale.ROOT,
                "EXECUTOR_TOOL_VIEW fullChars=%d modelChars=%d ratio=%.3f fullPromptChars=%d modelPromptChars=%d%n",
                fullCatalog.length(), compactCatalog.length(), ratio,
                canonical.length(), projected.length());
    }

    @Test
    void evaluatesExecutorDecisionsWithoutStartingTheBackend()
            throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(
                        System.getProperty("executor.eval.enabled", "false")),
                "set -Dexecutor.eval.enabled=true to run real model evaluation");

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
            System.out.printf(Locale.ROOT,
                    "EXECUTOR_EVAL %s id=%s reason=%s expected=%s actual=%s tool=%s detail=%s%n",
                    result.passed() ? "PASS" : "FAIL", scenario.id(),
                    scenario.callReason(), scenario.expectedKinds(),
                    result.actualKind(), result.actualToolId(),
                    result.detail());
            if (result.passed()) {
                passed++;
            } else {
                failures.add(scenario.id() + ": " + result.detail());
            }
        }

        writeReport(endpoint, selected.size(), passed, results);
        assertEquals(selected.size(), passed,
                "Executor evaluation failures:\n"
                        + String.join("\n", failures));
    }

    private static Evaluation evaluate(
            ProductChainChatModelAdapter adapter,
            StrictChainProviderOutputParser parser,
            Endpoint endpoint,
            Scenario scenario) {
        String prompt = canonicalPrompt(scenario);
        int modelPromptCharacters = ProductExecutorModelContextView.project(
                prompt).length();
        int maxAttempts = Integer.getInteger(
                "executor.eval.maxAttempts", 1);
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException(
                    "executor.eval.maxAttempts must be between 1 and 3");
        }
        String previousInvalidOutput = null;
        String repairFeedback = null;
        for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
            ChainModelCallResult call = adapter.call(
                    new ChainModelCallRequest(
                            "executor-eval-" + scenario.id(), CONTEXT_ID,
                            "executor-eval-completion", ChainRole.EXECUTOR,
                            ChainWorkState.EXECUTING,
                            scenario.callReason(), endpoint.provider(),
                            endpoint.model(), prompt, attemptNo,
                            attemptNo > 1, repairFeedback,
                            previousInvalidOutput));
            if (call instanceof ChainModelCallResult.Failure failure) {
                return Evaluation.failed(scenario, null, null,
                        "provider failure " + failure.errorCode() + " "
                                + failure.safeMetadata().getOrDefault(
                                "failureReason", "unknown"),
                        null, prompt.length(), modelPromptCharacters,
                        failure.safeMetadata());
            }

            ChainModelCallResult.Success success =
                    (ChainModelCallResult.Success) call;
            try {
                ProviderRoleOutput output = parser.parse(
                        success.rawOutput(), ChainRole.EXECUTOR,
                        ChainWorkState.EXECUTING, scenario.gapId());
                List<String> problems = validate(scenario, output);
                String actualToolId = output.payload()
                        instanceof ExecutorPayload.ToolAction action
                        ? action.toolId() : null;
                return new Evaluation(problems.isEmpty(), output.kind(),
                        actualToolId,
                        problems.isEmpty()
                                ? attemptNo == 1 ? "matched"
                                : "matched after protocol repair attempt "
                                + attemptNo
                                : String.join("; ", problems),
                        success.rawOutput(), prompt.length(),
                        modelPromptCharacters, success.safeMetadata(),
                        scenario);
            } catch (ChainProviderProtocolException invalid) {
                String detail = "protocol " + invalid.code() + " at "
                        + invalid.path() + ": " + invalid.getMessage();
                if (attemptNo < maxAttempts) {
                    previousInvalidOutput = success.rawOutput();
                    repairFeedback = detail;
                    continue;
                }
                return Evaluation.failed(scenario,
                        rawKind(success.rawOutput()), null, detail,
                        success.rawOutput(), prompt.length(),
                        modelPromptCharacters, success.safeMetadata());
            } catch (RuntimeException invalid) {
                String detail = "validation "
                        + invalid.getClass().getSimpleName() + ": "
                        + safeMessage(invalid);
                if (attemptNo < maxAttempts) {
                    previousInvalidOutput = success.rawOutput();
                    repairFeedback = detail;
                    continue;
                }
                return Evaluation.failed(scenario,
                        rawKind(success.rawOutput()), null, detail,
                        success.rawOutput(), prompt.length(),
                        modelPromptCharacters, success.safeMetadata());
            }
        }
        throw new IllegalStateException(
                "executor evaluation attempt loop ended");
    }

    private static List<String> validate(
            Scenario scenario, ProviderRoleOutput output) {
        List<String> problems = new ArrayList<>();
        if (!scenario.expectedKinds().contains(output.kind())) {
            problems.add("unexpected kind");
        }
        if (!(output.payload() instanceof ExecutorPayload.ToolAction action)) {
            return problems;
        }
        if (!action.toolId().equals(scenario.expectedToolId())) {
            problems.add("tool expected=" + scenario.expectedToolId()
                    + " actual=" + action.toolId());
        }
        if (!action.requiredPermission().equals(
                scenario.expectedPermissionRef())) {
            problems.add("permission expected="
                    + scenario.expectedPermissionRef() + " actual="
                    + action.requiredPermission());
        }
        if (scenario.forbiddenToolIds().contains(action.toolId())) {
            problems.add("selected explicitly confused tool "
                    + action.toolId());
        }
        try {
            JsonNode arguments = JSON.readTree(action.completeArguments());
            if (!arguments.isObject()) {
                problems.add("completeArguments is not an object");
            } else {
                for (String key : scenario.requiredArgumentKeys()) {
                    if (!arguments.has(key)) {
                        problems.add("missing argument key " + key);
                    }
                }
            }
        } catch (Exception invalidArguments) {
            problems.add("completeArguments is not valid JSON");
        }
        if ("PRESENT".equals(scenario.expectedRepairFields())) {
            if (action.priorErrorRef() == null
                    || action.priorActionRef() == null
                    || action.changeFromPriorAction() == null
                    || action.expectedProgress() == null) {
                problems.add("repair fields are not all present");
            }
        } else if ("ABSENT".equals(scenario.expectedRepairFields())
                && (action.priorErrorRef() != null
                || action.priorActionRef() != null
                || action.changeFromPriorAction() != null
                || action.expectedProgress() != null)) {
            problems.add("future contingency was treated as prior failure");
        }
        return problems;
    }

    private static String canonicalPrompt(Scenario scenario) {
        Map<ChainContextModule, Map<String, ChainContextValue>> values =
                scenarioFields(scenario);
        List<ContextModuleRecord> records = new ArrayList<>();
        for (ChainContextModule module
                : ChainContextInputMatrix.orderedModules()) {
            ChainContextVersionMatrix.VersionRequirement version =
                    ChainContextVersionMatrix.requirement(module);
            ChainContextSourceSnapshot snapshot = new ChainContextSourceSnapshot(
                    module, ChainContextModuleStatus.PRESENT,
                    vector(version.sourceVersionFields(), module, "source"),
                    vector(version.readBoundaryFields(), module, "boundary"),
                    "executor-evaluation-v1", "none-v1",
                    Map.of("fixtureSet", ChainContextValue.text(
                            "executor-decisions-v1")),
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
            scenarioFields(Scenario scenario) {
        Map<ChainContextModule, Map<String, ChainContextValue>> result =
                new LinkedHashMap<>();
        for (ChainContextModule module
                : ChainContextInputMatrix.orderedModules()) {
            Map<String, ChainContextValue> fields = new LinkedHashMap<>();
            for (String name : ChainContextInputMatrix
                    .requiredProjectionFields(ChainRole.EXECUTOR, module)) {
                fields.put(name, empty(name));
            }
            result.put(module, fields);
        }

        putInstruction(result, scenario);
        putConversation(result, scenario);
        putProject(result);
        putTaskAndStep(result, scenario);
        putRuntimeAndFacts(result, scenario);
        putRules(result, scenario);
        putModelCall(result, scenario);
        return result;
    }

    private static void putInstruction(
            Map<ChainContextModule, Map<String, ChainContextValue>> result,
            Scenario scenario) {
        Map<String, ChainContextValue> fields = result.get(
                ChainContextModule.USER_INSTRUCTION_CHAIN);
        fields.put("instructions.completeStructure",
                ChainContextValue.object(Map.of(
                        "currentInstructionRef",
                        referenced("instruction.executor-eval"),
                        "body", ChainContextValue.text(
                                scenario.instruction()))));
        fields.put("instructions.effectiveBodies",
                ChainContextValue.array(List.of(ChainContextValue.object(
                        Map.of("instructionRef",
                                referenced("instruction.executor-eval"),
                                "body", ChainContextValue.text(
                                        scenario.instruction()))))));
        fields.put("instructions.relations",
                ChainContextValue.array(List.of(ChainContextValue.object(
                        Map.of("relationKind",
                                ChainContextValue.text("CURRENT"))))));
        fields.put("instructions.runningInstructionState",
                ChainContextValue.text("ACTIVE"));
    }

    private static void putConversation(
            Map<ChainContextModule, Map<String, ChainContextValue>> result,
            Scenario scenario) {
        Map<String, ChainContextValue> fields = result.get(
                ChainContextModule.CONVERSATION_CONTEXT);
        ChainContextValue message = ChainContextValue.object(Map.of(
                "role", ChainContextValue.text("user"),
                "messageRef", referenced("message.executor-eval"),
                "body", ChainContextValue.text(scenario.instruction())));
        fields.put("conversation.latestUserMessage", message);
        fields.put("conversation.recentComplete",
                ChainContextValue.array(List.of(message)));
        fields.put("conversation.earlierSummary", ChainContextValue.nil());
        fields.put("conversation.summaryCoverage",
                ChainContextValue.object(Map.of("coveredThroughMessageRef",
                        ChainContextValue.text("NONE"))));
    }

    private static void putProject(
            Map<ChainContextModule, Map<String, ChainContextValue>> result) {
        Map<String, ChainContextValue> fields = result.get(
                ChainContextModule.PROJECT_AND_INPUT_MATERIALS);
        fields.put("project.version", ChainContextValue.object(Map.of(
                "projectRef", referenced("project.executor-eval"),
                "projectVersionRef",
                referenced("project-version.executor-eval"))));
        fields.put("project.manifest.complete", ChainContextValue.object(
                Map.of("projectVersionRef",
                        referenced("project-version.executor-eval"),
                        "paths", strings(List.of(
                                "src/main/java/Sort.java",
                                "paper/main.tex", "paper/appendix.md",
                                "paper/references.bib",
                                "reports/review.pdf", "reports/final.md",
                                "results/metrics.xlsx",
                                "results/metrics.csv", "logs/train.log")))));
        fields.put("project.currentStepObjects",
                ChainContextValue.text(
                        "Use only the paths and formal facts visible in this evaluation Context."));
        fields.put("project.targetAndModifiedFileExpansion",
                ChainContextValue.object(Map.of(
                        "status", ChainContextValue.text("BOUNDED_FIXTURE"))));
    }

    private static void putTaskAndStep(
            Map<ChainContextModule, Map<String, ChainContextValue>> result,
            Scenario scenario) {
        Map<String, ChainContextValue> task = result.get(
                ChainContextModule.TASK_CONTRACT);
        task.put("taskFrame.complete", ChainContextValue.object(Map.of(
                "taskFrameRef", referenced("task-frame.executor-eval"),
                "objective", ChainContextValue.text(scenario.instruction()),
                "permissionTier", ChainContextValue.text(
                        "SANDBOX_STANDARD"))));
        task.put("taskFrame.hardBoundary", ChainContextValue.object(Map.of(
                "workspaceOnly", ChainContextValue.bool(true),
                "arbitraryNetworkOrHostExecutionAllowed",
                ChainContextValue.bool(false))));

        Map<String, ChainContextValue> plan = result.get(
                ChainContextModule.PLAN_AND_STEP_CONTRACT);
        plan.put("plan.currentRevisionComplete",
                ChainContextValue.object(Map.of(
                        "planRef", referenced("plan.executor-eval"),
                        "planRevisionRef",
                        referenced("plan-revision.executor-eval"))));
        plan.put("plan.currentStep", ChainContextValue.object(Map.of(
                "stepRef", referenced("step.executor-eval"),
                "objective", ChainContextValue.text(
                        scenario.stepObjective()),
                "validationRequirementIds", validationIds(scenario))));
        plan.put("plan.dependencies", ChainContextValue.array(List.of()));
        plan.put("plan.completionConditions",
                strings(scenario.completionConditions()));
        plan.put("plan.constraints", strings(List.of(
                "Use one output form and only formal visible facts.")));
        plan.put("plan.scope", strings(List.of("current active step")));
        plan.put("plan.deliverables",
                strings(scenario.completionConditions()));
    }

    private static void putRuntimeAndFacts(
            Map<ChainContextModule, Map<String, ChainContextValue>> result,
            Scenario scenario) {
        Map<String, ChainContextValue> runtime = result.get(
                ChainContextModule.TASK_AND_STEP_RUNTIME_STATE);
        runtime.put("runtime.currentStep", ChainContextValue.object(Map.of(
                "stepRef", referenced("step.executor-eval"),
                "status", ChainContextValue.text("ACTIVE"),
                "callReason", ChainContextValue.text(
                        scenario.callReason()))));
        runtime.put("runtime.candidateResult",
                ChainContextValue.object(Map.of(
                        "status", ChainContextValue.text("NONE"))));
        runtime.put("runtime.acceptedResultCatalog",
                ChainContextValue.array(List.of()));
        runtime.put("runtime.directDependencies",
                ChainContextValue.array(List.of()));

        Map<String, ChainContextValue> actions = result.get(
                ChainContextModule.CURRENT_STEP_ACTION_TOOLS_AND_ERRORS);
        actions.put("action.currentStepAttemptTable",
                strings(scenario.modelFacts()));
        actions.put("action.latestOrUnresolvedReceiptAndErrorExpansion",
                strings(scenario.modelFacts()));

        Map<String, ChainContextValue> validation = result.get(
                ChainContextModule.VALIDATION_AND_PUBLISH);
        validation.put("validation.currentStepFormalValidation",
                strings(scenario.modelFacts()));
        validation.put("validation.finalizationFailureSeparated",
                ChainContextValue.bool(true));

        Map<String, ChainContextValue> review = result.get(
                ChainContextModule.REVIEW_DECISIONS_AND_PENDING_ITEMS);
        review.put("review.latestDecision",
                ChainContextValue.object(Map.of("callReason",
                        ChainContextValue.text(scenario.callReason()))));
        review.put("review.previousReviewGap",
                scenario.gapId() == null ? ChainContextValue.nil()
                        : ChainContextValue.object(Map.of(
                        "gapRef", referenced(scenario.gapId()),
                        "status", ChainContextValue.text(
                                "RESPONSE_RECEIVED"))));
        review.put("review.loopState", ChainContextValue.text("ACTIVE"));

        Map<String, ChainContextValue> evidence = result.get(
                ChainContextModule.MEMORY_RETRIEVAL_AND_KNOWLEDGE_EVIDENCE);
        evidence.put("evidence.frozenCompleteCatalog",
                strings(scenario.modelFacts()));
        evidence.put("evidence.currentStepMechanicalExpansion",
                strings(scenario.modelFacts()));
    }

    private static void putRules(
            Map<ChainContextModule, Map<String, ChainContextValue>> result,
            Scenario scenario) {
        Map<String, ChainContextValue> rules = result.get(
                ChainContextModule
                        .RUNTIME_RULES_CAPABILITIES_AND_PERMISSIONS);
        rules.put("rules.executorSchema",
                ProductChainRoleSchemaSource.schema(
                        ChainRole.EXECUTOR, scenario.callReason()).value());
        rules.put("rules.completeToolSchemas",
                ProductChainToolContextValueCodec.encode(
                        ProductChainToolContextProjection.project(
                                evaluationFrame())).value());
        rules.put("rules.skills", ChainContextValue.object(Map.of(
                "selectionKind", ChainContextValue.text("NONE"))));
        rules.put("rules.permissions", ChainContextValue.object(Map.of(
                "permissionTier", ChainContextValue.text(
                        "SANDBOX_STANDARD"),
                "arbitraryNetworkAllowed", ChainContextValue.bool(false))));
        rules.put("rules.workingDirectory", ChainContextValue.object(Map.of(
                "workspaceRef", referenced("workspace.executor-eval"),
                "root", ChainContextValue.text("PROJECT_ROOT"))));
        rules.put("rules.writeScope", ChainContextValue.object(Map.of(
                "workspaceRef", referenced("workspace.executor-eval"),
                "writeAllowed", ChainContextValue.bool(true))));
    }

    private static void putModelCall(
            Map<ChainContextModule, Map<String, ChainContextValue>> result,
            Scenario scenario) {
        Map<String, ChainContextValue> fields = result.get(
                ChainContextModule.MODEL_INVOCATIONS_AND_PROPOSALS);
        fields.put("model.stateHeader", ChainContextValue.object(Map.of(
                "role", ChainContextValue.text("EXECUTOR"),
                "workState", ChainContextValue.text("EXECUTING"),
                "callReason", ChainContextValue.text(
                        scenario.callReason()))));
        fields.put("model.callReason",
                ChainContextValue.text(scenario.callReason()));
        fields.put("model.currentAndLatestExecutorMetadata",
                ChainContextValue.object(Map.of(
                        "evaluationScenario", ChainContextValue.text(
                                scenario.id()),
                        "facts", strings(scenario.modelFacts()))));
    }

    private static ChainContextValue validationIds(Scenario scenario) {
        return "STEP_RESULT".equals(scenario.callReason())
                ? strings(List.of("validation.run.1"))
                : ChainContextValue.array(List.of());
    }

    private static TaskFrame evaluationFrame() {
        return new TaskFrame(
                new TaskFrameId("task.executor-evaluation"),
                "Evaluate one Executor decision.",
                List.of("frozen Project and current Step"),
                List.of("one typed Executor proposal"),
                List.of("respect exact tool and permission authorities"),
                Optional.of(new ProjectVersionRef(
                        "project.executor-eval",
                        "project-version.executor-eval")),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.values()),
                        NetworkPolicy.ALLOWLIST_ONLY,
                        List.of("product-literature-search"),
                        new ResourceLimits(
                                Duration.ofMinutes(1),
                                Duration.ofSeconds(30), 1024, 512, 2),
                        Set.of()),
                NOW);
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
        try (InputStream input = ExecutorDecisionEvaluationTest.class
                .getResourceAsStream(
                        "/executor-evaluation/executor-decisions-v1.json")) {
            if (input == null) {
                throw new IllegalStateException(
                        "executor evaluation dataset is missing");
            }
            return JSON.readValue(input,
                    new TypeReference<List<Scenario>>() { });
        }
    }

    private static List<Scenario> selectedScenarios(
            List<Scenario> scenarios) {
        String requested = System.getProperty(
                "executor.eval.ids", "").trim();
        List<Scenario> filtered = scenarios;
        if (!requested.isEmpty()) {
            Set<String> ids = Set.copyOf(
                    Arrays.asList(requested.split(",")));
            filtered = scenarios.stream()
                    .filter(value -> ids.contains(value.id())).toList();
        }
        int limit = Integer.getInteger(
                "executor.eval.limit", filtered.size());
        if (limit < 1) {
            throw new IllegalArgumentException(
                    "executor.eval.limit must be positive");
        }
        return filtered.stream().limit(limit).toList();
    }

    private static void writeReport(
            Endpoint endpoint, int total, int passed,
            ArrayNode results) throws Exception {
        ObjectNode report = JSON.createObjectNode();
        report.put("dataset", "executor-decisions-v1");
        report.put("provider", endpoint.provider());
        report.put("model", endpoint.model());
        report.put("total", total);
        report.put("passed", passed);
        report.put("failed", total - passed);
        report.set("results", results);
        Path directory = Path.of("target", "executor-evaluation");
        Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                directory.resolve("report.json").toFile(), report);
    }

    private static Map<String, ChainContextValue> vector(
            List<String> names, ChainContextModule module, String kind) {
        Map<String, ChainContextValue> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(name, referenced("executor-eval:"
                    + module.wireName() + ":" + kind + ":" + name));
        }
        return Map.copyOf(result);
    }

    private static ChainContextValue empty(String field) {
        return ChainContextValue.object(Map.of(
                "status", ChainContextValue.text("EXPLICIT_EMPTY"),
                "field", ChainContextValue.text(field)));
    }

    private static ChainContextValue.ArrayValue strings(
            List<String> values) {
        return ChainContextValue.array(values.stream()
                .map(ChainContextValue::text).toList());
    }

    private static ChainContextValue.Text referenced(String value) {
        return ChainContextValue.referencedText(value, value);
    }

    private static String rawKind(String raw) {
        try {
            return JSON.readTree(raw).path("kind").asText("UNKNOWN");
        } catch (Exception ignored) {
            return "UNPARSEABLE";
        }
    }

    private static String safeMessage(RuntimeException failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank()
                ? failure.getClass().getSimpleName() : value;
    }

    private record Endpoint(
            String provider, String model, String apiKey, String apiUrl) {
    }

    private record Scenario(
            String id,
            String callReason,
            String gapId,
            String instruction,
            String stepObjective,
            List<String> completionConditions,
            List<String> modelFacts,
            List<String> expectedKinds,
            String expectedToolId,
            String expectedPermissionRef,
            List<String> forbiddenToolIds,
            List<String> requiredArgumentKeys,
            String expectedRepairFields,
            String note) {
        private Scenario {
            if (id == null || id.isBlank()
                    || callReason == null || callReason.isBlank()
                    || instruction == null || instruction.isBlank()
                    || stepObjective == null || stepObjective.isBlank()
                    || completionConditions == null
                    || completionConditions.isEmpty()
                    || modelFacts == null || modelFacts.isEmpty()
                    || expectedKinds == null || expectedKinds.isEmpty()) {
                throw new IllegalArgumentException(
                        "invalid executor evaluation scenario");
            }
            completionConditions = List.copyOf(completionConditions);
            modelFacts = List.copyOf(modelFacts);
            expectedKinds = List.copyOf(expectedKinds);
            forbiddenToolIds = forbiddenToolIds == null
                    ? List.of() : List.copyOf(forbiddenToolIds);
            requiredArgumentKeys = requiredArgumentKeys == null
                    ? List.of() : List.copyOf(requiredArgumentKeys);
        }
    }

    private record Evaluation(
            boolean passed,
            String actualKind,
            String actualToolId,
            String detail,
            String rawOutput,
            int canonicalPromptCharacters,
            int modelPromptCharacters,
            Map<String, String> metadata,
            Scenario scenario) {
        private static Evaluation failed(
                Scenario scenario, String actualKind,
                String actualToolId, String detail, String rawOutput,
                int canonicalPromptCharacters,
                int modelPromptCharacters,
                Map<String, String> metadata) {
            return new Evaluation(false,
                    actualKind == null ? "NONE" : actualKind,
                    actualToolId, detail, rawOutput,
                    canonicalPromptCharacters, modelPromptCharacters,
                    metadata, scenario);
        }

        private ObjectNode json() {
            ObjectNode value = JSON.createObjectNode();
            value.put("id", scenario.id());
            value.put("callReason", scenario.callReason());
            value.putPOJO("expectedKinds", scenario.expectedKinds());
            value.put("expectedToolId", scenario.expectedToolId());
            value.put("actualKind", actualKind);
            value.put("actualToolId", actualToolId);
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
