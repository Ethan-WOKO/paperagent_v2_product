package com.yanban.api.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yanban.api.agent.AgentOrchestrationRequirements;
import com.yanban.api.agent.AgentRequestCapability;
import com.yanban.api.agent.AgentRuntimeMode;
import com.yanban.api.agent.AgentRuntimeRequest;
import com.yanban.api.agent.AgentStrategy;
import com.yanban.api.agent.AgentStrategyReasonCode;
import com.yanban.api.agent.AgentStrategySelectionOrigin;
import com.yanban.api.agent.AgentStrategySignal;
import com.yanban.api.agent.AgentToolCallingMode;
import com.yanban.api.agent.ProjectRuntimeContext;
import com.yanban.api.agent.ResearchMaterialKind;
import com.yanban.api.agent.ResearchMaterialRequirement;
import com.yanban.api.agent.ResolvedToolPolicy;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.research.ResearchToolContracts;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ControlledWorkerDispatchPlannerTest {

    private static final String VERSION = "a".repeat(64);
    private static final String PAPER_HASH = "b".repeat(64);
    private static final String CODE_HASH = "c".repeat(64);

    @Test
    void createsExactlyTwoDisjointTasksAndConservesParentBudgets() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 21L)).thenReturn(manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH),
                file("src/Main.java", 900, CODE_HASH),
                file("config/ignored.yaml", 100, "d".repeat(64)))));
        ControlledWorkerDispatchPlanner planner = new ControlledWorkerDispatchPlanner(projects);
        AgentRuntimeRequest request = request(AgentStrategy.PLAN_EXECUTE, List.of(
                paperRequirement(), codeRequirement()));

        ControlledWorkerDispatch dispatch = planner.plan(request, AgentRequestCapability.PROJECT_READ).orElseThrow();

        assertThat(dispatch.tasks()).hasSize(2);
        assertThat(dispatch.tasks().get(0).attestation().packet().materialScope())
                .extracting(path -> path.value()).containsExactly("paper/main.tex");
        assertThat(dispatch.tasks().get(1).attestation().packet().materialScope())
                .extracting(path -> path.value()).containsExactly("src/Main.java");
        assertThat(dispatch.tasks()).flatExtracting(task -> task.attestation().packet().allowedReadTools())
                .containsExactlyInAnyOrder(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                        ResearchToolContracts.PROJECT_CODE_SYMBOLS);
        assertThat(dispatch.tasks().stream().mapToInt(task ->
                task.attestation().packet().budget().maxToolCalls()).sum())
                .isEqualTo(request.toolPolicy().maxToolCalls());
        assertThat(dispatch.tasks().stream().mapToInt(ControlledWorkerDispatch.Task::maxSteps).sum() + 1)
                .isEqualTo(request.maxSteps());
        assertThat(dispatch.tasks().stream().mapToInt(ControlledWorkerDispatch.Task::maxTokens).sum()
                + dispatch.parentSynthesisMaxTokens()).isEqualTo(request.maxTokens());
        assertThat(dispatch.fileSizes()).hasSize(2).doesNotContainKey(
                com.yanban.core.research.ProjectRelativePath.of("config/ignored.yaml"));
        dispatch.validateAgainst(request.withControlledWorkerDispatch(dispatch));
        assertThat(dispatch.fileSizes().keySet()).allMatch(path ->
                List.of("paper/main.tex", "src/Main.java").contains(path.value()));
    }

    @Test
    void llmRoutedCrossMaterialPlanKeepsTheExistingControlledTwoWorkerPathReachable() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 21L)).thenReturn(manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH), file("src/Main.java", 900, CODE_HASH))));
        ControlledWorkerDispatchPlanner planner = new ControlledWorkerDispatchPlanner(projects);
        AgentRuntimeRequest serverAuto = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement()));
        AgentOrchestrationRequirements llmRouted = new AgentOrchestrationRequirements(
                serverAuto.orchestrationRequirements().signals(),
                List.of(AgentStrategyReasonCode.LLM_ROUTER_PLAN),
                serverAuto.orchestrationRequirements().materialRequirements(),
                AgentStrategySelectionOrigin.LLM_ROUTER,
                serverAuto.orchestrationRequirements().consistencyChecks());

        ControlledWorkerDispatch dispatch = planner.plan(
                serverAuto.withOrchestrationRequirements(llmRouted),
                AgentRequestCapability.PROJECT_READ).orElseThrow();

        assertThat(dispatch.tasks()).hasSize(2);
        assertThat(dispatch.tasks()).flatExtracting(task -> task.attestation().packet().allowedReadTools())
                .containsExactlyInAnyOrder(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                        ResearchToolContracts.PROJECT_CODE_SYMBOLS);
    }

    @Test
    void deterministicRouterFallbackPlanUsesTheSameControlledEligibilityChecks() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 21L)).thenReturn(manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH), file("src/Main.java", 900, CODE_HASH))));
        ControlledWorkerDispatchPlanner planner = new ControlledWorkerDispatchPlanner(projects);
        AgentRuntimeRequest serverAuto = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement()));
        AgentOrchestrationRequirements fallback = new AgentOrchestrationRequirements(
                serverAuto.orchestrationRequirements().signals(),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN,
                        AgentStrategyReasonCode.LLM_ROUTER_MODEL_UNAVAILABLE,
                        AgentStrategyReasonCode.LLM_ROUTER_FALLBACK_PLAN),
                serverAuto.orchestrationRequirements().materialRequirements(),
                AgentStrategySelectionOrigin.ROUTER_FALLBACK,
                serverAuto.orchestrationRequirements().consistencyChecks());

        assertThat(planner.plan(serverAuto.withOrchestrationRequirements(fallback),
                AgentRequestCapability.PROJECT_READ)).isPresent();
    }

    @Test
    void explicitManifestBackedPathsNarrowBothWorkerAssignmentsWithoutGuessingAdditionalFiles() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 21L)).thenReturn(manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH),
                file("paper/other.tex", 800, "d".repeat(64)),
                file("src/Main.java", 900, CODE_HASH),
                file("src/Other.java", 700, "e".repeat(64)))));
        ControlledWorkerDispatchPlanner planner = new ControlledWorkerDispatchPlanner(projects);
        AgentRuntimeRequest request = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement()),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                        ResearchToolContracts.PROJECT_CODE_SYMBOLS), 6,
                "Compare paper/main.tex with src/Main.java.");

        ControlledWorkerDispatch dispatch = planner.plan(request,
                AgentRequestCapability.PROJECT_READ).orElseThrow();

        assertThat(dispatch.tasks().get(0).attestation().packet().materialScope())
                .extracting(path -> path.value()).containsExactly("paper/main.tex");
        assertThat(dispatch.tasks().get(1).attestation().packet().materialScope())
                .extracting(path -> path.value()).containsExactly("src/Main.java");
        assertThat(dispatch.fileSizes()).hasSize(2);
    }

    @Test
    void explicitScopeThatDoesNotNameBothMaterialSidesFallsBackToTheExistingPlanPath() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 21L)).thenReturn(manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH), file("src/Main.java", 900, CODE_HASH))));
        ControlledWorkerDispatchPlanner planner = new ControlledWorkerDispatchPlanner(projects);
        AgentRuntimeRequest request = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement()),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                        ResearchToolContracts.PROJECT_CODE_SYMBOLS), 6,
                "Inspect only paper/main.tex and compare the implementation generally.");

        assertThat(planner.plan(request, AgentRequestCapability.PROJECT_READ)).isEmpty();
    }

    @Test
    void persistedPlanBindingReattestsEveryTaskToTheCanonicalPlanRunIdentity() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 21L)).thenReturn(manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH), file("src/Main.java", 900, CODE_HASH))));
        AgentRuntimeRequest request = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement()));
        ControlledWorkerDispatch initial = new ControlledWorkerDispatchPlanner(projects)
                .plan(request, AgentRequestCapability.PROJECT_READ).orElseThrow();

        ControlledWorkerDispatch persisted = initial.bindToParentPlan(19L);
        AgentRuntimeRequest persistedRequest = request.withPlanId(19L)
                .withControlledWorkerDispatch(persisted);

        assertThat(persisted.parentRunId()).isEqualTo("AGENT_PLAN:19");
        assertThat(persisted.tasks()).allSatisfy(task -> {
            assertThat(task.attestation().packet().parentRunId()).isEqualTo("AGENT_PLAN:19");
            assertThat(task.attestation().packet().workerTaskId()).startsWith("AGENT_PLAN:19:");
        });
        persisted.validateAgainst(persistedRequest);
    }

    @Test
    void persistedEnvelopeReissuesTheSameBoundedDispatchForTheSamePlanIdentity() {
        ProjectService projects = mock(ProjectService.class);
        ProjectManifestResponse current = manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH), file("src/Main.java", 900, CODE_HASH)));
        when(projects.manifest(7L, 21L)).thenReturn(current);
        AgentRuntimeRequest original = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement()));
        ControlledWorkerDispatch dispatch = new ControlledWorkerDispatchPlanner(projects)
                .plan(original, AgentRequestCapability.PROJECT_READ).orElseThrow();
        ObjectMapper json = new ObjectMapper();
        ObjectNode envelope = ControlledPlanDispatchEnvelope.capture(json, dispatch);

        ControlledPlanDispatchEnvelope.Recovery recovery = ControlledPlanDispatchEnvelope.recover(
                json, envelope, original.withPlanId(19L), current);
        AgentRuntimeRequest recovered = recovery.attach(original.withPlanId(19L));

        assertThat(recovered.controlledWorkerDispatch().parentRunId()).isEqualTo("AGENT_PLAN:19");
        assertThat(recovered.controlledWorkerDispatch().projectVersion().value()).isEqualTo(VERSION);
        assertThat(recovered.controlledWorkerDispatch().fileHashes()).isEqualTo(dispatch.fileHashes());
        assertThat(recovered.maxSteps()).isEqualTo(original.maxSteps());
        assertThat(recovered.maxTokens()).isEqualTo(original.maxTokens());
        assertThat(recovered.toolPolicy().maxToolCalls()).isEqualTo(original.toolPolicy().maxToolCalls());
        assertThat(envelope.toString()).doesNotContain("key", "url", "C:\\");
    }

    @Test
    void recoveryFailsClosedWhenManifestVersionOrFileHashChanged() {
        ProjectService projects = mock(ProjectService.class);
        ProjectManifestResponse current = manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH), file("src/Main.java", 900, CODE_HASH)));
        when(projects.manifest(7L, 21L)).thenReturn(current);
        AgentRuntimeRequest request = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement())).withPlanId(19L);
        ControlledWorkerDispatch dispatch = new ControlledWorkerDispatchPlanner(projects)
                .plan(request.withPlanId(null), AgentRequestCapability.PROJECT_READ).orElseThrow();
        ObjectMapper json = new ObjectMapper();
        ObjectNode envelope = ControlledPlanDispatchEnvelope.capture(json, dispatch);

        ProjectManifestResponse changedVersion = new ProjectManifestResponse(21L, "d".repeat(64), current.files());
        ProjectManifestResponse changedHash = manifest(List.of(
                file("paper/main.tex", 1200, "e".repeat(64)), file("src/Main.java", 900, CODE_HASH)));
        assertThatThrownBy(() -> ControlledPlanDispatchEnvelope.recover(json, envelope, request, changedVersion))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stale");
        assertThatThrownBy(() -> ControlledPlanDispatchEnvelope.recover(json, envelope, request, changedHash))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("current manifest");
    }

    @Test
    void recoveryFailsClosedWhenARequiredToolWasRevokedOrEnvelopeWasTampered() {
        ProjectService projects = mock(ProjectService.class);
        ProjectManifestResponse current = manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH), file("src/Main.java", 900, CODE_HASH)));
        when(projects.manifest(7L, 21L)).thenReturn(current);
        AgentRuntimeRequest original = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement()));
        ControlledWorkerDispatch dispatch = new ControlledWorkerDispatchPlanner(projects)
                .plan(original, AgentRequestCapability.PROJECT_READ).orElseThrow();
        ObjectMapper json = new ObjectMapper();
        ObjectNode envelope = ControlledPlanDispatchEnvelope.capture(json, dispatch);
        AgentRuntimeRequest revoked = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement()),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), 6).withPlanId(19L);
        ObjectNode tampered = envelope.deepCopy();
        tampered.put("parentMaxTokens", envelope.path("parentMaxTokens").asInt() + 1);

        assertThatThrownBy(() -> ControlledPlanDispatchEnvelope.recover(json, envelope, revoked, current))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("revoked");
        assertThatThrownBy(() -> ControlledPlanDispatchEnvelope.recover(
                json, tampered, original.withPlanId(19L), current))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conserve");
    }

    @Test
    void ordinarySingleMaterialAndIncompleteToolCoverageStayOnExistingRuntimePath() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 21L)).thenReturn(manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH), file("src/Main.java", 900, CODE_HASH))));
        ControlledWorkerDispatchPlanner planner = new ControlledWorkerDispatchPlanner(projects);

        assertThat(planner.plan(request(AgentStrategy.SINGLE_STEP_REACT,
                List.of(paperRequirement(), codeRequirement())), AgentRequestCapability.PROJECT_READ)).isEmpty();
        assertThat(planner.plan(request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement())), AgentRequestCapability.PROJECT_READ)).isEmpty();

        AgentRuntimeRequest noCodeTool = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement()),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE));
        assertThat(planner.plan(noCodeTool, AgentRequestCapability.PROJECT_READ)).isEmpty();
        assertThat(planner.plan(request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement())), AgentRequestCapability.TRUSTED_PROJECT_PLAN_READ))
                .isEmpty();
    }

    @Test
    void multiMaterialScenarioStaysOnExistingPathWhenParentBudgetCannotCoverEveryRequiredTool() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 21L)).thenReturn(manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH),
                file("src/Main.java", 900, CODE_HASH),
                file("config/run.yaml", 100, "d".repeat(64)))));
        ControlledWorkerDispatchPlanner planner = new ControlledWorkerDispatchPlanner(projects);
        AgentRuntimeRequest insufficient = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement(), configRequirement()),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                        ResearchToolContracts.PROJECT_CODE_SYMBOLS,
                        ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY), 2);

        assertThat(planner.plan(insufficient, AgentRequestCapability.PROJECT_READ)).isEmpty();
    }

    @Test
    void dispatchRejectsDifferentTrustedProjectBoundary() {
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 21L)).thenReturn(manifest(List.of(
                file("paper/main.tex", 1200, PAPER_HASH), file("src/Main.java", 900, CODE_HASH))));
        ControlledWorkerDispatch dispatch = new ControlledWorkerDispatchPlanner(projects)
                .plan(request(AgentStrategy.PLAN_EXECUTE, List.of(paperRequirement(), codeRequirement())),
                        AgentRequestCapability.PROJECT_READ).orElseThrow();
        AgentRuntimeRequest otherProject = request(AgentStrategy.PLAN_EXECUTE,
                List.of(paperRequirement(), codeRequirement())).withProjectContext(new ProjectRuntimeContext(7L, 22L));

        assertThatThrownBy(() -> otherProject.withControlledWorkerDispatch(dispatch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtime boundary");
    }

    private AgentRuntimeRequest request(AgentStrategy strategy, List<ResearchMaterialRequirement> requirements) {
        return request(strategy, requirements, List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE,
                ResearchToolContracts.PROJECT_CODE_SYMBOLS,
                ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY));
    }

    private AgentRuntimeRequest request(AgentStrategy strategy, List<ResearchMaterialRequirement> requirements,
                                        List<String> tools) {
        return request(strategy, requirements, tools, 6);
    }

    private AgentRuntimeRequest request(AgentStrategy strategy, List<ResearchMaterialRequirement> requirements,
                                        List<String> tools, int maxToolCalls) {
        return request(strategy, requirements, tools, maxToolCalls, "Compare the paper and code.");
    }

    private AgentRuntimeRequest request(AgentStrategy strategy, List<ResearchMaterialRequirement> requirements,
                                        List<String> tools, int maxToolCalls, String message) {
        AgentOrchestrationRequirements orchestration = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK,
                        AgentStrategySignal.MATERIAL_PAPER_LATEX, AgentStrategySignal.MATERIAL_CODE),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN), requirements,
                AgentStrategySelectionOrigin.SERVER_AUTO, List.of());
        return new AgentRuntimeRequest(strategy, 9L, List.of(), 7L, message,
                "test", "test", 0.0, 3000, 9, true, null, "key", "url", null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(tools, maxToolCalls, 1, "test"), maxToolCalls, 1,
                "trace-1", null, null)
                .withProjectContext(new ProjectRuntimeContext(7L, 21L))
                .withOrchestrationRequirements(orchestration);
    }

    private ResearchMaterialRequirement paperRequirement() {
        return new ResearchMaterialRequirement(ResearchMaterialKind.PAPER_LATEX,
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE),
                List.of(ResearchToolContracts.PROJECT_LATEX_OUTLINE), true);
    }

    private ResearchMaterialRequirement codeRequirement() {
        return new ResearchMaterialRequirement(ResearchMaterialKind.CODE,
                List.of(ResearchToolContracts.PROJECT_CODE_SYMBOLS),
                List.of(ResearchToolContracts.PROJECT_CODE_SYMBOLS), true);
    }

    private ResearchMaterialRequirement configRequirement() {
        return new ResearchMaterialRequirement(ResearchMaterialKind.EXPERIMENT_CONFIG,
                List.of(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY),
                List.of(ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY), true);
    }

    private ProjectManifestResponse manifest(List<ProjectFileEntry> files) {
        return new ProjectManifestResponse(21L, VERSION, files);
    }

    private ProjectFileEntry file(String path, long size, String hash) {
        return new ProjectFileEntry(path, size, Instant.EPOCH, hash);
    }
}
