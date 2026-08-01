package com.yanban.api.agent;

import com.yanban.core.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PlanReflectionRuntimeAdapter implements RuntimeAdapter {

    private static final String PLAN_REFLECT_PREFIX = "/plan reflect";

    private final PlanAgentService planAgentService;
    private final AgentStrategySelector strategySelector;

    public PlanReflectionRuntimeAdapter(@Lazy PlanAgentService planAgentService,
                                        AgentStrategySelector strategySelector) {
        this.planAgentService = planAgentService;
        this.strategySelector = strategySelector;
    }

    @Override
    public boolean supports(AgentStrategy strategy) {
        return strategy == AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION;
    }

    @Override
    public AgentRuntimeResult run(AgentRuntimeRequest request) {
        String goal = extractGoal(request.userMessage());
        PlanAgentService.PlanExecutionResult execution =
                planAgentService.createAndExecuteRuntimeReflectionPlan(request, goal);
        AgentPlanResponse plan = execution.plan();
        String summary = buildReflectionSummary(plan);
        if (request.tokenConsumer() != null && StringUtils.hasText(summary)) {
            request.tokenConsumer().accept(summary);
        }
        List<ChatMessage> messages = new ArrayList<>(request.history());
        messages.add(ChatMessage.assistant(summary));
        PlanRuntimeAdapter.PlanTerminal terminal = PlanRuntimeAdapter.classify(plan, execution.stopSignal());
        return new AgentRuntimeResult(
                terminal.success(),
                summary,
                messages,
                plan.steps() == null ? 0 : plan.steps().size(),
                terminal.success() ? null : plan.errorMessage(),
                List.of(),
                List.of(),
                null,
                null,
                null
        ).withCoordination(AgentStrategy.PLAN_EXECUTE_WITH_REFLECTION, terminal.stopReason(), terminal.outcome(),
                terminal.degraded(), terminal.degraded() ? AgentStrategy.PLAN_EXECUTE : null)
                .withRuntimeStopSignal(terminal.stopSignal())
                .withPlanId(plan.id())
                .withTrustedEvidenceLedger(execution.evidenceLedger())
                .withDomainRuntimeFacts(execution.domainRuntimeFacts());
    }

    private String extractGoal(String userMessage) {
        if (!StringUtils.hasText(userMessage) || !strategySelector.isPlanReflectIntent(userMessage)) {
            throw new IllegalArgumentException("PLAN_EXECUTE_WITH_REFLECTION requires '/plan reflect <goal>' input");
        }
        String goal = userMessage.trim().substring(PLAN_REFLECT_PREFIX.length()).trim();
        if (!StringUtils.hasText(goal)) {
            throw new IllegalArgumentException("Reflection plan goal must not be blank");
        }
        return goal;
    }

    private String buildReflectionSummary(AgentPlanResponse plan) {
        List<AgentPlanStepResponse> steps = plan.steps() == null ? List.of() : plan.steps();
        long completed = steps.stream().filter(step -> "COMPLETED".equals(step.status())).count();
        long degraded = steps.stream().filter(step -> "DEGRADED".equals(step.status())).count();
        long failed = steps.stream().filter(step -> "FAILED".equals(step.status())).count();
        long skipped = steps.stream().filter(step -> "SKIPPED".equals(step.status())).count();

        List<String> limitations = new ArrayList<>();
        for (AgentPlanStepResponse step : steps) {
            if ("DEGRADED".equals(step.status()) || "FAILED".equals(step.status()) || "SKIPPED".equals(step.status())) {
                limitations.add("- " + step.stepKey() + " [" + step.status() + "]: "
                        + blankToDefault(firstNonBlank(step.errorMessage(), SandboxTrustedResultBoundary.trusted(
                                step.type(), step.status(), step.result())), "No detail recorded."));
            }
        }
        if (StringUtils.hasText(plan.errorMessage())) {
            limitations.add("- plan error: " + plan.errorMessage());
        }

        List<String> followUps = new ArrayList<>();
        if ("COMPLETED".equals(plan.status()) && limitations.isEmpty()) {
            followUps.add("- The plan completed cleanly. You can now reuse the completed step results or turn them into a deliverable.");
        } else {
            if (failed > 0 || "FAILED".equals(plan.status())) {
                followUps.add("- Re-run the failed step with narrower scope or stronger source constraints before trusting the conclusion.");
            }
            if (degraded > 0) {
                followUps.add("- Review degraded steps and add the missing evidence before treating the output as final.");
            }
            if (skipped > 0) {
                followUps.add("- Inspect skipped dependents, because downstream coverage is incomplete.");
            }
        }
        if (followUps.isEmpty()) {
            followUps.add("- Review the recorded plan events if you need a step-by-step execution trace.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Reflection summary for plan `").append(plan.id()).append("`\n")
                .append("Plan status: ").append(plan.status()).append("\n")
                .append("Goal: ").append(blankToDefault(plan.goal(), "(not recorded)")).append("\n")
                .append("Step completion: ").append(completed).append("/").append(steps.size()).append(" completed");
        if (degraded > 0 || failed > 0 || skipped > 0) {
            sb.append(" (")
                    .append("degraded=").append(degraded).append(", ")
                    .append("failed=").append(failed).append(", ")
                    .append("skipped=").append(skipped)
                    .append(")");
        }
        sb.append("\n");
        sb.append("Evidence/completeness judgment: ")
                .append(reflectionJudgment(plan.status(), degraded, failed, skipped))
                .append("\n");

        sb.append("Limitations:\n");
        if (limitations.isEmpty()) {
            sb.append("- No degraded or failed steps were recorded.\n");
        } else {
            limitations.forEach(item -> sb.append(item).append("\n"));
        }

        sb.append("Follow-up suggestions:\n");
        followUps.forEach(item -> sb.append(item).append("\n"));
        String finalAnswer = PlanRuntimeAdapter.withoutInternalPresentationMetadata(
                SandboxTrustedResultBoundary.trustedPlanFinalAnswer(plan));
        if (StringUtils.hasText(finalAnswer)) {
            sb.append("Final Plan synthesis:\n")
                    .append(finalAnswer)
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String reflectionJudgment(String status, long degraded, long failed, long skipped) {
        if ("COMPLETED".equalsIgnoreCase(status) && degraded == 0 && failed == 0 && skipped == 0) {
            return "Complete with no recorded degradation; evidence looks sufficient for a first-pass conclusion.";
        }
        if (failed > 0 || "FAILED".equalsIgnoreCase(status)) {
            return "Incomplete or unreliable; one or more plan steps failed, so the result should not be treated as fully validated.";
        }
        if (degraded > 0 || skipped > 0) {
            return String.format(Locale.ROOT,
                    "Partially complete; %d degraded and %d skipped step(s) limit confidence in the final conclusion.",
                    degraded, skipped);
        }
        return "Execution finished, but review the plan details before treating the output as final.";
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String blankToDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
