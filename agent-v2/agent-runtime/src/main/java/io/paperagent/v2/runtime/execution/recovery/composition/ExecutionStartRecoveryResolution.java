package io.paperagent.v2.runtime.execution.recovery.composition;

public enum ExecutionStartRecoveryResolution {
    OBSERVED_COMMITTED,
    ATOMIC_START_APPLIED,
    ATOMIC_START_REPLAYED,
    RECONCILED_AFTER_RESPONSE_LOSS
}
