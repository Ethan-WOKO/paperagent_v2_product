package com.yanban.api.agent.v2.adaptive.reflection;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral, already-bounded facts supplied to a reflection model.
 */
public record ReflectionContext(
        String taskFrame,
        String currentPlan,
        List<String> conversationContext,
        List<String> completedFacts,
        List<String> recentExecutionFacts,
        List<String> unfinishedSteps,
        ReflectionStepResult currentStepResult) {

    public ReflectionContext {
        taskFrame = requireText(taskFrame, "taskFrame");
        currentPlan = requireText(currentPlan, "currentPlan");
        conversationContext = immutable(conversationContext);
        completedFacts = immutable(completedFacts);
        recentExecutionFacts = immutable(recentExecutionFacts);
        unfinishedSteps = immutable(unfinishedSteps);
    }

    public ReflectionContext(
            String taskFrame,
            String currentPlan,
            List<String> conversationContext,
            List<String> completedFacts,
            List<String> recentExecutionFacts,
            List<String> unfinishedSteps) {
        this(taskFrame, currentPlan, conversationContext, completedFacts,
                recentExecutionFacts, unfinishedSteps, null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static List<String> immutable(List<String> values) {
        Objects.requireNonNull(values, "values");
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("reflection facts cannot be null");
        }
        return List.copyOf(values);
    }
}
