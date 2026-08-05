package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class V2ModelReflectionProviderTest {
    @Test
    void promptDefinesOneGoalBasedReflectionWithStrictExamples() {
        String system = captureSystemPrompt();

        assertTrue(system.contains("current Plan\nStep's goal"));
        assertTrue(system.contains("adversarial two-sided review"));
        assertTrue(system.contains("strongest\nevidence"));
        assertTrue(system.contains("concrete gap"));
        assertTrue(system.contains("dependency\nSteps"));
        assertTrue(system.contains("Do not require another read"));
        assertTrue(system.contains("same outcome"));
        assertTrue(system.contains("previousReflectionFormatError"));
        assertTrue(system.contains(
                "{\"decision\":\"COMPLETE\""));
        assertTrue(system.contains(
                "{\"decision\":\"CONTINUE\""));
        assertTrue(system.contains(
                "{\"decision\":\"FAIL\""));
        assertFalse(system.contains(
                "Receipts belonging to other Steps are context, not"));
    }

    @Test
    void completeDecisionUsesExactlyOneModelCall() {
        AtomicInteger calls = new AtomicInteger();
        var provider = new V2ModelReflectionProvider(request -> {
            calls.incrementAndGet();
            return response("""
                    {"decision":"COMPLETE","reason":"facts satisfy goal",
                     "finalText":"done","replacementSteps":[]}
                    """);
        }, new ObjectMapper(), null, null, null);

        String result = provider.reflect(context());

        assertTrue(result.contains("\"decision\":\"COMPLETE\""));
        assertEquals(1, calls.get());
    }

    @Test
    void continueDecisionUsesExactlyOneModelCall() {
        AtomicInteger calls = new AtomicInteger();
        var provider = new V2ModelReflectionProvider(request -> {
            calls.incrementAndGet();
            return response("""
                    {"decision":"CONTINUE","reason":"edit is missing",
                     "finalText":null,"replacementSteps":[]}
                    """);
        }, new ObjectMapper(), null, null, null);

        String result = provider.reflect(context());

        assertTrue(result.contains("\"decision\":\"CONTINUE\""));
        assertEquals(1, calls.get());
    }

    @Test
    void fullCandidateAndAcceptedSandboxFactsReachReflection() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        var provider = new V2ModelReflectionProvider(request -> {
            captured.set(request);
            return response("""
                    {"decision":"COMPLETE","reason":"prior run is reusable",
                     "finalText":"reported","replacementSteps":[]}
                    """);
        }, new ObjectMapper(), null, null, null);
        String candidate = "class Sort { // COMPLETE_REPLACEMENT }";

        provider.reflect(new ReflectionContext(
                "task", "plan", List.of(),
                List.of("acceptedStepResult[result=exit code 0]"),
                List.of(
                        "activeStepId=step-4",
                        "toolExecution[stepId=step-3,toolKind=sandbox.execute,exitCode=0,stdout=ok]",
                        "candidateContent=<replacement path=\"src/main/java/Sort.java\">"
                                + candidate + "</replacement>"),
                List.of("report final result")));

        String input = captured.get().messages().get(1).content();
        assertTrue(input.contains("acceptedStepResult"));
        assertTrue(input.contains("toolKind=sandbox.execute"));
        assertTrue(input.contains("exitCode=0"));
        assertTrue(input.contains(candidate));
    }

    @Test
    void replanStepIdsAreNamespacedWithDependenciesPreserved()
            throws Exception {
        var provider = new V2ModelReflectionProvider(request -> response("""
                {"decision":"REPLAN","reason":"change approach",
                 "finalText":null,"replacementSteps":[
                   {"id":"step-1","intent":"prepare source",
                    "expectedOutcome":"source ready","dependencies":[],
                    "completionCriteria":["source exists"],
                    "maxAttempts":1,"maxDurationSeconds":60},
                   {"id":"step-2","intent":"compile source",
                    "expectedOutcome":"exit code 0",
                    "dependencies":["step-1"],
                    "completionCriteria":["compiles"],
                    "maxAttempts":1,"maxDurationSeconds":60}]}
                """), new ObjectMapper(), null, null, null);

        String result = provider.reflect(context());
        var root = new ObjectMapper().readTree(result);
        String first = root.path("replacementSteps").get(0)
                .path("id").asText();
        String second = root.path("replacementSteps").get(1)
                .path("id").asText();

        assertTrue(first.startsWith("replan-step-"));
        assertTrue(second.startsWith("replan-step-"));
        assertFalse(first.equals(second));
        assertEquals(first, root.path("replacementSteps").get(1)
                .path("dependencies").get(0).asText());
    }

    private static String captureSystemPrompt() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        var provider = new V2ModelReflectionProvider(request -> {
            captured.set(request);
            return response("""
                    {"decision":"FAIL","reason":"stop",
                     "finalText":null,"replacementSteps":[]}
                    """);
        }, new ObjectMapper(), null, null, null);
        provider.reflect(context());
        return captured.get().messages().get(0).content();
    }

    private static ReflectionContext context() {
        return new ReflectionContext(
                "task", "plan", List.of(), List.of(),
                List.of("activeStepId=step-2"), List.of("compile"));
    }

    private static ModelResponse response(String value) {
        return new ModelResponse(
                Optional.of(value), List.of(), FinishReason.STOP,
                new UsageMetadata(1, 1, 0, Map.of()), Map.of());
    }
}
