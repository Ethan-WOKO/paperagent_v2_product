package io.paperagent.v2.contracts;

public enum StepExecutionState {
    NOT_STARTED,
    ACTIVE,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SUPERSEDED_BY_REPLAN;

    public boolean terminal() {
        return this == SUCCEEDED
                || this == FAILED
                || this == CANCELLED
                || this == SUPERSEDED_BY_REPLAN;
    }
}
