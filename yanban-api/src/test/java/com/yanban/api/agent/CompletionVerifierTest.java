package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.agent.sandbox.CandidateArtifactResponse;
import com.yanban.api.agent.sandbox.CandidateIntent;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.model.ChatMessage;
import com.yanban.core.research.FileHash;
import com.yanban.core.research.ProjectRelativePath;
import com.yanban.core.research.ProjectVersionRef;
import com.yanban.core.research.ResearchToolContracts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class CompletionVerifierTest {
    private static final String PROJECT_VERSION = "b".repeat(64);
    private static final String FILE_HASH = "a".repeat(64);
    private final ProjectService projects = mock(ProjectService.class);
    private final CandidateChangeArtifactService candidates = mock(CandidateChangeArtifactService.class);
    private final CompletionVerifier verifier = new CompletionVerifier(new ObjectMapper(), new ProjectEvidenceValidator(projects), candidates);

    CompletionVerifierTest() {
        when(projects.manifest(anyLong(), anyLong())).thenReturn(new ProjectManifestResponse(42L, PROJECT_VERSION, List.of(
                new ProjectFileEntry("src/Main.java", 1, Instant.EPOCH, FILE_HASH),
                new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, FILE_HASH))));
    }

    @Test
    void projectClaimWithoutCurrentFileEvidenceCannotBeVerified() {
        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT), success("已全面审查所有文件", List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.assistantContent()).isEqualTo(
                "Insufficient Project evidence: no authorized file read/search observation was captured; this is not a complete review.");
        assertThat(result.messages()).filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .singleElement().extracting(ChatMessage::content).isEqualTo(result.assistantContent());
    }

    @Test
    void serverVerifiedRouterDirectKnowledgeAnswerDoesNotRequireProjectFileEvidence() {
        AgentOrchestrationRequirements routerKnowledge = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE),
                List.of(AgentStrategyReasonCode.LLM_ROUTER_DIRECT), List.of(),
                AgentStrategySelectionOrigin.LLM_ROUTER);
        AgentRuntimeRequest request = projectRequest(AgentStrategy.DIRECT, "1+1等于多少？不要读取项目文件。")
                .withOrchestrationRequirements(routerKnowledge)
                .withoutToolAuthority("direct_strategy_deny_all");

        AgentRuntimeResult result = verifier.verify(request, success("2", List.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("2");
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(request.toolPolicy().allowedTools()).isEmpty();
        assertThat(request.toolPolicy().maxToolCalls()).isZero();
    }

    @Test
    void serverVerifiedRouterFallbackDirectKnowledgeAnswerDoesNotRequireProjectFileEvidence() {
        AgentOrchestrationRequirements routerFallback = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE),
                List.of(AgentStrategyReasonCode.LLM_ROUTER_INVALID_RESPONSE,
                        AgentStrategyReasonCode.LLM_ROUTER_FALLBACK_DIRECT),
                List.of(), AgentStrategySelectionOrigin.ROUTER_FALLBACK);
        AgentRuntimeRequest request = projectRequest(AgentStrategy.DIRECT, "1+1等于多少？")
                .withOrchestrationRequirements(routerFallback)
                .withoutToolAuthority("direct_strategy_deny_all");

        AgentRuntimeResult result = verifier.verify(request, success("2", List.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(request.toolPolicy().allowedTools()).isEmpty();
        assertThat(request.toolPolicy().maxToolCalls()).isZero();
    }

    @Test
    void directWithoutTheVerifiedRouterKnowledgeAuditStillRequiresProjectEvidence() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.DIRECT,
                "State what README says about this Project.")
                .withoutToolAuthority("direct_strategy_deny_all");

        AgentRuntimeResult result = verifier.verify(request,
                success("README says the Project uses Java.", List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.completionVerification().status())
                .isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
    }

    @ParameterizedTest
    @EnumSource(value = AgentStrategy.class, names = {"SINGLE_STEP_REACT", "PLAN_EXECUTE"})
    void reactAndPlanProjectClaimsWithoutEvidenceRemainRejected(AgentStrategy strategy) {
        AgentRuntimeResult result = verifier.verify(projectRequest(strategy,
                "State what README says about this Project."),
                success("README says the Project uses Java.", List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.completionVerification().status())
                .isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
    }

    @ParameterizedTest
    @EnumSource(value = AgentStrategy.class, names = {"SINGLE_STEP_REACT", "PLAN_EXECUTE"})
    void routerFallbackReactAndPlanNeverReceiveTheDirectEvidenceExemption(AgentStrategy strategy) {
        AgentStrategyReasonCode reason = strategy == AgentStrategy.PLAN_EXECUTE
                ? AgentStrategyReasonCode.LLM_ROUTER_FALLBACK_PLAN
                : AgentStrategyReasonCode.LLM_ROUTER_FALLBACK_REACT;
        AgentOrchestrationRequirements fallback = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE), List.of(reason), List.of(),
                AgentStrategySelectionOrigin.ROUTER_FALLBACK);
        AgentRuntimeRequest request = projectRequest(strategy, "State what README says about this Project.")
                .withOrchestrationRequirements(fallback);

        AgentRuntimeResult result = verifier.verify(request,
                success("README says the Project uses Java.", List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.completionVerification().status())
                .isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void naturalLanguageModificationAnswerDoesNotBecomeCandidate() {
        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT), success("建议修复空指针", List.of(tool(42, "src/Main.java", "h1"))));

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("VERIFIED");
        assertThat(result.candidateChangeSet()).isNull();
        assertThat(result.candidateArtifact()).isNull();
        verify(candidates, never()).store(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void toolBudgetStopWithUsefulAnswerAndTrustedEvidenceIsNormallyDeliveredAsPartial() {
        AgentRuntimeResult raw = success("Partial answer from completed Project reads.",
                List.of(tool(42, "src/Main.java", "h1")))
                .withRuntimeStopSignal(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED);

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT), raw);

        assertThat(result.success()).isTrue();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.TOOL_CALL_BUDGET_EXHAUSTED);
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.trustedEvidenceLedger().evidence()).singleElement();
    }

    @Test
    void toolBudgetStopWithoutRequiredProjectEvidenceRemainsFailClosed() {
        AgentRuntimeResult raw = success("Unsupported partial Project claim.", List.of())
                .withRuntimeStopSignal(AgentRuntimeStopSignal.TOOL_CALL_BUDGET_EXHAUSTED);

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT), raw);

        assertThat(result.success()).isFalse();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.PLAN_PARTIAL);
        assertThat(result.outcome()).isEqualTo("PARTIAL");
    }

    @Test
    void planWaitingForSandboxConfirmationIsAControlledPartialWithoutInternalFailureText() {
        String internal = "SANDBOX_CONFIRMATION_REQUIRED: confirm scope; DOMAIN_REQUIRED_TOOL_NOT_EXECUTED";
        AgentRuntimeResult raw = new AgentRuntimeResult(false, internal,
                List.of(ChatMessage.assistant(internal)), 1, null, List.of(), List.of(), null, null, null)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.WAITING_FOR_USER,
                        "WAITING", false, null)
                .withPlanId(99L);

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.PLAN_EXECUTE,
                "Run src/Main.java in the governed sandbox."), raw);

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.WAITING_FOR_USER);
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(result.errorMessage()).isNull();
        assertThat(result.assistantContent())
                .isEqualTo("Plan execution is waiting for your confirmation or another required action.")
                .doesNotContain("SANDBOX_CONFIRMATION_REQUIRED", "DOMAIN_");
        assertThat(result.messages()).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement().extracting(ChatMessage::content).isEqualTo(result.assistantContent());
    }

    @Test
    void failedToolCallDoesNotEraseLaterTrustedEvidenceAndUsefulAnswer() {
        AgentRuntimeResult raw = new AgentRuntimeResult(true, "Useful answer from the narrower successful read.",
                List.of(tool(42, "src/Main.java", "h1")), 2, null,
                List.of(
                        "step=1 tool=project_search args={query=wide} success=false error=input scope too large",
                        "step=2 tool=project_read_file args={relativePath=src/Main.java} success=true"),
                List.of(), null, null, null);

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT), raw);

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.PLAN_PARTIAL);
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(result.trustedEvidenceLedger().evidence()).singleElement();
    }

    @Test
    void governedResearchEnvelopeClosesCompletionEvidenceLoopAndStaleHashIsDiscarded() {
        ProjectService researchProjects = mock(ProjectService.class);
        String hash = "a".repeat(64);
        when(researchProjects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(42L, "b".repeat(64), List.of(
                new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, hash))));
        CompletionVerifier researchVerifier = new CompletionVerifier(new ObjectMapper(), new ProjectEvidenceValidator(researchProjects),
                mock(CandidateChangeArtifactService.class));

        AgentRuntimeResult current = researchVerifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("grounded", researchTranscript(hash)));
        AgentRuntimeResult stale = researchVerifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("grounded", researchTranscript("c".repeat(64))));

        assertThat(current.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(current.trustedEvidenceLedger().evidence()).singleElement().extracting(EvidenceRef::version).isEqualTo(hash);
        assertThat(stale.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void historicalCrossProjectAndWrongHashEvidenceCannotPass() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT);
        AgentRuntimeResult historicalOnly = verifier.verify(request, success("review", List.of()));
        EvidenceLedger foreign = new EvidenceLedger(List.of(new EvidenceRef("trusted-tool:99:src/Main.java:h1:c1",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:c1", null, "h1", "test")));
        AgentRuntimeResult foreignResult = verifier.verify(request, success("review", List.of()).withEvidenceLedger(foreign));
        EvidenceLedger wrongHash = new EvidenceLedger(List.of(new EvidenceRef("trusted-tool:42:src/Main.java:old:c1",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:c1", null, "old", "test")));
        CandidateChangeSet candidate = new CandidateChangeSet(42L, "src/Main.java", "h1", "s", "p", List.of(), null, null);

        assertThat(historicalOnly.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(foreignResult.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(candidate.revalidate(wrongHash).status()).isEqualTo(CandidateChangeStatus.STALE);
    }

    @Test
    void currentInheritedPlanEvidenceCanGroundSummaryWithoutANewToolCall() {
        EvidenceRef inherited = versionedPlan("trusted-plan:42:src/Main.java:h1:step-1", FILE_HASH);
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT)
                .withInheritedTrustedEvidence(new EvidenceLedger(List.of(inherited)));

        AgentRuntimeResult result = verifier.verify(request, success("summary from completed dependencies", List.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(result.trustedEvidenceLedger().evidence()).singleElement().satisfies(ref -> {
            assertThat(ref.id()).isEqualTo(inherited.id());
            assertThat(ref.versionStatus()).isEqualTo(EvidenceVersionStatus.VERIFIED);
            assertThat(ref.projectVersion()).isEqualTo(PROJECT_VERSION);
            assertThat(ref.fileHash()).isEqualTo(FILE_HASH);
        });
    }

    @Test
    void staleInheritedPlanEvidenceCannotGroundSummary() {
        EvidenceRef stale = new EvidenceRef("trusted-plan:42:src/Main.java:old:step-1",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:step-1", null, "old", "test");
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT)
                .withInheritedTrustedEvidence(new EvidenceLedger(List.of(stale)));

        AgentRuntimeResult result = verifier.verify(request, success("stale summary", List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.trustedEvidenceLedger().evidence()).isEmpty();
    }

    @Test
    void legacyPlanEvidenceIsNotUpgradedEvenWhenPathAndHashAreCurrent() {
        EvidenceRef legacy = new EvidenceRef("trusted-plan:42:legacy", EvidenceSourceType.PROJECT, "PROJECT",
                "src/Main.java", "tool:old", null, FILE_HASH, "legacy");
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT)
                .withInheritedTrustedEvidence(new EvidenceLedger(List.of(legacy)));

        AgentRuntimeResult result = verifier.verify(request, success("legacy summary", List.of()));

        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.trustedEvidenceLedger().evidence()).isEmpty();
    }

    @Test
    void searchEvidencePreservesEachHitLineAndRejectsWrongHash() {
        String json = "{\"projectId\":42,\"hits\":["
                + "{\"relativePath\":\"src/Main.java\",\"hash\":\"" + FILE_HASH + "\",\"lineNumber\":7},"
                + "{\"relativePath\":\"src/Main.java\",\"hash\":\"" + FILE_HASH + "\",\"lineNumber\":11},"
                + "{\"relativePath\":\"src/Main.java\",\"hash\":\"" + "f".repeat(64) + "\",\"lineNumber\":13}]}";

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("search results", List.of(new ChatMessage("tool", json, null, "search-1"))));

        assertThat(result.trustedEvidenceLedger().evidence()).hasSize(2)
                .extracting(EvidenceRef::startLine).containsExactly(7, 11);
        assertThat(result.trustedEvidenceLedger().evidence()).allSatisfy(ref -> {
            assertThat(ref.startLine()).isEqualTo(ref.endLine());
            assertThat(ref.parserVersion()).isEqualTo("project-search@1");
        });
    }

    @Test
    void foreignProjectInheritedEvidenceCannotGroundSummaryEvenWithMatchingPathAndHash() {
        EvidenceRef foreign = new EvidenceRef("trusted-plan:99:src/Main.java:h1:step-1",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:step-1", null, "h1", "test");
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT)
                .withInheritedTrustedEvidence(new EvidenceLedger(List.of(foreign)));

        AgentRuntimeResult result = verifier.verify(request, success("foreign summary", List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.trustedEvidenceLedger().evidence()).isEmpty();
    }

    @Test
    void planNamespacedInheritedEvidenceDoesNotCollideWithReusedProviderToolCallId() {
        EvidenceRef inherited = versionedPlan("trusted-plan:42:19:101:1:stable-observation", FILE_HASH);
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT)
                .withInheritedTrustedEvidence(new EvidenceLedger(List.of(inherited)));

        AgentRuntimeResult result = verifier.verify(request,
                success("summary with one fresh observation", List.of(tool(42, "src/Main.java", "h1"))));

        assertThat(result.success()).isTrue();
        assertThat(result.trustedEvidenceLedger().evidence()).hasSize(2);
        assertThat(result.trustedEvidenceLedger().evidence()).extracting(EvidenceRef::id)
                .contains(inherited.id(), "trusted-tool:42:src/Main.java:" + FILE_HASH + ":call-1");
    }

    @Test
    void rawLedgerCannotBypassTrustedCurrentObservationRequirement() {
        EvidenceLedger forgedCurrent = new EvidenceLedger(List.of(new EvidenceRef("trusted-tool:42:src/Main.java:h1:forged",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:forged", null, "h1", "forged")));

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("claimed", List.of()).withEvidenceLedger(forgedCurrent));

        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void reflectionExecutesAtMostOneRepairTurn() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy strategy) { return true; }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest request) {
                calls.incrementAndGet();
                List<ChatMessage> runtimeMessages = new ArrayList<>(request.history());
                if (calls.get() > 1) runtimeMessages.add(tool(42, "src/Main.java", "h1"));
                return success(calls.get() == 1 ? "claimed complete" : "repaired", runtimeMessages);
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier, new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult result = runtime.run(projectRequest(AgentStrategy.SINGLE_STEP_REACT));

        assertThat(calls).hasValue(2);
        assertThat(result.completionVerification().reflectionAttempts()).isEqualTo(1);
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    @Test
    void explicitMissingProjectTargetIsNotRetriedByCompletionReflection() {
        AtomicInteger calls = new AtomicInteger();
        String missingPath = "good_code/s2/__worker10_11_missing_boundary_test__.py";
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy strategy) { return true; }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest request) {
                calls.incrementAndGet();
                return new AgentRuntimeResult(true,
                        ProjectMaterialScope.MISSING_TARGET_PREFIX + " " + missingPath,
                        new ArrayList<>(request.history()), 1, null,
                        List.of("step=1 tool=project_read_file executed=true budgetConsumed=true "
                                + "success=false reused=false skipped=false error=404 NOT_FOUND"),
                        List.of(ProjectMaterialScope.MISSING_TARGET_PREFIX + " " + missingPath),
                        null, null, null);
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier,
                new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult result = runtime.run(projectRequest(AgentStrategy.SINGLE_STEP_REACT,
                "read " + missingPath));

        assertThat(calls).hasValue(1);
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(result.completionVerification().repairable()).isFalse();
        assertThat(result.completionVerification().reflectionAttempts()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = AgentStrategy.class, names = {"DIRECT", "SINGLE_STEP_REACT"})
    void failedFirstToolAttemptCanBeRecoveredByOneAuthorizedLaterSuccess(AgentStrategy strategy) {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AgentRuntimeRequest> repairRequest = new AtomicReference<>();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy ignored) { return true; }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest request) {
                int call = calls.incrementAndGet();
                DomainRuntimeFacts.ToolOutcome outcome = new DomainRuntimeFacts.ToolOutcome(
                        "project_read_file", 1, null, true, true, call > 1, false, false);
                if (call == 1) {
                    return repairableFailure("first call failed", new ArrayList<>(request.history()))
                            .withDomainRuntimeFacts(new DomainRuntimeFacts(List.of(outcome), List.of(), List.of()));
                }
                repairRequest.set(request);
                List<ChatMessage> messages = new ArrayList<>(request.history());
                messages.add(tool(42, "src/Main.java", "h1"));
                return success("recovered", messages)
                        .withDomainRuntimeFacts(new DomainRuntimeFacts(List.of(outcome), List.of(), List.of()));
            }
        };
        AgentRuntimeRequest original = projectRequest(strategy);
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier,
                new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult result = runtime.run(original);

        assertThat(calls).hasValue(2);
        assertThat(result.outcome()).isEqualTo("VERIFIED");
        assertThat(result.completionVerification().reflectionAttempts()).isEqualTo(1);
        assertThat(result.domainRuntimeFacts().hasUnrecoveredToolFailure(original.orchestrationRequirements()))
                .isFalse();
        assertThat(result.domainRuntimeFacts().toolOutcomes())
                .extracting(DomainRuntimeFacts.ToolOutcome::executionAttempt).containsExactly(0, 1);
        assertThat(repairRequest.get().toolPolicy().allowedTools())
                .containsExactlyElementsOf(original.toolPolicy().allowedTools());
        assertThat(repairRequest.get().projectContext()).isEqualTo(original.projectContext());
        assertThat(repairRequest.get().repairContext()).isNotNull();
        assertThat(repairRequest.get().repairContext().failedTool()).isEqualTo("project_read_file");
        assertThat(repairRequest.get().repairContext().remainingAttempts()).isEqualTo(1);
        assertThat(repairRequest.get().withStrategy(strategy).repairContext())
                .isEqualTo(repairRequest.get().repairContext());
    }

    @Test
    void failedRepairRemainsPartialAndCannotStartASecondReflection() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy ignored) { return true; }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest request) {
                calls.incrementAndGet();
                DomainRuntimeFacts.ToolOutcome failed = new DomainRuntimeFacts.ToolOutcome(
                        "project_read_file", 1, null, true, true, false, false, false);
                return repairableFailure("still unsupported", new ArrayList<>(request.history()))
                        .withDomainRuntimeFacts(new DomainRuntimeFacts(List.of(failed), List.of(), List.of()));
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier,
                new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult result = runtime.run(projectRequest(AgentStrategy.SINGLE_STEP_REACT));

        assertThat(calls).hasValue(2);
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.completionVerification().reflectionAttempts()).isEqualTo(1);
        assertThat(result.completionVerification().repairable()).isFalse();
        assertThat(result.domainRuntimeFacts()
                .hasUnrecoveredToolFailure(AgentOrchestrationRequirements.empty())).isTrue();
    }

    @Test
    void manifestOnlyAttemptRepairsWithReadSearchInstructionAndKeepsOneFinalTranscript() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AgentRuntimeRequest> capturedRepair = new AtomicReference<>();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy strategy) { return true; }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest request) {
                int call = calls.incrementAndGet();
                List<ChatMessage> runtimeMessages = new ArrayList<>(request.history());
                if (call == 1) {
                    runtimeMessages.add(new ChatMessage("tool",
                            "{\"projectId\":42,\"relativePath\":\"manifest\",\"hash\":\"m\",\"version\":\"m\"}",
                            null, "manifest-call"));
                    runtimeMessages.add(ChatMessage.assistant("Unverified manifest-only answer."));
                    return new AgentRuntimeResult(true, "Unverified manifest-only answer.", runtimeMessages, 1, null,
                            List.of("step=1 tool=project_manifest success=true"), List.of(), 2, 3, 5);
                }
                capturedRepair.set(request);
                runtimeMessages.add(tool(42, "src/Main.java", "h1"));
                runtimeMessages.add(ChatMessage.assistant("Repaired file-grounded answer."));
                return new AgentRuntimeResult(true, "Repaired file-grounded answer.", runtimeMessages, 1, null,
                        List.of("step=1 tool=project_read_file success=true"), List.of(), 4, 5, 9);
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier,
                new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult result = runtime.run(projectRequest(AgentStrategy.SINGLE_STEP_REACT));

        assertThat(calls).hasValue(2);
        assertThat(capturedRepair.get().history()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("system");
            assertThat(message.content()).contains("project_read_file", "project_search", "project_manifest");
        });
        assertThat(capturedRepair.get().projectContext()).isEqualTo(new ProjectRuntimeContext(7L, 42L));
        assertThat(capturedRepair.get().toolPolicy().allowedTools())
                .isEqualTo(projectRequest(AgentStrategy.SINGLE_STEP_REACT).toolPolicy().allowedTools());
        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Repaired file-grounded answer.");
        assertThat(result.messages()).noneMatch(message -> "Unverified manifest-only answer.".equals(message.content()));
        assertThat(result.messages()).filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .singleElement().extracting(ChatMessage::content).isEqualTo("Repaired file-grounded answer.");
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.promptTokens()).isEqualTo(6);
        assertThat(result.completionTokens()).isEqualTo(8);
        assertThat(result.totalTokens()).isEqualTo(14);
        assertThat(result.trustedEvidenceLedger().evidence()).singleElement()
                .extracting(EvidenceRef::file).isEqualTo("src/Main.java");
    }

    @Test
    void exactDuplicateCurrentEvidenceIsDeduplicatedWithoutClearingResult() {
        ChatMessage observation = tool(42, "src/Main.java", "h1");

        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("Observed once.", List.of(observation, observation)));

        assertThat(result.success()).isTrue();
        assertThat(result.assistantContent()).isEqualTo("Observed once.");
        assertThat(result.trustedEvidenceLedger().evidence()).singleElement()
                .extracting(EvidenceRef::id).asString().contains("trusted-tool:42:src/Main.java:" + FILE_HASH + ":call-1");
    }

    @Test
    void repairDoesNotReclassifyRepeatedHistoryAsCurrentEvidence() {
        ChatMessage historicalAssistant = ChatMessage.assistant("previous turn answer");
        ChatMessage historical = tool(42, "src/Main.java", "h1");
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT,
                List.of(historicalAssistant, historical));
        AtomicInteger calls = new AtomicInteger();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy strategy) { return true; }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest current) {
                calls.incrementAndGet();
                List<ChatMessage> runtimeMessages = new ArrayList<>(current.history());
                runtimeMessages.add(new ChatMessage("tool",
                        "{\"projectId\":42,\"relativePath\":\"manifest\",\"hash\":\"m\",\"version\":\"m\"}",
                        null, "same-manifest-call"));
                runtimeMessages.add(ChatMessage.assistant("Manifest-only claim."));
                return new AgentRuntimeResult(true, "Manifest-only claim.", runtimeMessages, 1, null,
                        List.of("step=1 tool=project_manifest success=true"), List.of(), null, null, null);
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier,
                new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult result = runtime.run(request);

        assertThat(calls).hasValue(2);
        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.trustedEvidenceLedger().evidence()).isEmpty();
        assertThat(result.toolTrace()).hasSize(2);
        assertThat(result.fallbacks()).noneMatch(value -> value.contains("duplicate evidence id"));
        assertThat(result.messages()).filteredOn(message -> historicalAssistant.equals(message)).hasSize(1);
        assertThat(result.messages()).filteredOn(message -> historical.equals(message)).hasSize(1);
        assertThat(result.messages()).filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .hasSize(2);
        assertThat(AgentService.runtimeMessagesToPersist(result.messages(), 2))
                .filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .singleElement().extracting(ChatMessage::content).isEqualTo(result.assistantContent());
    }

    @Test
    void reflectionPreservesAuthorityBudgetAndProjectIdentity() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT);

        assertThat(new CompletionReflection().preserveAuthority(request)).isSameAs(request);
    }

    @Test
    void projectDenyAllAndToolBudgetExhaustionDoNotRunRepair() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy strategy) { return true; }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest ignored) { calls.incrementAndGet(); return success("claim", List.of()); }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier, new CompletionReflection(), new AdapterCompletionRepairExecutor());
        AgentRuntimeRequest denied = new AgentRuntimeRequest(AgentStrategy.SINGLE_STEP_REACT, 1L, List.of(), 7L, "inspect",
                "test", "model", null, null, 2, true, "skill", null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, new ResolvedToolPolicy(List.of(), 0, 0, "deny"),
                null, null, "trace", null, null).withProjectContext(new ProjectRuntimeContext(7L, 42L));

        runtime.run(denied);

        assertThat(calls).hasValue(1);
    }

    @Test
    void repairRequestRetainsAuthorityAndOnlyReducesBudgets() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT);
        AgentRuntimeRequest repair = new CompletionReflection().repairRequest(request, success("first", List.of()));

        assertThat(repair.projectContext()).isEqualTo(request.projectContext());
        assertThat(repair.provider()).isEqualTo(request.provider());
        assertThat(repair.model()).isEqualTo(request.model());
        assertThat(repair.skillId()).isEqualTo(request.skillId());
        assertThat(repair.toolPolicy().allowedTools()).isEqualTo(request.toolPolicy().allowedTools());
        assertThat(repair.maxSteps()).isLessThanOrEqualTo(request.maxSteps());
        assertThat(repair.toolPolicy().maxToolCalls()).isLessThanOrEqualTo(request.toolPolicy().maxToolCalls());
    }

    @Test
    void repairUsesRemainingTypedBudgetWithoutChangingPolicyEndpointOrIdentity() {
        AgentRuntimeRequest request = projectRequest(AgentStrategy.SINGLE_STEP_REACT);
        DomainRuntimeFacts firstFacts = new DomainRuntimeFacts(List.of(
                new DomainRuntimeFacts.ToolOutcome("project_read_file", 1, null,
                        true, true, true, false, false)), List.of(), List.of());
        AgentRuntimeResult first = success("first", List.of()).withDomainRuntimeFacts(firstFacts);

        AgentRuntimeRequest repair = new CompletionReflection().repairRequest(request, first);

        assertThat(repair.userId()).isEqualTo(request.userId());
        assertThat(repair.sessionId()).isEqualTo(request.sessionId());
        assertThat(repair.projectContext()).isEqualTo(request.projectContext());
        assertThat(repair.apiKey()).isEqualTo(request.apiKey());
        assertThat(repair.apiUrl()).isEqualTo(request.apiUrl());
        assertThat(repair.strategy()).isEqualTo(request.strategy());
        assertThat(repair.toolPolicy().allowedTools()).containsExactlyElementsOf(request.toolPolicy().allowedTools());
        assertThat(repair.toolPolicy().maxToolCalls()).isEqualTo(1);
        assertThat(repair.maxSteps()).isEqualTo(1);
    }

    @Test
    void autoCrossMaterialPlanWithCoverageButNoStructuredConsistencyFactIsPartial() {
        String paperTool = ResearchToolContracts.PROJECT_LATEX_OUTLINE;
        String codeTool = ResearchToolContracts.PROJECT_CODE_SYMBOLS;
        List<ResearchMaterialRequirement> requirements = List.of(
                new ResearchMaterialRequirement(ResearchMaterialKind.PAPER_LATEX,
                        List.of(paperTool), List.of(paperTool), true),
                new ResearchMaterialRequirement(ResearchMaterialKind.CODE,
                        List.of(codeTool), List.of(codeTool), true));
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK,
                        AgentStrategySignal.VERIFICATION_REQUIRED),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN), requirements,
                AgentStrategySelectionOrigin.SERVER_AUTO);
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 1L, List.of(), 7L,
                "compare paper and code, then verify consistency", "test", "model", null, null, 4,
                true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of(paperTool, codeTool), 4, 1, "project"), 4, 1,
                "trace", null, null).withProjectContext(new ProjectRuntimeContext(7L, 42L))
                .withOrchestrationRequirements(audit);
        EvidenceLedger evidence = new EvidenceLedger(List.of(
                new EvidenceRef("trusted-plan:42:19:1:paper", EvidenceSourceType.PROJECT, "PROJECT",
                        "paper/main.tex", "step:paper", null, FILE_HASH, "plan", PROJECT_VERSION, FILE_HASH,
                        1, 2, "latex@1", EvidenceVersionStatus.VERIFIED),
                new EvidenceRef("trusted-plan:42:19:2:code", EvidenceSourceType.PROJECT, "PROJECT",
                        "src/Main.java", "step:code", null, FILE_HASH, "plan", PROJECT_VERSION, FILE_HASH,
                        1, 2, "code@1", EvidenceVersionStatus.VERIFIED)));
        DomainRuntimeFacts facts = new DomainRuntimeFacts(List.of(
                new DomainRuntimeFacts.ToolOutcome(paperTool, 1, "paper", true, true, true, false, false),
                new DomainRuntimeFacts.ToolOutcome(codeTool, 1, "code", true, true, true, false, false)),
                List.of(new DomainRuntimeFacts.PlanStepOutcome(
                        "verify", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false)), List.of());
        AgentRuntimeResult raw = success("Plan 19 execution lifecycle status: COMPLETED.\n\n"
                        + "unsupported consistency claim", List.of())
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.COMPLETED, "SUCCESS", false, null)
                .withPlanId(19L).withTrustedEvidenceLedger(evidence).withDomainRuntimeFacts(facts);

        AgentRuntimeResult result = verifier.verify(request, raw);

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(result.completionVerification().repairable()).isFalse();
        assertThat(result.completionVerification().domainVerification().consistencyStatus())
                .isEqualTo(DomainVerification.ConsistencyStatus.UNRESOLVED);
        assertThat(result.completionVerification().domainVerification().materialCoverage())
                .allMatch(item -> item.status() == DomainVerification.MaterialStatus.COVERAGE_VERIFIED);
        assertThat(result.assistantContent())
                .startsWith("Governed completion status: PARTIAL\nCross-material consistency: UNRESOLVED")
                .contains("execution lifecycle status: COMPLETED", "unsupported consistency claim")
                .doesNotContain("outcome SUCCESS");
        assertThat(result.messages()).filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .singleElement().extracting(ChatMessage::content).isEqualTo(result.assistantContent());
        verify(candidates, never()).store(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void degradedCompletedPlanStillProjectsCanonicalPartialAndUnresolvedGovernance() {
        String paperTool = ResearchToolContracts.PROJECT_LATEX_OUTLINE;
        String codeTool = ResearchToolContracts.PROJECT_CODE_SYMBOLS;
        List<ResearchMaterialRequirement> requirements = List.of(
                new ResearchMaterialRequirement(ResearchMaterialKind.PAPER_LATEX,
                        List.of(paperTool), List.of(paperTool), true),
                new ResearchMaterialRequirement(ResearchMaterialKind.CODE,
                        List.of(codeTool), List.of(codeTool), true));
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK,
                        AgentStrategySignal.VERIFICATION_REQUIRED),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN), requirements,
                AgentStrategySelectionOrigin.SERVER_AUTO);
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 1L, List.of(), 7L,
                "compare paper and code, then verify consistency", "test", "model", null, null, 4,
                true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of(paperTool, codeTool), 4, 1, "project"), 4, 1,
                "trace", null, null).withProjectContext(new ProjectRuntimeContext(7L, 42L))
                .withOrchestrationRequirements(audit);
        EvidenceLedger evidence = new EvidenceLedger(List.of(
                new EvidenceRef("trusted-plan:42:19:1:paper", EvidenceSourceType.PROJECT, "PROJECT",
                        "paper/main.tex", "step:paper", null, FILE_HASH, "plan", PROJECT_VERSION, FILE_HASH,
                        1, 2, "latex@1", EvidenceVersionStatus.VERIFIED),
                new EvidenceRef("trusted-plan:42:19:2:code", EvidenceSourceType.PROJECT, "PROJECT",
                        "src/Main.java", "step:code", null, FILE_HASH, "plan", PROJECT_VERSION, FILE_HASH,
                        1, 2, "code@1", EvidenceVersionStatus.VERIFIED)));
        DomainRuntimeFacts facts = new DomainRuntimeFacts(List.of(
                new DomainRuntimeFacts.ToolOutcome(paperTool, 1, "paper", true, true, true, false, false),
                new DomainRuntimeFacts.ToolOutcome(codeTool, 1, "code", true, true, true, false, false)),
                List.of(
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "paper", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "code", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "cross_check", DomainRuntimeFacts.PlanStepStatus.DEGRADED, false)),
                List.of());
        String planContent = "Plan 19 execution lifecycle status: COMPLETED.\n"
                + "Plan execution outcome: PARTIAL.\n\n"
                + "Cross-check [DEGRADED]: bounded semantic assessment";
        AgentRuntimeResult raw = success(planContent, List.of())
                .asVerifiedFailure("Plan completed with degraded steps.")
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.PLAN_PARTIAL,
                        "PARTIAL", true, AgentStrategy.PLAN_EXECUTE)
                .withPlanId(19L)
                .withTrustedEvidenceLedger(evidence)
                .withDomainRuntimeFacts(facts);

        AgentRuntimeResult result = verifier.verify(request, raw);

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(result.completionVerification().domainVerification().consistencyStatus())
                .isEqualTo(DomainVerification.ConsistencyStatus.UNRESOLVED);
        assertThat(result.assistantContent())
                .startsWith("Governed completion status: PARTIAL\nCross-material consistency: UNRESOLVED")
                .contains("execution lifecycle status: COMPLETED", "execution outcome: PARTIAL",
                        "bounded semantic assessment")
                .doesNotContain("Governed completion status: VERIFIED", "Governed completion status: FAILED");
        assertThat(result.messages()).filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .singleElement().extracting(ChatMessage::content).isEqualTo(result.assistantContent());
    }

    @Test
    void requestedHashRuleProducesAndExposesProductionConsistencyFact() {
        String paperTool = ResearchToolContracts.PROJECT_LATEX_OUTLINE;
        String codeTool = ResearchToolContracts.PROJECT_CODE_SYMBOLS;
        List<ResearchMaterialRequirement> requirements = List.of(
                new ResearchMaterialRequirement(ResearchMaterialKind.PAPER_LATEX,
                        List.of(paperTool), List.of(paperTool), true),
                new ResearchMaterialRequirement(ResearchMaterialKind.CODE,
                        List.of(codeTool), List.of(codeTool), true));
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK,
                        AgentStrategySignal.VERIFICATION_REQUIRED),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN), requirements,
                AgentStrategySelectionOrigin.SERVER_AUTO,
                List.of(DomainConsistencyCheck.EVIDENCE_FILE_HASH_EQUALITY));
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 1L, List.of(), 7L,
                "verify the paper and code evidence files have the same content hash", "test", "model",
                null, null, 4, true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of(paperTool, codeTool), 4, 1, "project"), 4, 1,
                "trace", null, null).withProjectContext(new ProjectRuntimeContext(7L, 42L))
                .withOrchestrationRequirements(audit);
        EvidenceLedger evidence = new EvidenceLedger(List.of(
                new EvidenceRef("trusted-plan:42:19:1:paper", EvidenceSourceType.PROJECT, "PROJECT",
                        "paper/main.tex", "step:paper", null, FILE_HASH, "plan", PROJECT_VERSION, FILE_HASH,
                        1, 2, "latex@1", EvidenceVersionStatus.VERIFIED),
                new EvidenceRef("trusted-plan:42:19:2:code", EvidenceSourceType.PROJECT, "PROJECT",
                        "src/Main.java", "step:code", null, FILE_HASH, "plan", PROJECT_VERSION, FILE_HASH,
                        1, 2, "code@1", EvidenceVersionStatus.VERIFIED)));
        DomainRuntimeFacts facts = new DomainRuntimeFacts(List.of(
                new DomainRuntimeFacts.ToolOutcome(paperTool, 1, "paper", true, true, true, false, false),
                new DomainRuntimeFacts.ToolOutcome(codeTool, 1, "code", true, true, true, false, false)),
                List.of(new DomainRuntimeFacts.PlanStepOutcome(
                        "verify", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false)), List.of());
        AgentRuntimeResult raw = success("hash audit complete", List.of())
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.COMPLETED, "SUCCESS", false, null)
                .withPlanId(19L).withTrustedEvidenceLedger(evidence).withDomainRuntimeFacts(facts);

        AgentRuntimeResult result = verifier.verify(request, raw);

        assertThat(result.outcome()).isEqualTo("VERIFIED");
        assertThat(result.completionVerification().domainVerification().consistencyStatus())
                .isEqualTo(DomainVerification.ConsistencyStatus.VERIFIED_CONSISTENT);
        assertThat(result.assistantContent())
                .startsWith("Governed completion status: VERIFIED\nCross-material consistency: VERIFIED_CONSISTENT")
                .contains("does not prove broader semantic equivalence", "hash audit complete");
        assertThat(result.messages()).filteredOn(message -> "assistant".equals(message.role())
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                .singleElement().extracting(ChatMessage::content).isEqualTo(result.assistantContent());
        assertThat(result.domainRuntimeFacts().consistencyFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.ruleId()).isEqualTo(DomainConsistencyRuleEngine.EVIDENCE_FILE_HASH_EQUALITY_RULE);
            assertThat(fact.consistent()).isTrue();
        });
    }

    @Test
    void requestedHashRuleDeterministicallyVerifiesDifferentCurrentProjectFilesAsInconsistent() {
        String codeHash = "c".repeat(64);
        when(projects.manifest(anyLong(), anyLong())).thenReturn(new ProjectManifestResponse(
                42L, PROJECT_VERSION, List.of(
                new ProjectFileEntry("paper/main.tex", 1, Instant.EPOCH, FILE_HASH),
                new ProjectFileEntry("src/Main.java", 2, Instant.EPOCH, codeHash))));
        String paperTool = ResearchToolContracts.PROJECT_LATEX_OUTLINE;
        String codeTool = ResearchToolContracts.PROJECT_CODE_SYMBOLS;
        List<ResearchMaterialRequirement> requirements = List.of(
                new ResearchMaterialRequirement(ResearchMaterialKind.PAPER_LATEX,
                        List.of(paperTool), List.of(paperTool), true),
                new ResearchMaterialRequirement(ResearchMaterialKind.CODE,
                        List.of(codeTool), List.of(codeTool), true));
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK,
                        AgentStrategySignal.VERIFICATION_REQUIRED),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN), requirements,
                AgentStrategySelectionOrigin.SERVER_AUTO,
                List.of(DomainConsistencyCheck.EVIDENCE_FILE_HASH_EQUALITY));
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 1L, List.of(), 7L,
                "\u6bd4\u8f83\u8bba\u6587\u4e0e\u4ee3\u7801\u7684\u5b8c\u6574\u5185\u5bb9\u54c8\u5e0c\u6216\u5b57\u8282\u662f\u5426\u5b8c\u5168\u4e00\u81f4", "test", "model",
                null, null, 4, true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of(paperTool, codeTool), 4, 1, "project"), 4, 1,
                "trace", null, null).withProjectContext(new ProjectRuntimeContext(7L, 42L))
                .withOrchestrationRequirements(audit);
        EvidenceLedger evidence = new EvidenceLedger(List.of(
                new EvidenceRef("trusted-plan:42:45:1:paper", EvidenceSourceType.PROJECT, "PROJECT",
                        "paper/main.tex", "step:paper", null, FILE_HASH, "plan", PROJECT_VERSION, FILE_HASH,
                        1, 2, "project-read-file@1", EvidenceVersionStatus.VERIFIED),
                new EvidenceRef("trusted-plan:42:45:2:code", EvidenceSourceType.PROJECT, "PROJECT",
                        "src/Main.java", "step:code", null, codeHash, "plan", PROJECT_VERSION, codeHash,
                        1, 2, "project-read-file@1", EvidenceVersionStatus.VERIFIED)));
        DomainRuntimeFacts facts = new DomainRuntimeFacts(List.of(
                new DomainRuntimeFacts.ToolOutcome(paperTool, 1, "step_1", true, true, true, false, false),
                new DomainRuntimeFacts.ToolOutcome(codeTool, 1, "step_2", true, true, true, false, false)),
                List.of(
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "step_1", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "step_2", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "step_3", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false)),
                List.of());
        AgentRuntimeResult raw = success("structured hash comparison: hashes differ", List.of())
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.COMPLETED, "SUCCESS", false, null)
                .withPlanId(45L).withTrustedEvidenceLedger(evidence).withDomainRuntimeFacts(facts);

        AgentRuntimeResult result = verifier.verify(request, raw);

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("VERIFIED");
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(result.completionVerification().domainVerification().consistencyStatus())
                .isEqualTo(DomainVerification.ConsistencyStatus.VERIFIED_INCONSISTENT);
        assertThat(result.completionVerification().domainVerification().reasonCodes())
                .contains(DomainVerificationReasonCode.CONSISTENCY_VERIFIED_INCONSISTENT_BY_STRUCTURED_FACT);
        assertThat(result.assistantContent())
                .startsWith("Governed completion status: VERIFIED\nCross-material consistency: VERIFIED_INCONSISTENT")
                .contains("does not prove broader semantic equivalence", "hashes differ");
        assertThat(result.domainRuntimeFacts().consistencyFacts()).singleElement().satisfies(fact -> {
            assertThat(fact.ruleId()).isEqualTo(DomainConsistencyRuleEngine.EVIDENCE_FILE_HASH_EQUALITY_RULE);
            assertThat(fact.consistent()).isFalse();
            assertThat(fact.evidenceRefs()).containsExactly(
                    "trusted-plan:42:45:1:paper", "trusted-plan:42:45:2:code");
        });
    }

    @Test
    void laterSuccessfulPlanAttemptRecoversOlderFailureButUnrelatedFailureDoesNot() {
        String codeTool = ResearchToolContracts.PROJECT_CODE_SYMBOLS;
        AgentRuntimeRequest request = planRecoveryRequest(List.of(codeTool, "project_read_file"));
        EvidenceLedger evidence = new EvidenceLedger(List.of(versionedPlan(
                "trusted-plan:42:19:1:code", FILE_HASH)));
        DomainRuntimeFacts recoveredFacts = new DomainRuntimeFacts(List.of(
                new DomainRuntimeFacts.ToolOutcome(codeTool, 1, "step_1",
                        true, true, false, false, false, 0),
                new DomainRuntimeFacts.ToolOutcome(codeTool, 1, "step_1",
                        true, true, true, false, false, 2)),
                List.of(new DomainRuntimeFacts.PlanStepOutcome(
                        "step_1", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false)), List.of());
        DomainRuntimeFacts unrelatedFacts = new DomainRuntimeFacts(List.of(
                new DomainRuntimeFacts.ToolOutcome(codeTool, 1, "step_1",
                        true, true, false, false, false, 0),
                new DomainRuntimeFacts.ToolOutcome("project_read_file", 1, "step_1",
                        true, true, true, false, false, 2)),
                recoveredFacts.planStepOutcomes(), List.of());

        AgentRuntimeResult recovered = verifier.verify(request, planResult(evidence, recoveredFacts));
        AgentRuntimeResult unrelated = verifier.verify(request, planResult(evidence, unrelatedFacts));

        assertThat(recovered.outcome()).isEqualTo("VERIFIED");
        assertThat(unrelated.outcome()).isEqualTo("PARTIAL");
        assertThat(unrelated.completionVerification().reasons())
                .contains("at least one governed tool call failed");
    }

    @Test
    void supersededFailedPlanStepDoesNotPoisonItsCompletedReplacement() {
        String codeTool = ResearchToolContracts.PROJECT_CODE_SYMBOLS;
        AgentRuntimeRequest request = planRecoveryRequest(List.of(codeTool));
        EvidenceLedger evidence = new EvidenceLedger(List.of(versionedPlan(
                "trusted-plan:42:19:2:code", FILE_HASH)));
        DomainRuntimeFacts facts = new DomainRuntimeFacts(List.of(
                new DomainRuntimeFacts.ToolOutcome(codeTool, 1, "step_1",
                        true, true, false, false, false, 0),
                new DomainRuntimeFacts.ToolOutcome(codeTool, 1, "repair_step_1",
                        true, true, true, false, false, 0)),
                List.of(
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "step_1", DomainRuntimeFacts.PlanStepStatus.SUPERSEDED, false, "repair_step_1"),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "repair_step_1", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false)),
                List.of());

        AgentRuntimeResult result = verifier.verify(request, planResult(evidence, facts));

        assertThat(result.outcome()).isEqualTo("VERIFIED");

        DomainRuntimeFacts missingReplacementMapping = new DomainRuntimeFacts(
                facts.toolOutcomes(),
                List.of(
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "step_1", DomainRuntimeFacts.PlanStepStatus.SUPERSEDED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "repair_step_1", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false)),
                List.of());
        assertThat(verifier.verify(request, planResult(evidence, missingReplacementMapping)).outcome())
                .isEqualTo("PARTIAL");
    }

    @Test
    void explicitStructuredIntentPersistsCandidateWithoutChangingVerifiedOutcome() {
        AgentRuntimeRequest modify = new AgentRuntimeRequest(AgentStrategy.SINGLE_STEP_REACT, 1L, List.of(), 7L, "suggest patch",
                "test", "model", null, null, 2, true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, new ResolvedToolPolicy(List.of("project_read_file"), 2, 1, "project"),
                null, null, "trace", null, null).withProjectContext(new ProjectRuntimeContext(7L, 42L));
        String evidenceId = "trusted-tool:42:src/Main.java:" + FILE_HASH + ":call-1";
        CandidateIntent intent = new CandidateIntent(42L, new ProjectVersionRef(PROJECT_VERSION), List.of(
                new CandidateIntent.FileIntent(CandidateIntent.Type.MODIFY,
                        new ProjectRelativePath("src/Main.java"), new FileHash(FILE_HASH),
                        "full replacement", List.of(evidenceId))));
        CandidateArtifactResponse artifact = mock(CandidateArtifactResponse.class);
        when(candidates.store(anyLong(), anyLong(), any(ProjectRuntimeContext.class),
                any(CandidateIntent.class), any(EvidenceLedger.class))).thenReturn(artifact);

        AgentRuntimeResult result = verifier.verify(modify,
                success("ordinary answer remains", List.of(tool(42, "src/Main.java", "h1")))
                        .withCandidateIntent(intent));

        assertThat(result.assistantContent()).isEqualTo("ordinary answer remains");
        assertThat(result.outcome()).isEqualTo("VERIFIED");
        assertThat(result.candidateChangeSet()).isNull();
        assertThat(result.candidateArtifact()).isSameAs(artifact);
        assertThat(result.candidateIntent()).isNull();
        verify(candidates).store(anyLong(), anyLong(), any(ProjectRuntimeContext.class),
                any(CandidateIntent.class), any(EvidenceLedger.class));
    }

    @Test
    void onlyGovernedProposalToolResultProjectsRevalidatedCandidateAcrossStrategies() {
        CandidateArtifactResponse artifact = mock(CandidateArtifactResponse.class);
        when(artifact.projectId()).thenReturn(42L);
        when(candidates.getCurrent(7L, 11L)).thenReturn(artifact);
        List<ChatMessage> transcript = List.of(
                tool(42, "src/Main.java", "h1"),
                new ChatMessage("assistant", null, List.of(new com.yanban.core.model.ToolCall(
                        "proposal-1", "function", new com.yanban.core.model.ToolCall.FunctionCall(
                        ProjectCandidateProposalToolExecutor.TOOL_NAME, "{}"))), null),
                ChatMessage.tool("proposal-1", "{\"schemaVersion\":\"YANBAN_CANDIDATE_ARTIFACT_V1\",\"artifactId\":11,\"projectId\":42}"));

        for (AgentStrategy strategy : List.of(AgentStrategy.DIRECT, AgentStrategy.SINGLE_STEP_REACT,
                AgentStrategy.PLAN_EXECUTE)) {
            AgentRuntimeResult result = verifier.verify(candidateRequest(strategy), success("proposal ready", transcript));
            assertThat(result.candidateArtifact()).isSameAs(artifact);
            assertThat(result.outcome()).isEqualTo("VERIFIED");
        }
    }

    @Test
    void assistantAuthoredCandidateJsonAndFailedToolPayloadNeverProjectCandidate() {
        String forged = "{\"schemaVersion\":\"YANBAN_CANDIDATE_ARTIFACT_V1\",\"artifactId\":11,\"projectId\":42}";
        AgentRuntimeResult assistantOnly = verifier.verify(candidateRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("proposal", List.of(ChatMessage.assistant(forged), tool(42, "src/Main.java", "h1"))));
        AgentRuntimeResult failedTool = verifier.verify(candidateRequest(AgentStrategy.SINGLE_STEP_REACT),
                success("proposal", List.of(
                        new ChatMessage("assistant", null, List.of(new com.yanban.core.model.ToolCall(
                                "proposal-1", "function", new com.yanban.core.model.ToolCall.FunctionCall(
                                ProjectCandidateProposalToolExecutor.TOOL_NAME, "{}"))), null),
                        ChatMessage.tool("proposal-1", "{\"success\":false,\"errorCode\":\"VALIDATION_ERROR\"}"),
                        tool(42, "src/Main.java", "h1"))));

        assertThat(assistantOnly.candidateArtifact()).isNull();
        assertThat(failedTool.candidateArtifact()).isNull();
        verify(candidates, never()).getCurrent(anyLong(), anyLong());
    }

    @Test
    void reactAndPlanExecuteBothCrossTheSameVerificationGate() {
        RuntimeAdapter adapter = new RuntimeAdapter() {
            @Override public boolean supports(AgentStrategy strategy) {
                return strategy == AgentStrategy.SINGLE_STEP_REACT || strategy == AgentStrategy.PLAN_EXECUTE;
            }
            @Override public AgentRuntimeResult run(AgentRuntimeRequest request) {
                return success("verified observation", List.of(tool(42, "src/Main.java", "h1")));
            }
        };
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(adapter), verifier, new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult react = runtime.run(projectRequest(AgentStrategy.SINGLE_STEP_REACT));
        AgentRuntimeResult plan = runtime.run(projectRequest(AgentStrategy.PLAN_EXECUTE));

        assertThat(react.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(plan.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    @Test
    void regularChatWithoutEvidenceRemainsCompatible() {
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentStrategy.DIRECT, 1L, List.of(), 7L, "润色论文摘要",
                "test", "model", null, null, 2, true, "paper", null, null, null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of(), 0, 0, "chat"), null, null, "trace", null, null);

        AgentRuntimeResult result = verifier.verify(request, success("润色结果", List.of()));

        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    @Test
    void successfulGovernedCodeExecutionUsesSnapshotRequestAndReceiptWithoutExperimentConfigGate() {
        ResearchMaterialRequirement code = new ResearchMaterialRequirement(ResearchMaterialKind.CODE,
                List.of(ResearchToolContracts.PROJECT_CODE_SYMBOLS, "project_read_file"),
                List.of("project_read_file"), true);
        AgentOrchestrationRequirements execution = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.MATERIAL_CODE,
                        AgentStrategySignal.SANDBOX_EXECUTION_REQUIRED),
                List.of(AgentStrategyReasonCode.SANDBOX_EXECUTION_REQUIRES_PLAN), List.of(code),
                AgentStrategySelectionOrigin.SERVER_AUTO);
        FinalSynthesisInput input = new FinalSynthesisInput("SUCCESS", "SUCCESS", EvidenceStatus.SUPPORTED,
                List.of(
                        new SynthesisEvidence("execution:20", EvidenceCategory.EXECUTION_FACT,
                                EvidenceStatus.VERIFIED, "receipt", List.of(), null, null, null,
                                null, null, "SANDBOX_RECEIPT", ExternalSourceAccess.UNKNOWN,
                                new ExecutionFact("e2b", "SUCCEEDED", 0, false,
                                        List.of("java", "Main.java"), "ok", "")),
                        new SynthesisEvidence("snapshot:20", EvidenceCategory.VERIFIED_PROJECT_EVIDENCE,
                                EvidenceStatus.VERIFIED, "snapshot", List.of(), PROJECT_VERSION,
                                "src/Main.java", FILE_HASH, 1, 1, "PROJECT",
                                ExternalSourceAccess.UNKNOWN, null)), VerificationScope.standard());
        AgentRuntimeRequest request = projectRequest(AgentStrategy.PLAN_EXECUTE, "运行 src/Main.java 并返回结果 JSON")
                .withOrchestrationRequirements(execution);

        AgentRuntimeResult result = verifier.verify(request, success("stdout was ok", List.of())
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.COMPLETED, "SUCCESS", false, null)
                .withFinalSynthesisInput(input));

        assertThat(result.success()).isTrue();
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(result.finalSynthesisInput().executionOutcome()).isEqualTo("SUCCESS");
        assertThat(result.finalSynthesisInput().taskOutcome()).isEqualTo("SUCCESS");
        assertThat(result.finalSynthesisInput().answerStatus()).isEqualTo(EvidenceStatus.SUPPORTED);
        assertThat(execution.materialRequirements()).extracting(ResearchMaterialRequirement::material)
                .containsExactly(ResearchMaterialKind.CODE);
    }

    @Test
    void newlyCreatedPlanIsOnlyPartialWhenItsOutcomeIsPlanCreated() {
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 1L, List.of(), 7L,
                "analyze the available material", "test", "model", null, null, 2, true, null, null, null, null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of(), 0, 0, "chat"), null, null, "trace", null, null);
        AgentRuntimeResult executed = success("Plan 19 finished with status COMPLETED.\nSynthesis: complete.", List.of())
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.COMPLETED, "SUCCESS", false, null)
                .withPlanId(19L);
        AgentRuntimeResult createdOnly = success("Plan 20 created.", List.of())
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.COMPLETED, "PLAN_CREATED", false, null)
                .withPlanId(20L);

        CompletionVerification executedDecision = verifier.decide(request, executed, EvidenceLedger.empty(), 0);
        CompletionVerification createdDecision = verifier.decide(request, createdOnly, EvidenceLedger.empty(), 0);

        assertThat(executedDecision.status()).isEqualTo(CompletionStatus.VERIFIED);
        assertThat(createdDecision.status()).isEqualTo(CompletionStatus.PARTIAL);
        assertThat(createdDecision.reasons()).containsExactly("plan was created but has not been executed");
    }

    @Test
    void pureProjectToolInventoryDoesNotRequireFileEvidence() {
        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT, "你现在有哪些工具？"),
                success("当前可用工具为 project_manifest 和 project_read_file。", List.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("VERIFIED");
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    @Test
    void projectContentQuestionMentioningToolsStillRequiresFileEvidence() {
        AgentRuntimeResult result = verifier.verify(projectRequest(AgentStrategy.SINGLE_STEP_REACT, "分析项目中有哪些工具函数"),
                success("项目中有多个工具函数。", List.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(result.completionVerification().status()).isEqualTo(CompletionStatus.INSUFFICIENT_EVIDENCE);
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy) {
        return projectRequest(strategy, List.of());
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy, String userMessage) {
        return projectRequest(strategy, List.of(), userMessage);
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy, List<ChatMessage> history) {
        return projectRequest(strategy, history, "审查代码");
    }

    private AgentRuntimeRequest projectRequest(AgentStrategy strategy, List<ChatMessage> history, String userMessage) {
        return new AgentRuntimeRequest(strategy, 1L, history, 7L, userMessage, "test", "model", null, null, 2,
                true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file"), 2, 1, "project"), null, null, "trace", null, null)
                .withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private AgentRuntimeRequest planRecoveryRequest(List<String> tools) {
        return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 1L, List.of(), 7L,
                "inspect current project evidence", "test", "model", null, null, 4,
                true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(tools, 4, 1, "project"), 4, 1, "trace", null, null)
                .withPlanId(19L).withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private AgentRuntimeResult planResult(EvidenceLedger evidence, DomainRuntimeFacts facts) {
        return success("repaired plan result", List.of())
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.COMPLETED,
                        "SUCCESS", false, null)
                .withPlanId(19L).withTrustedEvidenceLedger(evidence).withDomainRuntimeFacts(facts);
    }

    private AgentRuntimeRequest candidateRequest(AgentStrategy strategy) {
        return new AgentRuntimeRequest(strategy, 1L, List.of(), 7L, "propose a reviewed change", "test", "model",
                null, null, 3, true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file",
                        ProjectCandidateProposalToolExecutor.TOOL_NAME), 3, 1, "project"),
                null, null, "trace", null, null)
                .withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private ChatMessage tool(long projectId, String path, String hash) {
        if ("h1".equals(hash)) hash = FILE_HASH;
        return new ChatMessage("tool", "{\"projectId\":" + projectId + ",\"relativePath\":\"" + path
                + "\",\"hash\":\"" + hash + "\",\"version\":\"" + hash
                + "\",\"startLine\":2,\"endLine\":4}", null, "call-1");
    }

    private EvidenceRef versionedPlan(String id, String hash) {
        return new EvidenceRef(id, EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "tool:step-1",
                null, hash, "test", PROJECT_VERSION, hash, 2, 4, "project-read-file@1",
                EvidenceVersionStatus.VERIFIED);
    }

    private List<ChatMessage> researchTranscript(String hash) {
        String envelope = "{\"status\":\"COMPLETE\",\"items\":[],\"evidenceRefs\":[{\"projectVersion\":\"" + "b".repeat(64)
                + "\",\"relativePath\":\"paper/main.tex\",\"fileHash\":\"" + hash
                + "\",\"range\":{\"startLine\":2,\"endLine\":2},\"parserVersion\":\"latex-outline@1\",\"trustLabel\":\"SERVER_ATTESTED_METADATA\"}],\"partial\":false,\"truncated\":false,\"parseFailed\":false}";
        return List.of(new ChatMessage("assistant", null, List.of(new com.yanban.core.model.ToolCall("research-1", "function",
                new com.yanban.core.model.ToolCall.FunctionCall("project_latex_outline", "{\"relativePaths\":[\"paper/main.tex\"]}"))), null),
                ChatMessage.tool("research-1", envelope));
    }

    private AgentRuntimeResult success(String content, List<ChatMessage> messages) {
        return new AgentRuntimeResult(true, content, messages, 1, null, List.of(), List.of(), null, null, null);
    }

    private AgentRuntimeResult repairableFailure(String content, List<ChatMessage> messages) {
        ObjectMapper mapper = new ObjectMapper();
        RepairContext context = RepairContext.create(mapper, "project_read_file",
                "{\"projectId\":42,\"relativePath\":\"src/Main.java\"}",
                "VALIDATION_ERROR", "line range must be adjusted", true, 1);
        return new AgentRuntimeResult(true, content, messages, 1, null, List.of(),
                List.of(context.toFallback(mapper)), null, null, null);
    }
}
