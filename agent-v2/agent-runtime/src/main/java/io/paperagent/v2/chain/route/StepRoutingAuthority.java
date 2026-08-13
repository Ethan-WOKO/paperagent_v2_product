package io.paperagent.v2.chain.route;

import io.paperagent.v2.chain.ChainTransitionType;

import java.util.Objects;
import java.util.Optional;

/** Narrow read boundary for formal Step activation and composite completion. */
public interface StepRoutingAuthority {
    Optional<ActiveStep> findActiveStep(String taskId);

    boolean isTransitionComplete(
            String taskId,
            String transitionId,
            ChainTransitionType expectedType);

    record ActiveStep(
            String taskId,
            String planRevisionId,
            String stepId,
            String activationEventId,
            long authoritySequence) {
        public ActiveStep {
            taskId = required(taskId, "taskId");
            planRevisionId = required(planRevisionId, "planRevisionId");
            stepId = required(stepId, "stepId");
            activationEventId = required(
                    activationEventId, "activationEventId");
            if (authoritySequence < 1) {
                throw new IllegalArgumentException(
                        "authoritySequence must be positive");
            }
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
