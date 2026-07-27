package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.persistence.VersionedCheckpoint;

public record StepTurnInput(
        TaskFrame taskFrame,
        Plan plan,
        VersionedCheckpoint checkpoint,
        PlanStep activeStep) {

    public StepTurnInput {
        taskFrame = SingleTurnStepKernelValues.required(
                taskFrame, "stepTurnInput.taskFrame");
        plan = SingleTurnStepKernelValues.required(plan, "stepTurnInput.plan");
        checkpoint = SingleTurnStepKernelValues.required(
                checkpoint, "stepTurnInput.checkpoint");
        activeStep = SingleTurnStepKernelValues.required(
                activeStep, "stepTurnInput.activeStep");
    }

    @Override
    public String toString() {
        return "StepTurnInput[taskFrame=<provided>, plan=<provided>, "
                + "checkpoint=<provided>, activeStep=<provided>]";
    }
}
