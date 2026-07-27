package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record FreshLeaseAcquisitionRejected(
        PlanId planId,
        PersistenceFailure failure)
        implements FreshExecutionStartOutcome {

    public FreshLeaseAcquisitionRejected {
        FreshExecutionStartValues.required(
                planId,
                "freshLeaseAcquisitionRejected.planId");
        FreshExecutionStartValues.required(
                failure,
                "freshLeaseAcquisitionRejected.failure");
    }

    @Override
    public String toString() {
        return "FreshLeaseAcquisitionRejected[planId=<provided>, "
                + "failure=<provided>]";
    }
}
