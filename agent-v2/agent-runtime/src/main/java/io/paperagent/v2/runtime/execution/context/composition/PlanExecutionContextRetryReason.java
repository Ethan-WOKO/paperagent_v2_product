package io.paperagent.v2.runtime.execution.context.composition;

public enum PlanExecutionContextRetryReason {
    RESERVATION_INDETERMINATE,
    MATERIALIZATION_INDETERMINATE,
    CONFIRMATION_INDETERMINATE,
    EXECUTION_START_NOT_COMMITTED
}
