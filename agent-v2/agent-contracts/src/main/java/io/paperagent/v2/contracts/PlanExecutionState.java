package io.paperagent.v2.contracts;

/**
 * Execution state only. User acceptance of a diff is a separate product action.
 */
public enum PlanExecutionState {
    NOT_STARTED,
    ACTIVE,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
