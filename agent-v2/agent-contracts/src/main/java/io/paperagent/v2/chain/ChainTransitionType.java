package io.paperagent.v2.chain;

import java.util.List;
import java.util.LinkedHashSet;

public enum ChainTransitionType {
    GAP_RESOLUTION(List.of(List.of(
            ChainTransitionStage.OPEN,
            ChainTransitionStage.NORMAL_SUCCESSOR_COMMITTED,
            ChainTransitionStage.PENDING_RESOLVED,
            ChainTransitionStage.COMPLETE))),
    ACCEPT_STEP(List.of(List.of(
            ChainTransitionStage.OPEN,
            ChainTransitionStage.ACCEPTED_RESULT_COMMITTED,
            ChainTransitionStage.APPLICABILITY_COMMITTED,
            ChainTransitionStage.STEP_COMPLETED,
            ChainTransitionStage.NEXT_STEP_ACTIVATED_OR_NONE,
            ChainTransitionStage.COMPLETE))),
    PLAN_CHANGE(List.of(List.of(
            ChainTransitionStage.OPEN,
            ChainTransitionStage.TASKFRAME_PLAN_COMMITTED,
            ChainTransitionStage.APPLICABILITY_COMMITTED,
            ChainTransitionStage.OLD_STEP_SUPERSEDED_OR_NONE,
            ChainTransitionStage.NEW_STEP_ACTIVATED,
            ChainTransitionStage.COMPLETE))),
    FINAL_STEP_READINESS(List.of(List.of(
            ChainTransitionStage.OPEN,
            ChainTransitionStage.ACCEPTED_RESULT_COMMITTED_OR_VERIFIED,
            ChainTransitionStage.APPLICABILITY_COMMITTED_OR_EMPTY,
            ChainTransitionStage.STEP_COMPLETED_OR_VERIFIED,
            ChainTransitionStage.READINESS_COMMITTED,
            ChainTransitionStage.COMPLETE))),
    FINALIZATION(List.of(List.of(
            ChainTransitionStage.OPEN,
            ChainTransitionStage.READINESS_VERIFIED,
            ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
            ChainTransitionStage.PUBLISH_COMMITTED_OR_NOT_REQUIRED,
            ChainTransitionStage.TASK_OUTCOME_COMMITTED,
            ChainTransitionStage.COMPLETE), List.of(
            ChainTransitionStage.OPEN,
            ChainTransitionStage.READINESS_VERIFIED,
            ChainTransitionStage.FINALIZATION_CHECK_COMMITTED,
            ChainTransitionStage.FAILED_CHECK_HANDOFF_COMMITTED,
            ChainTransitionStage.COMPLETE)));

    private final List<List<ChainTransitionStage>> paths;

    ChainTransitionType(List<List<ChainTransitionStage>> paths) {
        this.paths = paths.stream().map(List::copyOf).toList();
    }

    public List<List<ChainTransitionStage>> paths() {
        return paths;
    }

    public boolean accepts(ChainTransitionStage stage) {
        return paths.stream().anyMatch(path -> path.contains(stage));
    }

    public boolean isValidOrdinal(ChainTransitionStage stage, int ordinal) {
        return ordinal >= 0 && paths.stream()
                .anyMatch(path -> ordinal < path.size() && path.get(ordinal) == stage);
    }

    public List<ChainTransitionStage> validNextStages(List<ChainTransitionStage> committedPrefix) {
        List<ChainTransitionStage> prefix = List.copyOf(committedPrefix);
        LinkedHashSet<ChainTransitionStage> next = new LinkedHashSet<>();
        for (List<ChainTransitionStage> path : paths) {
            if (prefix.size() < path.size() && path.subList(0, prefix.size()).equals(prefix)) {
                next.add(path.get(prefix.size()));
            }
        }
        if (next.isEmpty() && paths.stream().noneMatch(prefix::equals)) {
            throw new IllegalArgumentException("committed stages are not a legal transition prefix");
        }
        return List.copyOf(next);
    }

    public boolean isCompleteSequence(List<ChainTransitionStage> stages) {
        return paths.contains(List.copyOf(stages));
    }
}
