package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistenceFailure;

public record SingleTurnPersistenceRejected(
        PlanId planId,
        PlanStepId stepId,
        PersistenceFailure failure) implements SingleTurnStepKernelOutcome {

    public SingleTurnPersistenceRejected {
        planId = SingleTurnStepKernelValues.required(
                planId, "singleTurnPersistenceRejected.planId");
        stepId = SingleTurnStepKernelValues.required(
                stepId, "singleTurnPersistenceRejected.stepId");
        failure = SingleTurnStepKernelValues.required(
                failure, "singleTurnPersistenceRejected.failure");
    }

    @Override
    public String toString() {
        return "SingleTurnPersistenceRejected[planId=<provided>, stepId=<provided>, "
                + "failure=<provided>]";
    }
}
