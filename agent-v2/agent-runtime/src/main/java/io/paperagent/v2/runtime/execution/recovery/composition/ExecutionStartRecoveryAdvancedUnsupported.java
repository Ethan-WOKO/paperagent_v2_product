package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record ExecutionStartRecoveryAdvancedUnsupported(
        PlanId planId,
        ExecutionStartRecoveryStage stage,
        PersistenceFailure failure,
        ExecutionStartRecoveryLeaseDisposition leaseDisposition)
        implements ExecutionStartRecoveryOutcome {

    public ExecutionStartRecoveryAdvancedUnsupported {
        ExecutionStartRecoveryValues.required(
                planId,
                "executionStartRecoveryAdvancedUnsupported.planId");
        ExecutionStartRecoveryValues.required(
                stage,
                "executionStartRecoveryAdvancedUnsupported.stage");
        ExecutionStartRecoveryValues.required(
                failure,
                "executionStartRecoveryAdvancedUnsupported.failure");
        ExecutionStartRecoveryValues.required(
                leaseDisposition,
                "executionStartRecoveryAdvancedUnsupported.leaseDisposition");
        ExecutionStartRecoveryValues.requireAdvancedCombination(
                stage,
                failure,
                leaseDisposition);
    }

    @Override
    public String toString() {
        return "ExecutionStartRecoveryAdvancedUnsupported"
                + "[planId=<provided>, stage="
                + stage
                + ", failure=<provided>, leaseDisposition="
                + leaseDisposition
                + "]";
    }
}
