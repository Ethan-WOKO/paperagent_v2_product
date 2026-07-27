package io.paperagent.v2.runtime.execution.context.composition;

public enum PlanExecutionContextLeaseDisposition {
    NO_LEASE_ACTION,
    NOT_ACQUIRED,
    ACQUISITION_INDETERMINATE,
    RETAINED_FOR_RECOVERY
}
