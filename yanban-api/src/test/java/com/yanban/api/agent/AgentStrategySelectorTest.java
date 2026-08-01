package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.research.ResearchToolContracts;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentStrategySelectorTest {

    private static final List<String> RESEARCH_TOOLS = List.of(
            "project_manifest",
            "project_read_file",
            ResearchToolContracts.PROJECT_LATEX_OUTLINE,
            ResearchToolContracts.PROJECT_CODE_SYMBOLS,
            ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY,
            ResearchToolContracts.PROJECT_BIBTEX_AUDIT,
            ResearchToolContracts.PROJECT_CROSS_MATERIAL_SEARCH);

    private final AgentStrategySelector selector = new AgentStrategySelector();

    @Test
    void explicitPlanReflectUsesOnlyRestrictedReflectionCapability() {
        AgentToolPolicyEngine.Decision toolPolicy = new AgentToolPolicyEngine.Decision(
                List.of("search_web"), 1, 1, "search");

        assertThat(selector.select("/plan reflect audit the current roadmap gaps", toolPolicy))
                .isEqualTo(AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION);
        assertThat(selector.decide(AgentCoordinationRequest.chat(
                request(AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION, "please reflect", List.of("search_web"), 2, 2))))
                .satisfies(decision -> {
                    assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
                    assertThat(decision.orchestration().reasonCodes())
                            .contains(AgentStrategyReasonCode.EXPLICIT_STRATEGY_NOT_ALLOWED);
                });
    }

    @Test
    void explicitDirectCannotBypassProjectPlanToolBoundary() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.DIRECT,
                "Compare the LaTeX paper with code and experiment results, then verify BibTeX citations.",
                RESEARCH_TOOLS, 8, 12);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(decision.explicitOverride()).isFalse();
        assertThat(decision.orchestration().selectionOrigin())
                .isEqualTo(AgentStrategySelectionOrigin.EXPLICIT_FALLBACK);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.orchestration().reasonCodes())
                .contains(AgentStrategyReasonCode.EXPLICIT_STRATEGY_NOT_ALLOWED,
                        AgentStrategyReasonCode.PROJECT_TOOLS_REQUIRE_PLAN);
    }

    @Test
    void simpleQuestionStaysDirectEvenWhenToolsAreAvailable() {
        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.chat(
                request(AgentStrategy.AUTO, "What is retrieval augmented generation?", List.of("search_web"), 4, 4)));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
        assertThat(decision.orchestration().signals()).contains(AgentStrategySignal.SIMPLE_QUESTION);
    }

    @Test
    void naturalCandidateChangeIntentIsSharedAndAlwaysRequiresProjectPlan() {
        List<String> requests = List.of(
                "\u8bf7\u4fee\u6539 Runner.java\uff0c\u8ba9\u5b83\u589e\u52a0\u8d85\u65f6\u5904\u7406\uff1b\u4e0d\u8981\u81ea\u52a8\u5e94\u7528",
                "\u7ed9\u8fd9\u4efd\u4ee3\u7801\u52a0\u5165\u5f52\u5e76\u6392\u5e8f\uff0c\u4e0d\u4fee\u6539\u5f53\u524d\u6587\u4ef6",
                "fix this Java file");

        assertThat(requests).allMatch(ProjectCandidateChangeIntent::requiresCandidateChange);
        assertThat(requests).allSatisfy(message -> {
            AgentStrategySelection selection = selector.decide(AgentCoordinationRequest.projectRead(
                    projectRequest(AgentStrategy.AUTO, message, RESEARCH_TOOLS, 6, 8)));
            assertThat(selection.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
            assertThat(selection.serverCandidates())
                    .containsExactly(AgentStrategy.DIRECT, AgentStrategy.PLAN_EXECUTE);
            assertThat(selection.orchestration().reasonCodes())
                    .contains(AgentStrategyReasonCode.PROJECT_TOOLS_REQUIRE_PLAN);
        });
    }

    @Test
    void candidateChangeIntentKeepsNegationAndNonCodeBoundaries() {
        assertThat(List.of(
                "\u8bf7\u5206\u6790\u5982\u4f55\u4fee\u6539 Runner.java\uff0c\u4f46\u4e0d\u8981\u751f\u6210\u6539\u52a8",
                "\u8bfb\u53d6\u6587\u6863\u5e76\u6bd4\u8f83 Candidate \u548c patch \u7684\u6982\u5ff5\uff0c\u4e0d\u8981\u4fee\u6539\u9879\u76ee",
                "\u4fee\u6539\u8bba\u6587\u7684\u5206\u6790\u65b9\u6cd5\u5e76\u7ed9\u51fa\u5efa\u8bae"))
                .noneMatch(ProjectCandidateChangeIntent::requiresCandidateChange);

        AgentStrategySelection readOnly = selector.decide(AgentCoordinationRequest.projectRead(projectRequest(
                AgentStrategy.AUTO,
                "\u8bf7\u5206\u6790\u5982\u4f55\u4fee\u6539 Runner.java\uff0c\u4f46\u4e0d\u8981\u751f\u6210\u6539\u52a8",
                RESEARCH_TOOLS, 6, 8)));
        assertThat(readOnly.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(readOnly.serverCandidates()).doesNotContain(AgentStrategy.SINGLE_STEP_REACT);
    }

    @Test
    void explicitLookupUsesReactAndNaturalLanguageCannotPromoteChatToPlan() {
        AgentStrategySelection lookup = selector.decide(AgentCoordinationRequest.chat(
                request(null, "Look up the latest release.", List.of("search_web"), 2, 2)));
        AgentStrategySelection planWords = selector.decide(AgentCoordinationRequest.chat(
                request(null, "Analyze this and propose a multi-stage plan.", List.of("search_web"), 6, 6)));

        assertThat(lookup.selectedStrategy()).isEqualTo(AgentStrategy.SINGLE_STEP_REACT);
        assertThat(planWords.selectedStrategy()).isEqualTo(AgentStrategy.SINGLE_STEP_REACT);
        assertThat(planWords.serverCandidates()).doesNotContain(AgentStrategy.PLAN_EXECUTE);
        assertThat(selector.isPlanReflectIntent("/plan reflection should not match")).isFalse();
    }

    @Test
    void projectCrossMaterialTaskSelectsPlanWithAuditableCoverage() throws Exception {
        String task = "Compare the LaTeX paper and source code with experiment config/results, "
                + "then verify every BibTeX citation.";
        AgentCoordinationRequest request = AgentCoordinationRequest.projectRead(
                projectRequest(AgentStrategy.AUTO, task, RESEARCH_TOOLS, 8, 12));

        AgentStrategySelection first = selector.decide(request);
        AgentStrategySelection second = selector.decide(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(first.orchestration().selectionOrigin()).isEqualTo(AgentStrategySelectionOrigin.SERVER_AUTO);
        assertThat(first.orchestration().signals()).contains(
                AgentStrategySignal.PROJECT_SCOPE,
                AgentStrategySignal.MATERIAL_PAPER_LATEX,
                AgentStrategySignal.MATERIAL_CODE,
                AgentStrategySignal.MATERIAL_EXPERIMENT_CONFIG,
                AgentStrategySignal.MATERIAL_BIBTEX,
                AgentStrategySignal.CROSS_MATERIAL_TASK,
                AgentStrategySignal.VERIFICATION_REQUIRED);
        assertThat(first.orchestration().materialRequirements())
                .extracting(ResearchMaterialRequirement::material)
                .containsExactly(ResearchMaterialKind.PAPER_LATEX, ResearchMaterialKind.CODE,
                        ResearchMaterialKind.EXPERIMENT_CONFIG, ResearchMaterialKind.BIBTEX);
        assertThat(first.orchestration().materialRequirements()).allMatch(ResearchMaterialRequirement::covered);
        assertThat(first.orchestration().consistencyChecks()).isEmpty();
        assertThat(new ObjectMapper().writeValueAsString(first))
                .contains("AUTO", "SERVER_AUTO", "AUTO_CROSS_MATERIAL_PLAN", "PAPER_LATEX")
                .doesNotContain(task);
    }

    @Test
    void explicitHashEqualityRequestSelectsNarrowDeterministicCheckWithoutChangingPlanAuthority() {
        String task = "Compare the LaTeX paper and source code, then verify they have the same content hash.";
        AgentRuntimeRequest request = projectRequest(AgentStrategy.AUTO, task, RESEARCH_TOOLS, 8, 12);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(decision.orchestration().consistencyChecks())
                .containsExactly(DomainConsistencyCheck.EVIDENCE_FILE_HASH_EQUALITY);
        assertThat(decision.orchestration().plannerInstruction())
                .contains("SHA-256 equality", "never semantic equivalence");
        assertThat(request.allowedToolNames()).containsExactlyElementsOf(RESEARCH_TOOLS);
    }

    @Test
    void chineseHashOrByteEqualityRequestSelectsNarrowDeterministicCheck() {
        String task = "\u8bf7\u6bd4\u8f83\u8bba\u6587 paper/main.tex \u4e0e\u4ee3\u7801 src/Main.java \u7684"
                + "\u5b8c\u6574\u5185\u5bb9\u54c8\u5e0c\u6216\u5b57\u8282\u662f\u5426\u5b8c\u5168\u4e00\u81f4\uff0c"
                + "\u5e76\u7ed9\u51fa\u7ed3\u6784\u5316\u6821\u9a8c\u7ed3\u8bba\u3002";
        AgentRuntimeRequest request = projectRequest(AgentStrategy.AUTO, task, RESEARCH_TOOLS, 8, 12);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(decision.orchestration().signals()).contains(AgentStrategySignal.VERIFICATION_REQUIRED);
        assertThat(decision.orchestration().consistencyChecks())
                .containsExactly(DomainConsistencyCheck.EVIDENCE_FILE_HASH_EQUALITY);
    }

    @Test
    void insufficientProjectPlanBudgetFailsClosedWithoutSelectingReact() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.AUTO,
                "Compare the LaTeX paper with code and verify experiment results.", RESEARCH_TOOLS, 1, 1);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.degradedFrom()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(decision.orchestration().reasonCodes()).contains(
                AgentStrategyReasonCode.PLAN_BUDGET_INSUFFICIENT,
                AgentStrategyReasonCode.PROJECT_TOOLS_REQUIRE_PLAN,
                AgentStrategyReasonCode.DEGRADED_TO_DIRECT);
        assertThat(request.allowedToolNames()).containsExactlyElementsOf(RESEARCH_TOOLS);
    }

    @Test
    void recognizesChineseCrossMaterialResearchSignalsDeterministically() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.AUTO,
                "对比论文、代码与实验配置，并核对参考文献引用的一致性。", RESEARCH_TOOLS, 8, 12);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(decision.orchestration().materialRequirements())
                .extracting(ResearchMaterialRequirement::material)
                .containsExactly(ResearchMaterialKind.PAPER_LATEX, ResearchMaterialKind.CODE,
                        ResearchMaterialKind.EXPERIMENT_CONFIG, ResearchMaterialKind.BIBTEX);
        assertThat(decision.orchestration().signals()).contains(
                AgentStrategySignal.MULTI_STAGE_TASK, AgentStrategySignal.VERIFICATION_REQUIRED,
                AgentStrategySignal.CROSS_MATERIAL_TASK);
    }

    @Test
    void incompleteToolPolicyCoverageDegradesAndNeverSynthesizesMaterialTools() {
        List<String> intersectedPolicy = List.of("project_manifest", ResearchToolContracts.PROJECT_LATEX_OUTLINE);
        AgentRuntimeRequest request = projectRequest(AgentStrategy.AUTO,
                "Compare the LaTeX paper with code and verify experiment results.", intersectedPolicy, 8, 12);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
        assertThat(decision.orchestration().reasonCodes())
                .contains(AgentStrategyReasonCode.MATERIAL_COVERAGE_INCOMPLETE,
                        AgentStrategyReasonCode.PROJECT_TOOLS_REQUIRE_PLAN)
                .doesNotContain(AgentStrategyReasonCode.DEGRADED_TO_REACT);
        assertThat(decision.orchestration().materialRequirements())
                .filteredOn(requirement -> requirement.material() == ResearchMaterialKind.CODE
                        || requirement.material() == ResearchMaterialKind.EXPERIMENT_CONFIG)
                .allMatch(requirement -> !requirement.covered() && requirement.availableTools().isEmpty());
        assertThat(request.allowedToolNames()).containsExactlyElementsOf(intersectedPolicy);
    }

    @Test
    void deniedToolPolicyFailsClosedToDirect() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT,
                "Read and inspect the project code.", List.of(), 8, 12);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.degradedFrom()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(decision.orchestration().reasonCodes()).contains(
                AgentStrategyReasonCode.EXPLICIT_STRATEGY_NOT_ALLOWED,
                AgentStrategyReasonCode.NO_ALLOWED_TOOLS,
                AgentStrategyReasonCode.DEGRADED_TO_DIRECT);
    }

    @Test
    void projectAutoDefaultsToPlanForReadOnlyProjectObservations() {
        List<String> explorationTools = List.of("project_manifest", "project_read_file");
        AgentStrategySelection english = selector.decide(AgentCoordinationRequest.projectRead(projectRequest(
                AgentStrategy.AUTO, "List this project's directory structure.", explorationTools, 4, 4)));
        AgentStrategySelection chinese = selector.decide(AgentCoordinationRequest.projectRead(projectRequest(
                AgentStrategy.AUTO, "README 里说明了什么？", explorationTools, 4, 4)));

        assertThat(List.of(english.selectedStrategy(), chinese.selectedStrategy()))
                .containsOnly(AgentStrategy.PLAN_EXECUTE);
        assertThat(List.of(english, chinese)).allSatisfy(decision -> {
            assertThat(decision.orchestration().signals()).contains(
                    AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.PROJECT_READ_REQUIRED);
            assertThat(decision.orchestration().reasonCodes())
                    .contains(AgentStrategyReasonCode.PROJECT_TOOLS_REQUIRE_PLAN);
            assertThat(decision.orchestration().selectionOrigin())
                    .isEqualTo(AgentStrategySelectionOrigin.SERVER_AUTO);
        });
    }

    @Test
    void genericChineseCodeEvidenceCitationUsesPlanWithoutInventingBibtexMaterial() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.AUTO,
                "\u8bf7\u8bfb\u53d6\u5e76\u6982\u62ec\u4ee3\u7801\u6587\u4ef6 good_code/s2/main.py \u4e2d main()\uff0c\u5e76\u5f15\u7528\u5177\u4f53\u4ee3\u7801\u884c\u3002",
                RESEARCH_TOOLS, 8, 12);

        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(request));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(decision.orchestration().materialRequirements())
                .extracting(ResearchMaterialRequirement::material)
                .containsExactly(ResearchMaterialKind.CODE);
        assertThat(decision.orchestration().signals()).doesNotContain(
                AgentStrategySignal.MATERIAL_BIBTEX,
                AgentStrategySignal.CROSS_MATERIAL_TASK);
        assertThat(decision.orchestration().reasonCodes())
                .contains(AgentStrategyReasonCode.PROJECT_TOOLS_REQUIRE_PLAN);
    }

    @Test
    void explicitSingleFileRunRequiresPlanWithoutInventingExperimentConfigMaterial() {
        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(projectRequest(
                AgentStrategy.AUTO, "src/main/java/xhs_1111.java，运行这个程序，结果是什么？",
                RESEARCH_TOOLS, 8, 12)));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(decision.orchestration().signals()).contains(
                AgentStrategySignal.MATERIAL_CODE,
                AgentStrategySignal.SANDBOX_EXECUTION_REQUIRED,
                AgentStrategySignal.PLAN_BUDGET_AVAILABLE);
        assertThat(decision.orchestration().reasonCodes())
                .contains(AgentStrategyReasonCode.SANDBOX_EXECUTION_REQUIRES_PLAN);
        assertThat(decision.orchestration().materialRequirements())
                .extracting(ResearchMaterialRequirement::material)
                .containsExactly(ResearchMaterialKind.CODE)
                .doesNotContain(ResearchMaterialKind.EXPERIMENT_CONFIG);
    }

    @Test
    void ordinaryCodeExecutionDoesNotTreatOutputJsonOrDataWordsAsExperimentMaterial() {
        for (String task : List.of(
                "运行 Main.java 并把结果输出为 JSON。",
                "Run Main.java and return the result data.")) {
            AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(projectRequest(
                    AgentStrategy.AUTO, task, RESEARCH_TOOLS, 8, 12)));

            assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
            assertThat(decision.orchestration().materialRequirements())
                    .extracting(ResearchMaterialRequirement::material)
                    .containsExactly(ResearchMaterialKind.CODE)
                    .doesNotContain(ResearchMaterialKind.EXPERIMENT_CONFIG);
        }
    }

    @Test
    void explicitExperimentResultCsvStillRequiresExperimentConfigMaterial() {
        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(projectRequest(
                AgentStrategy.AUTO, "请总结实验结果 CSV 并解释指标。", RESEARCH_TOOLS, 8, 12)));

        assertThat(decision.orchestration().materialRequirements())
                .extracting(ResearchMaterialRequirement::material)
                .containsExactly(ResearchMaterialKind.EXPERIMENT_CONFIG);
        assertThat(decision.orchestration().signals())
                .contains(AgentStrategySignal.MATERIAL_EXPERIMENT_CONFIG)
                .doesNotContain(AgentStrategySignal.SANDBOX_EXECUTION_REQUIRED);
    }

    @Test
    void projectExplorationWithoutToolBudgetFailsClosedFromRequiredPlan() {
        AgentStrategySelection decision = selector.decide(AgentCoordinationRequest.projectRead(projectRequest(
                AgentStrategy.AUTO, "README 里说明了什么？", List.of("project_read_file"), 4, 0)));

        assertThat(decision.selectedStrategy()).isEqualTo(AgentStrategy.DIRECT);
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.degradedFrom()).isEqualTo(AgentStrategy.PLAN_EXECUTE);
        assertThat(decision.orchestration().reasonCodes()).contains(
                AgentStrategyReasonCode.PROJECT_TOOLS_REQUIRE_PLAN,
                AgentStrategyReasonCode.PLAN_BUDGET_INSUFFICIENT,
                AgentStrategyReasonCode.TOOL_BUDGET_INSUFFICIENT,
                AgentStrategyReasonCode.DEGRADED_TO_DIRECT);
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy,
                                               String message,
                                               List<String> tools,
                                               int maxSteps,
                                               int maxToolCalls) {
        return request(strategy, message, tools, maxSteps, maxToolCalls)
                .withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private AgentRuntimeRequest request(AgentStrategy strategy,
                                        String message,
                                        List<String> tools,
                                        int maxSteps,
                                        int maxToolCalls) {
        return new AgentRuntimeRequest(strategy, 11L, List.of(ChatMessage.user("prior")), 7L, message,
                "test", "model", null, null, maxSteps, true, null, "key", "url", null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(tools, maxToolCalls, Math.min(1, maxToolCalls), "intersected_test_policy"),
                maxToolCalls, Math.min(1, maxToolCalls), "trace", null, null);
    }
}
