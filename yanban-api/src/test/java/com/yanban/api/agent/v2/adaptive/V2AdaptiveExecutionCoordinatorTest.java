package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.adaptive.reflection.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class V2AdaptiveExecutionCoordinatorTest {
    private static ReflectionContext context() {
        return new ReflectionContext(
                "task", "plan", List.of("recent conversation"),
                List.of(), List.of("receipt"), List.of("step"));
    }

    @Test
    void prematureModelCompleteCannotOverrideDurableFacts() {
        var coordinator = coordinator(
                command -> new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State.STEP_SUCCEEDED,
                        "step-1", "receipt ok", false, null),
                ignored -> complete());
        var result = coordinator.execute(command(Map.of()));
        assertEquals("FAILED", result.status());
        assertEquals("PREMATURE_COMPLETE", result.errorCode());
    }

    @Test
    void durableTerminalCutAndReflectionCompleteSucceeds() {
        var coordinator = coordinator(
                command -> new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED,
                        "step-1", "receipt ok", true, null),
                ignored -> complete());
        var result = coordinator.execute(command(Map.of()));
        assertEquals("SUCCEEDED", result.status());
        assertEquals("完成", result.finalText());
    }

    @Test
    void failedReceiptReplansWithoutDuplicateRowsAndCountsRepairOnlyAfterCommit() {
        AtomicInteger cycle = new AtomicInteger();
        V2AdaptiveCyclePort cycles = ignored -> switch (
                cycle.incrementAndGet()) {
            case 1 -> new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.FAILED,
                    "step-1", "compile failed", false, null,
                    List.of("executionReceipt=status=FAILURE stderr=bad import"),
                    true, true);
            case 2 -> new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.REPLAN_REQUIRED,
                    "step-1", "REPLAN_APPLIED", false, new Object(),
                    List.of("persistedReplan=revision-2"),
                    false, false);
            default -> new V2AdaptiveCyclePort.CycleResult(
                    V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED,
                    "repair-1", "compiled", true, null,
                    List.of("executionReceipt=status=SUCCESS exitCode=0"),
                    true, false);
        };
        AtomicInteger reflection = new AtomicInteger();
        ReflectionProvider provider = ignored -> switch (
                reflection.incrementAndGet()) {
            case 1 -> """
                    {"decision":"REPLAN","reason":"repair failed import",
                     "finalText":null,"replacementSteps":[{
                       "id":"repair-1","intent":"Repair source",
                       "expectedOutcome":"Compilation succeeds",
                       "dependencies":[],
                       "completionCriteria":["exit code is zero"],
                       "maxAttempts":1,"maxDurationSeconds":120,
                       "capability":"project_read"}]}
                    """;
            case 2 -> """
                    {"decision":"CONTINUE","reason":"run replacement",
                     "finalText":null,"replacementSteps":[]}
                    """;
            default -> complete();
        };
        var result = coordinator(cycles, provider).execute(command(
                Map.of("step-1", "project.read")));

        assertEquals("SUCCEEDED", result.status());
        assertEquals(1, result.replans());
        assertEquals(1, result.repairs());
        assertEquals(2, result.steps().size());
        assertEquals("SUPERSEDED_BY_REPLAN",
                result.steps().get(0).status());
        assertEquals("SUCCEEDED", result.steps().get(1).status());
        assertEquals(1, result.steps().stream()
                .filter(value -> "step-1".equals(value.title())).count());
    }

    @Test
    void sandboxFailsBeforeProviderOrRuntime() {
        AtomicInteger cycles = new AtomicInteger();
        AtomicInteger provider = new AtomicInteger();
        var coordinator = coordinator(command -> {
            cycles.incrementAndGet();
            throw new AssertionError();
        }, ignored -> {
            provider.incrementAndGet();
            throw new AssertionError();
        });
        var result = coordinator.execute(command(
                Map.of("step-1", "sandbox.execute")));
        assertEquals("SANDBOX_EXECUTION_UNAVAILABLE", result.errorCode());
        assertEquals(0, cycles.get());
        assertEquals(0, provider.get());
    }

    private static V2AdaptiveExecutionCoordinator coordinator(
            V2AdaptiveCyclePort cycles, ReflectionProvider provider) {
        return new V2AdaptiveExecutionCoordinator(
                cycles, provider,
                new StrictReflectionDecisionParser(new ObjectMapper()));
    }

    private static V2AdaptiveExecutionCoordinator.Command command(
            Map<String, String> bindings) {
        return new V2AdaptiveExecutionCoordinator.Command(
                1L, 2L, "plan-1",
                List.of(new V2AdaptiveTurnResponse.Step(
                        1, "step-1", "PENDING", "")),
                bindings, context());
    }

    private static String complete() {
        return "{\"decision\":\"COMPLETE\",\"reason\":\"done\","
                + "\"finalText\":\"完成\",\"replacementSteps\":[]}";
    }
}
