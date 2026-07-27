package io.paperagent.v2.runtime.execution.context.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.WorkspaceMaterializationSpec;

import java.util.Optional;

public record PlanExecutionContextCompositionRequest(
        PlanId planId,
        Optional<WorkspaceMaterializationSpec> proposedMaterializationSpec,
        Optional<PlanExecutionContextLeaseAttempt> leaseAttempt) {

    public PlanExecutionContextCompositionRequest {
        PlanExecutionContextCompositionValues.required(
                planId,
                "planExecutionContextComposition.request.planId");
        proposedMaterializationSpec =
                PlanExecutionContextCompositionValues.required(
                        proposedMaterializationSpec,
                        "planExecutionContextComposition.request"
                                + ".proposedMaterializationSpec");
        leaseAttempt = PlanExecutionContextCompositionValues.required(
                leaseAttempt,
                "planExecutionContextComposition.request.leaseAttempt");
    }

    @Override
    public String toString() {
        return "PlanExecutionContextCompositionRequest[planId=<provided>, "
                + "proposedMaterializationSpec="
                + (proposedMaterializationSpec.isPresent()
                        ? "<provided>"
                        : "<empty>")
                + ", leaseAttempt="
                + (leaseAttempt.isPresent() ? "<provided>]" : "<empty>]");
    }
}
