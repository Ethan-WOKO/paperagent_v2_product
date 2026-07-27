package io.paperagent.v2.runtime.execution.recovery.composition;

public enum ExecutionStartRecoveryLeaseDisposition {
    NO_LEASE_ACTION,
    NOT_ACQUIRED,
    ACQUISITION_INDETERMINATE,
    RETAINED_FOR_RECOVERY
}
