package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistedExecutionStartCommitted;

public record StepActivationCompositionRequest(
        PersistedExecutionStartCommitted committedStart,
        PlanStepId stepId,
        StepActivationAttempt attempt) {

    public StepActivationCompositionRequest {
        StepActivationCompositionValues.required(
                committedStart, "stepActivationCompositionRequest.committedStart");
        StepActivationCompositionValues.required(
                stepId, "stepActivationCompositionRequest.stepId");
        StepActivationCompositionValues.required(
                attempt, "stepActivationCompositionRequest.attempt");
    }

    @Override
    public String toString() {
        return "StepActivationCompositionRequest[committedStart=<provided>, "
                + "stepId=<provided>, attempt=<provided>]";
    }
}
