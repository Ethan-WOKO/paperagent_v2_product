package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.PersistedPlanExecutionContextConfirmed;
import io.paperagent.v2.workspace.VerifiedWorkspaceMaterialization;

public record PlanExecutionContextReady(
        PlanExecutionContextCompositionResolution resolution,
        PersistedPlanExecutionContextConfirmed persistedContext,
        VerifiedWorkspaceMaterialization verifiedWorkspace,
        PlanExecutionContextLeaseDisposition leaseDisposition)
        implements PlanExecutionContextCompositionOutcome {

    public PlanExecutionContextReady {
        PlanExecutionContextCompositionValues.required(
                resolution,
                "planExecutionContextReady.resolution");
        PlanExecutionContextCompositionValues.required(
                persistedContext,
                "planExecutionContextReady.persistedContext");
        PlanExecutionContextCompositionValues.required(
                verifiedWorkspace,
                "planExecutionContextReady.verifiedWorkspace");
        PlanExecutionContextCompositionValues.required(
                leaseDisposition,
                "planExecutionContextReady.leaseDisposition");
        PlanExecutionContextCompositionValues.requireReady(
                resolution,
                persistedContext,
                verifiedWorkspace,
                leaseDisposition);
    }

    @Override
    public PlanId planId() {
        return persistedContext.planId();
    }

    @Override
    public String toString() {
        return "PlanExecutionContextReady[resolution="
                + resolution
                + ", persistedContext=<provided>, "
                + "verifiedWorkspace=<provided>, leaseDisposition="
                + leaseDisposition
                + "]";
    }
}
