package com.yanban.api.agent.v2.adaptive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.adaptive.reflection.ReflectionContext;
import com.yanban.api.agent.v2.adaptive.reflection.ReflectionProvider;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.providers.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class V2ModelReflectionProvider implements ReflectionProvider {
    private static final String PROMPT = """
            Reflect on authoritative V2 execution facts. Return one strict JSON
            object with exactly decision, reason, finalText, replacementSteps.
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
        ModelProviderResult result = provider.complete(request);
        if (!(result instanceof ModelResponse response)
                || !response.proposedToolCalls().isEmpty()) {
            throw new IllegalStateException("reflection provider rejected");
        }
        return response.assistantText()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException(
                        "reflection provider returned no decision"));
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
}
