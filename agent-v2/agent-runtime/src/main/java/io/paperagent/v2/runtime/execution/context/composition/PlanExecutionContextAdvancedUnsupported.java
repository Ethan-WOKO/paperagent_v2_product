package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record PlanExecutionContextAdvancedUnsupported(
        PlanId planId,
        PlanExecutionContextCompositionStage stage,
        PersistenceFailure failure,
        PlanExecutionContextLeaseDisposition leaseDisposition)
        implements PlanExecutionContextCompositionOutcome {

    public PlanExecutionContextAdvancedUnsupported {
        PlanExecutionContextCompositionValues.required(
                planId,
                "planExecutionContextAdvancedUnsupported.planId");
        PlanExecutionContextCompositionValues.required(
                stage,
                "planExecutionContextAdvancedUnsupported.stage");
        PlanExecutionContextCompositionValues.required(
                failure,
                "planExecutionContextAdvancedUnsupported.failure");
        PlanExecutionContextCompositionValues.required(
                leaseDisposition,
                "planExecutionContextAdvancedUnsupported.leaseDisposition");
        PlanExecutionContextCompositionValues.requireAdvanced(
                stage,
                failure,
                leaseDisposition);
    }

    @Override
    public String toString() {
        return "PlanExecutionContextAdvancedUnsupported["
                + "planId=<provided>, stage="
                + stage
                + ", failure=<provided>, leaseDisposition="
                + leaseDisposition
                + "]";
    }
}
