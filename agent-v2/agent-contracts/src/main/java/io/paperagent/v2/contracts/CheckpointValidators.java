package io.paperagent.v2.contracts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CheckpointValidators {
    private CheckpointValidators() {
    }

    public static List<ContractViolation> validate(
            Checkpoint checkpoint,
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint previous) {
        List<ContractViolation> violations = new ArrayList<>();
        if (checkpoint == null || taskFrame == null || plan == null) {
            return List.of(Contracts.violation(ViolationCode.REQUIRED_VALUE_MISSING,
                    "checkpoint", "checkpoint, TaskFrame, and Plan are required"));
        }
        if (!checkpoint.taskFrameId().equals(taskFrame.id())
                || !plan.taskFrameId().equals(taskFrame.id())) {
            violations.add(Contracts.violation(ViolationCode.TASK_FRAME_MISMATCH,
                    "checkpoint.taskFrameId", "checkpoint and Plan must share the supplied TaskFrame"));
        }
        if (!checkpoint.planId().equals(plan.id())) {
            violations.add(Contracts.violation(ViolationCode.CHECKPOINT_PLAN_MISMATCH,
                    "checkpoint.planId", "checkpoint references another Plan"));
        }
        PlanRevision revision = plan.latestRevision();
        if (!revision.id().equals(checkpoint.revisionId())
                || revision.number() != checkpoint.revisionNumber()) {
            violations.add(Contracts.violation(ViolationCode.CHECKPOINT_REVISION_MISMATCH,
                    "checkpoint.revisionId", "checkpoint must reference the latest Plan revision"));
        } else {
            Set<PlanStepId> knownSteps = new HashSet<>();
            revision.steps().forEach(step -> knownSteps.add(step.id()));
            checkpoint.stepStates().keySet().stream()
                    .filter(stepId -> !knownSteps.contains(stepId))
                    .forEach(stepId -> violations.add(Contracts.violation(
                            ViolationCode.CHECKPOINT_UNKNOWN_STEP,
                            "checkpoint.stepStates." + stepId.value(),
                            "checkpoint references a step outside its revision")));
            knownSteps.stream()
                    .filter(stepId -> !checkpoint.stepStates().containsKey(stepId))
                    .forEach(stepId -> violations.add(Contracts.violation(
                            ViolationCode.CHECKPOINT_STATE_INCONSISTENT,
                            "checkpoint.stepStates." + stepId.value(),
                            "checkpoint must record every step in its revision")));
            revision.completedFacts().keySet().forEach(stepId -> {
                if (checkpoint.stepStates().get(stepId) != StepExecutionState.SUCCEEDED) {
                    violations.add(Contracts.violation(ViolationCode.CHECKPOINT_STATE_INCONSISTENT,
                            "checkpoint.stepStates." + stepId.value(),
                            "a completion fact requires SUCCEEDED state"));
                }
            });
            checkpoint.stepStates().forEach((stepId, state) -> {
                if (state == StepExecutionState.SUCCEEDED
                        && !revision.completedFacts().containsKey(stepId)) {
                    violations.add(Contracts.violation(
                            ViolationCode.CHECKPOINT_COMPLETION_FACT_MISSING,
                            "checkpoint.stepStates." + stepId.value(),
                            "a SUCCEEDED step requires an append-only completion fact"));
                }
            });
            if (checkpoint.planState() == PlanExecutionState.SUCCEEDED
                    && checkpoint.stepStates().values().stream()
                    .anyMatch(state -> state != StepExecutionState.SUCCEEDED)) {
                violations.add(Contracts.violation(ViolationCode.CHECKPOINT_STATE_INCONSISTENT,
                        "checkpoint.planState", "a succeeded Plan cannot contain unfinished or failed steps"));
            }
        }
        if (previous != null) {
            if (!previous.planId().equals(checkpoint.planId())
                    || !previous.taskFrameId().equals(checkpoint.taskFrameId())) {
                violations.add(Contracts.violation(ViolationCode.CHECKPOINT_PLAN_MISMATCH,
                        "checkpoint", "checkpoint history must belong to the same task and Plan"));
            }
            if (checkpoint.revisionNumber() < previous.revisionNumber()) {
                violations.add(Contracts.violation(ViolationCode.CHECKPOINT_REVISION_REGRESSION,
                        "checkpoint.revisionNumber", "checkpoint revision cannot regress"));
            } else if (checkpoint.revisionNumber() == previous.revisionNumber()
                    && !checkpoint.revisionId().equals(previous.revisionId())) {
                violations.add(Contracts.violation(ViolationCode.CHECKPOINT_REVISION_REGRESSION,
                        "checkpoint.revisionId",
                        "the same revision number must retain the same revision ID"));
            }
            if (checkpoint.lastEventSequence() < previous.lastEventSequence()) {
                violations.add(Contracts.violation(ViolationCode.EVENT_SEQUENCE_REGRESSION,
                        "checkpoint.lastEventSequence", "event sequence cannot regress"));
            }
            if (checkpoint.createdAt().isBefore(previous.createdAt())) {
                violations.add(Contracts.violation(ViolationCode.CHECKPOINT_TIME_REGRESSION,
                        "checkpoint.createdAt", "checkpoint creation time cannot regress"));
            }
            if (!checkpoint.receiptReferences().containsAll(previous.receiptReferences())) {
                violations.add(Contracts.violation(ViolationCode.CHECKPOINT_RECEIPT_REGRESSION,
                        "checkpoint.receiptReferences",
                        "checkpoint receipt references are append-only and cannot be removed"));
            }
            if (previous.planState().terminal()
                    && checkpoint.planState() != previous.planState()) {
                violations.add(Contracts.violation(ViolationCode.CHECKPOINT_STATE_REGRESSION,
                        "checkpoint.planState",
                        "a terminal Plan state cannot reopen or change to another terminal state"));
            } else if (previous.planState() != PlanExecutionState.NOT_STARTED
                    && checkpoint.planState() == PlanExecutionState.NOT_STARTED) {
                violations.add(Contracts.violation(ViolationCode.CHECKPOINT_STATE_REGRESSION,
                        "checkpoint.planState",
                        "a Plan that has started cannot return to NOT_STARTED"));
            }
            previous.stepStates().forEach((stepId, previousState) -> {
                StepExecutionState currentState = checkpoint.stepStates().get(stepId);
                if (previousState.terminal()
                        && currentState != null
                        && currentState != previousState) {
                    violations.add(Contracts.violation(ViolationCode.CHECKPOINT_STATE_REGRESSION,
                            "checkpoint.stepStates." + stepId.value(),
                            "a terminal step state cannot reopen or change to another terminal state"));
                } else if (previousState != StepExecutionState.NOT_STARTED
                        && currentState == StepExecutionState.NOT_STARTED) {
                    violations.add(Contracts.violation(ViolationCode.CHECKPOINT_STATE_REGRESSION,
                            "checkpoint.stepStates." + stepId.value(),
                            "a step that has started cannot return to NOT_STARTED"));
                }
            });
        }
        return List.copyOf(violations);
    }

    public static void requireValid(
            Checkpoint checkpoint,
            TaskFrame taskFrame,
            Plan plan,
            Checkpoint previous) {
        Contracts.requireNoViolations(validate(checkpoint, taskFrame, plan, previous));
    }
}
