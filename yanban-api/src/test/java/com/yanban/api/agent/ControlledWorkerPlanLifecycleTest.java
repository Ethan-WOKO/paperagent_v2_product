package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.worker.ControlledReadOnlyWorkerRuntimeAdapter;
import com.yanban.api.agent.worker.ControlledWorkerDispatch;
import com.yanban.api.agent.worker.ControlledWorkerDispatchPlanner;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.api.settings.UserSettingsService;
import com.yanban.api.skills.SkillsService;
import com.yanban.core.agent.AgentPlan;
import com.yanban.core.agent.AgentPlanEvent;
import com.yanban.core.agent.AgentPlanEventRepository;
import com.yanban.core.agent.AgentPlanRepository;
import com.yanban.core.agent.AgentPlanStep;
import com.yanban.core.agent.AgentPlanStepRepository;
import com.yanban.core.agent.AgentSession;
import com.yanban.core.research.ResearchToolContracts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ControlledWorkerPlanLifecycleTest {

    private static final long USER_ID = 7L;
    private static final long SESSION_ID = 9L;
    private static final long PROJECT_ID = 21L;
    private static final long PLAN_ID = 19L;
    private static final String VERSION = "a".repeat(64);
    private static final String PAPER_HASH = "b".repeat(64);
    private static final String CODE_HASH = "c".repeat(64);

    @Test
    void serverAutoControlledWorkersExecuteInsideOnePersistedParentPlanLifecycle() {
        ObjectMapper json = new ObjectMapper();
        AgentPlanRepository plans = mock(AgentPlanRepository.class);
        AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        AgentPlanEventRepository events = mock(AgentPlanEventRepository.class);
        AtomicReference<AgentPlan> storedPlan = new AtomicReference<>();
        List<AgentPlanStep> storedSteps = new ArrayList<>();
        List<AgentPlanEvent> storedEvents = new ArrayList<>();
        when(plans.saveAndFlush(any(AgentPlan.class))).thenAnswer(invocation -> {
            AgentPlan value = invocation.getArgument(0);
            if (value.getId() == null) ReflectionTestUtils.setField(value, "id", PLAN_ID);
            storedPlan.set(value);
            return value;
        });
        when(plans.findByIdAndUserId(PLAN_ID, USER_ID)).thenAnswer(invocation ->
                Optional.ofNullable(storedPlan.get()));
        when(steps.save(any(AgentPlanStep.class))).thenAnswer(invocation -> saveStep(invocation.getArgument(0), storedSteps));
        when(steps.saveAndFlush(any(AgentPlanStep.class))).thenAnswer(invocation -> saveStep(invocation.getArgument(0), storedSteps));
        when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenAnswer(invocation -> List.copyOf(storedSteps));
        when(events.save(any(AgentPlanEvent.class))).thenAnswer(invocation -> {
            AgentPlanEvent event = invocation.getArgument(0);
            storedEvents.add(event);
            return event;
        });
        when(events.findByPlanIdOrderByCreatedAtAsc(PLAN_ID)).thenAnswer(invocation -> List.copyOf(storedEvents));

        AgentSession session = new AgentSession(USER_ID, "Project", "test", "model", 9, true);
        ReflectionTestUtils.setField(session, "id", SESSION_ID);
        AgentService agentService = mock(AgentService.class);
        when(agentService.getOwnedSession(USER_ID, SESSION_ID)).thenReturn(session);
        ProjectService projects = mock(ProjectService.class);
        ProjectManifestResponse manifest = new ProjectManifestResponse(PROJECT_ID, VERSION, List.of(
                new ProjectFileEntry("paper/main.tex", 1200, Instant.EPOCH, PAPER_HASH),
                new ProjectFileEntry("src/Main.java", 900, Instant.EPOCH, CODE_HASH)));
        when(projects.manifest(USER_ID, PROJECT_ID)).thenReturn(manifest);
        AgentToolPolicyEngine policy = mock(AgentToolPolicyEngine.class);
        List<String> tools = List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                ResearchToolContracts.PROJECT_CODE_SYMBOLS,
                "project_read_file",
                "project_search");
        when(policy.decideProject(null, null)).thenReturn(new AgentToolPolicyEngine.Decision(tools, 6, 1, "test"));
        PlanningAgentPlanner planner = mock(PlanningAgentPlanner.class);
        ControlledReadOnlyWorkerRuntimeAdapter controlledExecutor = mock(ControlledReadOnlyWorkerRuntimeAdapter.class);
        when(controlledExecutor.executeWithinPlan(any())).thenAnswer(invocation -> {
            AgentRuntimeRequest execution = invocation.getArgument(0);
            EvidenceLedger evidence = new EvidenceLedger(List.of(
                    controlledEvidence("paper/main.tex", PAPER_HASH, "paper"),
                    controlledEvidence("src/Main.java", CODE_HASH, "code")));
            return new AgentRuntimeResult(true, "Canonical controlled answer", List.of(), 3,
                    null, List.of(), List.of("candidate=NOT_APPLIED"), null, null, null)
                    .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.PLAN_PARTIAL,
                            "PARTIAL", true, AgentStrategy.PLAN_EXECUTE)
                    .withTrustedEvidenceLedger(evidence)
                    .withPlanId(execution.planId());
        });
        UserSettingsService settings = mock(UserSettingsService.class);
        when(settings.resolveModelEndpoint(USER_ID, "test", "model")).thenReturn(
                new UserSettingsService.ModelEndpoint("test", "model", null, "key", "builtin", "url"));
        PlanAgentService planService = new PlanAgentService(plans, steps, events, agentService,
                mock(AgentRuntimeService.class), null, planner, mock(PlanStepVerifier.class),
                settings, mock(SkillsService.class), policy, json, projects,
                controlledExecutor);
        AgentRuntimeRequest request = request(tools);
        ControlledWorkerDispatch dispatch = new ControlledWorkerDispatchPlanner(projects)
                .plan(request, AgentRequestCapability.PROJECT_READ).orElseThrow();

        AgentPlanResponse created = planService.createPlanWithinAdapter(
                request.withControlledWorkerDispatch(dispatch));
        assertThat(created.id()).isEqualTo(PLAN_ID);
        AgentPlanResponse result = planService.executePlan(USER_ID, PLAN_ID);

        assertThat(result.id()).isEqualTo(PLAN_ID);
        assertThat(result.executionOutcome()).isEqualTo("PARTIAL");
        assertThat(result.finalAnswer()).contains("Canonical controlled answer", "projectEvidenceRefs=");
        assertThat(storedPlan.get().getStatus()).isEqualTo("COMPLETED");
        assertThat(storedSteps).singleElement().satisfies(step -> {
            assertThat(step.getPlanId()).isEqualTo(PLAN_ID);
            assertThat(step.getStatus()).isEqualTo("DEGRADED");
            assertThat(step.getResult()).contains("Canonical controlled answer", "projectEvidenceRefs=");
            assertThat(step.getAllowedToolsJson())
                    .contains(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                            ResearchToolContracts.PROJECT_CODE_SYMBOLS)
                    .doesNotContain("project_read_file", "project_search");
        });
        assertThat(storedEvents).extracting(AgentPlanEvent::getEventType)
                .contains("plan_created", "plan_started", "step_started", "step_project_evidence",
                        "step_degraded_after_controlled_stop", "plan_completed");
        assertThat(storedEvents).allMatch(event -> event.getPlanId().equals(PLAN_ID));
        ArgumentCaptor<AgentRuntimeRequest> executed = ArgumentCaptor.forClass(AgentRuntimeRequest.class);
        verify(controlledExecutor).executeWithinPlan(executed.capture());
        assertThat(executed.getValue().planId()).isEqualTo(PLAN_ID);
        assertThat(executed.getValue().strategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(executed.getValue().controlledWorkerDispatch().parentRunId()).isEqualTo("AGENT_PLAN:19");
        verifyNoInteractions(planner);
    }

    private AgentRuntimeRequest request(List<String> tools) {
        AgentOrchestrationRequirements orchestration = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK,
                        AgentStrategySignal.MATERIAL_PAPER_LATEX, AgentStrategySignal.MATERIAL_CODE),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN),
                List.of(requirement(ResearchMaterialKind.PAPER_LATEX, ResearchToolContracts.PROJECT_LATEX_OUTLINE),
                        requirement(ResearchMaterialKind.CODE, ResearchToolContracts.PROJECT_CODE_SYMBOLS)),
                AgentStrategySelectionOrigin.SERVER_AUTO, List.of());
        return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, SESSION_ID, List.of(), USER_ID,
                "Compare the paper and code.", "test", "model", 0.0, 3000, 9, true,
                null, "key", "url", null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(tools, 6, 1, "test"), 6, 1, "trace-1", null, null)
                .withProjectContext(new ProjectRuntimeContext(USER_ID, PROJECT_ID))
                .withOrchestrationRequirements(orchestration);
    }

    private ResearchMaterialRequirement requirement(ResearchMaterialKind kind, String tool) {
        return new ResearchMaterialRequirement(kind, List.of(tool), List.of(tool), true);
    }

    private EvidenceRef controlledEvidence(String path, String hash, String suffix) {
        return new EvidenceRef("controlled-worker:21:AGENT_PLAN:19:" + suffix,
                EvidenceSourceType.PROJECT, "PROJECT", path, "worker", null, hash,
                "controlled test evidence", VERSION, hash, 1, 2, "worker-test@1",
                EvidenceVersionStatus.VERIFIED);
    }

    private AgentPlanStep saveStep(AgentPlanStep value, List<AgentPlanStep> stored) {
        if (value.getId() == null) ReflectionTestUtils.setField(value, "id", 101L);
        if (!stored.contains(value)) stored.add(value);
        return value;
    }
}
