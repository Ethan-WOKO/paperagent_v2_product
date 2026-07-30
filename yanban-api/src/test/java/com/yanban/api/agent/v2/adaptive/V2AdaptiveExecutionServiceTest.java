package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.adaptive.reflection.ReflectionProvider;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnExecutionStartRecoveryComposer;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnPlanExecutionContextComposer;
import io.paperagent.v2.contracts.*;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextReady;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredExecutionStart;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class V2AdaptiveExecutionServiceTest {
    @Test
    void projectExecutionStartsContextRunsOneCycleReflectsAndStores() {
        var fixture = fixture();
        var recovered = mock(RecoveredExecutionStart.class);
        when(recovered.planId()).thenReturn(new PlanId("plan-1"));
        when(fixture.starts.recover(eq(7L), eq(11L), any()))
                .thenReturn(recovered);
        var context = mock(PlanExecutionContextReady.class);
        when(context.planId()).thenReturn(new PlanId("plan-1"));
        when(fixture.contexts.compose(eq(7L), eq(11L), any()))
                .thenReturn(context);
        V2AdaptiveCyclePort port = mock(V2AdaptiveCyclePort.class);
        when(port.executeOne(any())).thenReturn(
                new V2AdaptiveCyclePort.CycleResult(
                        V2AdaptiveCyclePort.CycleResult.State.PLAN_SUCCEEDED,
                        "step-1", "receipt-1", true, null));
        when(fixture.cycles.create(
                anyMap(), anyString(), anyString(), any(),
                anyString(), any())).thenReturn(port);
        when(fixture.reflections.reflect(any())).thenReturn(
                "{\"decision\":\"COMPLETE\",\"reason\":\"done\","
                        + "\"finalText\":\"任务完成\","
                        + "\"replacementSteps\":[]}");

        V2AdaptiveExecutionResult result = fixture.service.execute(
                command(fixture.bootstrap, "version-1",
                        Map.of("step-1", "project.read")));

        assertEquals("SUCCEEDED", result.status(), result.errorCode());
        assertEquals("任务完成", result.finalText());
        verify(port, times(1)).executeOne(any());
        verify(fixture.store).open(
                eq(5L), eq(7L), eq(9L), eq("request-1"),
                eq("plan-1"), eq("version-1"), anyList());
        verify(fixture.store).finish(
                eq(7L), eq(9L), eq("request-1"), eq("SUCCEEDED"),
                anyList(), eq("任务完成"), isNull(), eq(List.of()),
                isNull(), eq(1), eq(0), eq(0));
    }

    @Test
    void unavailableSandboxFailsBeforeStartContextCycleOrReflection() {
        var fixture = fixture();
        V2AdaptiveExecutionResult result = fixture.service.execute(
                command(fixture.bootstrap, "version-1",
                        Map.of("step-1", "sandbox.execute")));
        assertEquals("SANDBOX_EXECUTION_UNAVAILABLE", result.errorCode());
        verifyNoInteractions(
                fixture.starts, fixture.contexts,
                fixture.cycles, fixture.reflections);
        verify(fixture.store).finish(
                eq(7L), eq(9L), eq("request-1"), eq("FAILED"),
                anyList(), isNull(), isNull(), eq(List.of()),
                eq("SANDBOX_EXECUTION_UNAVAILABLE"),
                eq(0), eq(0), eq(0));
    }

    private static Fixture fixture() {
        var store = mock(V2AdaptiveExecutionStore.class);
        var starts = mock(
                AuthenticatedAgentTurnExecutionStartRecoveryComposer.class);
        var contexts = mock(
                AuthenticatedAgentTurnPlanExecutionContextComposer.class);
        var cycles = mock(V2AdaptiveRuntimeCycleFactory.class);
        var reflections = mock(ReflectionProvider.class);
        PlanStep step = new PlanStep(
                new PlanStepId("step-1"), "读取文件", "取得内容",
                Set.of(), List.of("receipt"),
                new BoundedExecutionHints(
                        1, java.time.Duration.ofMinutes(1)));
        PlanRevision revision = new PlanRevision(
                new PlanRevisionId("revision-1"),
                new TaskFrameId("frame-1"), 1, Optional.empty(),
                "initial", Instant.EPOCH, List.of(step), Map.of());
        Plan plan = new Plan(
                new PlanId("plan-1"), new TaskFrameId("frame-1"),
                List.of(revision));
        var bootstrap = mock(PersistedPlanBootstrap.class);
        when(bootstrap.plan()).thenReturn(plan);
        when(bootstrap.taskFrame()).thenReturn(new TaskFrame(
                new TaskFrameId("frame-1"), "objective",
                List.of("project"), List.of("answer"), List.of(),
                Optional.empty(),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.READ_PROJECT),
                        NetworkPolicy.DENY_ALL, List.of(),
                        new ResourceLimits(
                                java.time.Duration.ofMinutes(5),
                                java.time.Duration.ofMinutes(1),
                                1024, 1024, 1),
                        Set.of()),
                Instant.EPOCH));
        var service = new V2AdaptiveExecutionService(
                store, starts, contexts, cycles, reflections,
                new ObjectMapper());
        return new Fixture(
                service, store, starts, contexts, cycles,
                reflections, bootstrap);
    }

    private static V2AdaptiveExecutionService.Command command(
            PersistedPlanBootstrap bootstrap, String version,
            Map<String, String> bindings) {
        return new V2AdaptiveExecutionService.Command(
                5L, 7L, 9L, 11L, "request-1", version,
                bootstrap, bindings, List.of("user: 上一轮"), Instant.EPOCH);
    }

    private record Fixture(
            V2AdaptiveExecutionService service,
            V2AdaptiveExecutionStore store,
            AuthenticatedAgentTurnExecutionStartRecoveryComposer starts,
            AuthenticatedAgentTurnPlanExecutionContextComposer contexts,
            V2AdaptiveRuntimeCycleFactory cycles,
            ReflectionProvider reflections,
            PersistedPlanBootstrap bootstrap) {
    }
}
