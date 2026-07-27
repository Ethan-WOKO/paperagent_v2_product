package io.paperagent.v2.runtime.execution.activation.materialization;

import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;

import java.time.Instant;

public record CommittedStepActivationMaterializationRequest(
        PersistedExecutionStartCommitted committedStart,
        PlanStepId stepId,
        StepActivationEventDraft eventDraft,
        Instant checkpointCreatedAt) {

    public CommittedStepActivationMaterializationRequest {
        CommittedStepActivationMaterializationValues.required(
                committedStart,
                "committedStepActivationMaterializationRequest"
                        + ".committedStart");
        CommittedStepActivationMaterializationValues.required(
                stepId,
                "committedStepActivationMaterializationRequest.stepId");
        CommittedStepActivationMaterializationValues.required(
                eventDraft,
                "committedStepActivationMaterializationRequest.eventDraft");
        CommittedStepActivationMaterializationValues.required(
                checkpointCreatedAt,
                "committedStepActivationMaterializationRequest"
                        + ".checkpointCreatedAt");
    }

    @Override
    public String toString() {
        return "CommittedStepActivationMaterializationRequest"
                + "[committedStart=<provided>, stepId=<provided>, "
                + "eventDraft=<provided>, checkpointCreatedAt=<provided>]";
    }
}
