package io.paperagent.v2.runtime.execution.context.composition;

public enum PlanExecutionContextCompositionResolution {
    OBSERVED_CONFIRMED,
    CONFIRM_APPLIED,
    CONFIRM_REPLAYED,
    RECONCILED_AFTER_RESPONSE_LOSS,
    OBSERVED_CONCURRENT_CONFIRMATION
}
