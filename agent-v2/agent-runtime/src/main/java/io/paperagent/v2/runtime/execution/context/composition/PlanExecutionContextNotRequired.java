package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;

public record PlanExecutionContextNotRequired(
        PlanId planId,
        PlanExecutionContextLeaseDisposition leaseDisposition)
        implements PlanExecutionContextCompositionOutcome {

    public PlanExecutionContextNotRequired {
        PlanExecutionContextCompositionValues.required(
                planId,
                "planExecutionContextNotRequired.planId");
        PlanExecutionContextCompositionValues.required(
                leaseDisposition,
                "planExecutionContextNotRequired.leaseDisposition");
        PlanExecutionContextCompositionValues.requireDisposition(
                leaseDisposition,
                "planExecutionContextNotRequired.leaseDisposition",
                PlanExecutionContextLeaseDisposition.NO_LEASE_ACTION);
    }

    @Override
    public String toString() {
        return "PlanExecutionContextNotRequired[planId=<provided>, "
                + "leaseDisposition="
                + leaseDisposition
                + "]";
    }
}
