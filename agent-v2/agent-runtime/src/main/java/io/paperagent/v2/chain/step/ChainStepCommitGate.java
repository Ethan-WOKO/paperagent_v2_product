package io.paperagent.v2.chain.step;

/**
 * Authority-derived gate for instruction/gap/cancel/supersede/terminal state.
 * Implementations must fail when the supplied frozen identity is not current.
 */
@FunctionalInterface
public interface ChainStepCommitGate {
    void requireCurrent(GateQuery query);

    record GateQuery(
            CommitKind kind,
            String taskId,
            String instructionId,
            String taskFrameId,
            String planId,
            String planRevisionId,
            String stepId,
            String activationEventId) {
    }

    enum CommitKind {
        ACTION_BINDING,
        FINALIZATION_READINESS
    }
}
