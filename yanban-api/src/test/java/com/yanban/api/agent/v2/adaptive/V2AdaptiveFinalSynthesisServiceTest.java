package com.yanban.api.agent.v2.adaptive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.v2.result.V2StepResultService;
import com.yanban.api.agent.v2.result.V2StepResultSnapshot;
import com.yanban.api.agent.v2.result.V2StepResultSource;
import com.yanban.api.agent.v2.result.V2StepResultStatus;
import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.providers.FinishReason;
import io.paperagent.v2.providers.ModelProvider;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.UsageMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class V2AdaptiveFinalSynthesisServiceTest {
    @Test
    void synthesizesOneAnswerFromAcceptedResultsAndCandidateState() {
        V2StepResultService results = mock(V2StepResultService.class);
        ModelProvider provider = mock(ModelProvider.class);
        Plan plan = plan();
        when(results.acceptedCompletedFacts(plan.id())).thenReturn(List.of(
                accepted("result-1", "step-1", "已读取目标文件。"),
                accepted("result-2", "step-2",
                        "该程序使用回溯生成所有排列。")));
        when(provider.complete(any())).thenReturn(response(
                "该程序使用回溯算法生成并打印所有排列。候选修改尚未应用。"));
        var service = new V2AdaptiveFinalSynthesisService(
                results, new ObjectMapper());

        Optional<String> answer = service.synthesize(
                new V2AdaptiveFinalSynthesisService.Request(
                        taskFrame(), plan, 42L,
                        List.of("src/main/java/Example.java"), provider));

        assertEquals(
                "该程序使用回溯算法生成并打印所有排列。候选修改尚未应用。",
                answer.orElseThrow());
        ArgumentCaptor<ModelRequest> request = ArgumentCaptor.forClass(
                ModelRequest.class);
        verify(provider).complete(request.capture());
        String facts = request.getValue().messages().get(1).content();
        String instructions = request.getValue().messages().get(0).content();
        assertTrue(facts.contains("已读取目标文件"));
        assertTrue(facts.contains("回溯生成所有排列"));
        assertTrue(facts.contains("src/main/java/Example.java"));
        assertTrue(facts.contains(
                "\"workingCopyApplicationState\":\"NOT_APPLIED\""));
        assertTrue(instructions.contains("complete\nfinalWorkingCopy"));
    }

    @Test
    void synthesisReceivesAutomaticRevisionState() {
        V2StepResultService results = mock(V2StepResultService.class);
        ModelProvider provider = mock(ModelProvider.class);
        Plan plan = plan();
        when(results.acceptedCompletedFacts(plan.id())).thenReturn(List.of(
                accepted("result-1", "step-1", "sandbox succeeded")));
        when(provider.complete(any())).thenReturn(response("saved"));
        var autoApplications = mock(
                com.yanban.api.project.AgentCandidateAutoApplicationService.class);
        when(autoApplications.proof(plan.id().value(), 42L)).thenReturn(
                new com.yanban.api.project.AgentCandidateAutoApplicationService
                        .VerificationProof(
                        "receipt", List.of("src/main/java/Example.java"),
                        List.of("yanban-runner", "java",
                                "src/main/java/Example.java"),
                        "ok", "", 0,
                        Map.of("src/main/java/Example.java",
                                "class Example {}")));
        var service = new V2AdaptiveFinalSynthesisService(
                results, new ObjectMapper(), autoApplications);

        service.synthesize(new V2AdaptiveFinalSynthesisService.Request(
                taskFrame(), plan, 42L,
                List.of("src/main/java/Example.java"),
                29L, "f".repeat(64), provider));

        ArgumentCaptor<ModelRequest> request = ArgumentCaptor.forClass(
                ModelRequest.class);
        verify(provider).complete(request.capture());
        String facts = request.getValue().messages().get(1).content();
        String instructions = request.getValue().messages().get(0).content();
        assertTrue(facts.contains(
                "\"workingCopyApplicationState\":\"APPLIED\""));
        assertTrue(facts.contains("class Example {}"));
        assertTrue(facts.contains("yanban-runner"));
        assertTrue(facts.contains("\"standardOutput\":\"ok\""));
        assertTrue(instructions.contains("already the current Project revision"));
        assertTrue(instructions.contains("rollback"));
    }

    @Test
    void providerFailureLeavesTheAcceptedFallbackAvailable() {
        V2StepResultService results = mock(V2StepResultService.class);
        ModelProvider provider = mock(ModelProvider.class);
        Plan plan = plan();
        when(results.acceptedCompletedFacts(plan.id())).thenReturn(
                List.of(accepted(
                        "result-1", "step-1", "可持久化的步骤结果")));
        when(provider.complete(any())).thenThrow(
                new IllegalStateException("provider unavailable"));
        var service = new V2AdaptiveFinalSynthesisService(
                results, new ObjectMapper());

        Optional<String> answer = service.synthesize(
                new V2AdaptiveFinalSynthesisService.Request(
                        taskFrame(), plan, null, List.of(), provider));

        assertTrue(answer.isEmpty());
    }

    private static V2StepResultSnapshot accepted(
            String resultId, String stepId, String text) {
        return new V2StepResultSnapshot(
                resultId, new PlanId("plan-1"),
                new PlanRevisionId("revision-1"),
                new PlanStepId(stepId),
                new EventId("activation-" + stepId),
                V2StepResultSource.MODEL, text,
                "a".repeat(64), List.of(),
                V2StepResultStatus.ACCEPTED,
                Optional.of(text), Optional.of("b".repeat(64)),
                Instant.EPOCH, Instant.EPOCH);
    }

    private static ModelResponse response(String text) {
        return new ModelResponse(
                Optional.of(text), List.of(), FinishReason.STOP,
                new UsageMetadata(1, 1, 0, Map.of()), Map.of());
    }

    private static Plan plan() {
        PlanStep first = new PlanStep(
                new PlanStepId("step-1"), "读取文件", "获得内容",
                Set.of(), List.of("已读取"),
                new BoundedExecutionHints(1, Duration.ofMinutes(1)));
        PlanStep second = new PlanStep(
                new PlanStepId("step-2"), "分析代码", "解释用途",
                Set.of(first.id()), List.of("已解释"),
                new BoundedExecutionHints(1, Duration.ofMinutes(1)));
        return new Plan(
                new PlanId("plan-1"), new TaskFrameId("frame-1"),
                List.of(new PlanRevision(
                        new PlanRevisionId("revision-1"),
                        new TaskFrameId("frame-1"), 1,
                        Optional.empty(), "initial", Instant.EPOCH,
                        List.of(first, second), Map.of())));
    }

    private static TaskFrame taskFrame() {
        return new TaskFrame(
                new TaskFrameId("frame-1"), "读取并解释代码",
                List.of("project"), List.of("answer"), List.of(),
                Optional.empty(),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD, Set.of(),
                        NetworkPolicy.DENY_ALL, List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(5),
                                Duration.ofMinutes(1), 1024, 1024, 1),
                        Set.of()),
                Instant.EPOCH);
    }
}
