package io.paperagent.v2.runtime.execution.start;

public sealed interface FreshExecutionStartOutcome
        permits FreshExecutionStarted,
                FreshExecutionRecoveryRequired,
                FreshExecutionBootstrapRejected,
                FreshLeaseAcquisitionRejected,
                FreshAtomicExecutionStartRejected {
}
