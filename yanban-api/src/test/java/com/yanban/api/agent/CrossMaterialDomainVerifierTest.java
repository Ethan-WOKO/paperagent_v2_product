package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.core.model.ChatMessage;
import com.yanban.core.research.ResearchToolContracts;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class CrossMaterialDomainVerifierTest {
    private static final String PROJECT_VERSION = "b".repeat(64);
    private static final String FILE_HASH = "a".repeat(64);
    private final CrossMaterialDomainVerifier verifier = new CrossMaterialDomainVerifier();

    @ParameterizedTest(name = "{0}")
    @MethodSource("materialCases")
    void verifiesEachRequestedMaterialWithoutForcingCrossMaterialConsistency(
            ResearchMaterialKind material, String tool, String path) {
        ResearchMaterialRequirement requirement = requirement(material, tool);
        AgentRuntimeRequest request = request(AgentStrategy.SINGLE_STEP_REACT, List.of(requirement), List.of(tool), false);
        EvidenceRef evidence = evidence(42L, path, material.name());
        AgentRuntimeResult result = success(new DomainRuntimeFacts(
                List.of(tool(tool, true, true)), List.of(), List.of()));

        DomainVerification decision = verifier.verify(request, result, new EvidenceLedger(List.of(evidence)), 0);

        assertThat(decision.status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(decision.consistencyStatus()).isEqualTo(DomainVerification.ConsistencyStatus.NOT_REQUIRED);
        assertThat(decision.materialCoverage()).singleElement().satisfies(coverage -> {
            assertThat(coverage.material()).isEqualTo(material);
            assertThat(coverage.status()).isEqualTo(DomainVerification.MaterialStatus.COVERAGE_VERIFIED);
            assertThat(coverage.successfulTools()).containsExactly(tool);
            assertThat(coverage.evidenceRefs()).containsExactly(evidence.id());
        });
    }

    @Test
    void ordinaryProjectDirectCanVerifyOneRequestedMaterial() {
        String tool = ResearchToolContracts.PROJECT_CODE_SYMBOLS;
        AgentRuntimeRequest request = request(AgentStrategy.DIRECT,
                List.of(requirement(ResearchMaterialKind.CODE, tool)), List.of(tool), false);
        DomainVerification decision = verifier.verify(request,
                success(new DomainRuntimeFacts(List.of(tool(tool, true, true)), List.of(), List.of())),
                new EvidenceLedger(List.of(evidence(42L, "src/Main.java", "direct"))), 0);

        assertThat(decision.status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(decision.reasonCodes()).contains(DomainVerificationReasonCode.MATERIAL_COVERAGE_VERIFIED);
    }

    @Test
    void nonProjectDirectRemainsOutsideTheDomainGate() {
        ResearchMaterialRequirement paper = requirement(ResearchMaterialKind.PAPER_LATEX,
                ResearchToolContracts.PROJECT_LATEX_OUTLINE);
        AgentRuntimeRequest request = baseRequest(AgentStrategy.DIRECT, List.of(paper), paper.availableTools(), false);

        DomainVerification decision = verifier.verify(request, success(DomainRuntimeFacts.empty()),
                EvidenceLedger.empty(), 0);

        assertThat(decision.applicable()).isFalse();
        assertThat(decision.status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    @Test
    void autoCrossMaterialPlanReportsCoverageButNotUnprovenConsistency() {
        Fixture fixture = crossMaterialFixture();

        DomainVerification decision = verifier.verify(fixture.request(), fixture.result(), fixture.evidence(), 0);

        assertThat(decision.status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(decision.materialCoverage()).allMatch(item ->
                item.status() == DomainVerification.MaterialStatus.COVERAGE_VERIFIED);
        assertThat(decision.consistencyStatus()).isEqualTo(DomainVerification.ConsistencyStatus.UNRESOLVED);
        assertThat(decision.reasonCodes()).contains(DomainVerificationReasonCode.CONSISTENCY_UNRESOLVED,
                DomainVerificationReasonCode.REFLECTION_NOT_ELIGIBLE);
        assertThat(decision.reflectionEligible()).isFalse();
    }

    @Test
    void unsupportedSemanticFactCannotVerifyCrossMaterialConsistency() {
        Fixture fixture = crossMaterialFixture();
        List<String> evidenceIds = fixture.evidence().evidence().stream().map(EvidenceRef::id).toList();
        DomainRuntimeFacts.ConsistencyFact fact = new DomainRuntimeFacts.ConsistencyFact(
                "paper-code-symbol-contract-v1",
                List.of(ResearchMaterialKind.PAPER_LATEX, ResearchMaterialKind.CODE),
                evidenceIds,
                false,
                DomainRuntimeFacts.ConsistencyFactSource.DETERMINISTIC_DOMAIN_RULE);
        AgentRuntimeResult result = fixture.result().withDomainRuntimeFacts(
                fixture.result().domainRuntimeFacts().merge(new DomainRuntimeFacts(List.of(), List.of(), List.of(fact))));

        DomainVerification decision = verifier.verify(fixture.request(), result, fixture.evidence(), 0);

        assertThat(decision.status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(decision.consistencyStatus()).isEqualTo(DomainVerification.ConsistencyStatus.UNRESOLVED);
        assertThat(decision.consistencyFacts()).isEmpty();
        assertThat(decision.reasonCodes()).contains(DomainVerificationReasonCode.CONSISTENCY_UNRESOLVED);
    }

    @Test
    void requestedHashEqualityRuleProducesStructuredConsistentFinding() {
        Fixture fixture = crossMaterialHashFixture(FILE_HASH);

        DomainVerification decision = verifier.verify(fixture.request(), fixture.result(), fixture.evidence(), 0);

        assertThat(decision.status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(decision.consistencyStatus()).isEqualTo(DomainVerification.ConsistencyStatus.VERIFIED_CONSISTENT);
        assertThat(decision.reasonCodes()).contains(
                DomainVerificationReasonCode.CONSISTENCY_VERIFIED_CONSISTENT_BY_STRUCTURED_FACT);
        assertThat(decision.consistencyFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.ruleId()).isEqualTo(DomainConsistencyRuleEngine.EVIDENCE_FILE_HASH_EQUALITY_RULE);
            assertThat(fact.consistent()).isTrue();
            assertThat(fact.evidenceRefs()).hasSize(2);
        });
    }

    @Test
    void requestedHashEqualityRulePreservesStructuredInconsistentFinding() {
        Fixture fixture = crossMaterialHashFixture("c".repeat(64));
        DomainRuntimeFacts.ConsistencyFact forgedConsistent = new DomainRuntimeFacts.ConsistencyFact(
                DomainConsistencyRuleEngine.EVIDENCE_FILE_HASH_EQUALITY_RULE,
                List.of(ResearchMaterialKind.PAPER_LATEX, ResearchMaterialKind.CODE),
                fixture.evidence().evidence().stream().map(EvidenceRef::id).toList(), true,
                DomainRuntimeFacts.ConsistencyFactSource.DETERMINISTIC_DOMAIN_RULE);
        AgentRuntimeResult result = fixture.result().withDomainRuntimeFacts(
                fixture.result().domainRuntimeFacts().withConsistencyFacts(List.of(forgedConsistent)));

        DomainVerification decision = verifier.verify(fixture.request(), result, fixture.evidence(), 0);

        assertThat(decision.status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(decision.consistencyStatus()).isEqualTo(DomainVerification.ConsistencyStatus.VERIFIED_INCONSISTENT);
        assertThat(decision.reasonCodes()).contains(
                DomainVerificationReasonCode.CONSISTENCY_VERIFIED_INCONSISTENT_BY_STRUCTURED_FACT);
        assertThat(decision.consistencyFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.ruleId()).isEqualTo(DomainConsistencyRuleEngine.EVIDENCE_FILE_HASH_EQUALITY_RULE);
            assertThat(fact.consistent()).isFalse();
        });
    }

    @Test
    void consistencyFactMustReferenceCurrentEvidenceFromEveryRequestedMaterial() {
        Fixture fixture = crossMaterialFixture();
        String paperEvidence = fixture.evidence().evidence().get(0).id();
        DomainRuntimeFacts.ConsistencyFact incompleteFact = new DomainRuntimeFacts.ConsistencyFact(
                "incomplete-cross-material-rule-v1",
                List.of(ResearchMaterialKind.PAPER_LATEX, ResearchMaterialKind.CODE),
                List.of(paperEvidence), true,
                DomainRuntimeFacts.ConsistencyFactSource.DETERMINISTIC_DOMAIN_RULE);
        AgentRuntimeResult result = fixture.result().withDomainRuntimeFacts(
                fixture.result().domainRuntimeFacts().merge(
                        new DomainRuntimeFacts(List.of(), List.of(), List.of(incompleteFact))));

        DomainVerification decision = verifier.verify(fixture.request(), result, fixture.evidence(), 0);

        assertThat(decision.status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(decision.consistencyStatus()).isEqualTo(DomainVerification.ConsistencyStatus.UNRESOLVED);
    }

    @Test
    void missingRequiredToolExecutionIsRepairableOnlyOnce() {
        String tool = ResearchToolContracts.PROJECT_BIBTEX_AUDIT;
        AgentRuntimeRequest request = request(AgentStrategy.SINGLE_STEP_REACT,
                List.of(requirement(ResearchMaterialKind.BIBTEX, tool)), List.of(tool), false);
        AgentRuntimeResult result = success(DomainRuntimeFacts.empty());

        DomainVerification first = verifier.verify(request, result, EvidenceLedger.empty(), 0);
        DomainVerification second = verifier.verify(request, result, EvidenceLedger.empty(), 1);

        assertThat(first.status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(first.reflectionEligible()).isTrue();
        assertThat(first.reasonCodes()).contains(DomainVerificationReasonCode.REQUIRED_TOOL_NOT_EXECUTED,
                DomainVerificationReasonCode.REFLECTION_ELIGIBLE);
        assertThat(second.reflectionEligible()).isFalse();
        assertThat(second.reasonCodes()).contains(DomainVerificationReasonCode.REFLECTION_NOT_ELIGIBLE);
    }

    @Test
    void boundedRepairCanUseAnAlreadyAuthorizedSpecializedToolWithoutExpandingPolicy() {
        String tool = ResearchToolContracts.PROJECT_BIBTEX_AUDIT;
        AgentRuntimeRequest request = request(AgentStrategy.SINGLE_STEP_REACT,
                List.of(requirement(ResearchMaterialKind.BIBTEX, tool)), List.of(tool), false);
        AgentRuntimeResult result = success(DomainRuntimeFacts.empty());
        DomainVerification domain = verifier.verify(request, result, EvidenceLedger.empty(), 0);
        CompletionVerification completion = new CompletionVerification(domain.status(), domain.auditReasons(),
                domain.evidenceRefs(), domain.reflectionEligible(), 0, domain);
        CompletionReflection reflection = new CompletionReflection();

        assertThat(reflection.mayAttempt(request, completion, result)).isTrue();
        AgentRuntimeRequest repair = reflection.repairRequest(request, result, completion);
        assertThat(repair.toolPolicy().allowedTools()).containsExactly(tool);
        assertThat(repair.toolPolicy().maxToolCalls()).isLessThanOrEqualTo(request.toolPolicy().maxToolCalls());
        assertThat(repair.history()).anySatisfy(message -> assertThat(message.content())
                .contains("BIBTEX", tool, "Do not claim cross-material consistency"));
    }

    @Test
    void failedRequiredToolAndUnavailablePolicyNeverBecomeVerified() {
        String tool = ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY;
        ResearchMaterialRequirement available = requirement(ResearchMaterialKind.EXPERIMENT_CONFIG, tool);
        DomainVerification failed = verifier.verify(
                request(AgentStrategy.SINGLE_STEP_REACT, List.of(available), List.of(tool), false),
                success(new DomainRuntimeFacts(List.of(tool(tool, true, false)), List.of(), List.of())),
                new EvidenceLedger(List.of(evidence(42L, "results.csv", "failed"))), 0);

        ResearchMaterialRequirement unavailable = new ResearchMaterialRequirement(
                ResearchMaterialKind.EXPERIMENT_CONFIG, List.of(tool), List.of(), false);
        DomainVerification denied = verifier.verify(
                request(AgentStrategy.SINGLE_STEP_REACT, List.of(unavailable), List.of(), false),
                success(DomainRuntimeFacts.empty()), EvidenceLedger.empty(), 0);

        assertThat(failed.status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(failed.reasonCodes()).contains(DomainVerificationReasonCode.REQUIRED_TOOL_FAILED);
        assertThat(denied.status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(denied.reflectionEligible()).isFalse();
        assertThat(denied.reasonCodes()).contains(DomainVerificationReasonCode.MATERIAL_TOOL_UNAVAILABLE);
    }

    @ParameterizedTest(name = "evidence:{0}")
    @MethodSource("invalidEvidenceCases")
    void missingStaleLegacyAndCrossProjectEvidenceFailClosed(String label, EvidenceLedger evidence) {
        String tool = ResearchToolContracts.PROJECT_CODE_SYMBOLS;
        AgentRuntimeRequest request = request(AgentStrategy.SINGLE_STEP_REACT,
                List.of(requirement(ResearchMaterialKind.CODE, tool)), List.of(tool), false);

        DomainVerification decision = verifier.verify(request,
                success(new DomainRuntimeFacts(List.of(tool(tool, true, true)), List.of(), List.of())), evidence, 0);

        assertThat(decision.status()).as(label).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(decision.materialCoverage()).singleElement().extracting(DomainVerification.MaterialCoverage::status)
                .isEqualTo(DomainVerification.MaterialStatus.EVIDENCE_MISSING);
        assertThat(decision.reasonCodes())
                .contains(DomainVerificationReasonCode.CURRENT_TRUSTED_EVIDENCE_MISSING_OR_INVALID);
    }

    @ParameterizedTest(name = "plan:{0}")
    @CsvSource({
            "FAILED,FAILED",
            "SKIPPED,PARTIAL",
            "DEGRADED,PARTIAL",
            "RUNNING,PARTIAL"
    })
    void planStepOutcomesCannotBeDisguisedAsVerified(String stepStatus, String expectedStatus) {
        AgentRuntimeRequest request = request(AgentStrategy.PLAN_EXECUTE, List.of(), List.of(), false);
        DomainRuntimeFacts.PlanStepOutcome step = new DomainRuntimeFacts.PlanStepOutcome(
                "step_1", DomainRuntimeFacts.PlanStepStatus.from(stepStatus), false);

        DomainVerification decision = verifier.verify(request,
                success(new DomainRuntimeFacts(List.of(), List.of(step), List.of())), EvidenceLedger.empty(), 0);

        assertThat(decision.status()).isEqualTo(CompletionStatus.valueOf(expectedStatus));
        assertThat(decision.reflectionEligible()).isFalse();
    }

    @Test
    void controlledCompletedPlanStepAndBudgetExhaustionRemainPartial() {
        AgentRuntimeRequest request = request(AgentStrategy.PLAN_EXECUTE, List.of(), List.of(), false);
        DomainRuntimeFacts facts = new DomainRuntimeFacts(List.of(), List.of(new DomainRuntimeFacts.PlanStepOutcome(
                "step_1", DomainRuntimeFacts.PlanStepStatus.COMPLETED, true)), List.of());
        AgentRuntimeResult result = success(facts).withRuntimeStopSignal(AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED);

        DomainVerification decision = verifier.verify(request, result, EvidenceLedger.empty(), 0);

        assertThat(decision.status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(decision.reasonCodes()).contains(DomainVerificationReasonCode.PLAN_STEP_PARTIAL,
                DomainVerificationReasonCode.RUNTIME_BUDGET_EXHAUSTED);
    }

    @Test
    void trustedTraceParserDoesNotAcceptLegacyOrPolicyExpandedFacts() {
        List<String> trace = List.of(
                "step=1 tool=project_code_symbols executed=true budgetConsumed=true success=true reused=false skipped=false args={q:' success=false '}",
                "step=2 tool=project_bibtex_audit executed=true budgetConsumed=true success=true reused=false skipped=false args={}",
                "step=3 tool=legacy args={} success=true");

        DomainRuntimeFacts facts = DomainRuntimeFacts.fromTrustedToolTrace(
                trace, List.of(ResearchToolContracts.PROJECT_CODE_SYMBOLS));

        assertThat(facts.toolOutcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.toolName()).isEqualTo(ResearchToolContracts.PROJECT_CODE_SYMBOLS);
            assertThat(outcome.success()).isTrue();
            assertThat(outcome.executed()).isTrue();
            assertThat(outcome.budgetConsumed()).isTrue();
        });
    }

    @Test
    void laterSuccessInSameModelStepRecoversEarlierTrustedToolFailure() {
        List<String> trace = List.of(
                "step=1 tool=project_read_file executed=true budgetConsumed=true success=false reused=false skipped=false error=VALIDATION_ERROR",
                "step=1 tool=project_read_file executed=true budgetConsumed=true success=true reused=false skipped=false observation=lines:4766-4830");

        DomainRuntimeFacts facts = DomainRuntimeFacts.fromTrustedToolTrace(
                trace, List.of("project_read_file"));

        assertThat(facts.toolOutcomes())
                .extracting(DomainRuntimeFacts.ToolOutcome::runtimeStep)
                .containsExactly(1, 2);
        assertThat(facts.hasUnrecoveredToolFailure(AgentOrchestrationRequirements.empty())).isFalse();
    }

    @Test
    void earlierSuccessInSameModelStepDoesNotHideLaterTrustedToolFailure() {
        List<String> trace = List.of(
                "step=1 tool=project_read_file executed=true budgetConsumed=true success=true reused=false skipped=false observation=lines:4766-4830",
                "step=1 tool=project_read_file executed=true budgetConsumed=true success=false reused=false skipped=false error=VALIDATION_ERROR");

        DomainRuntimeFacts facts = DomainRuntimeFacts.fromTrustedToolTrace(
                trace, List.of("project_read_file"));

        assertThat(facts.toolOutcomes())
                .extracting(DomainRuntimeFacts.ToolOutcome::runtimeStep)
                .containsExactly(1, 2);
        assertThat(facts.hasUnrecoveredToolFailure(AgentOrchestrationRequirements.empty())).isTrue();
    }

    private Fixture crossMaterialFixture() {
        return crossMaterialFixture(List.of(), FILE_HASH);
    }

    private Fixture crossMaterialHashFixture(String codeHash) {
        return crossMaterialFixture(List.of(DomainConsistencyCheck.EVIDENCE_FILE_HASH_EQUALITY), codeHash);
    }

    private Fixture crossMaterialFixture(List<DomainConsistencyCheck> consistencyChecks, String codeHash) {
        String paperTool = ResearchToolContracts.PROJECT_LATEX_OUTLINE;
        String codeTool = ResearchToolContracts.PROJECT_CODE_SYMBOLS;
        List<ResearchMaterialRequirement> requirements = List.of(
                requirement(ResearchMaterialKind.PAPER_LATEX, paperTool),
                requirement(ResearchMaterialKind.CODE, codeTool));
        AgentRuntimeRequest request = request(AgentStrategy.PLAN_EXECUTE, requirements,
                List.of(paperTool, codeTool), true, consistencyChecks);
        EvidenceLedger evidence = new EvidenceLedger(List.of(
                evidence(42L, "paper/main.tex", "paper"),
                evidence(42L, "src/Main.java", "code", codeHash)));
        DomainRuntimeFacts facts = new DomainRuntimeFacts(
                List.of(tool(paperTool, true, true), tool(codeTool, true, true)),
                List.of(new DomainRuntimeFacts.PlanStepOutcome(
                        "step_1", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false)),
                List.of());
        return new Fixture(request, success(facts), evidence);
    }

    private AgentRuntimeRequest request(AgentStrategy strategy,
                                        List<ResearchMaterialRequirement> requirements,
                                        List<String> tools,
                                        boolean crossMaterial) {
        return request(strategy, requirements, tools, crossMaterial, List.of());
    }

    private AgentRuntimeRequest request(AgentStrategy strategy,
                                        List<ResearchMaterialRequirement> requirements,
                                        List<String> tools,
                                        boolean crossMaterial,
                                        List<DomainConsistencyCheck> consistencyChecks) {
        return baseRequest(strategy, requirements, tools, crossMaterial, consistencyChecks)
                .withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private AgentRuntimeRequest baseRequest(AgentStrategy strategy,
                                            List<ResearchMaterialRequirement> requirements,
                                            List<String> tools,
                                            boolean crossMaterial) {
        return baseRequest(strategy, requirements, tools, crossMaterial, List.of());
    }

    private AgentRuntimeRequest baseRequest(AgentStrategy strategy,
                                            List<ResearchMaterialRequirement> requirements,
                                            List<String> tools,
                                            boolean crossMaterial,
                                            List<DomainConsistencyCheck> consistencyChecks) {
        List<AgentStrategySignal> signals = crossMaterial
                ? List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK,
                AgentStrategySignal.VERIFICATION_REQUIRED)
                : List.of(AgentStrategySignal.PROJECT_SCOPE);
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(signals,
                crossMaterial ? List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN) : List.of(),
                requirements,
                crossMaterial ? AgentStrategySelectionOrigin.SERVER_AUTO : AgentStrategySelectionOrigin.EXPLICIT_OVERRIDE,
                consistencyChecks);
        return new AgentRuntimeRequest(strategy, 11L, List.of(), 7L, "inspect requested material", "test", "model",
                null, null, 4, true, null, "key", "url", null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(tools, tools.isEmpty() ? 0 : 4, 1, "project"), 4, 1,
                "trace", null, null).withOrchestrationRequirements(audit);
    }

    private AgentRuntimeResult success(DomainRuntimeFacts facts) {
        return new AgentRuntimeResult(true, "answer", List.of(ChatMessage.assistant("answer")), 1,
                null, List.of(), List.of(), null, null, null).withDomainRuntimeFacts(facts);
    }

    private static ResearchMaterialRequirement requirement(ResearchMaterialKind material, String tool) {
        return new ResearchMaterialRequirement(material, List.of(tool), List.of(tool), true);
    }

    private static DomainRuntimeFacts.ToolOutcome tool(String name, boolean executed, boolean success) {
        return new DomainRuntimeFacts.ToolOutcome(name, 1, null, executed, executed, success, false, false);
    }

    private static EvidenceRef evidence(Long projectId, String path, String suffix) {
        return evidence(projectId, path, suffix, FILE_HASH);
    }

    private static EvidenceRef evidence(Long projectId, String path, String suffix, String fileHash) {
        return new EvidenceRef("trusted-tool:" + projectId + ":" + path + ":" + suffix,
                EvidenceSourceType.PROJECT, "PROJECT", path, "tool:" + suffix, null, fileHash, "test",
                PROJECT_VERSION, fileHash, 1, 2, "test-parser@1", EvidenceVersionStatus.VERIFIED);
    }

    private static Stream<Arguments> materialCases() {
        return Stream.of(
                Arguments.of(ResearchMaterialKind.PAPER_LATEX, ResearchToolContracts.PROJECT_LATEX_OUTLINE, "paper/main.tex"),
                Arguments.of(ResearchMaterialKind.CODE, ResearchToolContracts.PROJECT_CODE_SYMBOLS, "src/Main.java"),
                Arguments.of(ResearchMaterialKind.EXPERIMENT_CONFIG, ResearchToolContracts.PROJECT_EXPERIMENT_SUMMARY, "config/run.yaml"),
                Arguments.of(ResearchMaterialKind.BIBTEX, ResearchToolContracts.PROJECT_BIBTEX_AUDIT, "paper/references.bib")
        );
    }

    private static Stream<Arguments> invalidEvidenceCases() {
        EvidenceRef stale = evidence(42L, "src/Main.java", "stale").stale();
        EvidenceRef legacy = new EvidenceRef("trusted-tool:42:src/Main.java:legacy",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:legacy", null,
                FILE_HASH, "legacy");
        EvidenceRef crossProject = evidence(99L, "src/Main.java", "cross-project");
        return Stream.of(
                Arguments.of("missing", EvidenceLedger.empty()),
                Arguments.of("stale", new EvidenceLedger(List.of(stale))),
                Arguments.of("legacy", new EvidenceLedger(List.of(legacy))),
                Arguments.of("cross-project", new EvidenceLedger(List.of(crossProject)))
        );
    }

    private record Fixture(AgentRuntimeRequest request, AgentRuntimeResult result, EvidenceLedger evidence) { }
}
