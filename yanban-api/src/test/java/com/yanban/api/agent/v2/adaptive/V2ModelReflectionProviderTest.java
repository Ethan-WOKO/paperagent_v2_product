package com.yanban.api.agent.v2.adaptive;

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
    void promptDefinesTheExactReplanSchemaAndFailedReceiptAction() {
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

        String system = captured.get().messages().get(0).content();
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
}
