package io.paperagent.v2.runtime.execution.recovery.composition;

public enum ExecutionStartRecoveryStage {
    INITIAL_INSPECT,
    MATERIALIZE,
    LEASE_ACQUIRE,
    POST_LEASE_INSPECT,
    POST_LEASE_MATERIALIZE,
    ATOMIC_START,
    POST_START_INSPECT
}
