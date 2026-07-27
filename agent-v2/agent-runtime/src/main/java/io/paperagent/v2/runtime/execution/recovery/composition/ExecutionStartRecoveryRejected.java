package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record ExecutionStartRecoveryRejected(
        PlanId planId,
        ExecutionStartRecoveryStage stage,
        PersistenceFailure failure,
        ExecutionStartRecoveryLeaseDisposition leaseDisposition)
        implements ExecutionStartRecoveryOutcome {

    public ExecutionStartRecoveryRejected {
        ExecutionStartRecoveryValues.required(
                planId,
                "executionStartRecoveryRejected.planId");
        ExecutionStartRecoveryValues.required(
                stage,
                "executionStartRecoveryRejected.stage");
        ExecutionStartRecoveryValues.required(
                failure,
                "executionStartRecoveryRejected.failure");
        ExecutionStartRecoveryValues.required(
                leaseDisposition,
                "executionStartRecoveryRejected.leaseDisposition");
        ExecutionStartRecoveryValues.requireRejectedCombination(
                stage,
                failure,
                leaseDisposition);
    }

    @Override
    public String toString() {
        return "ExecutionStartRecoveryRejected[planId=<provided>, stage="
                + stage
                + ", failure=<provided>, leaseDisposition="
                + leaseDisposition
                + "]";
    }
}
