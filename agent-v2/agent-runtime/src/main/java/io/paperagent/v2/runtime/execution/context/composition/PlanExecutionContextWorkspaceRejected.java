package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.workspace.WorkspaceErrorCode;

public record PlanExecutionContextWorkspaceRejected(
        PlanId planId,
        PlanExecutionContextCompositionStage stage,
        WorkspaceErrorCode workspaceErrorCode,
        PlanExecutionContextLeaseDisposition leaseDisposition)
        implements PlanExecutionContextCompositionOutcome {

    public PlanExecutionContextWorkspaceRejected {
        PlanExecutionContextCompositionValues.required(
                planId,
                "planExecutionContextWorkspaceRejected.planId");
        PlanExecutionContextCompositionValues.required(
                stage,
                "planExecutionContextWorkspaceRejected.stage");
        PlanExecutionContextCompositionValues.required(
                workspaceErrorCode,
                "planExecutionContextWorkspaceRejected.workspaceErrorCode");
        PlanExecutionContextCompositionValues.required(
                leaseDisposition,
                "planExecutionContextWorkspaceRejected.leaseDisposition");
        PlanExecutionContextCompositionValues.requireWorkspaceRejected(
                stage,
                workspaceErrorCode,
                leaseDisposition);
    }

    @Override
    public String toString() {
        return "PlanExecutionContextWorkspaceRejected["
                + "planId=<provided>, stage="
                + stage
                + ", workspaceErrorCode="
                + workspaceErrorCode
                + ", leaseDisposition="
                + leaseDisposition
                + "]";
    }
}
