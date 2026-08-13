package io.paperagent.v2.chain.step;

import io.paperagent.v2.chain.ChainPersistenceRecords.AppendResult;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Narrow adapter to the stable V2 Plan/Step authority. */
public interface ChainStepAuthorityPort {
    Optional<PlanSnapshot> findPlan(String taskId, String planRevisionId);

    List<StepEvent> findStepEvents(String taskId, String planRevisionId);

    AppendResult<StepEvent> appendStepEvent(StepEventCommand command);

    record PlanSnapshot(
            String taskId,
            String taskFrameId,
            String planId,
            String planRevisionId,
            String targetCandidateKey,
            String targetInstructionVersionId,
            List<StepDefinition> steps) {
        public PlanSnapshot {
            taskId = required(taskId, "taskId");
            taskFrameId = required(taskFrameId, "taskFrameId");
            planId = required(planId, "planId");
            planRevisionId = required(planRevisionId, "planRevisionId");
            targetCandidateKey = required(
                    targetCandidateKey, "targetCandidateKey");
            targetInstructionVersionId = required(
                    targetInstructionVersionId,
                    "targetInstructionVersionId");
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            if (steps.isEmpty()) {
                throw new IllegalArgumentException(
                        "a Plan snapshot must contain at least one Step");
            }
        }
    }

    record StepDefinition(
            String stepId,
            int stableOrder,
            Set<String> prerequisiteStepIds) {
        public StepDefinition {
            stepId = required(stepId, "stepId");
            if (stableOrder < 1) {
                throw new IllegalArgumentException(
                        "stableOrder must be positive");
            }
            prerequisiteStepIds = Set.copyOf(
                    Objects.requireNonNull(prerequisiteStepIds,
                            "prerequisiteStepIds"));
            if (prerequisiteStepIds.stream().anyMatch(
                    value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException(
                        "prerequisite Step IDs must not be blank");
            }
        }
    }

    enum StepEventKind {
        ACTIVATED,
        COMPLETED,
        SUPERSEDED_BY_REPLAN
    }

    record StepEventCommand(
            String eventId,
            String taskId,
            String planRevisionId,
            String stepId,
            String activationEventId,
            StepEventKind eventKind,
            String sourceDecisionId,
            String transitionId,
            Instant committedAt) {
        public StepEventCommand {
            eventId = required(eventId, "eventId");
            taskId = required(taskId, "taskId");
            planRevisionId = required(planRevisionId, "planRevisionId");
            stepId = required(stepId, "stepId");
            activationEventId = required(
                    activationEventId, "activationEventId");
            eventKind = Objects.requireNonNull(eventKind, "eventKind");
            sourceDecisionId = required(
                    sourceDecisionId, "sourceDecisionId");
            transitionId = required(transitionId, "transitionId");
            Objects.requireNonNull(committedAt, "committedAt");
            if (eventKind == StepEventKind.ACTIVATED
                    && !eventId.equals(activationEventId)) {
                throw new IllegalArgumentException(
                        "activation event must be its own activation identity");
            }
        }
    }

    record StepEvent(StepEventCommand command, long authoritySequence) {
        public StepEvent {
            Objects.requireNonNull(command, "command");
            if (authoritySequence < 1) {
                throw new IllegalArgumentException(
                        "authoritySequence must be positive");
            }
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
