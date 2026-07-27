package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record FreshAtomicExecutionStartRejected(
        PlanId planId,
        PersistenceFailure failure,
        FreshExecutionLeaseDisposition leaseDisposition)
        implements FreshExecutionStartOutcome {

    public FreshAtomicExecutionStartRejected {
        FreshExecutionStartValues.required(
                planId,
                "freshAtomicExecutionStartRejected.planId");
        FreshExecutionStartValues.required(
                failure,
                "freshAtomicExecutionStartRejected.failure");
        FreshExecutionStartValues.required(
                leaseDisposition,
                "freshAtomicExecutionStartRejected.leaseDisposition");
        if (leaseDisposition
                != FreshExecutionLeaseDisposition.RETAINED_FOR_RECOVERY) {
            throw FreshExecutionStartValues.failure(
                    FreshExecutionStartValidationCode.INVALID_OUTCOME_STATE,
                    "freshAtomicExecutionStartRejected.leaseDisposition",
                    "atomic start rejection must retain the lease for recovery");
        }
    }

    @Override
    public String toString() {
        return "FreshAtomicExecutionStartRejected[planId=<provided>, "
                + "failure=<provided>, leaseDisposition="
                + leaseDisposition
                + "]";
    }
}
