package com.yanban.api.agent.v2.adaptive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.V2SafeFailureDiagnostics;
import com.yanban.api.agent.v2.adaptive.reflection.ReflectionContext;
import com.yanban.api.agent.v2.adaptive.reflection.ReflectionProvider;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.providers.CorrelationId;
import io.paperagent.v2.providers.GenerationOptions;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.providers.ModelProviderResult;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelRequestId;
import io.paperagent.v2.providers.ModelResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class V2ModelReflectionProvider implements ReflectionProvider {
    private static final Logger log = LoggerFactory.getLogger(
            V2ModelReflectionProvider.class);
    private static final String PROMPT = """
            You are the reflection model. Judge only whether the current Plan
            Step's goal, expected outcome, and completion criteria are satisfied
            by the supplied authoritative execution facts. Do not call tools.

            Perform an adversarial two-sided review: first identify the strongest
            evidence that the Step is complete; then identify any concrete gap
            between that evidence and the Step. Do not default to rejection and
            do not invent a gap. Accepted results from completed dependency
            Steps and their linked tool results are reusable facts.

            A read proves the content it returned. A successful working-copy
            write proves the exact persisted replacement content. A sandbox
            result proves the exact input content, command, environment, and
            output of that run. A Project-version update is required only when
            the current Step explicitly asks for that update; do not invent
            publication, confirmation, selection, application, or rollback as
            completion requirements. Do not require another read, write, or
            sandbox run when an accepted fact already proves the same outcome.

            decision is CONTINUE, REPLAN, COMPLETE, or FAIL. COMPLETE means the
            current Step is satisfied, even if later Steps remain. CONTINUE
            means this same Step still needs useful work. REPLAN means the
            current approach cannot meet the remaining work and must be replaced.
            FAIL means the Step cannot proceed. Do not choose CONTINUE merely
            because the final user answer or a later Step is not ready.

            Return one strict JSON object with exactly decision, reason,
            finalText, replacementSteps. Do not return markdown or prose.

            CONTINUE, FAIL, and REPLAN require finalText:null. COMPLETE
            requires a nonblank finalText. Only REPLAN may have nonempty
            replacementSteps. Each replacement Step must contain exactly:
            id, intent, expectedOutcome, dependencies, completionCriteria,
            maxAttempts, maxDurationSeconds. dependencies contains only
            earlier replacement Step ids. completionCriteria is a nonempty
            string array. maxAttempts is 1-5. maxDurationSeconds is 1-3600.
            Replacement Steps describe goals and outcomes, not fixed tools.
            REPLAN appends 1-8 replacement Steps and never rewrites completed
            facts.

            If the supplied facts contain previousReflectionFormatError and
            previousReflectionOutput, correct that exact format error while
            preserving the intended decision.

            COMPLETE example:
            {"decision":"COMPLETE","reason":"the accepted sandbox result proves the corrected file ran successfully","finalText":"The corrected code ran successfully.","replacementSteps":[]}
            CONTINUE example:
            {"decision":"CONTINUE","reason":"the source was read but the requested edit has not been written yet","finalText":null,"replacementSteps":[]}
            FAIL example:
            {"decision":"FAIL","reason":"the required authority is unavailable","finalText":null,"replacementSteps":[]}
            REPLAN example:
            {"decision":"REPLAN","reason":"the failed approach must change","finalText":null,"replacementSteps":[{"id":"repair-1","intent":"repair the code","expectedOutcome":"corrected source","dependencies":[],"completionCriteria":["the reported defect is removed"],"maxAttempts":1,"maxDurationSeconds":120}]}
            """;

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
                        new ModelMessage(
                                MessageRole.USER,
                                "Authoritative bounded facts:\n" + facts)),
                List.of(),
                new GenerationOptions(
                        4096, 0, 0.1d, OptionalLong.empty(), Map.of()),
                taskFrameId, planId, revisionId,
                Optional.empty(), false);
        long started = System.nanoTime();
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
                    elapsedMillis(started),
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
                elapsedMillis(started),
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
        String action = safeDecision(decision);
        if ("REPLAN".equals(action)) {
            decision = namespaceReplanStepIds(decision, suffix);
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
                if (!step.isObject()
                        || !step.path("id").isTextual()
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
                for (int index = 0;
                        index < dependencies.size(); index++) {
                    if (!dependencies.get(index).isTextual()) {
                        return raw;
                    }
                    String replacement = replacements.get(
                            dependencies.get(index).textValue());
                    if (replacement != null) {
                        dependencies.set(index,
                                json.getNodeFactory()
                                        .textNode(replacement));
                    }
                }
            }
            log.info(
                    "V2 reflection replan identities namespaced "
                            + "planId={} revisionId={} replacementCount={}",
                    planId.map(PlanId::value).orElse("none"),
                    revisionId.map(PlanRevisionId::value)
                            .orElse("none"),
                    replacements.size());
            return json.writeValueAsString(root);
        } catch (Exception invalid) {
            return raw;
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
}
