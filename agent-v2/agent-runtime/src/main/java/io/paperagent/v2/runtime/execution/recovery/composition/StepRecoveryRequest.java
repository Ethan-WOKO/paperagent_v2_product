package io.paperagent.v2.runtime.execution.recovery.composition;

import io.paperagent.v2.contracts.PlanId;

public record StepRecoveryRequest(
        PlanId planId,
        StepRecoveryLeaseAttempt leaseAttempt) {

    public StepRecoveryRequest {
        StepRecoveryCompositionValues.required(
                planId, "stepRecoveryRequest.planId");
        StepRecoveryCompositionValues.required(
                leaseAttempt, "stepRecoveryRequest.leaseAttempt");
    }

    @Override
    public String toString() {
        return "StepRecoveryRequest[planId=<provided>, leaseAttempt=<provided>]";
    }
}
