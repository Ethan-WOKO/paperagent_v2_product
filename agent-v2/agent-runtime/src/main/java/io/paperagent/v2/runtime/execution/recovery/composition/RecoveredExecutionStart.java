package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedExecutionStart;

public record RecoveredExecutionStart(
        ExecutionStartRecoveryResolution resolution,
        PersistedExecutionStart persistedStart,
        ExecutionStartRecoveryLeaseDisposition leaseDisposition)
        implements ExecutionStartRecoveryOutcome {

    public RecoveredExecutionStart {
        ExecutionStartRecoveryValues.required(
                resolution,
                "recoveredExecutionStart.resolution");
        ExecutionStartRecoveryValues.required(
                persistedStart,
                "recoveredExecutionStart.persistedStart");
        ExecutionStartRecoveryValues.required(
                leaseDisposition,
                "recoveredExecutionStart.leaseDisposition");
        ExecutionStartRecoveryValues.requireRecoveredCombination(
                resolution,
                leaseDisposition);
    }

    @Override
    public PlanId planId() {
        return persistedStart.planId();
    }

    @Override
    public String toString() {
        return "RecoveredExecutionStart[resolution="
                + resolution
                + ", persistedStart=<provided>, leaseDisposition="
                + leaseDisposition
                + "]";
    }
}
