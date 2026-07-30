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
import org.springframework.stereotype.Component;

@Component
public class V2ModelReflectionProvider implements ReflectionProvider {
    private static final String PROMPT = """
            Reflect on authoritative V2 execution facts. Return one strict JSON
            object with exactly decision, reason, finalText, replacementSteps.
            decision is CONTINUE, REPLAN, COMPLETE, or FAIL.
            COMPLETE is allowed only when the supplied durable cut is terminal.
            REPLAN appends 1-8 replacement Steps and never rewrites facts.
            Use only public aliases: literature_search, project_read,
            project_search, project_candidate, sandbox_execute.
            finalText is non-null only for COMPLETE. No markdown.
            """;
    private final ModelProvider provider;
    private final ObjectMapper json;
    private final Optional<TaskFrameId> taskFrameId;
    private final Optional<PlanId> planId;
    private final Optional<PlanRevisionId> revisionId;

    public V2ModelReflectionProvider(
            ModelProvider provider, ObjectMapper json) {
        this(provider, json, null, null, null);
    }

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
