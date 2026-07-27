package io.paperagent.v2.runtime.execution.start;

import io.paperagent.v2.contracts.PlanId;

public record FreshExecutionRecoveryRequired(PlanId planId)
        implements FreshExecutionStartOutcome {

    public FreshExecutionRecoveryRequired {
        FreshExecutionStartValues.required(
                planId,
                "freshExecutionRecoveryRequired.planId");
    }

    @Override
    public String toString() {
        return "FreshExecutionRecoveryRequired[planId=<provided>]";
    }
}
