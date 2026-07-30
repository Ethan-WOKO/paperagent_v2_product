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
