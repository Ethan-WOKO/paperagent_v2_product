package com.yanban.api.agent.v2.adaptive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
import com.yanban.api.agent.v2.adaptive.reflection.ReflectionAuditFormatException;
import com.yanban.api.agent.v2.adaptive.reflection.ReflectionContext;
import com.yanban.api.agent.v2.adaptive.reflection.ReflectionProvider;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.providers.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class V2ModelReflectionProvider implements ReflectionProvider {
    private static final Logger log = LoggerFactory.getLogger(
            V2ModelReflectionProvider.class);
    private static final String PROMPT = """
            Reflect on authoritative V2 execution facts. Return one strict JSON
            object with exactly decision, reason, finalText, replacementSteps.
            The recent facts include activeStepId and activeStepTitle. Judge
            only that active Step; do not treat work for another Step as proof
            that the active Step is complete.
            decision is CONTINUE, REPLAN, COMPLETE, or FAIL.
            COMPLETE means the current active Step's completion criteria are
            satisfied by the supplied persisted Step result, durable Receipts
            and facts, even when
            the Plan has later Steps. The coordinator then persists this Step
            completion and advances the Plan. It exposes finalText as the
            user's final answer only when the completed Step makes the whole
            Plan terminal. CONTINUE means the same active Step still needs
            another effect. For a completed nonterminal Step, put a concise
            verified Step-result summary in finalText; the coordinator
            discards that provisional text after advancing. Do not return
            CONTINUE merely because later Steps remain or the final user
            answer is not ready.
            When a successful Receipt satisfies the current active Step,
            COMPLETE that Step even if its result reveals work for a later
            Step. Let the persisted Plan advance normally. Do not REPLAN
            merely to streamline, combine, reorder, or make future
            not-started Steps more specific. Use REPLAN only when the current
            active Step itself cannot proceed as planned or a failed Receipt
            requires a changed approach.
            Do not COMPLETE a Step that requires creating or modifying Project
            files unless the facts contain a successful
            project.candidate.compose Receipt. A sandbox.execute Receipt proves
            execution only; it does not prove a Workspace diff or Candidate.
            A project.read Receipt proves reading only. Do not COMPLETE a Step
            whose intent, expected outcome, or completion criteria require
            sandbox compilation, execution, or tests unless the facts contain
            a successful executionReceipt whose toolKind is sandbox.execute
            for that active Step. Likewise, only an executionReceipt whose
            toolKind is project.candidate.compose proves Candidate creation.
            Every executionReceipt includes stepId and authorityScope. Use
            only Receipts whose stepId equals activeStepId as evidence for the
            active Step. Treat PROJECT_CONTENT_READ_ONLY and PROJECT_SEARCH_ONLY
            as read-only evidence, SANDBOX_EXECUTION_ONLY as execution-only
            evidence, and REVIEWABLE_CANDIDATE_CREATED as Candidate evidence.
            A persisted currentStepResult may satisfy a reasoning-only or
            synthesis-only Step without a current Receipt. It never proves a
            Project read, mutable Project state, Candidate, tool execution,
            sandbox result, retrieval, or network fact unless matching
            authoritative Receipts or artifacts are also supplied. Accepted
            completedFacts may be used as dependencies, but are not evidence
            that the current Step performed a new external action.
            CONTINUE, FAIL, and REPLAN require finalText:null. COMPLETE
            requires a nonblank finalText. Only REPLAN may have nonempty
            replacementSteps.
            Each REPLAN replacement Step must contain exactly:
            id, intent, expectedOutcome, dependencies, completionCriteria,
            maxAttempts, maxDurationSeconds.
            dependencies is an array containing only earlier replacement Step
            ids. completionCriteria is a nonempty string array. maxAttempts is
            an integer from 1 to 5. maxDurationSeconds is an integer from 1 to
            3600. Replacement Steps describe goals and outcomes; do not bind
            them to tools. Tool selection remains dynamic during execution.
            When a failed Receipt can be corrected, return REPLAN with a
            complete replacement Step instead of CONTINUE unchanged.
            REPLAN appends 1-8 replacement Steps and never rewrites facts.
            finalText is non-null only for COMPLETE. No markdown.
            """;
    private static final String STEP_STATE_AUDIT_PROMPT = """
            Audit whether the current active Step is complete against
            authoritative V2 facts. The proposed decision may be COMPLETE or
            CONTINUE, but do not merely repeat it. This audit does not choose
            tools or change permissions. Judge only the
            active Step. A Receipt supports that Step only when its stepId
            equals activeStepId, and its authorityScope must actually prove
            the Step's intent, expected outcome, and completion criteria.
            PROJECT_CONTENT_READ_ONLY and PROJECT_SEARCH_ONLY do not prove
            execution or Candidate creation. SANDBOX_EXECUTION_ONLY does not
            prove Candidate creation. REVIEWABLE_CANDIDATE_CREATED does not by
            itself prove sandbox execution. Receipts belonging to other Steps
            are context, not evidence for this completion.
            Return exactly one strict JSON object with exactly three fields:
            complete, reason, and stepResult. complete is true only when the
            current Step is fully supported as complete; otherwise it is
            false. reason is a concise nonblank string. stepResult is a
            concise nonblank verified Step-result string when complete is
            true, and null when complete is false. The audit input isolates
            currentStepReceipts for activeStepId; receipts from other Steps
            are deliberately excluded. It also provides
            successfulCurrentStepReceipts as an explicit subset. A later
            successful Receipt is not invalidated by earlier failed Receipts
            or by earlier attempts with a different tool. For a Step whose
            completion criteria require compilation or execution, a
            successful sandbox.execute Receipt with exitCode 0 completes the
            Step unless the criteria explicitly require another assertion
            that the Receipt does not establish. Example complete response:
            A nonblank currentStepResult may complete a reasoning-only or
            synthesis-only Step when it satisfies the Step and its claims are
            supported by completedFacts. It cannot replace required tool,
            Project, Candidate, sandbox, retrieval, or network evidence.
            Example complete response:
            {"complete":true,"reason":"receipt proves the step",
            "stepResult":"verified result"}. Example incomplete response:
            {"complete":false,"reason":"required evidence is missing",
            "stepResult":null}. No markdown.
            """;
    private static final String STEP_STATE_AUDIT_REPAIR_PROMPT = """
            Repair only the response format of a V2 step-state audit.
            Re-evaluate the supplied authoritative bounded facts and return
            exactly one strict JSON object with exactly three fields:
            complete, reason, and stepResult. complete must be a boolean.
            reason must be a concise nonblank string. stepResult must be a
            concise nonblank verified Step-result string when complete is
            true, and null when complete is false. Do not add fields, prose,
            markdown, or code fences. Do not return decision, finalText, or
            replacementSteps. Do not change tools or permissions.
            """;
    private static final int MAX_AUDIT_REASON_CHARACTERS = 1_000;
    private static final int MAX_AUDIT_RESULT_CHARACTERS = 20_000;
    private final ModelProvider provider;
    private final ObjectMapper json;
    private final Optional<TaskFrameId> taskFrameId;
    private final Optional<PlanId> planId;
    private final Optional<PlanRevisionId> revisionId;

    public V2ModelReflectionProvider(
            ModelProvider provider, ObjectMapper json,
            TaskFrameId taskFrameId, PlanId planId,
            PlanRevisionId revisionId) {
        this.provider = provider;
        this.json = json;
        this.taskFrameId = Optional.ofNullable(taskFrameId);
        this.planId = Optional.ofNullable(planId);
        this.revisionId = Optional.ofNullable(revisionId);
    }

    @Override
    public String reflect(ReflectionContext context) {
        String facts;
        try {
            facts = json.writeValueAsString(context);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "reflection context encoding failed");
        }
        String suffix = hash(facts).substring(0, 32);
        ModelRequest request = new ModelRequest(
                new ModelRequestId("adaptive-reflection-" + suffix),
                new CorrelationId("adaptive-reflection-" + suffix),
                List.of(
                        new ModelMessage(MessageRole.SYSTEM, PROMPT),
                        new ModelMessage(MessageRole.USER,
                                "Authoritative bounded facts:\n" + facts)),
                List.of(),
                new GenerationOptions(
                        4096, 0, 0.1d, OptionalLong.empty(), Map.of()),
                taskFrameId, planId, revisionId,
                Optional.empty(), false);
        long modelStarted = System.nanoTime();
        log.info(
                "V2 reflection model call started planId={} revisionId={}",
                planId.map(PlanId::value).orElse("none"),
                revisionId.map(PlanRevisionId::value).orElse("none"));
        ModelProviderResult result;
        try {
            result = provider.complete(request);
        } catch (RuntimeException failure) {
            log.warn(
                    "V2 reflection model call failed planId={} "
                            + "revisionId={} elapsedMillis={} "
                            + "exceptionType={} causeType={} origin={}",
                    planId.map(PlanId::value).orElse("none"),
                    revisionId.map(PlanRevisionId::value).orElse("none"),
                    elapsedMillis(modelStarted),
                    V2SafeFailureDiagnostics.exceptionType(failure),
                    V2SafeFailureDiagnostics.causeType(failure),
                    V2SafeFailureDiagnostics.origin(failure));
            throw failure;
        }
        log.info(
                "V2 reflection model call completed planId={} "
                        + "revisionId={} elapsedMillis={} resultType={}",
                planId.map(PlanId::value).orElse("none"),
                revisionId.map(PlanRevisionId::value).orElse("none"),
                elapsedMillis(modelStarted),
                result == null ? "null"
                        : result.getClass().getSimpleName());
        if (!(result instanceof ModelResponse response)
                || !response.proposedToolCalls().isEmpty()) {
            throw new IllegalStateException("reflection provider rejected");
        }
        String decision = response.assistantText()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "reflection provider returned no decision"));
        String proposedAction = safeDecision(decision);
        if ("REPLAN".equals(proposedAction)) {
            decision = namespaceReplanStepIds(decision, suffix);
        } else if (Set.of("COMPLETE", "CONTINUE").contains(
                proposedAction)) {
            decision = auditStepState(
                    auditFacts(context), decision, suffix);
        }
        log.info(
                "V2 reflection model decision planId={} revisionId={} "
                        + "decision={}",
                planId.map(PlanId::value).orElse("none"),
                revisionId.map(PlanRevisionId::value).orElse("none"),
                safeDecision(decision));
        return decision;
    }

    private String namespaceReplanStepIds(String raw, String suffix) {
        try {
            var root = json.readTree(raw);
            if (root == null || !root.isObject()
                    || !"REPLAN".equals(
                            root.path("decision").asText())
                    || !root.path("replacementSteps").isArray()) {
                return raw;
            }
            Map<String, String> replacements = new LinkedHashMap<>();
            int ordinal = 0;
            for (var step : root.path("replacementSteps")) {
                if (!step.isObject() || !step.path("id").isTextual()
                        || !step.path("dependencies").isArray()) {
                    return raw;
                }
                String previousId = step.path("id").textValue();
                if (previousId == null || previousId.isBlank()
                        || replacements.containsKey(previousId)) {
                    return raw;
                }
                String namespaced = "replan-step-"
                        + suffix.substring(0, 12)
                        + "-" + ++ordinal;
                replacements.put(previousId, namespaced);
                ((com.fasterxml.jackson.databind.node.ObjectNode) step)
                        .put("id", namespaced);
                var dependencies =
                        (com.fasterxml.jackson.databind.node.ArrayNode)
                                step.path("dependencies");
                for (int index = 0; index < dependencies.size(); index++) {
                    if (!dependencies.get(index).isTextual()) {
                        return raw;
                    }
                    String replacement = replacements.get(
                            dependencies.get(index).textValue());
                    if (replacement != null) {
                        dependencies.set(index,
                                json.getNodeFactory().textNode(replacement));
                    }
                }
            }
            log.info(
                    "V2 reflection replan identities namespaced planId={} "
                            + "revisionId={} replacementCount={}",
                    planId.map(PlanId::value).orElse("none"),
                    revisionId.map(PlanRevisionId::value).orElse("none"),
                    replacements.size());
            return json.writeValueAsString(root);
        } catch (Exception invalid) {
            return raw;
        }
    }

    private String auditFacts(ReflectionContext context) {
        String activeStepId = factValue(
                context.recentExecutionFacts(), "activeStepId=");
        String activeStepTitle = factValue(
                context.recentExecutionFacts(), "activeStepTitle=");
        List<String> currentStepReceipts = activeStepId == null
                ? List.of()
                : context.recentExecutionFacts().stream()
                        .filter(value -> value.startsWith(
                                "executionReceipt="))
                        .filter(value -> value.contains(
                                "stepId=" + activeStepId + ","))
                        .toList();
        List<String> successfulCurrentStepReceipts =
                currentStepReceipts.stream()
                        .filter(value -> value.contains("status=SUCCESS,"))
                        .toList();
        long successfulSandboxReceipts =
                successfulCurrentStepReceipts.stream()
                        .filter(value -> value.contains(
                                "toolKind=sandbox.execute,"))
                        .filter(value -> value.contains(
                                "exitCode=Optional[0],"))
                        .count();
        List<String> currentStepReceiptIds = context
                .recentExecutionFacts().stream()
                .filter(value -> value.startsWith(
                        "activeStepReceiptId="))
                .map(value -> value.substring(
                        "activeStepReceiptId=".length()))
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        log.info(
                "V2 reflection audit evidence planId={} activeStepId={} "
                        + "receiptCount={} successfulReceiptCount={} "
                        + "successfulSandboxReceiptCount={}",
                planId.map(PlanId::value).orElse("none"),
                activeStepId == null ? "none" : activeStepId,
                currentStepReceipts.size(),
                successfulCurrentStepReceipts.size(),
                successfulSandboxReceipts);
        try {
            var bounded = json.createObjectNode();
            bounded.put("taskFrame", context.taskFrame());
            bounded.put("currentPlan", context.currentPlan());
            if (activeStepId == null) {
                bounded.putNull("activeStepId");
            } else {
                bounded.put("activeStepId", activeStepId);
            }
            if (activeStepTitle == null) {
                bounded.putNull("activeStepTitle");
            } else {
                bounded.put("activeStepTitle", activeStepTitle);
            }
            bounded.set("currentStepReceipts",
                    json.valueToTree(currentStepReceipts));
            bounded.set("currentStepReceiptIds",
                    json.valueToTree(currentStepReceiptIds));
            bounded.set("successfulCurrentStepReceipts",
                    json.valueToTree(successfulCurrentStepReceipts));
            bounded.set("completedFacts",
                    json.valueToTree(context.completedFacts()));
            bounded.set("currentStepResult",
                    json.valueToTree(context.currentStepResult()));
            bounded.set("unfinishedSteps",
                    json.valueToTree(context.unfinishedSteps()));
            return json.writeValueAsString(bounded);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "reflection step-state audit facts encoding failed");
        }
    }

    private static String factValue(
            List<String> facts, String prefix) {
        return facts.stream()
                .filter(value -> value.startsWith(prefix))
                .reduce((ignored, latest) -> latest)
                .map(value -> value.substring(prefix.length()))
                .filter(value -> !value.isBlank())
                .orElse(null);
    }

    private String auditStepState(
            String facts, String proposedDecision, String suffix) {
        ModelRequest request = new ModelRequest(
                new ModelRequestId("adaptive-step-state-audit-" + suffix),
                new CorrelationId("adaptive-step-state-audit-" + suffix),
                List.of(
                        new ModelMessage(
                                MessageRole.SYSTEM,
                                STEP_STATE_AUDIT_PROMPT),
                        new ModelMessage(
                                MessageRole.USER,
                                "Authoritative bounded facts:\n" + facts
                                        + "\nProposed decision:\n"
                                        + proposedDecision)),
                List.of(),
                new GenerationOptions(
                        4096, 0, 0.1d, OptionalLong.empty(), Map.of()),
                taskFrameId, planId, revisionId,
                Optional.empty(), false);
        long modelStarted = System.nanoTime();
        log.info(
                "V2 reflection step-state audit started planId={} "
                        + "revisionId={}",
                planId.map(PlanId::value).orElse("none"),
                revisionId.map(PlanRevisionId::value).orElse("none"));
        ModelProviderResult result;
        try {
            result = provider.complete(request);
        } catch (RuntimeException failure) {
            log.warn(
                    "V2 reflection step-state audit failed planId={} "
                            + "revisionId={} elapsedMillis={} "
                            + "exceptionType={} causeType={} origin={}",
                    planId.map(PlanId::value).orElse("none"),
                    revisionId.map(PlanRevisionId::value).orElse("none"),
                    elapsedMillis(modelStarted),
                    V2SafeFailureDiagnostics.exceptionType(failure),
                    V2SafeFailureDiagnostics.causeType(failure),
                    V2SafeFailureDiagnostics.origin(failure));
            throw failure;
        }
        String audited = "";
        StepStateAudit audit;
        try {
            audited = auditText(result);
            audit = parseStepStateAudit(audited);
        } catch (ReflectionAuditFormatException invalid) {
            log.warn(
                    "V2 reflection step-state audit format invalid "
                            + "planId={} revisionId={} elapsedMillis={} "
                            + "outputDigest={}",
                    planId.map(PlanId::value).orElse("none"),
                    revisionId.map(PlanRevisionId::value).orElse("none"),
                    elapsedMillis(modelStarted), digest(audited));
            audit = repairStepStateAudit(
                    facts, proposedDecision, audited, suffix);
        }
        String action = audit.complete() ? "COMPLETE" : "CONTINUE";
        log.info(
                "V2 reflection step-state audit completed planId={} "
                        + "revisionId={} elapsedMillis={} decision={}",
                planId.map(PlanId::value).orElse("none"),
                revisionId.map(PlanRevisionId::value).orElse("none"),
                elapsedMillis(modelStarted), action);
        try {
            var corrected = json.createObjectNode();
            corrected.put("decision", action);
            corrected.put("reason", audit.reason());
            if (audit.complete()) {
                corrected.put("finalText", audit.stepResult());
            } else {
                corrected.putNull("finalText");
            }
            corrected.putArray("replacementSteps");
            return json.writeValueAsString(corrected);
        } catch (Exception impossible) {
            throw new IllegalStateException(
                    "reflection step-state audit encoding failed");
        }
    }

    private StepStateAudit repairStepStateAudit(
            String facts, String proposedDecision, String invalidAudit,
            String suffix) {
        String repairSuffix = hash(
                suffix + "\n" + proposedDecision + "\n" + invalidAudit)
                .substring(0, 32);
        ModelRequest request = new ModelRequest(
                new ModelRequestId(
                        "adaptive-step-state-audit-repair-" + repairSuffix),
                new CorrelationId(
                        "adaptive-step-state-audit-repair-" + repairSuffix),
                List.of(
                        new ModelMessage(
                                MessageRole.SYSTEM,
                                STEP_STATE_AUDIT_REPAIR_PROMPT),
                        new ModelMessage(
                                MessageRole.USER,
                                "Authoritative bounded facts:\n" + facts
                                        + "\nProposed decision:\n"
                                        + proposedDecision
                                        + "\nPrevious invalid audit output:\n"
                                        + boundedRepairInput(invalidAudit))),
                List.of(),
                new GenerationOptions(
                        4096, 0, 0.1d, OptionalLong.empty(), Map.of()),
                taskFrameId, planId, revisionId,
                Optional.empty(), false);
        long modelStarted = System.nanoTime();
        log.info(
                "V2 reflection step-state audit format repair started "
                        + "planId={} revisionId={} invalidOutputDigest={}",
                planId.map(PlanId::value).orElse("none"),
                revisionId.map(PlanRevisionId::value).orElse("none"),
                digest(invalidAudit));
        ModelProviderResult result;
        try {
            result = provider.complete(request);
        } catch (RuntimeException failure) {
            log.warn(
                    "V2 reflection step-state audit format repair failed "
                            + "planId={} revisionId={} elapsedMillis={} "
                            + "exceptionType={} causeType={} origin={}",
                    planId.map(PlanId::value).orElse("none"),
                    revisionId.map(PlanRevisionId::value).orElse("none"),
                    elapsedMillis(modelStarted),
                    V2SafeFailureDiagnostics.exceptionType(failure),
                    V2SafeFailureDiagnostics.causeType(failure),
                    V2SafeFailureDiagnostics.origin(failure));
            throw failure;
        }
        String repaired = auditText(result);
        StepStateAudit audit = parseStepStateAudit(repaired);
        log.info(
                "V2 reflection step-state audit format repair completed "
                        + "planId={} revisionId={} elapsedMillis={} decision={}",
                planId.map(PlanId::value).orElse("none"),
                revisionId.map(PlanRevisionId::value).orElse("none"),
                elapsedMillis(modelStarted),
                audit.complete() ? "COMPLETE" : "CONTINUE");
        return audit;
    }

    private static String auditText(ModelProviderResult result) {
        if (!(result instanceof ModelResponse response)
                || !response.proposedToolCalls().isEmpty()) {
            throw new ReflectionAuditFormatException();
        }
        return response.assistantText()
                .filter(value -> !value.isBlank())
                .orElseThrow(ReflectionAuditFormatException::new);
    }

    private StepStateAudit parseStepStateAudit(String raw) {
        try {
            var root = json.readTree(raw);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException();
            }
            Set<String> fields = new HashSet<>();
            root.fieldNames().forEachRemaining(fields::add);
            if (fields.equals(Set.of(
                    "decision", "reason", "finalText",
                    "replacementSteps"))) {
                return reflectionShapedStepStateAudit(root);
            }
            if (!fields.equals(Set.of("complete", "reason", "stepResult"))
                    || !root.path("complete").isBoolean()
                    || !root.path("reason").isTextual()) {
                throw new IllegalArgumentException();
            }
            String reason = root.path("reason").textValue();
            if (reason == null || reason.isBlank()
                    || reason.length() > MAX_AUDIT_REASON_CHARACTERS) {
                throw new IllegalArgumentException();
            }
            boolean complete = root.path("complete").booleanValue();
            var resultNode = root.get("stepResult");
            String stepResult = null;
            if (complete) {
                if (resultNode == null || !resultNode.isTextual()) {
                    throw new IllegalArgumentException();
                }
                stepResult = resultNode.textValue();
                if (stepResult == null || stepResult.isBlank()
                        || stepResult.length()
                                > MAX_AUDIT_RESULT_CHARACTERS) {
                    throw new IllegalArgumentException();
                }
                stepResult = stepResult.trim();
            } else if (resultNode == null || !resultNode.isNull()) {
                throw new IllegalArgumentException();
            }
            return new StepStateAudit(
                    complete, reason.trim(), stepResult);
        } catch (ReflectionAuditFormatException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ReflectionAuditFormatException();
        }
    }

    private StepStateAudit reflectionShapedStepStateAudit(
            com.fasterxml.jackson.databind.JsonNode root) {
        if (!root.path("decision").isTextual()
                || !root.path("reason").isTextual()
                || !root.path("replacementSteps").isArray()
                || !root.path("replacementSteps").isEmpty()) {
            throw new ReflectionAuditFormatException();
        }
        String decision = root.path("decision").textValue();
        String reason = root.path("reason").textValue();
        if (reason == null || reason.isBlank()
                || reason.length() > MAX_AUDIT_REASON_CHARACTERS) {
            throw new ReflectionAuditFormatException();
        }
        if ("COMPLETE".equals(decision)
                && root.path("finalText").isTextual()) {
            String result = root.path("finalText").textValue();
            if (result == null || result.isBlank()
                    || result.length() > MAX_AUDIT_RESULT_CHARACTERS) {
                throw new ReflectionAuditFormatException();
            }
            return new StepStateAudit(
                    true, reason.trim(), result.trim());
        }
        if ("CONTINUE".equals(decision)
                && root.path("finalText").isNull()) {
            return new StepStateAudit(false, reason.trim(), null);
        }
        throw new ReflectionAuditFormatException();
    }

    private static String boundedRepairInput(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 12_000
                ? value : value.substring(0, 12_000);
    }

    private static String digest(String value) {
        return value == null || value.isEmpty()
                ? "empty" : hash(value).substring(0, 16);
    }

    private String safeDecision(String raw) {
        try {
            var root = json.readTree(raw);
            String value = root != null && root.isObject()
                    ? root.path("decision").asText("") : "";
            return Set.of("CONTINUE", "REPLAN", "COMPLETE", "FAIL")
                    .contains(value) ? value : "INVALID";
        } catch (Exception ignored) {
            return "INVALID";
        }
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

    private static long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, System.nanoTime() - startedNanos));
    }

    private record StepStateAudit(
            boolean complete, String reason, String stepResult) {
    }
}
