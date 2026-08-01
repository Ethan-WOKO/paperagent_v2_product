package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.SkillsService;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanStepRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.model.ChatChunk;
import com.yanban.core.model.ChatModelProvider;
import com.yanban.core.model.ChatRequest;
import com.yanban.core.model.ChatResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

class ProjectPlanCreateFailureVerticalTest {

    private static final Long USER_ID = 7L;
    private static final Long PROJECT_ID = 42L;
    private static final Long SESSION_ID = 11L;

    @Test
    void projectFacadeRejectsNonReadOnlyBindingBeforePlanRuntimeDelegation() {
        ProjectService projects = mock(ProjectService.class);
        PlanAgentService plans = mock(PlanAgentService.class);
        when(projects.manifest(USER_ID, PROJECT_ID)).thenThrow(
                new ResponseStatusException(HttpStatus.FORBIDDEN, "Project is not read-only"));
        ProjectAgentRuntimeService service = new ProjectAgentRuntimeService(
                projects, mock(AgentService.class), plans);

        assertThatThrownBy(() -> service.createPlan(USER_ID, PROJECT_ID, SESSION_ID,
                new CreateAgentPlanRequest("inspect project", true, null, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not read-only");

        verifyNoInteractions(plans);
    }

    @Test
    void modelFailureTraversesTrustedProjectPlanChainAndRemainsDiagnostic() {
        ObjectMapper json = new ObjectMapper();
        AgentPlanRepository plans = mock(AgentPlanRepository.class);
        AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentPlanEventRepository events = mock(AgentPlanEventRepository.class);
        AgentService agent = mock(AgentService.class);
        ProjectService projects = mock(ProjectService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        SkillsService skills = mock(SkillsService.class);
        AgentToolPolicyEngine policy = mock(AgentToolPolicyEngine.class);

        AgentSession session = new AgentSession(
                USER_ID, "Project Plan", "deepseek", "deepseek-v4-flash", 3, true);
        when(agent.getOwnedSession(USER_ID, SESSION_ID)).thenReturn(session);
        when(projects.manifest(USER_ID, PROJECT_ID))
                .thenReturn(new ProjectManifestResponse(PROJECT_ID, "manifest-v1", List.of()));
        when(settings.resolveModelEndpoint(anyLong(), any(), any())).thenReturn(
                new UserSettingsService.ModelEndpoint(
                        "deepseek", "deepseek-v4-flash", null, "test-key", "builtin", "DeepSeek"));
        when(policy.decideProject(any(), any())).thenReturn(
                new AgentToolPolicyEngine.Decision(List.of("project_read_file"), 6, 1, "project_read_only"));

        PlanningAgentPlanner planner = new PlanningAgentPlanner(failingModelProvider(), json);
        AtomicReference<PlanAgentService> serviceRef = new AtomicReference<>();
        AtomicReference<AgentRuntimeRequest> runtimeRequest = new AtomicReference<>();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override
            public boolean supports(AgentStrategy strategy) {
                return strategy == AgentStrategy.PLAN_EXECUTE;
            }

            @Override
            public AgentRuntimeResult run(AgentRuntimeRequest request) {
                runtimeRequest.set(request);
                return new PlanRuntimeAdapter(serviceRef.get()).run(request);
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter));
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(runtime, new AgentStrategySelector());
        PlanAgentService planService = new PlanAgentService(
                plans, steps, events, agent, runtime, coordinator, planner, mock(PlanStepVerifier.class),
                settings, skills, policy, json, projects);
        serviceRef.set(planService);
        ProjectAgentRuntimeService facade = new ProjectAgentRuntimeService(projects, agent, planService);

        try {
            assertThatThrownBy(() -> facade.createPlan(USER_ID, PROJECT_ID, SESSION_ID,
                    new CreateAgentPlanRequest("inspect project", true, null, true)))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(exception.getReason())
                                .contains("Project Plan creation failed", "traceId=plan-create-", "MODEL_CALL_FAILED")
                                .contains("provider connection refused")
                                .doesNotContain("502 BAD_GATEWAY");
                    });

            assertThat(runtimeRequest.get()).isNotNull();
            assertThat(runtimeRequest.get().strategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
            assertThat(runtimeRequest.get().userId()).isEqualTo(USER_ID);
            assertThat(runtimeRequest.get().sessionId()).isEqualTo(SESSION_ID);
            assertThat(runtimeRequest.get().projectContext())
                    .isEqualTo(new ProjectRuntimeContext(USER_ID, PROJECT_ID));
            assertThat(runtimeRequest.get().toolPolicy().allowedTools()).containsExactly("project_read_file");
            verify(projects, atLeast(3)).manifest(USER_ID, PROJECT_ID);
            verify(plans, never()).saveAndFlush(any(AgentPlan.class));
        } finally {
            planService.shutdownPlanExecutor();
        }
    }

    @Test
    void twoTruncatedPlannerAttemptsRemainBoundedDiagnosticAndNeverPersist() {
        ObjectMapper json = new ObjectMapper();
        AgentPlanRepository plans = mock(AgentPlanRepository.class);
        AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentPlanEventRepository events = mock(AgentPlanEventRepository.class);
        AgentService agent = mock(AgentService.class);
        ProjectService projects = mock(ProjectService.class);
        UserSettingsService settings = mock(UserSettingsService.class);
        SkillsService skills = mock(SkillsService.class);
        AgentToolPolicyEngine policy = mock(AgentToolPolicyEngine.class);
        ChatModelProvider modelProvider = mock(ChatModelProvider.class);

        AgentSession session = new AgentSession(
                USER_ID, "Project Plan", "deepseek", "deepseek-v4-flash", 3, true);
        when(agent.getOwnedSession(USER_ID, SESSION_ID)).thenReturn(session);
        when(projects.manifest(USER_ID, PROJECT_ID))
                .thenReturn(new ProjectManifestResponse(PROJECT_ID, "manifest-v1", List.of()));
        when(settings.resolveModelEndpoint(anyLong(), any(), any())).thenReturn(
                new UserSettingsService.ModelEndpoint(
                        "deepseek", "deepseek-v4-flash", null, "test-key", "builtin", "DeepSeek"));
        when(policy.decideProject(any(), any())).thenReturn(
                new AgentToolPolicyEngine.Decision(List.of("project_read_file"), 6, 1, "project_read_only"));
        String raw = "{\"summary\":\"Inspect Project\",\"steps\":[{\"id\":\"s1\",\"description\":\"truncated";
        when(modelProvider.chat(any(ChatRequest.class)))
                .thenReturn(new ChatResponse(com.yanban.core.model.ChatMessage.assistant(raw), "length", null))
                .thenReturn(new ChatResponse(com.yanban.core.model.ChatMessage.assistant(raw), "length", null));

        PlanningAgentPlanner planner = new PlanningAgentPlanner(modelProvider, json);
        AtomicReference<PlanAgentService> serviceRef = new AtomicReference<>();
        AtomicReference<AgentRuntimeRequest> runtimeRequest = new AtomicReference<>();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override
            public boolean supports(AgentStrategy strategy) {
                return strategy == AgentStrategy.PLAN_EXECUTE;
            }

            @Override
            public AgentRuntimeResult run(AgentRuntimeRequest request) {
                runtimeRequest.set(request);
                return new PlanRuntimeAdapter(serviceRef.get()).run(request);
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter));
        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(runtime, new AgentStrategySelector());
        PlanAgentService planService = new PlanAgentService(
                plans, steps, events, agent, runtime, coordinator, planner, mock(PlanStepVerifier.class),
                settings, skills, policy, json, projects);
        serviceRef.set(planService);
        ProjectAgentRuntimeService facade = new ProjectAgentRuntimeService(projects, agent, planService);

        try {
            assertThatThrownBy(() -> facade.createPlan(USER_ID, PROJECT_ID, SESSION_ID,
                    new CreateAgentPlanRequest("inspect project", true, null, false)))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(exception.getReason())
                                .contains("Project Plan creation failed", "traceId=plan-create-",
                                        "INVALID_PLAN", "after one bounded retry")
                                .doesNotContain(raw);
                        assertThat(exception.getReason().length()).isLessThan(900);
                    });

            verify(modelProvider, times(2)).chat(any(ChatRequest.class));
            verify(plans, never()).saveAndFlush(any(AgentPlan.class));
            verify(steps, never()).save(any());
            assertThat(runtimeRequest.get()).isNotNull();
            assertThat(runtimeRequest.get().projectContext())
                    .isEqualTo(new ProjectRuntimeContext(USER_ID, PROJECT_ID));
            assertThat(runtimeRequest.get().toolPolicy().allowedTools()).containsExactly("project_read_file");
        } finally {
            planService.shutdownPlanExecutor();
        }
    }

    private ChatModelProvider failingModelProvider() {
        return new ChatModelProvider() {
            @Override
            public String providerName() {
                return "failing-test-provider";
            }

            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("provider connection refused");
            }

            @Override
            public Flux<ChatChunk> streamChat(ChatRequest request) {
                return Flux.error(new IllegalStateException("provider connection refused"));
            }
        };
    }
}
