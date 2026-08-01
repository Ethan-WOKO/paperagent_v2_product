package com.yanban.api.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.yanban.api.observability.TraceIdFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

class AgentServiceRuntimeAssemblyTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void websocketRuntimeAssemblyCreatesServerOwnedTraceWhenUpgradeMdcIsGone() {
        MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);

        String traceId = AgentService.resolvedRuntimeTraceId();

        assertThat(traceId).isNotBlank();
        assertThatCodeAsUuid(traceId);
    }

    @Test
    void httpRuntimeAssemblyPreservesFilterResolvedTrace() {
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "http-trace-17");

        assertThat(AgentService.resolvedRuntimeTraceId()).isEqualTo("http-trace-17");
    }

    @Test
    void exactPlanReflectKeepsProjectContextButUsesRestrictedReflectionCapability() {
        ProjectRuntimeContext projectContext = new ProjectRuntimeContext(7L, 42L);
        AgentRuntimeRequest runtimeRequest = new AgentRuntimeRequest(
                AgentStrategy.AUTO, 11L, List.of(), 7L,
                "/plan reflect inspect the current Project evidence", "test", "model",
                null, null, 20, true, null, "key", "url", null,
                AgentRuntimeMode.LANGCHAIN4J, AgentToolCallingMode.LANGCHAIN4J_TOOL_BINDING,
                new ResolvedToolPolicy(List.of("project_read_file"), 12, 1, "project"),
                12, 1, "trace", null, null).withProjectContext(projectContext);

        AgentCoordinationRequest reflection = AgentService.coordinationRequestFor(
                runtimeRequest, projectContext, runtimeRequest.userMessage());
        AgentCoordinationRequest ordinaryProjectRead = AgentService.coordinationRequestFor(
                runtimeRequest, projectContext, "please reflect on this code");

        assertThat(reflection.capability()).isEqualTo(AgentRequestCapability.LEGACY_PLAN_REFLECT);
        assertThat(reflection.runtimeRequest().projectContext()).isEqualTo(projectContext);
        assertThat(ordinaryProjectRead.capability()).isEqualTo(AgentRequestCapability.PROJECT_READ);
    }

    @Test
    void planProcessSummaryUsesNestedTrustedToolFactsInsteadOfClaimingADirectAnswer() {
        AgentRuntimeResult result = new AgentRuntimeResult(
                false,
                "preserved plan output",
                List.of(),
                6,
                "repair failed",
                List.of(),
                List.of(),
                null,
                null,
                null
        ).withPlanId(33L)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.RUNTIME_FAILED,
                        "FAILED", false, null)
                .withDomainRuntimeFacts(new DomainRuntimeFacts(List.of(
                        new DomainRuntimeFacts.ToolOutcome(
                                "project_read_file", 1, "paper", true, true, true, false, false),
                        new DomainRuntimeFacts.ToolOutcome(
                                "project_read_file", 2, "paper", true, true, true, false, false),
                        new DomainRuntimeFacts.ToolOutcome(
                                "project_code_symbols", 1, "code", true, true, true, false, false),
                        new DomainRuntimeFacts.ToolOutcome(
                                "project_latex_outline", 1, "paper", true, true, true, false, false),
                        new DomainRuntimeFacts.ToolOutcome(
                                "project_search", 1, "cross_check", true, true, true, false, false)
                ), List.of(
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "paper", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "code", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "cross_check", DomainRuntimeFacts.PlanStepStatus.DEGRADED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "synthesis", DomainRuntimeFacts.PlanStepStatus.SUPERSEDED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "repair", DomainRuntimeFacts.PlanStepStatus.FAILED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "rebuild", DomainRuntimeFacts.PlanStepStatus.SKIPPED, false)
                ), List.of()));

        String summary = processSummary(result);

        assertThat(summary)
                .contains("\u6b63\u5728\u8bfb\u53d6\u9879\u76ee\u6587\u4ef6")
                .contains("\u9879\u76ee\u4ee3\u7801\u7ed3\u6784\u5206\u6790\u5b8c\u6210")
                .contains("\u8ba1\u5212\u672a\u5b8c\u6574\u6267\u884c")
                .doesNotContain("\u76f4\u63a5\u751f\u6210\u56de\u7b54");
        assertThat(summary.lines()).hasSizeLessThanOrEqualTo(8);
    }

    @Test
    void completedPlanWithGovernancePartialDoesNotClaimLifecycleFailure() {
        AgentRuntimeResult result = new AgentRuntimeResult(
                false, "bounded result", List.of(), 3, "semantic consistency unresolved",
                List.of(), List.of(), null, null, null)
                .withPlanId(34L)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.PLAN_PARTIAL,
                        "PARTIAL", true, AgentStrategy.PLAN_EXECUTE)
                .withDomainRuntimeFacts(new DomainRuntimeFacts(List.of(), List.of(
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "paper", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "code", DomainRuntimeFacts.PlanStepStatus.COMPLETED, false),
                        new DomainRuntimeFacts.PlanStepOutcome(
                                "cross_check", DomainRuntimeFacts.PlanStepStatus.DEGRADED, false)
                ), List.of()));

        assertThat(processSummary(result))
                .contains("\u8ba1\u5212\u6b65\u9aa4\u5df2\u6267\u884c\u5b8c\u6210")
                .contains("\u6700\u7ec8\u6821\u9a8c\u4ecd\u6709\u672a\u51b3\u9879")
                .doesNotContain("\u8ba1\u5212\u672a\u5b8c\u6574\u6267\u884c");
    }

    @Test
    void planFailureWithoutCompletedStepsDoesNotClaimPreservedResults() {
        AgentRuntimeResult result = new AgentRuntimeResult(
                false, "failed", List.of(), 0, "planner failed",
                List.of(), List.of(), null, null, null)
                .withPlanId(35L)
                .withCoordination(AgentStrategy.PLAN_EXECUTE, AgentStopReason.RUNTIME_FAILED,
                        "FAILED", false, null);

        assertThat(processSummary(result))
                .contains("\u8ba1\u5212\u6267\u884c\u7ed3\u679c\u672a\u901a\u8fc7\u6700\u7ec8\u6821\u9a8c")
                .doesNotContain("\u5df2\u4fdd\u7559", "\u8ba1\u5212\u672a\u5b8c\u6574\u6267\u884c");
    }

    private String processSummary(AgentRuntimeResult result) {
        AgentService probe = Mockito.mock(AgentService.class, Mockito.CALLS_REAL_METHODS);
        return ReflectionTestUtils.invokeMethod(probe, "buildProcessSummary", result, null);
    }

    private void assertThatCodeAsUuid(String value) {
        assertThat(UUID.fromString(value)).isNotNull();
    }
}
