package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanban.api.project.ProjectFileEntry;
import com.yanban.api.project.ProjectManifestResponse;
import com.yanban.api.project.ProjectService;
import com.yanban.core.model.ChatMessage;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanRuntimeAdapterTest {

    @Test
    void sandboxPlanDisplaysPersistedFactsAndLabeledOutputAnalysis() {
        PlanAgentService service = mock(PlanAgentService.class);
        String canonical = "Sandbox execution facts: status=SUCCEEDED, exitCode=0\n"
                + "AI interpretation based on program output; not independently verified.\nPrinted six rows.";
        AgentPlanStepResponse sandbox = new AgentPlanStepResponse(1L, "sandbox", 1, "Run", "run",
                "SANDBOX_EXECUTE", List.of(), List.of("sandbox_execute"), "done", "COMPLETED", 1,
                "display-only stdout", null, null, null);
        AgentPlanResponse completed = new AgentPlanResponse(19L, 11L, "goal", "summary", "COMPLETED", true,
                null, null, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(),
                List.of(sandbox), "SUCCESS", canonical);
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", true))
                .thenReturn(new PlanAgentService.PlanExecutionResult(completed, AgentRuntimeStopSignal.NONE));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(projectRequest());

        assertThat(result.assistantContent()).contains(canonical).doesNotContain("display-only stdout");
        verify(service).executePlanResultWithinAdapter(7L, 19L, "trace", true);
    }

    @Test
    void mapsPersistedPlanTerminalStatesWithoutTrustingTheSummaryText() {
        assertThat(PlanRuntimeAdapter.classify(plan("COMPLETED", null, List.of())).outcome()).isEqualTo("SUCCESS");
        assertThat(PlanRuntimeAdapter.classify(plan("COMPLETED", null, List.of(step("DEGRADED")))).outcome())
                .isEqualTo("PARTIAL");
        assertThat(PlanRuntimeAdapter.classify(plan("FAILED", "model says everything is fine", List.of())).outcome())
                .isEqualTo("FAILED");
        assertThat(PlanRuntimeAdapter.classify(plan("PAUSED", null, List.of())).outcome()).isEqualTo("PAUSED");
        assertThat(PlanRuntimeAdapter.classify(plan("RUNNING", null, List.of())).outcome()).isEqualTo("WAITING");
        assertThat(PlanRuntimeAdapter.classify(plan("CANCELLED", "User cancelled plan.", List.of())).outcome())
                .isEqualTo("CANCELLED");
        assertThat(PlanRuntimeAdapter.classify(plan("FAILED", "sandbox timed out",
                List.of(step("FAILED", "stdout retained", "SANDBOX_TIMED_OUT")))).outcome())
                .isEqualTo("TIMED_OUT");
        assertThat(PlanRuntimeAdapter.classify(plan("FAILED", "arbitrary error text", List.of()),
                AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED).outcome()).isEqualTo("BUDGET_STOP");
    }

    @Test
    void failedPlanWithPreservedEvidenceIsPartialAndDoesNotReuseAnIntermediateAsFinalAnswer() {
        AgentPlanStepResponse paper = new AgentPlanStepResponse(
                1L, "paper", 1, "Paper", "description", "ANALYSIS", List.of(), List.of(),
                "done", "COMPLETED", 1, "paper intermediate result", null, null, null);
        AgentPlanStepResponse code = new AgentPlanStepResponse(
                2L, "code", 2, "Code", "description", "ANALYSIS", List.of(), List.of(),
                "done", "FAILED", 2, null, "missing code evidence", null, null);
        AgentPlanStepResponse synthesis = new AgentPlanStepResponse(
                3L, "cross_check", 3, "Cross-check", "description", "ANALYSIS",
                List.of("paper", "code"), List.of(), "done", "SKIPPED", 0,
                null, "Dependency step failed: code", null, null);
        AgentPlanResponse response = plan("FAILED", "code failed", List.of(paper, code, synthesis));

        assertThat(response.executionOutcome()).isEqualTo("PARTIAL");
        assertThat(response.finalAnswer()).isNull();
        assertThat(PlanRuntimeAdapter.classify(response).outcome()).isEqualTo("PARTIAL");
        assertThat(PlanRuntimeAdapter.classify(response).stopReason()).isEqualTo(AgentStopReason.PLAN_PARTIAL);
    }

    @Test
    void adapterDoesNotReportFailedPlanAsSuccessfulChatAnswer() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", true))
                .thenReturn(new PlanAgentService.PlanExecutionResult(
                        plan("FAILED", "step failed", List.of()), AgentRuntimeStopSignal.NONE));
        PlanRuntimeAdapter adapter = new PlanRuntimeAdapter(service);

        AgentRuntimeResult result = adapter.run(new AgentRuntimeRequest(
                AgentStrategy.PLAN_EXECUTE, 11L, List.of(), 7L, "persisted goal", "test", "model", null, null,
                1, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, List.of(), 0, 0, "trace", null, null).withPlanId(19L));

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(result.planId()).isEqualTo(19L);
        verify(service).executePlanResultWithinAdapter(7L, 19L, "trace", true);
        verify(service, never()).createPlanWithinAdapter(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void adapterCarriesServerPersistedPlanLevelIntoRuntimeProjectionMetadata() {
        PlanAgentService service = mock(PlanAgentService.class);
        AgentPlanResponse durable = new AgentPlanResponse(19L, 11L, "goal", "summary", "FAILED",
                false, null, "failed", null, null, null, null, List.of(),
                "FAILED", null, "L2_DURABLE");
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", true))
                .thenReturn(new PlanAgentService.PlanExecutionResult(durable, AgentRuntimeStopSignal.NONE));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(new AgentRuntimeRequest(
                AgentStrategy.PLAN_EXECUTE, 11L, List.of(), 7L, "persisted goal", "test", "model", null, null,
                1, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, List.of(), 0, 0, "trace", null, null)
                .withPlanId(19L));

        assertThat(result.planPersistenceLevel()).isEqualTo("L2_DURABLE");
    }

    @Test
    void trustedCreateWithAutoLikeTextDoesNotExecute() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan("REVIEWING", null, List.of()));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(requestWithoutPlanId());

        assertThat(result.success()).isTrue();
        assertThat(result.planId()).isEqualTo(19L);
        assertThat(result.outcome()).isEqualTo("PLAN_CREATED");
        verify(service, never()).executePlanResultWithinAdapter(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void existingPlanIdKeepsConversationSummaryPersistenceEvenWithServerAutoAudit() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", true))
                .thenReturn(new PlanAgentService.PlanExecutionResult(
                        plan("COMPLETED", null, List.of(step("COMPLETED", "Existing plan result", null))),
                        AgentRuntimeStopSignal.NONE));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(autoProjectRequest().withPlanId(19L));

        assertThat(result.success()).isTrue();
        assertThat(result.planId()).isEqualTo(19L);
        verify(service).executePlanResultWithinAdapter(7L, 19L, "trace", true);
        verify(service, never()).executePlanResultWithinAdapter(7L, 19L, "trace", false);
        verify(service, never()).createPlanWithinAdapter(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nestedReflectionPlanCanSuppressDuplicateConversationSummaryPersistence() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", false))
                .thenReturn(new PlanAgentService.PlanExecutionResult(
                        plan("COMPLETED", null, List.of(step("COMPLETED", "Reflection result", null))),
                        AgentRuntimeStopSignal.NONE));

        AgentRuntimeRequest nestedReflection = autoProjectRequest().withPlanId(19L)
                .withPlanConversationSummaryPersistence(false);
        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(nestedReflection);

        assertThat(result.success()).isTrue();
        assertThat(result.planId()).isEqualTo(19L);
        verify(service).executePlanResultWithinAdapter(7L, 19L, "trace", false);
        verify(service, never()).executePlanResultWithinAdapter(7L, 19L, "trace", true);
        verify(service, never()).createPlanWithinAdapter(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void serverAutoProjectPlanIsCreatedAndExecutedWithRealStepResults() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan("REVIEWING", null, List.of()));
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", false))
                .thenReturn(new PlanAgentService.PlanExecutionResult(plan("COMPLETED", null,
                        List.of(step("COMPLETED", "Cross-material analysis result\n"
                                + "[projectEvidenceRefs=trusted-tool:42:paper.tex:hash:call-1]\n"
                                + "Q1: semantic consistency remains unresolved.\n"
                                + "Inline [projectEvidenceRefs=trusted-tool:43:code.py:hash:call-2] marker removed.\n"
                                + "[projectEvidenceRefs=trusted-tool:44:paper.tex:hash:call-3]", null))),
                        AgentRuntimeStopSignal.NONE));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(autoProjectRequest());

        assertThat(result.success()).isTrue();
        assertThat(result.outcome()).isEqualTo("SUCCESS");
        assertThat(result.planId()).isEqualTo(19L);
        assertThat(result.assistantContent())
                .contains("Cross-material analysis result",
                        "Q1: semantic consistency remains unresolved.", "Inline  marker removed.")
                .doesNotContain("execution lifecycle status", "outcome SUCCESS", "projectEvidenceRefs=");
        verify(service).createPlanWithinAdapter(org.mockito.ArgumentMatchers.argThat(
                request -> !request.shouldPersistPlanConversationSummary(true)));
        verify(service).executePlanResultWithinAdapter(7L, 19L, "trace", false);
        verify(service, never()).executePlanResultWithinAdapter(7L, 19L, "trace", true);
    }

    @Test
    void serverAutoProjectPlanReportsControlledPartialWithoutClaimingSuccess() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan("REVIEWING", null, List.of()));
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", false))
                .thenReturn(new PlanAgentService.PlanExecutionResult(plan("COMPLETED", null,
                        List.of(step("DEGRADED", "Partial governed result", "coverage limited"))),
                        AgentRuntimeStopSignal.NONE));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(autoProjectRequest());

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.PLAN_PARTIAL);
        assertThat(result.degraded()).isTrue();
        assertThat(result.planId()).isEqualTo(19L);
        assertThat(result.assistantContent())
                .contains("Partial governed result")
                .doesNotContain("execution lifecycle status", "Plan execution outcome", "finished with status COMPLETED");
    }

    @Test
    void canonicalChatUsesTheCompleteFinalSynthesisWithoutConcatenatingOrTruncatingEarlierSteps() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan("REVIEWING", null, List.of()));
        String finalSynthesis = "FINAL-BEGIN\n" + "evidence row\n".repeat(1800) + "FINAL-END.";
        AgentPlanResponse completed = plan("COMPLETED", null, List.of(
                step("COMPLETED", "EARLY-STEP-BLOB", null),
                new AgentPlanStepResponse(2L, "step_2", 2, "Final synthesis", "description", "ANALYSIS",
                        List.of("step_1"), List.of(), "done", "COMPLETED", 1,
                        finalSynthesis, null, null, null)));
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", false))
                .thenReturn(new PlanAgentService.PlanExecutionResult(completed, AgentRuntimeStopSignal.NONE));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(autoProjectRequest());

        assertThat(result.assistantContent())
                .contains("FINAL-BEGIN", "FINAL-END.")
                .doesNotContain("EARLY-STEP-BLOB");
        assertThat(result.assistantContent().length()).isGreaterThan(12_000);
    }

    @Test
    void serverAutoProjectPlanWithHistoryPersistsOneCanonicalAssistantForSuccessAndPartial() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("Compare the paper and implementation."),
                ChatMessage.assistant("Earlier answer"));

        for (String stepStatus : List.of("COMPLETED", "DEGRADED")) {
            PlanAgentService service = mock(PlanAgentService.class);
            when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(plan("REVIEWING", null, List.of()));
            when(service.executePlanResultWithinAdapter(7L, 19L, "trace", false))
                    .thenReturn(new PlanAgentService.PlanExecutionResult(plan("COMPLETED", null,
                            List.of(step(stepStatus, stepStatus + " governed result", null))),
                            AgentRuntimeStopSignal.NONE));
            AgentRuntimeRequest request = autoProjectRequest(history);

            AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(request);

            assertThat(result.messages()).startsWith(history.toArray(ChatMessage[]::new));
            assertThat(AgentService.runtimeMessagesToPersist(result.messages(), request.history().size()))
                    .singleElement()
                    .satisfies(message -> {
                        assertThat(message.role()).isEqualTo("assistant");
                        assertThat(message.content()).isEqualTo(result.assistantContent());
                    });
            assertThat(result.messages().stream().filter(message -> "user".equals(message.role())))
                    .extracting(ChatMessage::content)
                    .containsExactly("Compare the paper and implementation.")
                    .noneMatch(content -> content.startsWith("/plan"));
            verify(service).executePlanResultWithinAdapter(7L, 19L, "trace", false);
            verify(service, never()).executePlanResultWithinAdapter(7L, 19L, "trace", true);
        }
    }

    @Test
    void serverAutoProjectPlanExecutionFailureRetainsCreatedPlanIdentity() {
        PlanAgentService service = mock(PlanAgentService.class);
        when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(plan("REVIEWING", null, List.of()));
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", false))
                .thenThrow(new IllegalStateException("execution unavailable"));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(autoProjectRequest());

        assertThat(result.success()).isFalse();
        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(result.planId()).isEqualTo(19L);
        assertThat(result.errorMessage()).contains("execution unavailable");
    }

    @Test
    void budgetSignalIsStructuredAndIndependentOfPlanErrorText() {
        PlanRuntimeAdapter.PlanTerminal terminal = PlanRuntimeAdapter.classify(
                plan("FAILED", "arbitrary wording", List.of()), AgentRuntimeStopSignal.MAX_STEPS_BUDGET_EXHAUSTED);

        assertThat(terminal.outcome()).isEqualTo("BUDGET_STOP");
    }

    @Test
    void adapterProjectsPersistedToolAndStepFactsWithoutUsingPlanSummaryProse() {
        PlanAgentService service = mock(PlanAgentService.class);
        DomainRuntimeFacts facts = new DomainRuntimeFacts(
                List.of(new DomainRuntimeFacts.ToolOutcome("project_code_symbols", 1, "step_1",
                        true, true, true, false, false)),
                List.of(new DomainRuntimeFacts.PlanStepOutcome(
                        "step_1", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false)),
                List.of());
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", true)).thenReturn(
                new PlanAgentService.PlanExecutionResult(
                        plan("COMPLETED", null, List.of(step("COMPLETED"))),
                        AgentRuntimeStopSignal.NONE, EvidenceLedger.empty(), facts));

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(projectRequest());

        assertThat(result.domainRuntimeFacts()).isEqualTo(facts);
        assertThat(result.domainRuntimeFacts().toolOutcomes()).singleElement()
                .extracting(DomainRuntimeFacts.ToolOutcome::toolName).isEqualTo("project_code_symbols");
    }

    @Test
    void outerVerifierUsesTypedPlanEvidenceAndRejectsMissingOrOldEvidence() {
        PlanAgentService service = mock(PlanAgentService.class);
        String projectVersion = "b".repeat(64); String fileHash = "a".repeat(64);
        EvidenceLedger current = new EvidenceLedger(List.of(new EvidenceRef("trusted-plan:42:src/Main.java:h1:step",
                EvidenceSourceType.PROJECT, "PROJECT", "src/Main.java", "step", null, fileHash, "event",
                projectVersion, fileHash, 3, 3, "project-search@1", EvidenceVersionStatus.VERIFIED)));
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", true)).thenReturn(
                new PlanAgentService.PlanExecutionResult(plan("COMPLETED", null, List.of(step("COMPLETED"))),
                        AgentRuntimeStopSignal.NONE, current));
        ProjectService projects = mock(ProjectService.class);
        when(projects.manifest(7L, 42L)).thenReturn(new ProjectManifestResponse(42L, projectVersion, List.of(
                new ProjectFileEntry("src/Main.java", 1, Instant.EPOCH, fileHash))));
        CompletionVerifier verifier = new CompletionVerifier(new ObjectMapper(), new ProjectEvidenceValidator(projects),
                mock(CandidateChangeArtifactService.class));
        AgentRuntimeService runtime = new AgentRuntimeService(List.of(new PlanRuntimeAdapter(service)), verifier,
                new CompletionReflection(), new AdapterCompletionRepairExecutor());

        AgentRuntimeResult verified = runtime.run(projectRequest());

        assertThat(verified.completionVerification().status()).isEqualTo(CompletionStatus.VERIFIED);
    }

    @Test
    void llmRoutedProjectPlanUsesSameCreateAndExecutePath() {
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE),
                List.of(AgentStrategyReasonCode.LLM_ROUTER_PLAN), List.of(),
                AgentStrategySelectionOrigin.LLM_ROUTER);
        AgentRuntimeRequest request = autoProjectRequest(List.of()).withOrchestrationRequirements(audit);

        assertThat(PlanRuntimeAdapter.isServerAutoProjectPlan(request)).isTrue();
    }

    @Test
    void deterministicRouterFallbackPlanUsesSameCreateAndExecutePath() {
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN,
                        AgentStrategyReasonCode.LLM_ROUTER_INVALID_RESPONSE,
                        AgentStrategyReasonCode.LLM_ROUTER_FALLBACK_PLAN),
                List.of(), AgentStrategySelectionOrigin.ROUTER_FALLBACK);
        AgentRuntimeRequest request = autoProjectRequest(List.of()).withOrchestrationRequirements(audit);

        assertThat(PlanRuntimeAdapter.isServerAutoProjectPlan(request)).isTrue();
    }

    @Test
    void runtimeForcedSandboxPlanUsesCreateAndExecutePathToSurfaceConfirmation() {
        PlanAgentService service = mock(PlanAgentService.class);
        AgentPlanResponse waiting = plan("REVIEWING", null, List.of(new AgentPlanStepResponse(
                        1L, "run", 1, "Run", "Run src/main/java/xhs_1111.java", "SANDBOX_EXECUTE",
                        List.of(), List.of("sandbox_execute"), "receipt", "PENDING", 0,
                        null, null, null, null)));
        when(service.createPlanWithinAdapter(org.mockito.ArgumentMatchers.any())).thenReturn(waiting);
        when(service.getPlan(7L, 19L)).thenReturn(waiting);
        when(service.executePlanResultWithinAdapter(7L, 19L, "trace", false))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "SANDBOX_CONFIRMATION_REQUIRED: current ProjectVersion and sandbox step set must be confirmed"));
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.SANDBOX_EXECUTION_REQUIRED),
                List.of(AgentStrategyReasonCode.LLM_ROUTER_SUGGESTION_NOT_ALLOWED,
                        AgentStrategyReasonCode.SANDBOX_EXECUTION_REQUIRES_PLAN),
                List.of(), AgentStrategySelectionOrigin.LLM_ROUTER);
        AgentRuntimeRequest request = autoProjectRequest(List.of()).withOrchestrationRequirements(audit);

        AgentRuntimeResult result = new PlanRuntimeAdapter(service).run(request);

        assertThat(PlanRuntimeAdapter.isServerAutoProjectPlan(request)).isTrue();
        assertThat(result.success()).isFalse();
        assertThat(result.planId()).isEqualTo(19L);
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.WAITING_FOR_USER);
        assertThat(result.outcome()).isEqualTo("WAITING");
        assertThat(result.errorMessage()).isNull();
        assertThat(result.assistantContent()).doesNotContain("SANDBOX_CONFIRMATION_REQUIRED", "DOMAIN_");
        verify(service).executePlanResultWithinAdapter(7L, 19L, "trace", false);
    }

    private static AgentRuntimeRequest requestWithoutPlanId() {
        AgentOrchestrationRequirements trustedCreate = new AgentOrchestrationRequirements(List.of(),
                List.of(AgentStrategyReasonCode.TRUSTED_PLAN_CAPABILITY), List.of(),
                AgentStrategySelectionOrigin.TRUSTED_CAPABILITY);
        return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 11L, List.of(), 7L, "cross material", "test", "model",
                null, null, 1, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING, List.of(), 0, 0, "trace", null, null)
                .withOrchestrationRequirements(trustedCreate);
    }

    private static AgentRuntimeRequest projectRequest() {
        return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 11L, List.of(), 7L, "inspect", "test", "model",
                null, null, 1, false, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file"), 1, 1, "project"), null, null, "trace", null, null)
                .withPlanId(19L).withProjectContext(new ProjectRuntimeContext(7L, 42L));
    }

    private static AgentRuntimeRequest autoProjectRequest() {
        return autoProjectRequest(List.of());
    }

    private static AgentRuntimeRequest autoProjectRequest(List<ChatMessage> history) {
        AgentOrchestrationRequirements audit = new AgentOrchestrationRequirements(
                List.of(AgentStrategySignal.PROJECT_SCOPE, AgentStrategySignal.CROSS_MATERIAL_TASK),
                List.of(AgentStrategyReasonCode.AUTO_CROSS_MATERIAL_PLAN), List.of(),
                AgentStrategySelectionOrigin.SERVER_AUTO);
        return new AgentRuntimeRequest(AgentStrategy.PLAN_EXECUTE, 11L, history, 7L, "cross material", "test", "model",
                null, null, 4, true, null, null, null, null, AgentRuntimeMode.LANGCHAIN4J,
                AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file"), 4, 1, "project"), 4, 1,
                "trace", null, null)
                .withProjectContext(new ProjectRuntimeContext(7L, 42L))
                .withOrchestrationRequirements(audit);
    }

    private static AgentPlanResponse plan(String status, String error, List<AgentPlanStepResponse> steps) {
        return new AgentPlanResponse(19L, 11L, "goal", "persuasive summary", status, false, null, error,
                null, null, null, null, steps);
    }

    private static AgentPlanStepResponse step(String status) {
        return step(status, null, null);
    }

    private static AgentPlanStepResponse step(String status, String result, String error) {
        return new AgentPlanStepResponse(1L, "step_1", 1, "title", "description", "ANALYSIS", List.of(), List.of(),
                "done", status, 1, result, error, null, null);
    }
}
