package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record PlanExecutionContextPersistenceRejected(
        PlanId planId,
        PlanExecutionContextCompositionStage stage,
        PersistenceFailure failure,
        PlanExecutionContextLeaseDisposition leaseDisposition)
        implements PlanExecutionContextCompositionOutcome {

    public PlanExecutionContextPersistenceRejected {
        PlanExecutionContextCompositionValues.required(
                planId,
                "planExecutionContextPersistenceRejected.planId");
        PlanExecutionContextCompositionValues.required(
                stage,
                "planExecutionContextPersistenceRejected.stage");
        PlanExecutionContextCompositionValues.required(
                failure,
                "planExecutionContextPersistenceRejected.failure");
        PlanExecutionContextCompositionValues.required(
                leaseDisposition,
                "planExecutionContextPersistenceRejected.leaseDisposition");
        PlanExecutionContextCompositionValues.requirePersistenceRejected(
                stage,
                failure,
                leaseDisposition);
    }

    @Override
    public String toString() {
        return "PlanExecutionContextPersistenceRejected["
                + "planId=<provided>, stage="
                + stage
                + ", failure=<provided>, leaseDisposition="
                + leaseDisposition
                + "]";
    }
}
