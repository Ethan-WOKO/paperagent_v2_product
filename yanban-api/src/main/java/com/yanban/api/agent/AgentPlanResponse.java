package com.yanban.api.agent;

import com.yanban.core.agent.AgentPlan;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

public record AgentPlanResponse(
        Long id,
        Long sessionId,
        String goal,
        String summary,
        String status,
        Boolean ragDisabled,
        String skillId,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        List<AgentPlanStepResponse> steps,
        String executionOutcome,
        String finalAnswer,
        String persistenceLevel,
        String taskOutcome,
        EvidenceStatus answerStatus,
        FinalSynthesisInput finalSynthesisInput
) {
    /** Source-compatible bridge for callers created before the result contract was projected. */
    public AgentPlanResponse(
            Long id, Long sessionId, String goal, String summary, String status, Boolean ragDisabled,
            String skillId, String errorMessage, LocalDateTime createdAt, LocalDateTime updatedAt,
            LocalDateTime startedAt, LocalDateTime finishedAt, List<AgentPlanStepResponse> steps,
            String executionOutcome, String finalAnswer, String persistenceLevel
    ) {
        this(id, sessionId, goal, summary, status, ragDisabled, skillId, errorMessage, createdAt, updatedAt,
                startedAt, finishedAt, steps, executionOutcome, finalAnswer, persistenceLevel,
                executionOutcome, null, null);
    }

    /** Source-compatible bridge for callers created before persistence level was projected. */
    public AgentPlanResponse(
            Long id, Long sessionId, String goal, String summary, String status, Boolean ragDisabled,
            String skillId, String errorMessage, LocalDateTime createdAt, LocalDateTime updatedAt,
            LocalDateTime startedAt, LocalDateTime finishedAt, List<AgentPlanStepResponse> steps,
            String executionOutcome, String finalAnswer
    ) {
        this(id, sessionId, goal, summary, status, ragDisabled, skillId, errorMessage, createdAt, updatedAt,
                startedAt, finishedAt, steps, executionOutcome, finalAnswer, null);
    }

    public AgentPlanResponse(
            Long id,
            Long sessionId,
            String goal,
            String summary,
            String status,
            Boolean ragDisabled,
            String skillId,
            String errorMessage,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            List<AgentPlanStepResponse> steps
    ) {
        this(id, sessionId, goal, summary, status, ragDisabled, skillId, errorMessage,
                createdAt, updatedAt, startedAt, finishedAt, steps,
                executionOutcome(status, steps), finalAnswer(steps), null);
    }

    public static AgentPlanResponse from(AgentPlan plan, List<AgentPlanStepResponse> steps) {
        return new AgentPlanResponse(
                plan.getId(),
                plan.getSessionId(),
                plan.getGoal(),
                plan.getSummary(),
                plan.getStatus(),
                plan.getRagDisabled(),
                plan.getSkillId(),
                plan.getErrorMessage(),
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                plan.getStartedAt(),
                plan.getFinishedAt(),
                steps,
                executionOutcome(plan.getStatus(), steps),
                plan.getCanonicalAnswer() == null ? finalAnswer(steps) : plan.getCanonicalAnswer(),
                plan.getPersistenceLevel()
        );
    }

    public AgentPlanResponse withFinalSynthesisInput(FinalSynthesisInput input) {
        if (input == null) return this;
        return new AgentPlanResponse(id, sessionId, goal, summary, status, ragDisabled, skillId, errorMessage,
                createdAt, updatedAt, startedAt, finishedAt, steps, input.executionOutcome(), finalAnswer,
                persistenceLevel, input.taskOutcome(), input.answerStatus(), input);
    }

    private static String executionOutcome(String lifecycleStatus, List<AgentPlanStepResponse> steps) {
        boolean partial = steps != null && steps.stream().anyMatch(step -> step != null
                && ("DEGRADED".equals(step.status()) || "SKIPPED".equals(step.status())));
        if ("COMPLETED".equals(lifecycleStatus)) return partial ? "PARTIAL" : "SUCCESS";
        if ("CANCELLED".equals(lifecycleStatus)) return "CANCELLED";
        if ("FAILED".equals(lifecycleStatus)) {
            if (hasTerminalCode(steps, "TIMED_OUT")) return "TIMED_OUT";
            if (hasTerminalCode(steps, "CANCELLED")) return "CANCELLED";
            if (hasFailedSandboxExecution(steps)) return "FAILED";
            boolean preservedResult = steps != null && steps.stream().anyMatch(step -> step != null
                    && ("COMPLETED".equals(step.status()) || "DEGRADED".equals(step.status()))
                    && step.result() != null && !step.result().isBlank());
            return partial && preservedResult ? "PARTIAL" : "FAILED";
        }
        return lifecycleStatus;
    }

    private static boolean hasFailedSandboxExecution(List<AgentPlanStepResponse> steps) {
        return steps != null && steps.stream().filter(java.util.Objects::nonNull).anyMatch(step ->
                "SANDBOX_EXECUTE".equals(step.type())
                        && "FAILED".equals(step.status())
                        && step.errorMessage() != null
                        && step.errorMessage().startsWith("SANDBOX_"));
    }

    private static boolean hasTerminalCode(List<AgentPlanStepResponse> steps, String code) {
        if (steps == null || steps.isEmpty()) return false;
        String marker = code == null ? "" : code.toUpperCase(java.util.Locale.ROOT);
        return steps.stream().filter(java.util.Objects::nonNull).anyMatch(step -> {
            String error = step.errorMessage() == null ? "" : step.errorMessage().toUpperCase(java.util.Locale.ROOT);
            return error.equals(marker) || error.startsWith("SANDBOX_" + marker)
                    || error.startsWith(marker + ":") || error.contains("_" + marker + ":");
        });
    }

    private static String finalAnswer(List<AgentPlanStepResponse> steps) {
        if (steps == null || steps.isEmpty()) return null;
        AgentPlanStepResponse terminalStep = steps.stream()
                .filter(step -> step != null && step.sortOrder() != null)
                .filter(step -> !"SUPERSEDED".equals(step.status()))
                .max(Comparator.comparing(AgentPlanStepResponse::sortOrder))
                .orElse(null);
        if (terminalStep == null
                || !("COMPLETED".equals(terminalStep.status()) || "DEGRADED".equals(terminalStep.status()))
                || terminalStep.result() == null || terminalStep.result().isBlank()) {
            return null;
        }
        return terminalStep.result();
    }
}
