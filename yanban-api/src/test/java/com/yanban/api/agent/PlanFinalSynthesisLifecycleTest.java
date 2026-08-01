package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanRunLeaseService;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentPlanStepRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PlanFinalSynthesisLifecycleTest {

    @Test
    void terminalPlanPublishesOneCanonicalAndRefreshReusesItWithoutAnotherModelCall() {
        AgentPlanRepository plans = mock(AgentPlanRepository.class);
        AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentPlanEventRepository events = mock(AgentPlanEventRepository.class);
        AgentService agentService = mock(AgentService.class);
        FinalSynthesisService synthesis = mock(FinalSynthesisService.class);
        AgentPlan plan = new AgentPlan(10L, 1L, "运行并解释 Main.java", "run", true, null, "{}");
        ReflectionTestUtils.setField(plan, "id", 21L);
        plan.markCompleted();
        AgentPlanStep step = new AgentPlanStep(21L, "run", 1, "Run Main", "Run", "SANDBOX_EXECUTE",
                "[]", "[]", "exit 0");
        ReflectionTestUtils.setField(step, "id", 22L);
        step.markCompleted("status=SUCCEEDED, exitCode=0\nstdout:\n42");
        AgentSession session = new AgentSession(1L, "Project", "test", "model", 4, true);
        ReflectionTestUtils.setField(session, "id", 10L);
        when(plans.findById(21L)).thenReturn(Optional.of(plan));
        when(plans.findByIdAndUserId(21L, 1L)).thenReturn(Optional.of(plan));
        when(plans.saveAndFlush(plan)).thenReturn(plan);
        when(steps.findByPlanIdOrderBySortOrderAsc(21L)).thenReturn(List.of(step));
        when(events.findByPlanIdOrderByCreatedAtAsc(21L)).thenReturn(List.of());
        when(agentService.getOwnedSession(1L, 10L)).thenReturn(session);
        when(synthesis.synthesize(eq(plan), eq(session), eq(List.of(step)), eq(List.of()), any(), any()))
                .thenReturn("本次执行已验证：exitCode=0，stdout=42；这不独立证明算法对所有输入都正确。");

        PlanAgentService service = new PlanAgentService(plans, steps, events, agentService,
                mock(AgentRuntimeService.class), null, mock(PlanningAgentPlanner.class),
                mock(PlanStepVerifier.class), null, null, null, new ObjectMapper(), mock(ProjectService.class));
        ReflectionTestUtils.setField(service, "finalSynthesisService", synthesis);
        AgentPlanResponse initial = AgentPlanResponse.from(plan, List.of(AgentPlanStepResponse.from(step, List.of(), List.of())));

        AgentPlanResponse first = ReflectionTestUtils.invokeMethod(service, "finalizeTerminalSynthesis", initial);
        AgentPlanResponse refreshed = ReflectionTestUtils.invokeMethod(service, "finalizeTerminalSynthesis", first);
        AgentPlanResponse afterRestartGet = service.getPlan(1L, 21L);

        assertThat(first.finalAnswer()).isEqualTo("本次执行已验证：exitCode=0，stdout=42；这不独立证明算法对所有输入都正确。");
        assertThat(refreshed.finalAnswer()).isEqualTo(first.finalAnswer());
        assertThat(afterRestartGet.finalAnswer()).isEqualTo(first.finalAnswer());
        assertThat(plan.getCanonicalAnswerHash()).hasSize(64);
        verify(synthesis, times(1)).synthesize(eq(plan), eq(session), eq(List.of(step)), eq(List.of()), any(), any());
        verify(plans, times(1)).saveAndFlush(plan);
    }

    @Test
    void finalPresentationNeverAddsLegacyPlanUserOrAssistantRows() {
        AgentService agentService = mock(AgentService.class);
        PlanAgentService service = new PlanAgentService(mock(AgentPlanRepository.class),
                mock(AgentPlanStepRepository.class), mock(AgentPlanEventRepository.class), agentService,
                mock(AgentRuntimeService.class), null, mock(PlanningAgentPlanner.class),
                mock(PlanStepVerifier.class), null, null, null, new ObjectMapper(), mock(ProjectService.class));
        AgentPlan plan = new AgentPlan(10L, 1L, "goal", "summary", true, null, "{}");

        ReflectionTestUtils.invokeMethod(service, "persistConversationSummary", 1L, 10L, plan, List.of());

        verify(agentService, never()).saveMessage(any(), any(), any(ChatMessage.class));
    }

    @Test
    void repeatedTerminalGetRepairsWithOneLockedFallbackAndZeroModelCalls() {
        AgentPlanRepository plans = mock(AgentPlanRepository.class);
        AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentPlanEventRepository events = mock(AgentPlanEventRepository.class);
        AgentService agentService = mock(AgentService.class);
        FinalSynthesisService synthesis = mock(FinalSynthesisService.class);
        AgentPlanRunLeaseService leases = mock(AgentPlanRunLeaseService.class);
        AgentPlan plan = terminalPlan(31L, 11L, "FAILED");
        when(plans.findByIdAndUserId(31L, 1L)).thenReturn(Optional.of(plan));
        when(steps.findByPlanIdOrderBySortOrderAsc(31L)).thenReturn(List.of());
        when(leases.publishTerminalCanonical(eq(31L), eq(1L), any(), any())).thenAnswer(invocation -> {
            plan.publishCanonicalAnswer(invocation.getArgument(2), invocation.getArgument(3));
            return plan;
        });
        PlanAgentService service = service(plans, steps, events, agentService, leases, synthesis);

        AgentPlanResponse first = service.getPlan(1L, 31L);
        AgentPlanResponse second = service.getPlan(1L, 31L);

        assertThat(first.finalAnswer()).isNotBlank().isEqualTo(second.finalAnswer());
        verify(synthesis, never()).synthesize(any(), any(), any(), any(), any(), any());
        verify(leases, times(1)).publishTerminalCanonical(eq(31L), eq(1L), any(), any());
    }

    @Test
    void listingMultipleHistoricalTerminalPlansNeverCallsTheModel() {
        AgentPlanRepository plans = mock(AgentPlanRepository.class);
        AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentPlanEventRepository events = mock(AgentPlanEventRepository.class);
        AgentService agentService = mock(AgentService.class);
        FinalSynthesisService synthesis = mock(FinalSynthesisService.class);
        AgentPlanRunLeaseService leases = mock(AgentPlanRunLeaseService.class);
        AgentPlan failed = terminalPlan(41L, 12L, "FAILED");
        AgentPlan cancelled = terminalPlan(42L, 12L, "CANCELLED");
        AgentSession session = new AgentSession(1L, "history", "test", "model", 4, true);
        ReflectionTestUtils.setField(session, "id", 12L);
        when(agentService.getOwnedSession(1L, 12L)).thenReturn(session);
        when(plans.findBySessionIdAndUserIdOrderByCreatedAtDesc(12L, 1L))
                .thenReturn(List.of(failed, cancelled));
        when(steps.findByPlanIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        when(leases.publishTerminalCanonical(any(), eq(1L), any(), any())).thenAnswer(invocation -> {
            Long planId = invocation.getArgument(0);
            AgentPlan target = planId.equals(41L) ? failed : cancelled;
            target.publishCanonicalAnswer(invocation.getArgument(2), invocation.getArgument(3));
            return target;
        });
        PlanAgentService service = service(plans, steps, events, agentService, leases, synthesis);

        List<AgentPlanResponse> history = service.listSessionPlans(1L, 12L);

        assertThat(history).hasSize(2).allSatisfy(item -> assertThat(item.finalAnswer()).isNotBlank());
        verify(synthesis, never()).synthesize(any(), any(), any(), any(), any(), any());
        verify(leases, times(2)).publishTerminalCanonical(any(), eq(1L), any(), any());
    }

    private PlanAgentService service(AgentPlanRepository plans,
                                     AgentPlanStepRepository steps,
                                     AgentPlanEventRepository events,
                                     AgentService agentService,
                                     AgentPlanRunLeaseService leases,
                                     FinalSynthesisService synthesis) {
        PlanAgentService service = new PlanAgentService(plans, steps, events, agentService,
                mock(AgentRuntimeService.class), null, mock(PlanningAgentPlanner.class),
                mock(PlanStepVerifier.class), null, null, null, new ObjectMapper(), mock(ProjectService.class),
                null, leases, mock(AgentPlanCheckpointService.class));
        ReflectionTestUtils.setField(service, "finalSynthesisService", synthesis);
        return service;
    }

    private AgentPlan terminalPlan(Long planId, Long sessionId, String status) {
        AgentPlan plan = new AgentPlan(sessionId, 1L, "WORKER21 recovery", "history", true, null, "{}");
        ReflectionTestUtils.setField(plan, "id", planId);
        if ("CANCELLED".equals(status)) plan.markCancelled("cancelled");
        else plan.markFailed("failed");
        return plan;
    }
}
