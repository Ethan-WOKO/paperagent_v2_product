package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.worker.ControlledReadOnlyWorkerRuntimeAdapter;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.SkillsService;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanEvent;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanExecutionLease;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanRunLeaseService;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentPlanStepRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatMessage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class PlanAgentDurableRecoveryBoundaryTest {

    private Fixture fixture;

    @AfterEach
    void shutdownExecutors() {
        if (fixture != null) fixture.service.shutdownPlanExecutor();
    }

    @Test
    void exhaustedUnknownCompletionTerminatesWithoutRepairDegradeOrRuntimeDispatch() {
        fixture = new Fixture();
        AgentPlanStep step = fixture.step("read", "[\"project_read_file\"]");
        step.markRunning();
        step.markRunning();

        AgentPlanResponse response = fixture.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(step.getStatus()).isEqualTo("FAILED");
        assertThat(step.getErrorMessage()).startsWith("UNKNOWN_COMPLETION:");
        assertThat(fixture.eventTypes()).contains("step_recovery_failed_closed", "plan_failed")
                .doesNotContain("step_repair_started", "step_degraded", "plan_repaired");
        verify(fixture.planner, never()).createRecoveryPlan(any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.runtime, never()).run(any());
        verify(fixture.controlled, never()).executeWithinPlan(any());
    }

    @Test
    void persistedTotalBudgetIsTightenedBeforeDispatchAndBlocksEveryLaterAttemptAndRepair() {
        fixture = new Fixture();
        AgentPlanStep step = fixture.step("read", "[\"project_read_file\"]");
        when(fixture.checkpoints.remainingToolCalls(any(), any())).thenReturn(1, 0, 0);
        when(fixture.runtime.run(any())).thenReturn(new AgentRuntimeResult(false, null,
                List.of(ChatMessage.assistant("failed after one bounded dispatch")), 1,
                "failed", List.of(), List.of(), null, null, null));

        AgentPlanResponse response = fixture.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(step.getErrorMessage()).startsWith("TOOL_BUDGET_EXHAUSTED:");
        ArgumentCaptor<AgentRuntimeRequest> request = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(fixture.runtime).run(request.capture());
        assertThat(request.getValue().toolPolicy().maxToolCalls()).isEqualTo(1);
        assertThat(request.getValue().toolPolicy().reason()).contains("durable_remaining_total_budget");
        verify(fixture.planner, never()).createRecoveryPlan(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(fixture.eventTypes()).contains("step_tool_budget_exhausted", "step_repair_rejected");
    }

    @Test
    void alreadyExhaustedBudgetFailsBeforeAnyRuntimeOrRepairDispatch() {
        fixture = new Fixture();
        fixture.step("read", "[\"project_read_file\"]");
        when(fixture.checkpoints.remainingToolCalls(any(), any())).thenReturn(0);

        AgentPlanResponse response = fixture.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID);

        assertThat(response.status()).isEqualTo("FAILED");
        verify(fixture.runtime, never()).run(any());
        verify(fixture.planner, never()).createRecoveryPlan(any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.controlled, never()).executeWithinPlan(any());
    }

    @Test
    void nullCurrentProjectPolicyFailsClosedDuringRecoveryWithoutRuntimeDispatch() {
        fixture = new Fixture();
        fixture.step("sandbox", "[\"sandbox_execute\"]");
        fixture.plan.markRunning();
        when(fixture.policy.decideProject(org.mockito.ArgumentMatchers.nullable(java.util.Set.class),
                org.mockito.ArgumentMatchers.nullable(java.util.Set.class))).thenReturn(null);

        AgentPlanResponse response = fixture.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorMessage()).contains("RECOVERY_REJECTED", "current Project tool policy is unavailable");
        assertThat(fixture.eventTypes()).contains("plan_recovery_rejected");
        verify(fixture.runtime, never()).run(any());
        verify(fixture.controlled, never()).executeWithinPlan(any());
    }

    @Test
    void durableRetryRejectsExhaustedStepWithoutResetQueueOrAttemptRefund() {
        fixture = new Fixture();
        AgentPlanStep step = fixture.step("read", "[\"project_read_file\"]");
        step.markRunning();
        step.markRunning();
        step.markFailed("attempts exhausted");
        fixture.plan.markFailed("failed");

        assertThatThrownBy(() -> fixture.service.retryPlan(Fixture.USER_ID, Fixture.PLAN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("retry budget is exhausted");

        assertThat(fixture.plan.getStatus()).isEqualTo("FAILED");
        assertThat(step.getStatus()).isEqualTo("FAILED");
        assertThat(step.getAttemptCount()).isEqualTo(2);
        verify(fixture.leases, never()).queue(anyLong(), anyLong());
        verify(fixture.runtime, never()).run(any());
    }

    @Test
    void missingTrustedSessionAfterClaimIsFencedRecoveryRejectionNotInfiniteInterruption() {
        fixture = new Fixture();
        fixture.step("read", "[\"project_read_file\"]");
        fixture.plan.markRunning();
        when(fixture.agentService.getOwnedSession(Fixture.USER_ID, Fixture.SESSION_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "session revoked"));

        AgentPlanResponse response = fixture.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorMessage()).contains("RECOVERY_REJECTED", "session revoked");
        verify(fixture.leases).rejectRecovery(eq(fixture.lease),
                org.mockito.ArgumentMatchers.contains("session revoked"));
        verify(fixture.runtime, never()).run(any());
        assertThat(fixture.eventTypes()).contains("plan_recovery_rejected")
                .doesNotContain("plan_execution_interrupted");
    }

    @Test
    void revokedProjectAccessAfterClaimIsFencedRecoveryRejection() {
        fixture = new Fixture();
        fixture.step("read", "[\"project_read_file\"]");
        fixture.plan.markRunning();
        when(fixture.projects.manifest(Fixture.USER_ID, Fixture.PROJECT_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "project access revoked"));

        AgentPlanResponse response = fixture.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorMessage()).contains("RECOVERY_REJECTED", "project access revoked");
        verify(fixture.leases).rejectRecovery(eq(fixture.lease),
                org.mockito.ArgumentMatchers.contains("project access revoked"));
        verify(fixture.runtime, never()).run(any());
    }

    @Test
    void revokedCheckpointToolAuthorityAfterClaimIsFencedRecoveryRejection() {
        fixture = new Fixture();
        fixture.step("read", "[\"project_read_file\"]");
        fixture.plan.markRunning();
        when(fixture.checkpoints.initializeOrValidate(eq(fixture.lease), any(), any()))
                .thenThrow(new IllegalStateException("durable Plan tool authority was revoked"));

        AgentPlanResponse response = fixture.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorMessage()).contains("RECOVERY_REJECTED", "tool authority was revoked");
        verify(fixture.leases).rejectRecovery(eq(fixture.lease),
                org.mockito.ArgumentMatchers.contains("tool authority was revoked"));
        verify(fixture.runtime, never()).run(any());
    }

    @Test
    void transientAuthorityInfrastructureFailureReleasesLeaseWithoutPermanentRejection() {
        fixture = new Fixture();
        fixture.step("read", "[\"project_read_file\"]");
        fixture.plan.markRunning();
        when(fixture.agentService.getOwnedSession(Fixture.USER_ID, Fixture.SESSION_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "database temporarily down"));

        assertThatThrownBy(() -> fixture.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("database temporarily down");

        assertThat(fixture.plan.getStatus()).isEqualTo("RUNNING");
        verify(fixture.leases, never()).rejectRecovery(any(), any());
        verify(fixture.leases).release(fixture.lease, "INTERRUPTED");
        verify(fixture.runtime, never()).run(any());
    }

    private static final class Fixture {
        private static final long USER_ID = 7L;
        private static final long SESSION_ID = 11L;
        private static final long PROJECT_ID = 42L;
        private static final long PLAN_ID = 19L;

        private final ObjectMapper json = new ObjectMapper();
        private final AgentPlanRepository plans = mock(AgentPlanRepository.class);
        private final AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        private final AgentPlanEventRepository events = mock(AgentPlanEventRepository.class);
        private final AgentService agentService = mock(AgentService.class);
        private final AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        private final PlanningAgentPlanner planner = mock(PlanningAgentPlanner.class);
        private final PlanStepVerifier verifier = mock(PlanStepVerifier.class);
        private final UserSettingsService settings = mock(UserSettingsService.class);
        private final SkillsService skills = mock(SkillsService.class);
        private final AgentToolPolicyEngine policy = mock(AgentToolPolicyEngine.class);
        private final ProjectService projects = mock(ProjectService.class);
        private final ControlledReadOnlyWorkerRuntimeAdapter controlled =
                mock(ControlledReadOnlyWorkerRuntimeAdapter.class);
        private final AgentPlanRunLeaseService leases = mock(AgentPlanRunLeaseService.class);
        private final AgentPlanCheckpointService checkpoints = mock(AgentPlanCheckpointService.class);
        private final List<AgentPlanStep> storedSteps = new ArrayList<>();
        private final List<AgentPlanEvent> storedEvents = new ArrayList<>();
        private final ProjectRuntimeContext context = new ProjectRuntimeContext(USER_ID, PROJECT_ID);
        private final ProjectManifestResponse manifest = new ProjectManifestResponse(
                PROJECT_ID, "a".repeat(64), List.of());
        private final AgentPlanCheckpointService.BudgetCeiling ceiling =
                new AgentPlanCheckpointService.BudgetCeiling(240, 2, 1, 2);
        private final AgentPlanExecutionLease lease = new AgentPlanExecutionLease(
                PLAN_ID, USER_ID, "instance", "token", 1L,
                LocalDateTime.now().plusMinutes(1), true);
        private final AgentPlan plan;
        private final PlanAgentService service;

        private Fixture() {
            plan = new AgentPlan(SESSION_ID, USER_ID, "audit project", "summary", true, null,
                    ProjectPlanEnvelope.wrap(json, "{}", context));
            plan.enableDurableExecution();
            ReflectionTestUtils.setField(plan, "id", PLAN_ID);

            AgentSession session = new AgentSession(USER_ID, "Project", "test", "model", 4, true);
            ReflectionTestUtils.setField(session, "id", SESSION_ID);
            when(plans.findByIdAndUserId(PLAN_ID, USER_ID)).thenReturn(Optional.of(plan));
            when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenAnswer(invocation -> List.copyOf(storedSteps));
            when(events.findByPlanIdOrderByCreatedAtAsc(PLAN_ID)).thenAnswer(invocation -> List.copyOf(storedEvents));
            when(events.findByPlanIdAndIdempotencyKey(eq(PLAN_ID), any())).thenReturn(Optional.empty());
            when(agentService.getOwnedSession(USER_ID, SESSION_ID)).thenReturn(session);
            when(settings.resolveModelEndpoint(USER_ID, "test", "model")).thenReturn(
                    new UserSettingsService.ModelEndpoint("test", "model", null, "key", "builtin", "Test"));
            when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(manifest);
            when(policy.decideProject(org.mockito.ArgumentMatchers.nullable(java.util.Set.class),
                    org.mockito.ArgumentMatchers.nullable(java.util.Set.class))).thenReturn(new AgentToolPolicyEngine.Decision(
                    List.of("project_read_file"), 2, 1, "current"));
            when(leases.claim(eq(PLAN_ID), eq(USER_ID), any(), any(Duration.class)))
                    .thenReturn(Optional.of(lease));
            when(leases.saveOwnedPlan(eq(lease), any(AgentPlan.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(leases.saveOwnedStep(eq(lease), any(AgentPlanStep.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(leases.saveOwnedEvent(eq(lease), any(AgentPlanEvent.class))).thenAnswer(invocation -> {
                AgentPlanEvent event = invocation.getArgument(1);
                storedEvents.add(event);
                return true;
            });
            when(leases.finish(eq(lease), any(AgentPlan.class),
                    org.mockito.ArgumentMatchers.nullable(String.class),
                    org.mockito.ArgumentMatchers.nullable(String.class), any()))
                    .thenAnswer(invocation -> invocation.getArgument(1));
            when(leases.rejectRecovery(eq(lease), any())).thenAnswer(invocation -> {
                plan.markFailed(invocation.getArgument(1));
                return plan;
            });
            when(checkpoints.initializeOrValidate(eq(lease), any(), any()))
                    .thenReturn(new AgentPlanCheckpointService.Validation(true, 1L, context, manifest, ceiling));
            when(checkpoints.saveBoundary(eq(lease), any(), any())).thenReturn(plan);
            when(checkpoints.remainingToolCalls(eq(lease), any())).thenReturn(2);

            service = new PlanAgentService(plans, steps, events, agentService, runtime, null, planner, verifier,
                    settings, skills, policy, json, projects, controlled, leases, checkpoints);
        }

        private AgentPlanStep step(String key, String allowedTools) {
            AgentPlanStep step = new AgentPlanStep(PLAN_ID, key, storedSteps.size() + 1, key,
                    "Read governed Project material", "ANALYSIS", "[]", allowedTools, "evidence");
            ReflectionTestUtils.setField(step, "id", (long) storedSteps.size() + 1);
            storedSteps.add(step);
            return step;
        }

        private List<String> eventTypes() {
            return storedEvents.stream().map(AgentPlanEvent::getEventType).toList();
        }
    }
}
