package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;

public record SingleTurnNoEffect(PlanId planId, PlanStepId stepId)
        implements SingleTurnStepKernelOutcome {

    public SingleTurnNoEffect {
        planId = SingleTurnStepKernelValues.required(
                planId, "singleTurnNoEffect.planId");
        stepId = SingleTurnStepKernelValues.required(
                stepId, "singleTurnNoEffect.stepId");
    }

    @Override
    public String toString() {
        return "SingleTurnNoEffect[planId=<provided>, stepId=<provided>]";
    }
}
