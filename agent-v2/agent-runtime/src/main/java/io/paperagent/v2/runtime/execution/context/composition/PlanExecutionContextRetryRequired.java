package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;

public record PlanExecutionContextRetryRequired(
        PlanId planId,
        PlanExecutionContextCompositionStage stage,
        PlanExecutionContextRetryReason retryReason,
        PlanExecutionContextLeaseDisposition leaseDisposition)
        implements PlanExecutionContextCompositionOutcome {

    public PlanExecutionContextRetryRequired {
        PlanExecutionContextCompositionValues.required(
                planId,
                "planExecutionContextRetryRequired.planId");
        PlanExecutionContextCompositionValues.required(
                stage,
                "planExecutionContextRetryRequired.stage");
        PlanExecutionContextCompositionValues.required(
                retryReason,
                "planExecutionContextRetryRequired.retryReason");
        PlanExecutionContextCompositionValues.required(
                leaseDisposition,
                "planExecutionContextRetryRequired.leaseDisposition");
        PlanExecutionContextCompositionValues.requireRetry(
                stage,
                retryReason,
                leaseDisposition);
    }

    @Override
    public String toString() {
        return "PlanExecutionContextRetryRequired[planId=<provided>, "
                + "stage="
                + stage
                + ", retryReason="
                + retryReason
                + ", leaseDisposition="
                + leaseDisposition
                + "]";
    }
}
