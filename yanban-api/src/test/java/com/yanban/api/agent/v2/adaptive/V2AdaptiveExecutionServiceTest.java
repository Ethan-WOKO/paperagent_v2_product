package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnExecutionStartRecoveryCommand;
import com.yanban.api.agent.v2.bootstrap.AuthenticatedAgentTurnExecutionStartRecoveryComposer;
import com.yanban.api.agent.v2.workspace.AuthenticatedAgentTurnPlanExecutionContextComposer;
import com.yanban.api.agent.v2.effect.project.NaturalLanguageCandidateAuthorityStore;
import com.yanban.api.agent.v2.effect.project.ProjectCandidateCompositionEffect;
import com.yanban.api.agent.v2.compatibility.project.ProjectCandidateEffectAuthority;
import io.paperagent.v2.contracts.*;
import io.paperagent.v2.persistence.PersistedPlanBootstrap;
import io.paperagent.v2.providers.*;
import io.paperagent.v2.runtime.execution.context.composition.PlanExecutionContextReady;
import io.paperagent.v2.runtime.execution.recovery.composition.RecoveredExecutionStart;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
                        "step-1", "receipt-1", true, null,
                        List.of("executionReceipt=receipt-1"),
                        true, false));
        when(fixture.cycles.create(
                anyMap(), anyString(), anyString(), any(),
                anyString(), any(), same(fixture.modelProvider)))
                .thenReturn(port);
        V2AdaptiveExecutionResult result = fixture.service.execute(
                command(fixture.bootstrap, "version-1",
                        Map.of("step-1", "project.read"),
                        fixture.modelProvider));

        assertEquals("SUCCEEDED", result.status(), result.errorCode());
        assertEquals("任务完成", result.finalText());
        verify(port, times(1)).executeOne(any());
        ArgumentCaptor<AuthenticatedAgentTurnExecutionStartRecoveryCommand>
                start = ArgumentCaptor.forClass(
                        AuthenticatedAgentTurnExecutionStartRecoveryCommand
                                .class);
        verify(fixture.starts).recover(eq(7L), eq(11L), start.capture());
        assertEquals(0, start.getValue().attempt().orElseThrow()
                .leaseExpiresAt().getNano() % 1_000);
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
                        Map.of("step-1", "sandbox.execute"),
                        fixture.modelProvider));
        assertEquals("SANDBOX_EXECUTION_UNAVAILABLE", result.errorCode());
        verifyNoInteractions(
                fixture.starts, fixture.contexts,
                fixture.cycles, fixture.modelProvider);
        verify(fixture.store).finish(
                eq(7L), eq(9L), eq("request-1"), eq("FAILED"),
                anyList(), isNull(), isNull(), eq(List.of()),
                eq("SANDBOX_EXECUTION_UNAVAILABLE"),
                eq(0), eq(0), eq(0));
    }

    @Test
    void executionStartExceptionHasExplicitStageCode() {
        var fixture = fixture();
        when(fixture.starts.recover(eq(7L), eq(11L), any()))
                .thenThrow(new IllegalStateException("start failed"));

        V2AdaptiveExecutionResult result = fixture.service.execute(
                command(fixture.bootstrap, "version-1",
                        Map.of("step-1", "project.read"),
                        fixture.modelProvider));

        assertEquals("EXECUTION_START_EXCEPTION", result.errorCode());
        verifyNoInteractions(
                fixture.contexts, fixture.cycles, fixture.modelProvider);
    }

    @Test
    void workspaceContextExceptionHasExplicitStageCode() {
        var fixture = fixture();
        recoverPlan(fixture);
        when(fixture.contexts.compose(eq(7L), eq(11L), any()))
                .thenThrow(new IllegalStateException("context failed"));

        V2AdaptiveExecutionResult result = fixture.service.execute(
                command(fixture.bootstrap, "version-1",
                        Map.of("step-1", "project.read"),
                        fixture.modelProvider));

        assertEquals("WORKSPACE_CONTEXT_EXCEPTION", result.errorCode());
        verifyNoInteractions(fixture.cycles, fixture.modelProvider);
    }

    @Test
    void cycleSetupExceptionHasExplicitStageCode() {
        var fixture = fixture();
        recoverPlanAndContext(fixture);
        when(fixture.cycles.create(
                anyMap(), anyString(), anyString(), any(),
                anyString(), any(), same(fixture.modelProvider)))
                .thenThrow(new IllegalStateException("cycle setup failed"));

        V2AdaptiveExecutionResult result = fixture.service.execute(
                command(fixture.bootstrap, "version-1",
                        Map.of("step-1", "project.read"),
                        fixture.modelProvider));

        assertEquals("CYCLE_SETUP_EXCEPTION", result.errorCode());
        verifyNoInteractions(fixture.modelProvider);
    }

    @Test
    void cycleExecutionExceptionHasExplicitStageCode() {
        var fixture = fixture();
        recoverPlanAndContext(fixture);
        V2AdaptiveCyclePort port = mock(V2AdaptiveCyclePort.class);
        when(port.executeOne(any()))
                .thenThrow(new IllegalStateException("cycle failed"));
        when(fixture.cycles.create(
                anyMap(), anyString(), anyString(), any(),
                anyString(), any(), same(fixture.modelProvider)))
                .thenReturn(port);

        V2AdaptiveExecutionResult result = fixture.service.execute(
                command(fixture.bootstrap, "version-1",
                        Map.of("step-1", "project.read"),
                        fixture.modelProvider));

        assertEquals("CYCLE_EXECUTION_EXCEPTION", result.errorCode());
        verifyNoInteractions(fixture.modelProvider);
    }

    @Test
    void durableTerminalNaturalCandidatePublishesAndWaitsForConfirmation() {
        var fixture = fixture();
        when(fixture.modelProvider.complete(any()))
                .thenReturn(complete("candidate ready"));
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
                        "step-1", "candidate prepared", true, null,
                        List.of("executionReceipt=status=SUCCESS"),
                        true, false));
        when(fixture.cycles.create(
                anyMap(), anyString(), anyString(), any(),
                anyString(), any(), same(fixture.modelProvider)))
                .thenReturn(port);
        var authorities = mock(
                NaturalLanguageCandidateAuthorityStore.class);
        var authority = mock(ProjectCandidateEffectAuthority.class);
        when(authority.paths()).thenReturn(
                List.of("src/Main.java", "README.md"));
        when(authorities.require("plan-1"))
                .thenReturn(authority);
        var candidates = mock(ProjectCandidateCompositionEffect.class);
        when(candidates.publishNatural(
                "plan-1", 7L, 11L, authorities))
                .thenReturn(
                        new ProjectCandidateCompositionEffect.CandidateResult(
                                77L, "c".repeat(64), "d".repeat(64)));
        var service = new V2AdaptiveExecutionService(
                fixture.store, fixture.starts, fixture.contexts,
                fixture.cycles, new ObjectMapper(),
                candidates, authorities);

        V2AdaptiveExecutionResult result = service.execute(
                command(fixture.bootstrap, "version-1",
                        Map.of("step-1",
                                "project.candidate.compose"),
                        fixture.modelProvider));

        assertEquals("WAITING_CONFIRMATION", result.status());
        assertEquals(77L, result.candidateArtifactId());
        assertEquals(
                List.of("src/Main.java", "README.md"),
                result.outputPaths());
        verify(fixture.store).finish(
                eq(7L), eq(9L), eq("request-1"),
                eq("WAITING_CONFIRMATION"), anyList(),
                eq("candidate ready"), eq(77L),
                eq(List.of("src/Main.java", "README.md")),
                isNull(), eq(1), eq(0), eq(0));
    }

    @Test
    void candidateBindingAuthorityFailureCannotBecomeSucceeded() {
        var fixture = fixture();
        when(fixture.modelProvider.complete(any()))
                .thenReturn(complete("candidate ready"));
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
                        "step-1", "candidate prepared", true, null,
                        List.of("executionReceipt=status=SUCCESS"),
                        true, false));
        when(fixture.cycles.create(
                anyMap(), anyString(), anyString(), any(),
                anyString(), any(), same(fixture.modelProvider)))
                .thenReturn(port);
        var authorities = mock(
                NaturalLanguageCandidateAuthorityStore.class);
        when(authorities.require("plan-1"))
                .thenThrow(new IllegalStateException("missing authority"));
        var candidates = mock(ProjectCandidateCompositionEffect.class);
        var service = new V2AdaptiveExecutionService(
                fixture.store, fixture.starts, fixture.contexts,
                fixture.cycles, new ObjectMapper(),
                candidates, authorities);

        V2AdaptiveExecutionResult result = service.execute(
                command(fixture.bootstrap, "version-1",
                        Map.of("step-1",
                                "project.candidate.compose"),
                        fixture.modelProvider));

        assertEquals("FAILED", result.status());
        assertEquals("CANDIDATE_PUBLISH_FAILED", result.errorCode());
        assertNull(result.candidateArtifactId());
        assertEquals(List.of(), result.outputPaths());
        verifyNoInteractions(candidates);
        verify(fixture.store).finish(
                eq(7L), eq(9L), eq("request-1"), eq("FAILED"),
                anyList(), isNull(), isNull(), eq(List.of()),
                eq("CANDIDATE_PUBLISH_FAILED"),
                eq(1), eq(0), eq(0));
    }

    private static Fixture fixture() {
        var store = mock(V2AdaptiveExecutionStore.class);
        var starts = mock(
                AuthenticatedAgentTurnExecutionStartRecoveryComposer.class);
        var contexts = mock(
                AuthenticatedAgentTurnPlanExecutionContextComposer.class);
        var cycles = mock(V2AdaptiveRuntimeCycleFactory.class);
        var modelProvider = mock(ModelProvider.class);
        when(modelProvider.complete(any()))
                .thenReturn(complete("任务完成"));
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
                store, starts, contexts, cycles, new ObjectMapper());
        return new Fixture(
                service, store, starts, contexts, cycles,
                modelProvider, bootstrap);
    }

    private static void recoverPlan(Fixture fixture) {
        var recovered = mock(RecoveredExecutionStart.class);
        when(recovered.planId()).thenReturn(new PlanId("plan-1"));
        when(fixture.starts.recover(eq(7L), eq(11L), any()))
                .thenReturn(recovered);
    }

    private static void recoverPlanAndContext(Fixture fixture) {
        recoverPlan(fixture);
        var context = mock(PlanExecutionContextReady.class);
        when(context.planId()).thenReturn(new PlanId("plan-1"));
        when(fixture.contexts.compose(eq(7L), eq(11L), any()))
                .thenReturn(context);
    }

    private static V2AdaptiveExecutionService.Command command(
            PersistedPlanBootstrap bootstrap, String version,
            Map<String, String> bindings, ModelProvider modelProvider) {
        return new V2AdaptiveExecutionService.Command(
                5L, 7L, 9L, 11L, "request-1", version,
                bootstrap, bindings, List.of("user: 上一轮"),
                Instant.ofEpochSecond(0, 123_456_789),
                modelProvider);
    }

    private static ModelResponse complete(String finalText) {
        return new ModelResponse(
                Optional.of(
                        "{\"decision\":\"COMPLETE\",\"reason\":\"done\","
                                + "\"finalText\":\"" + finalText + "\","
                                + "\"replacementSteps\":[]}"),
                List.of(), FinishReason.STOP,
                new UsageMetadata(1, 1, 0, Map.of()), Map.of());
    }

    private record Fixture(
            V2AdaptiveExecutionService service,
            V2AdaptiveExecutionStore store,
            AuthenticatedAgentTurnExecutionStartRecoveryComposer starts,
            AuthenticatedAgentTurnPlanExecutionContextComposer contexts,
            V2AdaptiveRuntimeCycleFactory cycles,
            ModelProvider modelProvider,
            PersistedPlanBootstrap bootstrap) {
    }
}
