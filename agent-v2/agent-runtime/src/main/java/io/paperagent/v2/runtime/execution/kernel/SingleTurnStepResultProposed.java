package io.paperagent.v2.runtime.execution.kernel;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ReceiptId;
import java.util.List;

/** Product boundary input for durably recording one model Step result. */
public record SingleTurnStepResultProposed(
        PlanId planId,
        PlanStepId stepId,
        String resultText,
        List<ReceiptId> evidenceReceiptIds)
        implements SingleTurnStepKernelOutcome {
    public SingleTurnStepResultProposed {
        planId = SingleTurnStepKernelValues.required(
                planId, "singleTurnStepResultProposed.planId");
        stepId = SingleTurnStepKernelValues.required(
                stepId, "singleTurnStepResultProposed.stepId");
        if (resultText == null || resultText.isBlank()) {
            throw SingleTurnStepKernelValues.failure(
                    SingleTurnStepKernelValidationCode
                            .REQUIRED_VALUE_MISSING,
                    "singleTurnStepResultProposed.resultText");
        }
        evidenceReceiptIds = List.copyOf(
                SingleTurnStepKernelValues.required(
                        evidenceReceiptIds,
                        "singleTurnStepResultProposed.evidenceReceiptIds"));
    }

    @Override
    public String toString() {
        return "SingleTurnStepResultProposed[planId=<provided>, "
                + "stepId=<provided>, resultText=<redacted>, "
                + "evidenceReceiptIds=<redacted>]";
    }
}
