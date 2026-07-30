package com.yanban.api.agent.v2.adaptive.reflection;

import java.util.List;

/**
 * A structurally validated reflection decision.
 */
public record ReflectionOutcome(
        ReflectionAction decision,
        String reason,
        String finalText,
        List<ReflectionReplacementStep> replacementSteps) {

    public ReflectionOutcome {
        if (decision == null) {
            throw new IllegalArgumentException("decision is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        replacementSteps = replacementSteps == null
                ? List.of() : List.copyOf(replacementSteps);
        if (decision == ReflectionAction.COMPLETE) {
            if (finalText == null || finalText.isBlank()) {
                throw new IllegalArgumentException(
                        "finalText is required for COMPLETE");
            }
        } else if (finalText != null) {
            throw new IllegalArgumentException(
                    "finalText is only allowed for COMPLETE");
        }
        if (decision == ReflectionAction.REPLAN) {
            if (replacementSteps.isEmpty()) {
                throw new IllegalArgumentException(
                        "replacementSteps are required for REPLAN");
            }
        } else if (!replacementSteps.isEmpty()) {
            throw new IllegalArgumentException(
                    "replacementSteps are only allowed for REPLAN");
        }
    }
}
