package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.adaptive.reflection.ReflectionContext;
import io.paperagent.v2.providers.FinishReason;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.UsageMetadata;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V2ModelReflectionProviderTest {
    @Test
    void promptDefinesCompleteAsCurrentStepCompletionNotPlanTerminal() {
        String system = captureSystemPrompt();

        assertTrue(system.contains(
                "current active Step's completion criteria are"));
        assertTrue(system.contains("the Plan has later Steps"));
        assertTrue(system.contains("persists this Step"));
        assertTrue(system.contains("completion and advances the Plan"));
        assertTrue(system.contains(
                "final answer only when the completed Step makes the whole"));
        assertTrue(system.contains(
                "CONTINUE means the same active Step still needs"));
        assertTrue(system.contains(
                "completed nonterminal Step"));
        assertTrue(system.contains(
                "discards that provisional text after advancing"));
        assertTrue(system.contains("Do not return"));
        assertTrue(system.contains(
                "CONTINUE merely because later Steps remain"));
        assertFalse(system.contains(
                "COMPLETE is allowed only when the supplied durable cut is terminal"));
    }

    @Test
    void promptDefinesTheExactReplanSchemaAndFailedReceiptAction() {
        String system = captureSystemPrompt();

        assertTrue(system.contains(
                "id, intent, expectedOutcome, dependencies"));
        assertTrue(system.contains("completionCriteria"));
        assertTrue(system.contains("maxAttempts"));
        assertTrue(system.contains("maxDurationSeconds"));
        assertTrue(system.contains(
                "Tool selection remains dynamic"));
        assertTrue(system.contains(
                "failed Receipt can be corrected, return REPLAN"));
    }

    private static String captureSystemPrompt() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        var provider = new V2ModelReflectionProvider(request -> {
            captured.set(request);
            return new ModelResponse(
                    Optional.of(
                            "{\"decision\":\"FAIL\",\"reason\":\"stop\","
                                    + "\"finalText\":null,"
                                    + "\"replacementSteps\":[]}"),
                    List.of(), FinishReason.STOP,
                    new UsageMetadata(1, 1, 0, Map.of()), Map.of());
        }, new ObjectMapper(), null, null, null);

        provider.reflect(new ReflectionContext(
                "task", "plan", List.of(), List.of(),
                List.of("failed receipt"), List.of("step")));

        return captured.get().messages().get(0).content();
    }
}
