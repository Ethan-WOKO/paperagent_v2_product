package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;

public record ExecutionStartRecoveryRetryRequired(
        PlanId planId,
        ExecutionStartRecoveryLeaseDisposition leaseDisposition)
        implements ExecutionStartRecoveryOutcome {

    public ExecutionStartRecoveryRetryRequired {
        ExecutionStartRecoveryValues.required(
                planId,
                "executionStartRecoveryRetryRequired.planId");
        ExecutionStartRecoveryValues.required(
                leaseDisposition,
                "executionStartRecoveryRetryRequired.leaseDisposition");
        if (leaseDisposition
                != ExecutionStartRecoveryLeaseDisposition
                        .RETAINED_FOR_RECOVERY) {
            throw ExecutionStartRecoveryValues.validationFailure(
                    ExecutionStartRecoveryValidationCode.INVALID_OUTCOME_STATE,
                    "executionStartRecoveryRetryRequired.leaseDisposition");
        }
    }

    @Override
    public String toString() {
        return "ExecutionStartRecoveryRetryRequired[planId=<provided>, "
                + "leaseDisposition="
                + leaseDisposition
                + "]";
    }
}
