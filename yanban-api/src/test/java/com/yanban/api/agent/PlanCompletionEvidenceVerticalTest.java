package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.yanban.core.model.ChatMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PlanCompletionEvidenceVerticalTest {
    private static final String FILE_HASH = "a".repeat(64);
    private static final String PROJECT_VERSION = "b".repeat(64);
    @Test
    void realPlanServiceWritesAndRestoresStepEvidenceForOuterVerifier() throws Exception {
        Fixture fixture = new Fixture(FILE_HASH);
        fixture.innerRuntimeResult = toolResult(FILE_HASH);

        AgentRuntimeResult result = fixture.outerRuntime.run(fixture.request());

        assertThat(fixture.events).anyMatch(event -> "step_project_evidence".equals(event.getEventType()));
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
        fixture.shutdown();
    }

    @Test
    void realPlanServiceRestoresNoEventOrOldHashAsInsufficient() throws Exception {
        Fixture missing = new Fixture(FILE_HASH);
        missing.plan.markCompleted();
        AgentRuntimeResult noEvent = missing.outerRuntime.run(missing.request());
        assertThat(noEvent.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        missing.shutdown();

        Fixture old = new Fixture(FILE_HASH);
        old.plan.markCompleted();
        old.events.add(new AgentPlanEvent(19L, 1L, "step_project_evidence", old.json.writeValueAsString(java.util.Map.of(
                "evidence", List.of(new EvidenceRef("trusted-plan:42:src/Main.java:old:step", EvidenceSourceType.PROJECT,
                        "PROJECT", "src/Main.java", "step", null, "old", "test"))))));
        AgentRuntimeResult oldHash = old.outerRuntime.run(old.request());
        assertThat(oldHash.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        old.shutdown();
    }

    private static AgentRuntimeResult toolResult(String hash) {
        String tool = "{\"projectId\":42,\"projectVersion\":\"" + PROJECT_VERSION
                + "\",\"relativePath\":\"src/Main.java\",\"hash\":\"" + hash
                + "\",\"startLine\":2,\"endLine\":4,\"evidenceRefs\":[\"project:42:src/Main.java:" + hash + ":c1\"]}";
        return new AgentRuntimeResult(true, "done", List.of(new ChatMessage("tool", tool, null, "c1"),
                ChatMessage.assistant("done")), 1, null, List.of(), List.of(), null, null, null);
    }

    private static final class Fixture {
        final ObjectMapper json = new ObjectMapper();
        final AgentPlanRepository plans = mock(AgentPlanRepository.class);
        final AgentPlanStepRepository steps = mock(AgentPlanStepRepository.class);
        final AgentPlanEventRepository eventRepository = mock(AgentPlanEventRepository.class);
        final ProjectService projects = mock(ProjectService.class);
        final List<AgentPlanEvent> events = new ArrayList<>();
        final AgentPlan plan;
        final AgentPlanStep step;
        final AgentRuntimeService innerRuntime = mock(AgentRuntimeService.class);
        final PlanAgentService planService;
        final AgentRuntimeService outerRuntime;
        AgentRuntimeResult innerRuntimeResult;

        Fixture(String currentHash) throws Exception {
            plan = new AgentPlan(7L, 7L, "inspect", "inspect", true, null,
                    ProjectPlanEnvelope.wrap(json, "{}", new ProjectRuntimeContext(7L, 42L)));
            ReflectionTestUtils.setField(plan, "id", 19L);
            step = new AgentPlanStep(19L, "read", 1, "read", "read", "ANALYSIS", "[]", "[\"project_read_file\"]", "read");
            ReflectionTestUtils.setField(step, "id", 1L);
            AgentSession session = new AgentSession(7L, "s", "test", "model", 3, true);
            ReflectionTestUtils.setField(session, "id", 7L);
            AgentService agent = mock(AgentService.class);
            UserSettingsService settings = mock(UserSettingsService.class);
            AgentToolPolicyEngine policy = mock(AgentToolPolicyEngine.class);
            PlanStepVerifier verifier = mock(PlanStepVerifier.class);
            when(plans.findByIdAndUserId(19L, 7L)).thenReturn(Optional.of(plan));
            when(steps.findByPlanIdOrderBySortOrderAsc(19L)).thenReturn(List.of(step));
            when(eventRepository.findByPlanIdOrderByCreatedAtAsc(19L)).thenAnswer(call -> List.copyOf(events));
            when(eventRepository.save(any())).thenAnswer(call -> { events.add(call.getArgument(0)); return call.getArgument(0); });
            when(plans.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
            when(steps.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
            when(agent.getOwnedSession(7L, 7L)).thenReturn(session);
            when(settings.resolveModelEndpoint(anyLong(), any(), any())).thenReturn(
                    new UserSettingsService.ModelEndpoint("test", "model", null, "key", "builtin", "url"));
            when(policy.decideProject(any(), any())).thenReturn(new AgentToolPolicyEngine.Decision(List.of("project_read_file"), 3, 1, "project"));
            when(projects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(42L, PROJECT_VERSION, List.of(
                    new ProjectFileEntry("src/Main.java", 1, Instant.EPOCH, currentHash))));
            when(verifier.verify(any())).thenReturn(PlanStepVerifier.VerificationResult.passed("ok"));
            when(innerRuntime.run(any())).thenAnswer(call -> innerRuntimeResult);
            planService = new PlanAgentService(plans, steps, eventRepository, agent, innerRuntime, null,
                    mock(PlanningAgentPlanner.class), verifier, settings, mock(SkillsService.class), policy, json, projects);
            CompletionVerifier completion = new CompletionVerifier(json, new ProjectEvidenceValidator(projects),
                    mock(CandidateChangeArtifactService.class));
            outerRuntime = new AgentRuntimeService(List.of(new PlanRuntimeAdapter(planService)), completion,
                    new CompletionReflection(), new AdapterCompletionRepairExecutor());
        }

        AgentRuntimeRequest request() {
            return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 7L, List.of(), 7L, "inspect", "test", "model",
                    null, null, 1, true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                    AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                    new ResolvedToolPolicy(List.of("project_read_file"), 3, 1, "project"), null, null, "trace", null, null)
                    .withPlanId(19L).withProjectContext(new ProjectRuntimeContext(7L, 42L));
        }

        void shutdown() { planService.shutdownPlanExecutor(); }
    }
}
