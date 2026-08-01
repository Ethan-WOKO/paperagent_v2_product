package com.yanban.api.agent;

import com.yanban.core.agent.AgentPlanStep;
import java.time.LocalDateTime;
import java.util.List;

public record AgentPlanStepResponse(
        Long id,
        String stepKey,
        Integer sortOrder,
        String title,
        String description,
        String type,
        List<String> dependencies,
        List<String> allowedTools,
        String successCriteria,
        String status,
        Integer attemptCount,
        String result,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
    public static AgentPlanStepResponse from(AgentPlanStep step, List<String> dependencies, List<String> allowedTools) {
        return new AgentPlanStepResponse(
                step.getId(),
                step.getStepKey(),
                step.getSortOrder(),
                step.getTitle(),
                step.getDescription(),
                step.getType(),
                dependencies,
                allowedTools,
                step.getSuccessCriteria(),
                step.getStatus(),
                step.getAttemptCount(),
                step.getResult(),
                step.getErrorMessage(),
                step.getStartedAt(),
                step.getFinishedAt()
        );
    }
}
