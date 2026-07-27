package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;

public sealed interface ExecutionStartRecoveryOutcome
        permits RecoveredExecutionStart,
                ExecutionStartRecoveryRejected,
                ExecutionStartRecoveryAdvancedUnsupported,
                ExecutionStartRecoveryRetryRequired {
    PlanId planId();

    ExecutionStartRecoveryLeaseDisposition leaseDisposition();
}
