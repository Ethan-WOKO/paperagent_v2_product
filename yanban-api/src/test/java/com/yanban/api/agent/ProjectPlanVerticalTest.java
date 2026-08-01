package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.SkillsService;
import com.yanban.core.agent.*;
import com.yanban.core.model.ChatMessage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ProjectPlanVerticalTest {
    @Test
    void retryAndAsyncFailClosedBeforeMutatingUnauthorizedOrDamagedProjectPlans() {
        ObjectMapper json = new ObjectMapper();
        AgentPlanRepository plans = mock(AgentPlanRepository.class); AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentPlanEventRepository events = mock(AgentPlanEventRepository.class); AgentService agent = mock(AgentService.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class); ProjectService projects = mock(ProjectService.class);
        PlanAgentService service = new PlanAgentService(plans, steps, events, agent, runtime, null, mock(PlanningAgentPlanner.class),
                mock(PlanStepVerifier.class), mock(UserSettingsService.class), mock(SkillsService.class), mock(AgentToolPolicyEngine.class), json, projects);
        try {
            AgentPlan denied = new AgentPlan(7L, 7L, "g", "s", true, null,
                    ProjectPlanEnvelope.wrap(json, "{}", new ProjectRuntimeContext(7L, 99L)));
            ReflectionTestUtils.setField(denied, "id", 19L); denied.markFailed("failed");
            AgentPlanStep step = new AgentPlanStep(19L, "s", 1, "s", "d", "A", "[]", "[]", "c"); step.markFailed("failed");
            ReflectionTestUtils.setField(step, "id", 1L);
            when(plans.findByIdAndUserId(19L, 7L)).thenReturn(Optional.of(denied)); when(steps.findByPlanIdOrderBySortOrderAsc(19L)).thenReturn(List.of(step));
            when(projects.manifest(7L, 99L)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
            String status = denied.getStatus(); String stepStatus = step.getStatus(); Integer attempts = step.getAttemptCount();

            assertThatThrownBy(() -> service.retryPlan(7L, 19L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(denied.getStatus()).isEqualTo(status); assertThat(step.getStatus()).isEqualTo(stepStatus); assertThat(step.getAttemptCount()).isEqualTo(attempts);
            verifyNoInteractions(runtime);
        } finally { service.shutdownPlanExecutor(); }
    }

    @Test
    void damagedEnvelopeFailsBeforeAsyncOrRetryMutation() {
        ObjectMapper json = new ObjectMapper();
        AgentPlanRepository plans = mock(AgentPlanRepository.class); AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class);
        PlanAgentService service = new PlanAgentService(plans, steps, mock(AgentPlanEventRepository.class), mock(AgentService.class), runtime, null,
                mock(PlanningAgentPlanner.class), mock(PlanStepVerifier.class), mock(UserSettingsService.class), mock(SkillsService.class), mock(AgentToolPolicyEngine.class), json, mock(ProjectService.class));
        try {
            AgentPlan plan = new AgentPlan(7L, 7L, "g", "s", true, null, "{\"schemaVersion\":\"project_plan_envelope_v1\"}");
            ReflectionTestUtils.setField(plan, "id", 19L); plan.markFailed("failed");
            AgentPlanStep step = new AgentPlanStep(19L, "s", 1, "s", "d", "A", "[]", "[]", "c"); step.markFailed("failed");
            ReflectionTestUtils.setField(step, "id", 1L); when(plans.findByIdAndUserId(19L, 7L)).thenReturn(Optional.of(plan)); when(steps.findByPlanIdOrderBySortOrderAsc(19L)).thenReturn(List.of(step));
            String status = plan.getStatus(); String result = step.getResult(); Integer attempts = step.getAttemptCount();
            ReflectionTestUtils.setField(plan, "status", "REVIEWING");
            assertThatThrownBy(() -> service.executePlanAsync(7L, 19L)).isInstanceOf(IllegalStateException.class);
            ReflectionTestUtils.setField(plan, "status", "FAILED");
            assertThatThrownBy(() -> service.retryPlan(7L, 19L)).isInstanceOf(IllegalStateException.class);
            assertThat(plan.getStatus()).isEqualTo(status); assertThat(step.getResult()).isEqualTo(result); assertThat(step.getAttemptCount()).isEqualTo(attempts);
            verify(plans, never()).saveAndFlush(any()); verify(steps, never()).save(any()); verifyNoInteractions(runtime);
        } finally { service.shutdownPlanExecutor(); }
    }

    @Test
    void asyncWorkerRestoresProjectContextBeforeRunningProjectStep() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AgentPlanRepository plans = mock(AgentPlanRepository.class); AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentPlanEventRepository events = mock(AgentPlanEventRepository.class); AgentService agent = mock(AgentService.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class); PlanningAgentPlanner planner = mock(PlanningAgentPlanner.class);
        PlanStepVerifier verifier = mock(PlanStepVerifier.class); UserSettingsService settings = mock(UserSettingsService.class);
        SkillsService skills = mock(SkillsService.class); AgentToolPolicyEngine policy = mock(AgentToolPolicyEngine.class); ProjectService projects = mock(ProjectService.class);
        PlanAgentService service = new PlanAgentService(plans, steps, events, agent, runtime, null, planner, verifier, settings, skills, policy, json, projects);
        try {
            AgentPlan plan = new AgentPlan(7L, 7L, "inspect", "inspect", true, null,
                    ProjectPlanEnvelope.wrap(json, "{}", new ProjectRuntimeContext(7L, 42L)));
            ReflectionTestUtils.setField(plan, "id", 19L);
            AgentPlanStep step = new AgentPlanStep(19L, "s1", 1, "read", "read", "ANALYSIS", "[]", "[\"project_read_file\"]", "read");
            ReflectionTestUtils.setField(step, "id", 1L);
            AgentSession session = new AgentSession(7L, "s", "deepseek", "deepseek-chat", 3, true);
            ReflectionTestUtils.setField(session, "id", 7L);
            when(plans.findByIdAndUserId(19L, 7L)).thenReturn(Optional.of(plan)); when(agent.getOwnedSession(7L, 7L)).thenReturn(session);
            when(projects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(42L, "m", List.of()));
            when(settings.resolveModelEndpoint(anyLong(), any(), any())).thenReturn(new UserSettingsService.ModelEndpoint("deepseek", "deepseek-chat", null, "k", "builtin", "x"));
            when(policy.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(List.of("project_read_file"), 3, 1, "project"));
            when(steps.findByPlanIdOrderBySortOrderAsc(19L)).thenReturn(List.of(step)); when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
            when(steps.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0)); when(events.save(any())).thenAnswer(i -> i.getArgument(0));
            String tool = "{\"projectId\":42,\"relativePath\":\"src/Main.java\",\"hash\":\"h1\",\"evidenceRefs\":[\"project:42:src/Main.java:h1:c1\"]}";
            when(runtime.run(any())).thenReturn(new AgentRuntimeResult(true, "done", List.of(new ChatMessage("tool", tool, null, "c1"), ChatMessage.assistant("done")), 1, null, List.of(), List.of(), null, null, null));
            when(verifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

            service.executePlanAsync(7L, 19L);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!plan.terminal() && System.nanoTime() < deadline) Thread.yield();
            assertThat(plan.getStatus()).isEqualTo("COMPLETED");
            ArgumentCaptor<AgentRuntimeRequest> requests = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
            verify(runtime, atLeastOnce()).run(requests.capture());
            assertThat(requests.getAllValues()).anySatisfy(request -> {
                assertThat(request.projectContext().projectId()).isEqualTo(42L);
                assertThat(request.toolPolicy().allowedTools()).containsExactly("project_read_file");
            });
            verify(projects, atLeast(3)).manifest(7L, 42L);
        } finally { service.shutdownPlanExecutor(); }
    }

    @Test
    void authorizedFailedRetryRunsWorkerToCompletion() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AgentPlanRepository plans = mock(AgentPlanRepository.class); AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentPlanEventRepository events = mock(AgentPlanEventRepository.class); AgentService agent = mock(AgentService.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class); PlanningAgentPlanner planner = mock(PlanningAgentPlanner.class);
        PlanStepVerifier verifier = mock(PlanStepVerifier.class); UserSettingsService settings = mock(UserSettingsService.class);
        SkillsService skills = mock(SkillsService.class); AgentToolPolicyEngine policy = mock(AgentToolPolicyEngine.class); ProjectService projects = mock(ProjectService.class);
        PlanAgentService service = new PlanAgentService(plans, steps, events, agent, runtime, null, planner, verifier, settings, skills, policy, json, projects);
        try {
            AgentPlan plan = new AgentPlan(7L, 7L, "inspect", "inspect", true, null,
                    ProjectPlanEnvelope.wrap(json, "{}", new ProjectRuntimeContext(7L, 42L)));
            ReflectionTestUtils.setField(plan, "id", 19L);
            plan.markFailed("failed");
            AgentPlanStep step = new AgentPlanStep(19L, "s1", 1, "read", "read", "ANALYSIS", "[]", "[\"project_read_file\"]", "read");
            ReflectionTestUtils.setField(step, "id", 1L);
            step.markFailed("failed");
            AgentSession session = new AgentSession(7L, "s", "deepseek", "deepseek-chat", 3, true);
            ReflectionTestUtils.setField(session, "id", 7L);
            when(plans.findByIdAndUserId(19L, 7L)).thenReturn(Optional.of(plan)); when(agent.getOwnedSession(7L, 7L)).thenReturn(session);
            when(projects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(42L, "m", List.of()));
            when(settings.resolveModelEndpoint(anyLong(), any(), any())).thenReturn(new UserSettingsService.ModelEndpoint("deepseek", "deepseek-chat", null, "k", "builtin", "x"));
            when(policy.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(List.of("project_read_file"), 3, 1, "project"));
            when(steps.findByPlanIdOrderBySortOrderAsc(19L)).thenReturn(List.of(step)); when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
            when(steps.save(any())).thenAnswer(i -> i.getArgument(0)); when(steps.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0)); when(events.save(any())).thenAnswer(i -> i.getArgument(0));
            String tool = "{\"projectId\":42,\"relativePath\":\"src/Main.java\",\"hash\":\"h1\",\"evidenceRefs\":[\"project:42:src/Main.java:h1:c1\"]}";
            when(runtime.run(any())).thenReturn(new AgentRuntimeResult(true, "done", List.of(new ChatMessage("tool", tool, null, "c1"), ChatMessage.assistant("done")), 1, null, List.of(), List.of(), null, null, null));
            when(verifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

            service.retryPlan(7L, 19L);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!plan.terminal() && System.nanoTime() < deadline) Thread.yield();
            assertThat(plan.getStatus()).isEqualTo("COMPLETED");
            assertThat(step.getStatus()).isEqualTo("COMPLETED");
            assertThat(step.getAttemptCount()).isGreaterThanOrEqualTo(1);
            ArgumentCaptor<AgentRuntimeRequest> requests = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
            verify(runtime, atLeastOnce()).run(requests.capture());
            assertThat(requests.getAllValues()).anySatisfy(request -> {
                assertThat(request.projectContext().projectId()).isEqualTo(42L);
                assertThat(request.toolPolicy().allowedTools()).containsExactly("project_read_file");
            });
            verify(projects, atLeast(3)).manifest(7L, 42L);
        } finally { service.shutdownPlanExecutor(); }
    }

    @Test
    void cancelledRetryRevalidatesBeforeReset() {
        ObjectMapper json = new ObjectMapper();
        AgentPlanRepository plans = mock(AgentPlanRepository.class); AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class); ProjectService projects = mock(ProjectService.class);
        PlanAgentService service = new PlanAgentService(plans, steps, mock(AgentPlanEventRepository.class), mock(AgentService.class), runtime, null,
                mock(PlanningAgentPlanner.class), mock(PlanStepVerifier.class), mock(UserSettingsService.class), mock(SkillsService.class), mock(AgentToolPolicyEngine.class), json, projects);
        try {
            AgentPlan plan = new AgentPlan(7L, 7L, "inspect", "inspect", true, null,
                    ProjectPlanEnvelope.wrap(json, "{}", new ProjectRuntimeContext(7L, 42L)));
            ReflectionTestUtils.setField(plan, "id", 19L);
            plan.markCancelled("cancelled");
            AgentPlanStep step = new AgentPlanStep(19L, "s1", 1, "read", "read", "ANALYSIS", "[]", "[\"project_read_file\"]", "read");
            ReflectionTestUtils.setField(step, "id", 1L);
            step.markFailed("failed", "previous result");
            when(plans.findByIdAndUserId(19L, 7L)).thenReturn(Optional.of(plan));
            when(steps.findByPlanIdOrderBySortOrderAsc(19L)).thenReturn(List.of(step));
            when(projects.manifest(7L, 42L)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
            String planStatus = plan.getStatus();
            String stepStatus = step.getStatus();
            Integer attempts = step.getAttemptCount();
            String result = step.getResult();

            assertThatThrownBy(() -> service.retryPlan(7L, 19L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

            assertThat(plan.getStatus()).isEqualTo(planStatus);
            assertThat(step.getStatus()).isEqualTo(stepStatus);
            assertThat(step.getAttemptCount()).isEqualTo(attempts);
            assertThat(step.getResult()).isEqualTo(result);
            verify(plans, never()).saveAndFlush(any());
            verify(steps, never()).save(any());
            verifyNoInteractions(runtime);
        } finally { service.shutdownPlanExecutor(); }
    }

    @Test
    void restoredProjectEnvelopeRequiresCurrentFileEvidenceAndPersistsItOnStep() {
        ObjectMapper json = new ObjectMapper();
        AgentPlanRepository plans = mock(AgentPlanRepository.class); AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentPlanEventRepository events = mock(AgentPlanEventRepository.class); AgentService agent = mock(AgentService.class);
        AgentRuntimeService runtime = mock(AgentRuntimeService.class); PlanningAgentPlanner planner = mock(PlanningAgentPlanner.class);
        PlanStepVerifier verifier = mock(PlanStepVerifier.class); UserSettingsService settings = mock(UserSettingsService.class);
        SkillsService skills = mock(SkillsService.class); AgentToolPolicyEngine policy = mock(AgentToolPolicyEngine.class); ProjectService projects = mock(ProjectService.class);
        PlanAgentService service = new PlanAgentService(plans, steps, events, agent, runtime, null, planner, verifier, settings, skills, policy, json, projects);
        AgentPlan plan = new AgentPlan(7L, 7L, "inspect", "inspect", true, null,
                ProjectPlanEnvelope.wrap(json, "{\"projectId\":999}", new ProjectRuntimeContext(7L, 42L)));
        ReflectionTestUtils.setField(plan, "id", 19L);
        AgentPlanStep step = new AgentPlanStep(19L, "s1", 1, "read", "read file", "ANALYSIS", "[]", "[\"project_read_file\"]", "read");
        ReflectionTestUtils.setField(step, "id", 1L);
        AgentSession session = new AgentSession(7L, "s", "deepseek", "deepseek-chat", 3, true);
        ReflectionTestUtils.setField(session, "id", 7L);
        when(plans.findByIdAndUserId(19L, 7L)).thenReturn(Optional.of(plan)); when(agent.getOwnedSession(7L, 7L)).thenReturn(session);
        when(projects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(42L, "m", List.of()));
        when(settings.resolveModelEndpoint(anyLong(), any(), any())).thenReturn(new UserSettingsService.ModelEndpoint("deepseek", "deepseek-chat", null, "k", "builtin", "x"));
        when(policy.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(List.of("project_read_file"), 3, 1, "project"));
        when(steps.findByPlanIdOrderBySortOrderAsc(19L)).thenReturn(List.of(step)); when(plans.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(steps.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0)); when(events.save(any())).thenAnswer(i -> i.getArgument(0));
        String tool = "{\"projectId\":42,\"relativePath\":\"src/Main.java\",\"hash\":\"h1\",\"evidenceRefs\":[\"project:42:src/Main.java:h1:c1\"]}";
        when(runtime.run(any())).thenReturn(new AgentRuntimeResult(true, "done", List.of(new ChatMessage("tool", tool, null, "c1"), ChatMessage.assistant("done")), 1, null, List.of(), List.of(), null, null, null));
        when(verifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));

        AgentPlanResponse response = service.executePlan(7L, 19L);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(step.getResult()).contains("projectEvidenceRefs=project:42:src/Main.java:h1:c1");
        verify(projects, atLeast(2)).manifest(7L, 42L);
        verify(runtime).run(argThat(r -> r.projectContext() != null && r.projectContext().projectId().equals(42L)
                && r.toolPolicy().allowedTools().equals(List.of("project_read_file"))));
    }
}
