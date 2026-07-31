package com.yanban.api.agent.v2.adaptive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
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
            satisfied by the supplied durable Receipts and facts, even when
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
            that the Receipt does not establish. No markdown.
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
        if (Set.of("COMPLETE", "CONTINUE").contains(
                safeDecision(decision))) {
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
            bounded.set("successfulCurrentStepReceipts",
                    json.valueToTree(successfulCurrentStepReceipts));
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
        if (!(result instanceof ModelResponse response)
                || !response.proposedToolCalls().isEmpty()) {
            throw new IllegalStateException(
                    "reflection step-state audit rejected");
        }
        String audited = response.assistantText()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "reflection step-state audit returned no decision"));
        StepStateAudit audit = parseStepStateAudit(audited);
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

    private StepStateAudit parseStepStateAudit(String raw) {
        try {
            var root = json.readTree(raw);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException();
            }
            Set<String> fields = new HashSet<>();
            root.fieldNames().forEachRemaining(fields::add);
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
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "reflection step-state audit returned invalid result");
        }
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
