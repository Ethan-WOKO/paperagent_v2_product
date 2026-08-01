package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectService;
import com.yanban.core.agent.AgentMessage;
import com.yanban.core.agent.AgentMessageRepository;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanStepRepository;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AgentPlanHandoffReconciliationTest {

    @Test
    void confirmationHandoffIsBoundToItsPlanAndReplacedInPlaceAfterTimeout() {
        List<ChatMessage> bound = AgentService.bindPlanHandoffMarker(
                List.of(ChatMessage.assistant(AgentService.PLAN_CONFIRMATION_HANDOFF)), 163L);
        assertThat(bound).singleElement().satisfies(message -> {
            assertThat(message.content()).isEqualTo(AgentService.PLAN_CONFIRMATION_HANDOFF);
            assertThat(message.toolCallId()).isEqualTo("plan-handoff:163");
        });

        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        AgentMessageCacheService cache = mock(AgentMessageCacheService.class);
        AgentService service = service(messages, cache);
        AgentMessage persisted = new AgentMessage(163L, 1L, "assistant",
                AgentService.PLAN_CONFIRMATION_HANDOFF, null, "plan-handoff:163", null);
        when(messages.findFirstBySessionIdAndToolCallIdOrderByIdDesc(163L, "plan-handoff:163"))
                .thenReturn(Optional.of(persisted));
        when(messages.saveAndFlush(persisted)).thenReturn(persisted);

        String terminal = AgentService.terminalPlanAssistantContent(plan("FAILED", "TIMED_OUT"));
        assertThat(service.reconcilePlanHandoffMessage(163L, 163L, terminal)).isTrue();

        AgentMessageResponse apiMessage = AgentMessageResponse.from(persisted);
        assertThat(apiMessage.content())
                .contains("executionOutcome=TIMED_OUT", "taskOutcome=TIMED_OUT")
                .doesNotContain("Review the Plan card");
        assertThat(apiMessage.toolCallId()).isEqualTo("plan-handoff:163");
        verify(messages).saveAndFlush(persisted);
        verify(cache).evictSession(1L, 163L);
        verify(messages, never()).save(any(AgentMessage.class));
    }

    @Test
    void eachGovernedTerminalOutcomeReplacesItsOwnPersistedWaitWithoutAddingAMessage() {
        List<OutcomeCase> cases = List.of(
                new OutcomeCase(201L, "FAILED", "TIMED_OUT", "executionOutcome=TIMED_OUT"),
                new OutcomeCase(202L, "FAILED", "FAILED", "executionOutcome=FAILED"),
                new OutcomeCase(203L, "CANCELLED", "CANCELLED", "executionOutcome=CANCELLED"),
                new OutcomeCase(204L, "FAILED", "PARTIAL", "executionOutcome=PARTIAL"));

        for (OutcomeCase terminalCase : cases) {
            AgentMessageRepository messages = mock(AgentMessageRepository.class);
            AgentMessageCacheService cache = mock(AgentMessageCacheService.class);
            AgentService service = service(messages, cache);
            String marker = "plan-handoff:" + terminalCase.planId();
            AgentMessage persisted = new AgentMessage(163L, 1L, "assistant",
                    AgentService.PLAN_CONFIRMATION_HANDOFF, null, marker, null);
            when(messages.findFirstBySessionIdAndToolCallIdOrderByIdDesc(163L, marker))
                    .thenReturn(Optional.of(persisted));
            when(messages.saveAndFlush(persisted)).thenReturn(persisted);

            String terminal = AgentService.terminalPlanAssistantContent(
                    plan(terminalCase.planId(), terminalCase.status(), terminalCase.outcome()));
            assertThat(service.reconcilePlanHandoffMessage(163L, terminalCase.planId(), terminal)).isTrue();
            assertThat(AgentMessageResponse.from(persisted).content())
                    .contains(terminalCase.contentPrefix())
                    .doesNotContain("Review the Plan card");
            verify(messages).saveAndFlush(persisted);
            verify(messages, never()).save(any(AgentMessage.class));
        }
    }

    @Test
    void anUnboundLegacyWaitIsNotGuessedAcrossPlans() {
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        AgentMessageCacheService cache = mock(AgentMessageCacheService.class);
        AgentService service = service(messages, cache);
        when(messages.findFirstBySessionIdAndToolCallIdOrderByIdDesc(163L, "plan-handoff:164"))
                .thenReturn(Optional.empty());

        assertThat(service.reconcilePlanHandoffMessage(163L, 164L, "Plan execution was cancelled."))
                .isFalse();
        verify(messages, never()).saveAndFlush(any(AgentMessage.class));
        verify(cache, never()).evictSession(any(), any());
    }

    @Test
    void repeatedTerminalReconciliationDoesNotWriteTheSameAssistantAgain() {
        AgentMessageRepository messages = mock(AgentMessageRepository.class);
        AgentMessageCacheService cache = mock(AgentMessageCacheService.class);
        AgentService service = service(messages, cache);
        AgentMessage persisted = new AgentMessage(163L, 1L, "assistant",
                AgentService.PLAN_CONFIRMATION_HANDOFF, null, "plan-handoff:163", null);
        when(messages.findFirstBySessionIdAndToolCallIdOrderByIdDesc(163L, "plan-handoff:163"))
                .thenReturn(Optional.of(persisted));
        when(messages.saveAndFlush(persisted)).thenReturn(persisted);

        String terminal = "唯一的最终回答";
        assertThat(service.reconcilePlanHandoffMessage(163L, 163L, terminal)).isTrue();
        assertThat(service.reconcilePlanHandoffMessage(163L, 163L, terminal)).isTrue();

        verify(messages, times(1)).saveAndFlush(persisted);
        verify(cache, times(1)).evictSession(1L, 163L);
    }

    @Test
    void planTerminalBoundaryDelegatesEveryAuthoritativeOutcomeToTheSingleMessageReconciler() {
        AgentService agentService = mock(AgentService.class);
        PlanAgentService plans = new PlanAgentService(
                mock(AgentPlanRepository.class), mock(AgentPlanStepRepository.class),
                mock(AgentPlanEventRepository.class), agentService, mock(AgentRuntimeService.class), null,
                mock(PlanningAgentPlanner.class), mock(PlanStepVerifier.class), null, null, null,
                new ObjectMapper(), mock(ProjectService.class));
        ReflectionTestUtils.invokeMethod(plans, "reconcileTerminalPlanHandoff", plan("FAILED", "TIMED_OUT"));
        ReflectionTestUtils.invokeMethod(plans, "reconcileTerminalPlanHandoff", plan("FAILED", "FAILED"));
        ReflectionTestUtils.invokeMethod(plans, "reconcileTerminalPlanHandoff", plan("CANCELLED", "CANCELLED"));
        ReflectionTestUtils.invokeMethod(plans, "reconcileTerminalPlanHandoff", plan("FAILED", "PARTIAL"));

        verify(agentService).reconcilePlanHandoffMessage(eq(163L), eq(163L), contains("executionOutcome=TIMED_OUT"));
        verify(agentService).reconcilePlanHandoffMessage(eq(163L), eq(163L), contains("executionOutcome=FAILED"));
        verify(agentService).reconcilePlanHandoffMessage(eq(163L), eq(163L), contains("executionOutcome=CANCELLED"));
        verify(agentService).reconcilePlanHandoffMessage(eq(163L), eq(163L), contains("executionOutcome=PARTIAL"));
    }

    @Test
    void waitingForConfirmationKeepsTheHandoffAndDoesNotRunTerminalReconciliation() {
        AgentService agentService = mock(AgentService.class);
        PlanAgentService plans = new PlanAgentService(
                mock(AgentPlanRepository.class), mock(AgentPlanStepRepository.class),
                mock(AgentPlanEventRepository.class), agentService, mock(AgentRuntimeService.class), null,
                mock(PlanningAgentPlanner.class), mock(PlanStepVerifier.class), null, null, null,
                new ObjectMapper(), mock(ProjectService.class));

        ReflectionTestUtils.invokeMethod(plans, "reconcileTerminalPlanHandoff",
                plan("REVIEWING", "REVIEWING"));

        verify(agentService, never()).reconcilePlanHandoffMessage(any(), any(), any());
    }

    private static AgentService service(AgentMessageRepository messages, AgentMessageCacheService cache) {
        return new AgentService(null, messages, null, cache, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static AgentPlanResponse plan(String status, String outcome) {
        return plan(163L, status, outcome);
    }

    private static AgentPlanResponse plan(Long planId, String status, String outcome) {
        return new AgentPlanResponse(planId, 163L, "Run", "Run in sandbox", status, true, null, null,
                null, null, null, null, List.of(), outcome, null, "L2_DURABLE");
    }

    private record OutcomeCase(Long planId, String status, String outcome, String contentPrefix) {
    }
}
