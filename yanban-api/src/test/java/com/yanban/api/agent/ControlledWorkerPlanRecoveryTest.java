package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.test.util.ReflectionTestUtils;

class ControlledWorkerPlanRecoveryTest {

    @Test
    void asyncExecutionRecoversDispatchFromPersistenceAfterOriginalRequestIsDiscarded() {
        Fixture fixture = new Fixture();
        fixture.createControlledPlan();

        AgentPlanResponse response = fixture.service.executePlanAsync(Fixture.USER_ID, Fixture.PLAN_ID);

        assertThat(response.id()).isEqualTo(Fixture.PLAN_ID);
        assertThat(fixture.storedPlan.get().getStatus()).isEqualTo("COMPLETED");
        assertThat(fixture.storedSteps).singleElement().satisfies(step ->
                assertThat(step.getStatus()).isEqualTo("DEGRADED"));
        verify(fixture.controlledExecutor).executeWithinPlan(any());
    }

    @Test
    void failedRetryReusesTheSamePlanIdAndRecoversFreshAuthority() {
        Fixture fixture = new Fixture();
        fixture.createControlledPlan();
        fixture.storedSteps.get(0).markFailed("simulated interruption");
        fixture.storedPlan.get().markFailed("simulated interruption");

        AgentPlanResponse response = fixture.service.retryPlan(Fixture.USER_ID, Fixture.PLAN_ID);

        assertThat(response.id()).isEqualTo(Fixture.PLAN_ID);
        assertThat(fixture.storedPlan.get().getStatus()).isEqualTo("COMPLETED");
        assertThat(fixture.storedEvents).extracting(AgentPlanEvent::getEventType)
                .contains("plan_retry_queued", "plan_started", "plan_completed");
        verify(fixture.controlledExecutor).executeWithinPlan(any());
    }

    @Test
    void changedVersionOrHashFailsClosedBeforeWorkerExecution() {
        Fixture changedVersion = new Fixture();
        changedVersion.createControlledPlan();
        changedVersion.manifest.set(new ProjectManifestResponse(Fixture.PROJECT_ID, "d".repeat(64),
                changedVersion.manifest.get().files()));

        assertThatThrownBy(() -> changedVersion.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");
        verify(changedVersion.controlledExecutor, never()).executeWithinPlan(any());

        Fixture changedHash = new Fixture();
        changedHash.createControlledPlan();
        changedHash.manifest.set(new ProjectManifestResponse(Fixture.PROJECT_ID, Fixture.VERSION, List.of(
                Fixture.file("paper/main.tex", 1200, "e".repeat(64)),
                Fixture.file("src/Main.java", 900, Fixture.CODE_HASH))));

        assertThatThrownBy(() -> changedHash.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("current manifest");
        verify(changedHash.controlledExecutor, never()).executeWithinPlan(any());
    }

    @Test
    void revokedToolOrTamperedEnvelopeFailsClosedBeforeWorkerExecution() throws Exception {
        Fixture revoked = new Fixture();
        revoked.createControlledPlan();
        revoked.currentTools.set(List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE));

        assertThatThrownBy(() -> revoked.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("revoked");
        verify(revoked.controlledExecutor, never()).executeWithinPlan(any());

        Fixture tampered = new Fixture();
        tampered.createControlledPlan();
        ObjectNode root = (ObjectNode) tampered.json.readTree(tampered.storedPlan.get().getRawPlanJson());
        ObjectNode controlled = (ObjectNode) root.path("controlledPlanEnvelope");
        controlled.put("parentMaxTokens", controlled.path("parentMaxTokens").asInt() + 1);
        ReflectionTestUtils.setField(tampered.storedPlan.get(), "rawPlanJson", tampered.json.writeValueAsString(root));

        assertThatThrownBy(() -> tampered.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(tampered.controlledExecutor, never()).executeWithinPlan(any());
    }

    @Test
    void ordinaryProjectPlanWithSameStepKeyDoesNotInvokeControlledExecutor() {
        Fixture fixture = new Fixture();
        fixture.createControlledPlan();
        ReflectionTestUtils.setField(fixture.storedPlan.get(), "rawPlanJson",
                ProjectPlanEnvelope.wrap(fixture.json, "{}",
                        new ProjectRuntimeContext(Fixture.USER_ID, Fixture.PROJECT_ID)));
        ReflectionTestUtils.setField(fixture.storedSteps.get(0), "successCriteria",
                "Ordinary planner-owned success criteria.");
        clearInvocations(fixture.controlledExecutor, fixture.agentRuntimeService);

        AgentPlanResponse response = fixture.service.executePlan(Fixture.USER_ID, Fixture.PLAN_ID);

        assertThat(response.status()).isEqualTo("FAILED");
        verify(fixture.agentRuntimeService, atLeastOnce()).run(any());
        verify(fixture.controlledExecutor, never()).executeWithinPlan(any());
    }

    @Test
    void missingEnvelopeFailsClosedBeforeRetryResetsPersistedState() {
        Fixture fixture = new Fixture();
        fixture.createControlledPlan();
        ReflectionTestUtils.setField(fixture.storedPlan.get(), "rawPlanJson",
                ProjectPlanEnvelope.wrap(fixture.json, "{}",
                        new ProjectRuntimeContext(Fixture.USER_ID, Fixture.PROJECT_ID)));
        fixture.storedSteps.get(0).markFailed("interrupted");
        fixture.storedPlan.get().markFailed("interrupted");
        clearInvocations(fixture.controlledExecutor, fixture.agentRuntimeService);

        assertThatThrownBy(() -> fixture.service.retryPlan(Fixture.USER_ID, Fixture.PLAN_ID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("envelope is missing");
        assertThat(fixture.storedPlan.get().getStatus()).isEqualTo("FAILED");
        assertThat(fixture.storedSteps.get(0).getStatus()).isEqualTo("FAILED");
        verify(fixture.agentRuntimeService, never()).run(any());
        verify(fixture.controlledExecutor, never()).executeWithinPlan(any());
    }

    private static final class Fixture {
        private static final long USER_ID = 7L;
        private static final long SESSION_ID = 9L;
        private static final long PROJECT_ID = 21L;
        private static final long PLAN_ID = 19L;
        private static final String VERSION = "a".repeat(64);
        private static final String PAPER_HASH = "b".repeat(64);
        private static final String CODE_HASH = "c".repeat(64);

        private final ObjectMapper json = new ObjectMapper();
        private final AgentPlanRepository plans = mock(AgentPlanRepository.class);
        private final AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        private final AgentPlanEventRepository events = mock(AgentPlanEventRepository.class);
        private final AtomicReference<AgentPlan> storedPlan = new AtomicReference<>();
        private final List<AgentPlanStep> storedSteps = new ArrayList<>();
        private final List<AgentPlanEvent> storedEvents = new ArrayList<>();
        private final AtomicReference<ProjectManifestResponse> manifest = new AtomicReference<>(
                new ProjectManifestResponse(PROJECT_ID, VERSION, List.of(
                        file("paper/main.tex", 1200, PAPER_HASH),
                        file("src/Main.java", 900, CODE_HASH))));
        private final AtomicReference<List<String>> currentTools = new AtomicReference<>(List.of(
                ResearchToolContracts.PROJECT_LATEX_OUTLINE, ResearchToolContracts.PROJECT_CODE_SYMBOLS));
        private final ProjectService projects = mock(ProjectService.class);
        private final AgentRuntimeService agentRuntimeService = mock(AgentRuntimeService.class);
        private final ControlledReadOnlyWorkerRuntimeAdapter controlledExecutor =
                mock(ControlledReadOnlyWorkerRuntimeAdapter.class);
        private final PlanningAgentPlanner planner = mock(PlanningAgentPlanner.class);
        private final SynchronousAsyncPlanService service;

        private Fixture() {
            when(plans.saveAndFlush(any(AgentPlan.class))).thenAnswer(invocation -> {
                AgentPlan value = invocation.getArgument(0);
                if (value.getId() == null) ReflectionTestUtils.setField(value, "id", PLAN_ID);
                storedPlan.set(value);
                return value;
            });
            when(plans.findByIdAndUserId(PLAN_ID, USER_ID)).thenAnswer(invocation ->
                    Optional.ofNullable(storedPlan.get()));
            when(steps.save(any(AgentPlanStep.class))).thenAnswer(invocation -> saveStep(invocation.getArgument(0)));
            when(steps.saveAndFlush(any(AgentPlanStep.class))).thenAnswer(invocation -> saveStep(invocation.getArgument(0)));
            when(steps.findByPlanIdOrderBySortOrderAsc(PLAN_ID)).thenAnswer(invocation -> List.copyOf(storedSteps));
            when(events.save(any(AgentPlanEvent.class))).thenAnswer(invocation -> {
                AgentPlanEvent event = invocation.getArgument(0);
                storedEvents.add(event);
                return event;
            });
            when(events.findByPlanIdOrderByCreatedAtAsc(PLAN_ID)).thenAnswer(invocation -> List.copyOf(storedEvents));
            when(projects.manifest(USER_ID, PROJECT_ID)).thenAnswer(invocation -> manifest.get());

            AgentSession session = new AgentSession(USER_ID, "Project", "test", "model", 9, true);
            ReflectionTestUtils.setField(session, "id", SESSION_ID);
            AgentService agentService = mock(AgentService.class);
            when(agentService.getOwnedSession(USER_ID, SESSION_ID)).thenReturn(session);
            AgentToolPolicyEngine policy = mock(AgentToolPolicyEngine.class);
            when(policy.decideProject(null, null)).thenAnswer(invocation ->
                    new AgentToolPolicyEngine.Decision(currentTools.get(), 6, 1, "current"));
            UserSettingsService settings = mock(UserSettingsService.class);
            when(settings.resolveModelEndpoint(USER_ID, "test", "model")).thenReturn(
                    new UserSettingsService.ModelEndpoint("test", "model", null, "key", "builtin", "url"));
            when(controlledExecutor.executeWithinPlan(any())).thenAnswer(invocation -> {
                AgentRuntimeRequest request = invocation.getArgument(0);
                return new AgentRuntimeResult(true, "Recovered canonical answer", List.of(), 2,
                        null, List.of(), List.of("candidate=NOT_APPLIED"), null, null, null)
                        .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.PLAN_PARTIAL,
                                "PARTIAL", true, AgentStrategy.PLAN_EXECUTE)
                        .withPlanId(request.planId());
            });
            when(agentRuntimeService.run(any())).thenReturn(new AgentRuntimeResult(false, null, List.of(), 1,
                    "ordinary runtime failed", List.of(), List.of(), null, null, null));
            service = new SynchronousAsyncPlanService(plans, steps, events, agentService, agentRuntimeService,
                    planner, mock(PlanStepVerifier.class), settings, mock(SkillsService.class), policy,
                    json, projects, controlledExecutor);
        }

        private AgentPlanResponse createControlledPlan() {
            AgentRuntimeRequest request = request();
            ControlledWorkerDispatch dispatch = new ControlledWorkerDispatchPlanner(projects)
                    .plan(request, AgentRequestCapability.PROJECT_READ).orElseThrow();
            return service.createPlanWithinAdapter(request.withControlledWorkerDispatch(dispatch));
        }

        private AgentRuntimeRequest request() {
            List<String> tools = List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                    ResearchToolContracts.PROJECT_CODE_SYMBOLS);
            AgentOrchestrationRequirements orchestration = new AgentOrchestrationRequirements(
                    List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK,
                            AgentStrategySignal.MATERIAL_PAPER_LATEX, AgentStrategySignal.MATERIAL_CODE),
                    List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN),
                    List.of(requirement(ResearchMaterialKind.PAPER_LATEX,
                                    ResearchToolContracts.PROJECT_LATEX_OUTLINE),
                            requirement(ResearchMaterialKind.CODE, ResearchToolContracts.PROJECT_CODE_SYMBOLS)),
                    AgentStrategySelectionOrigin.SERVER_AUTO, List.of());
            return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, SESSION_ID, List.of(), USER_ID,
                    "Compare the paper and code.", "test", "model", 0.0, 3000, 9, true,
                    null, "key", "url", null, AgentRuntimeMode.LANGCHAIN4J,
                    AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                    new ResolvedToolPolicy(tools, 6, 1, "test"), 6, 1,
                    "trace", null, null).withProjectContext(new ProjectRuntimeContext(USER_ID, PROJECT_ID))
                    .withOrchestrationRequirements(orchestration);
        }

        private AgentPlanStep saveStep(AgentPlanStep value) {
            if (value.getId() == null) ReflectionTestUtils.setField(value, "id", (long) storedSteps.size() + 31);
            if (!storedSteps.contains(value)) storedSteps.add(value);
            return value;
        }

        private static ResearchMaterialRequirement requirement(ResearchMaterialKind kind, String tool) {
            return new ResearchMaterialRequirement(kind, List.of(tool), List.of(tool), true);
        }

        private static ProjectFileEntry file(String path, long size, String hash) {
            return new ProjectFileEntry(path, size, Instant.EPOCH, hash);
        }
    }

    private static final class SynchronousAsyncPlanService extends PlanAgentService {
        private SynchronousAsyncPlanService(AgentPlanRepository plans, AgentPlanStepRepository steps,
                                            AgentPlanEventRepository events, AgentService agentService,
                                            AgentRuntimeService runtimeService, PlanningAgentPlanner planner,
                                            PlanStepVerifier verifier, UserSettingsService settings,
                                            SkillsService skills, AgentToolPolicyEngine policy, ObjectMapper json,
                                            ProjectService projects,
                                            ControlledReadOnlyWorkerRuntimeAdapter controlledExecutor) {
            super(plans, steps, events, agentService, runtimeService, null, planner, verifier, settings, skills,
                    policy, json, projects, controlledExecutor);
        }

        @Override
        void submitAsyncExecutionTask(Long userId, Long planId, String traceId) {
            executePlan(userId, planId);
        }
    }
}
