package com.yanban.api.agent.reactplan;

import io.paperagent.v2.contracts.BoundedExecutionHints;

import java.util.List;
import java.util.Objects;

/** A model-facing goal uses local keys, never authoritative Plan or Step IDs. */
public record ReactPlanGoal(
        String key,
        String objective,
        String expectedOutcome,
        List<String> dependsOn,
        List<String> doneWhen,
        List<String> constraints,
        BoundedExecutionHints executionHints) {

    public ReactPlanGoal {
        key = requiredText(key, "key");
        objective = requiredText(objective, "objective");
        expectedOutcome = requiredText(expectedOutcome, "expectedOutcome");
        dependsOn = textList(dependsOn, "dependsOn");
        doneWhen = textList(doneWhen, "doneWhen");
        if (doneWhen.isEmpty()) {
            throw new IllegalArgumentException("doneWhen must not be empty");
        }
        constraints = textList(constraints, "constraints");
        Objects.requireNonNull(executionHints, "executionHints");
    }

    private static List<String> textList(List<String> values, String name) {
        Objects.requireNonNull(values, name);
        return values.stream().map(value -> requiredText(value, name + "[]")).toList();
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
